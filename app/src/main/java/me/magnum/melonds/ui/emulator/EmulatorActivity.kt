package me.magnum.melonds.ui.emulator

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.core.view.*
import androidx.recyclerview.widget.LinearLayoutManager
import com.squareup.picasso.Picasso
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.PublishSubject
import kotlinx.coroutines.delay
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.R
import me.magnum.melonds.common.CameraManager
import me.magnum.melonds.common.RetroAchievementsCallback
import me.magnum.melonds.common.Schedulers
import me.magnum.melonds.common.uridelegates.UriHandler
import me.magnum.melonds.common.vibration.TouchVibrator
import me.magnum.melonds.databinding.ActivityEmulatorBinding
import me.magnum.melonds.domain.model.*
import me.magnum.melonds.domain.repositories.SettingsRepository
import me.magnum.melonds.extensions.insetsControllerCompat
import me.magnum.melonds.extensions.isMicrophonePermissionGranted
import me.magnum.melonds.extensions.setLayoutOrientation
import me.magnum.melonds.parcelables.RomInfoParcelable
import me.magnum.melonds.parcelables.RomParcelable
import me.magnum.melonds.ui.cheats.CheatsActivity
import me.magnum.melonds.ui.emulator.DSRenderer.RendererListener
import me.magnum.melonds.ui.emulator.camera.AndroidCameraManager
import me.magnum.melonds.ui.emulator.firmware.FirmwareEmulatorDelegate
import me.magnum.melonds.ui.emulator.input.*
import me.magnum.melonds.ui.emulator.rewind.EdgeSpacingDecorator
import me.magnum.melonds.ui.emulator.rewind.RewindSaveStateAdapter
import me.magnum.melonds.ui.emulator.rewind.model.RewindSaveState
import me.magnum.melonds.ui.emulator.rom.RomEmulatorDelegate
import me.magnum.melonds.ui.emulator.ui.AchievementPopupUi
import me.magnum.melonds.ui.settings.SettingsActivity
import me.magnum.melonds.utils.PackageManagerCompat
import me.magnum.rcheevosapi.model.RAAchievement
import java.net.URLEncoder
import java.nio.ByteBuffer
import javax.inject.Inject

@AndroidEntryPoint
class EmulatorActivity : AppCompatActivity(), RendererListener {
    companion object {
        const val KEY_ROM = "rom"
        const val KEY_PATH = "PATH"
        const val KEY_URI = "uri"
        const val KEY_BOOT_FIRMWARE_CONSOLE = "boot_firmware_console"
        const val KEY_BOOT_FIRMWARE_ONLY = "boot_firmware_only"

        fun getRomEmulatorActivityIntent(context: Context, rom: Rom): Intent {
            return Intent(context, EmulatorActivity::class.java).apply {
                putExtra(KEY_ROM, RomParcelable(rom))
            }
        }

        fun getFirmwareEmulatorActivityIntent(context: Context, consoleType: ConsoleType): Intent {
            return Intent(context, EmulatorActivity::class.java).apply {
                putExtra(KEY_BOOT_FIRMWARE_ONLY, true)
                putExtra(KEY_BOOT_FIRMWARE_CONSOLE, consoleType.ordinal)
            }
        }
    }

    class RomLoadFailedException(reason: String) : Exception("Failed to load ROM: $reason") {
        constructor(result: MelonEmulator.LoadResult) : this(result.toString())
    }

    class FirmwareLoadFailedException(result: MelonEmulator.FirmwareLoadResult) : Exception("Failed to load firmware: $result")

    private lateinit var binding: ActivityEmulatorBinding
    val viewModel: EmulatorViewModel by viewModels()

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var uriHandler: UriHandler

    @Inject
    lateinit var picasso: Picasso

    @Inject
    lateinit var touchVibrator: TouchVibrator

    @Inject
    lateinit var schedulers: Schedulers
    private lateinit var delegate: EmulatorDelegate

    private lateinit var dsRenderer: DSRenderer
    private lateinit var melonTouchHandler: MelonTouchHandler
    private lateinit var nativeInputListener: INativeInputListener
    private val frontendInputHandler = object : FrontendInputHandler() {
        private var fastForwardEnabled = false

        override fun onSoftInputTogglePressed() {
            toggleSoftInputVisibility()
        }

        override fun onPausePressed() {
            if (emulatorReady) {
                showPauseMenu()
            }
        }

        override fun onFastForwardPressed() {
            fastForwardEnabled = !fastForwardEnabled
            MelonEmulator.setFastForwardEnabled(fastForwardEnabled)
        }

        override fun onResetPressed() {
            if (emulatorReady) {
                resetEmulation()
            }
        }

        override fun onSwapScreens() {
            swapScreen()
        }

        override fun onQuickSave() {
            performQuickSave()
        }

        override fun onQuickLoad() {
            performQuickLoad()
        }

        override fun onRewind() {
            if (viewModel.isRewindEnabled()) {
                pauseEmulation()
                openRewindWindow()
            } else {
                Toast.makeText(this@EmulatorActivity, R.string.rewind_not_enabled, Toast.LENGTH_SHORT).show()
            }
        }
    }
    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val newEmulatorConfiguration = delegate.getEmulatorConfiguration()
        MelonEmulator.updateEmulatorConfiguration(newEmulatorConfiguration)
        dsRenderer.updateRendererConfiguration(newEmulatorConfiguration.rendererConfiguration)
        updateSoftInput()
        setupInputHandling()
        setupSustainedPerformanceMode()
        setupFpsCounter()
    }
    private val cheatsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        cheatsClosedListener?.invoke()
        cheatsClosedListener = null
    }
    private val microphonePermissionRequester = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        microphonePermissionSubject.onNext(it)
    }
    private val cameraPermissionRequester = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) {
            cameraPermissionGrantedListener?.invoke()
            cameraPermissionGrantedListener = null
        }
    }
    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            handleBackPressed()
        }
    }

    private var cameraManager: AndroidCameraManager? = null
    private val rewindSaveStateAdapter = RewindSaveStateAdapter {
        MelonEmulator.loadRewindState(it)
        closeRewindWindow()
    }
    private val retroAchievementsCallback = object : RetroAchievementsCallback {
        override fun onAchievementPrimed(achievementId: Long) {
            Log.d("RetroAchievements", "Achievement primed: $achievementId")
        }

        override fun onAchievementTriggered(achievementId: Long) {
            Log.d("RetroAchievements", "Achievement triggered: $achievementId")
            viewModel.onAchievementTriggered(achievementId)
        }

        override fun onAchievementUnprimed(achievementId: Long) {
            Log.d("RetroAchievements", "Achievement unprimed: $achievementId")
        }
    }
    private val microphonePermissionSubject = PublishSubject.create<Boolean>()
    private var emulatorSetupDisposable: Disposable? = null
    private var cheatsClosedListener: (() -> Unit)? = null
    private var cameraPermissionGrantedListener: (() -> Unit)? = null
    private var emulatorReady = false
    private var emulatorPaused = false
    private var softInputVisible = true
    private var screensSwapped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmulatorBinding.inflate(layoutInflater)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setupFullscreen()
        initializeDelegate()

        onBackPressedDispatcher.addCallback(backPressedCallback)

        melonTouchHandler = MelonTouchHandler()
        dsRenderer = DSRenderer(buildRendererConfiguration(), this)
        dsRenderer.setRendererListener(this)
        binding.surfaceMain.apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            /*setEGLConfigChooser(8, 8, 8, 8, 0, 0)
            holder.setFormat(PixelFormat.RGBA_8888)*/
            setRenderer(dsRenderer)
        }

        binding.textFps.visibility = View.INVISIBLE
        binding.viewLayoutControls.setLayoutComponentViewBuilderFactory(RuntimeLayoutComponentViewBuilderFactory())
        binding.layoutRewind.setOnClickListener {
            closeRewindWindow()
        }
        binding.listRewind.apply {
            val listLayoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, true)
            layoutManager = listLayoutManager
            addItemDecoration(EdgeSpacingDecorator())
            adapter = rewindSaveStateAdapter
        }

        viewModel.getBackground().observe(this) {
            dsRenderer.setBackground(it)
        }
        viewModel.getLayout().observe(this) {
            softInputVisible = true
            setupSoftInput(it)
        }

        setupInputHandling()
        launchEmulator()

        binding.layoutAchievement.setContent {
            var currentAchievement by remember {
                mutableStateOf<RAAchievement?>(null)
            }
            var popupOffset by remember {
                mutableStateOf(-1f)
            }
            var popupHeight by remember {
                mutableStateOf<Int?>(null)
            }

            LaunchedEffect(null) {
                viewModel.achievementTriggeredEvent.collect {
                    currentAchievement = it
                    animate(
                        initialValue = -1f,
                        targetValue = 0f,
                        animationSpec = tween(easing = LinearEasing),
                    ) { value, _ ->
                        popupOffset = value
                    }
                    delay(5500)
                    animate(
                        initialValue = 0f,
                        targetValue = -1f,
                        animationSpec = tween(easing = LinearEasing),
                    ) { value, _ ->
                        popupOffset = value
                    }
                    currentAchievement = null
                }
            }

            Box(Modifier.fillMaxWidth()) {
                currentAchievement?.let { achievement ->
                    AchievementPopupUi(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = (popupOffset * (popupHeight ?: Int.MAX_VALUE)).dp)
                            .onSizeChanged { popupHeight = it.height },
                        achievement = achievement,
                    )
                }
            }
        }
    }

    private fun initializeDelegate() {
        val bootFirmwareOnly = intent.extras?.getBoolean(KEY_BOOT_FIRMWARE_ONLY) ?: false
        delegate = if (bootFirmwareOnly) FirmwareEmulatorDelegate(this) else RomEmulatorDelegate(this, picasso)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        if (emulatorReady) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            MelonEmulator.pauseEmulation()
            emulatorPaused = true
            emulatorReady = false
            backPressedCallback.isEnabled = false

            AlertDialog.Builder(this)
                    .setTitle(getString(R.string.title_emulator_running))
                    .setMessage(getString(R.string.message_stop_emulation))
                    .setPositiveButton(R.string.ok) { _, _ ->
                        MelonEmulator.stopEmulation()
                        emulatorPaused = false
                        setIntent(intent)
                        delegate.dispose()
                        initializeDelegate()
                        launchEmulator()
                    }
                    .setNegativeButton(R.string.no) { dialog, _ ->
                        dialog.cancel()
                    }
                    .setOnCancelListener {
                        emulatorReady = true
                        backPressedCallback.isEnabled = true
                        resumeEmulation()
                    }
                    .show()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.surfaceMain.onResume()

        if (emulatorReady && !emulatorPaused) {
            MelonEmulator.resumeEmulation()
        }
    }

    private fun launchEmulator() {
        // Force view model resolution
        viewModel.let { }
        val setupObservable = delegate.getEmulatorSetupObservable(intent.extras)

        cameraManager?.destroy()
        cameraManager = AndroidCameraManager(this, this) {
            cameraPermissionGrantedListener = it
            cameraPermissionRequester.launch(Manifest.permission.CAMERA)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        emulatorSetupDisposable = setupObservable.subscribeOn(schedulers.backgroundThreadScheduler)
                .observeOn(schedulers.uiThreadScheduler)
                .doOnSubscribe {
                    runOnUiThread {
                        binding.viewLayoutControls.isInvisible = true
                        binding.textFps.isGone = true
                        binding.textLoading.isVisible = true
                    }
                }
                .subscribe({
                    try {
                        setupSustainedPerformanceMode()
                        MelonEmulator.startEmulation()
                        setupFpsCounter()
                        binding.textLoading.visibility = View.GONE
                        binding.viewLayoutControls.isVisible = true
                        dsRenderer.canRenderBackground = true
                        emulatorReady = true
                        backPressedCallback.isEnabled = true
                    } catch (e: Exception) {
                        showLaunchFailDialog(e)
                    }
                }) {
                    cameraManager?.destroy()
                    cameraManager = null
                    it.printStackTrace()
                    showLaunchFailDialog(it)
                }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        setupFullscreen()
    }

    private fun buildRendererConfiguration(): RendererConfiguration {
        return RendererConfiguration(settingsRepository.getVideoFiltering(), settingsRepository.isThreadedRenderingEnabled())
    }

    private fun setupFullscreen() {
        window.insetsControllerCompat?.let {
            it.hide(WindowInsetsCompat.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun setupSustainedPerformanceMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            window.setSustainedPerformanceMode(viewModel.isSustainedPerformanceModeEnabled())
        }
    }

    private fun setupFpsCounter() {
        val fpsCounterPosition = viewModel.getFpsCounterPosition()
        if (fpsCounterPosition == FpsCounterPosition.HIDDEN) {
            binding.textFps.isGone = true
        } else {
            binding.textFps.isVisible = true
            val newParams = ConstraintLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT)
            when (fpsCounterPosition) {
                FpsCounterPosition.TOP_LEFT -> {
                    newParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    newParams.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                }
                FpsCounterPosition.TOP_CENTER -> {
                    newParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    newParams.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                    newParams.rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                }
                FpsCounterPosition.TOP_RIGHT -> {
                    newParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    newParams.rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                }
                FpsCounterPosition.BOTTOM_LEFT -> {
                    newParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    newParams.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                }
                FpsCounterPosition.BOTTOM_CENTER -> {
                    newParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    newParams.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                    newParams.rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                }
                FpsCounterPosition.BOTTOM_RIGHT -> {
                    newParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    newParams.rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                }
                FpsCounterPosition.HIDDEN -> { /* Do nothing here */ }
            }
            binding.textFps.layoutParams = newParams
        }
    }

    private fun setupSoftInput(layoutConfiguration: LayoutConfiguration) {
        val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateRendererScreenAreas()

            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                viewModel.setSystemOrientation(Orientation.PORTRAIT)
            } else {
                viewModel.setSystemOrientation(Orientation.LANDSCAPE)
            }
            updateSoftInput()
        }
        binding.viewLayoutControls.addOnLayoutChangeListener(layoutChangeListener)

        val orientationChanged = setLayoutOrientation(layoutConfiguration.orientation)
        if (!orientationChanged) {
            binding.viewLayoutControls.instantiateLayout(layoutConfiguration)
        }
    }

    private fun updateSoftInput() {
        if (settingsRepository.showSoftInput()) {
            val opacity = viewModel.getSoftInputOpacity()
            val inputAlpha = opacity / 100f

            val enableHapticFeedback = viewModel.isTouchHapticFeedbackEnabled()
            binding.viewLayoutControls.getLayoutComponentView(LayoutComponent.DPAD)?.view?.setOnTouchListener(DpadInputHandler(melonTouchHandler, enableHapticFeedback, touchVibrator))
            binding.viewLayoutControls.getLayoutComponentView(LayoutComponent.BUTTONS)?.view?.setOnTouchListener(ButtonsInputHandler(melonTouchHandler, enableHapticFeedback, touchVibrator))
            binding.viewLayoutControls.getLayoutComponentView(LayoutComponent.BUTTON_L)?.view?.setOnTouchListener(SingleButtonInputHandler(melonTouchHandler, Input.L, enableHapticFeedback, touchVibrator))
            binding.viewLayoutControls.getLayoutComponentView(LayoutComponent.BUTTON_R)?.view?.setOnTouchListener(SingleButtonInputHandler(melonTouchHandler, Input.R, enableHapticFeedback, touchVibrator))
            binding.viewLayoutControls.getLayoutComponentView(LayoutComponent.BUTTON_SELECT)?.view?.setOnTouchListener(SingleButtonInputHandler(melonTouchHandler, Input.SELECT, enableHapticFeedback, touchVibrator))
            binding.viewLayoutControls.getLayoutComponentView(LayoutComponent.BUTTON_START)?.view?.setOnTouchListener(SingleButtonInputHandler(melonTouchHandler, Input.START, enableHapticFeedback, touchVibrator))
            binding.viewLayoutControls.getLayoutComponentView(LayoutComponent.BUTTON_HINGE)?.view?.setOnTouchListener(SingleButtonInputHandler(melonTouchHandler, Input.HINGE, enableHapticFeedback, touchVibrator))
            binding.viewLayoutControls.getLayoutComponentView(LayoutComponent.BUTTON_RESET)?.view?.setOnTouchListener(SingleButtonInputHandler(frontendInputHandler, Input.RESET, enableHapticFeedback, touchVibrator))
            binding.viewLayoutControls.getLayoutComponentView(LayoutComponent.BUTTON_PAUSE)?.view?.setOnTouchListener(SingleButtonInputHandler(frontendInputHandler, Input.PAUSE, enableHapticFeedback, touchVibrator))
            binding.viewLayoutControls.getLayoutComponentView(LayoutComponent.BUTTON_FAST_FORWARD_TOGGLE)?.view?.setOnTouchListener(SingleButtonInputHandler(frontendInputHandler, Input.FAST_FORWARD, enableHapticFeedback, touchVibrator))
            binding.viewLayoutControls.getLayoutComponentView(LayoutComponent.BUTTON_TOGGLE_SOFT_INPUT)?.view?.setOnTouchListener(SingleButtonInputHandler(frontendInputHandler, Input.TOGGLE_SOFT_INPUT, enableHapticFeedback, touchVibrator))
            binding.viewLayoutControls.getLayoutComponentView(LayoutComponent.BUTTON_SWAP_SCREENS)?.view?.setOnTouchListener(SingleButtonInputHandler(frontendInputHandler, Input.SWAP_SCREENS, enableHapticFeedback, touchVibrator))
            binding.viewLayoutControls.getLayoutComponentView(LayoutComponent.BUTTON_QUICK_SAVE)?.view?.setOnTouchListener(SingleButtonInputHandler(frontendInputHandler, Input.QUICK_SAVE, enableHapticFeedback, touchVibrator))
            binding.viewLayoutControls.getLayoutComponentView(LayoutComponent.BUTTON_QUICK_LOAD)?.view?.setOnTouchListener(SingleButtonInputHandler(frontendInputHandler, Input.QUICK_LOAD, enableHapticFeedback, touchVibrator))
            binding.viewLayoutControls.getLayoutComponentView(LayoutComponent.BUTTON_REWIND)?.view?.setOnTouchListener(SingleButtonInputHandler(frontendInputHandler, Input.REWIND, enableHapticFeedback, touchVibrator))

            binding.viewLayoutControls.getLayoutComponentViews().forEach {
                if (!it.component.isScreen()) {
                    it.view.apply {
                        visibility = View.VISIBLE
                        alpha = inputAlpha
                    }
                }
            }
            binding.viewLayoutControls.setSoftInputVisibility(softInputVisible)
        } else {
            binding.viewLayoutControls.getLayoutComponentViews().forEach {
                if (!it.component.isScreen()) {
                    it.view.apply {
                        visibility = View.GONE
                    }
                }
            }
        }

        val touchScreenComponent = if (screensSwapped) {
            LayoutComponent.TOP_SCREEN
        } else {
            LayoutComponent.BOTTOM_SCREEN
        }
        binding.viewLayoutControls.getLayoutComponentView(touchScreenComponent)?.view?.setOnTouchListener(TouchscreenInputHandler(melonTouchHandler))
    }

    private fun toggleSoftInputVisibility() {
        softInputVisible = !softInputVisible
        binding.viewLayoutControls.setSoftInputVisibility(softInputVisible)
    }

    private fun swapScreen() {
        screensSwapped = !screensSwapped

        updateRendererScreenAreas()
        updateSoftInput()
    }

    private fun performQuickSave() {
        delegate.performQuickSave()
    }

    private fun performQuickLoad() {
        delegate.performQuickLoad()
    }

    private fun updateRendererScreenAreas() {
        val (topScreen, bottomScreen) = if (screensSwapped) {
            LayoutComponent.BOTTOM_SCREEN to LayoutComponent.TOP_SCREEN
        } else {
            LayoutComponent.TOP_SCREEN to LayoutComponent.BOTTOM_SCREEN
        }
        dsRenderer.updateScreenAreas(
                binding.viewLayoutControls.getLayoutComponentView(topScreen)?.getRect(),
                binding.viewLayoutControls.getLayoutComponentView(bottomScreen)?.getRect()
        )
    }

    private fun setupInputHandling() {
        nativeInputListener = InputProcessor(settingsRepository.getControllerConfiguration(), melonTouchHandler, frontendInputHandler)
    }

    private fun handleBackPressed() {
        if (isRewindWindowOpen()) {
            closeRewindWindow()
        } else {
            this.showPauseMenu()
        }
    }

    private fun showPauseMenu() {
        pauseEmulation()
        val values = delegate.getPauseMenuOptions()
        val options = Array(values.size) { i -> getString(values[i].textResource) }

        AlertDialog.Builder(this)
                .setTitle(R.string.pause)
                .setItems(options) { _, which -> delegate.onPauseMenuOptionSelected(values[which]) }
                .setOnCancelListener { resumeEmulation() }
                .show()
    }

    private fun pauseEmulation() {
        emulatorPaused = true
        MelonEmulator.pauseEmulation()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun resumeEmulation() {
        emulatorPaused = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        MelonEmulator.resumeEmulation()
    }

    fun resetEmulation() {
        val result = MelonEmulator.resetEmulation()
        if (!result) {
            Toast.makeText(this, R.string.failed_reset_emulation, Toast.LENGTH_SHORT).show()
        }
    }

    fun getRetroAchievementsCallback(): RetroAchievementsCallback {
        return retroAchievementsCallback
    }

    fun getRendererTextureBuffer(): ByteBuffer {
        return dsRenderer.textureBuffer
    }

    fun getCameraManager(): CameraManager? {
        return cameraManager
    }

    fun takeScreenshot(): Bitmap {
        return dsRenderer.takeScreenshot()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isRewindWindowOpen() && nativeInputListener.onKeyEvent(event))
            return true

        return super.dispatchKeyEvent(event)
    }

    override fun onFrameRendered() {
        if (!emulatorReady)
            return

        val fps = MelonEmulator.getFPS()
        runOnUiThread { binding.textFps.text = getString(R.string.info_fps, fps) }
    }

    fun openSettings() {
        // Allow emulator to resume once the user returns from Settings
        emulatorPaused = false
        val settingsIntent = Intent(this, SettingsActivity::class.java)
        settingsLauncher.launch(Intent(settingsIntent))
    }

    fun openCheats(rom: Rom, onCheatsClosed: () -> Unit) {
        cheatsClosedListener = onCheatsClosed
        val romInfo = viewModel.getRomInfo(rom) ?: return

        val intent = Intent(this, CheatsActivity::class.java)
        intent.putExtra(CheatsActivity.KEY_ROM_INFO, RomInfoParcelable.fromRomInfo(romInfo))
        cheatsLauncher.launch(intent)
    }

    private fun isRewindWindowOpen(): Boolean {
        return binding.root.currentState == R.id.rewind_visible
    }

    fun openRewindWindow() {
        binding.root.transitionToState(R.id.rewind_visible)
        val rewindWindow = MelonEmulator.getRewindWindow()
        rewindSaveStateAdapter.setRewindWindow(rewindWindow)
    }

    private fun closeRewindWindow() {
        binding.root.transitionToState(R.id.rewind_hidden)
        MelonEmulator.resumeEmulation()
    }

    fun rewindToState(state: RewindSaveState) {
        if (!MelonEmulator.loadRewindState(state)) {
            Toast.makeText(this@EmulatorActivity, "Failed to rewind", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Returns a [Single] that emits the emulator's configuration taking into account permissions that have not been granted. If the provided base configuration requires the
     * use of certain permissions and [requestPermissions] is true, they will be requested to the user before returning the final configuration.
     */
    fun adjustEmulatorConfigurationForPermissions(baseConfiguration: EmulatorConfiguration, requestPermissions: Boolean): Single<EmulatorConfiguration> {
        if (baseConfiguration.micSource == MicSource.DEVICE) {
            if (!isMicrophonePermissionGranted()) {
                return if (requestPermissions) {
                    requestMicrophonePermission().map { granted ->
                        if (granted) {
                            baseConfiguration
                        } else {
                            baseConfiguration.copy(micSource = MicSource.NONE)
                        }
                    }
                } else {
                    Single.just(baseConfiguration.copy(micSource = MicSource.NONE))
                }
            }
        }

        return Single.just(baseConfiguration)
    }

    private fun requestMicrophonePermission(): Single<Boolean> {
        return microphonePermissionSubject
                .first(false)
                .subscribeOn(schedulers.uiThreadScheduler)
                .doOnSubscribe {
                    microphonePermissionRequester.launch(Manifest.permission.RECORD_AUDIO)
                }
    }

    private fun showLaunchFailDialog(e: Throwable) {
        AlertDialog.Builder(this)
                .setTitle(R.string.error_load_rom)
                .setMessage(R.string.error_load_rom_report_issue)
                .setPositiveButton(R.string.ok) { dialog, _ ->
                    val intent = getGitHubReportIntent(e)
                    startActivity(intent)
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel) { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(true)
                .setOnDismissListener {
                    finish()
                }
                .show()
    }

    private fun getGitHubReportIntent(e: Throwable): Intent {
        val packageInfo = PackageManagerCompat.getPackageInfo(packageManager, packageName, 0)

        val errorBody = "" +
                "* **Device model:** ${Build.MODEL}\n" +
                "* **Android version:** Android API ${Build.VERSION.SDK_INT}\n" +
                "* **melonDS version:** ${packageInfo.versionName}\n\n" +
                "**Problem:**  \n" +
                "Insert a small description of the problem.\n\n" +
                "**Stack trace:**  \n" +
                "```\n" +
                e.stackTraceToString() +
                "\n```\n\n" +
                "**Configuration:**  \n" +
                "```\n" +
                delegate.getCrashContext() +
                "\n```\n"

        val urlEncodedBody = URLEncoder.encode(errorBody, "utf-8")
        val url = "https://github.com/rafaelvcaetano/melonDS-android/issues/new?labels=app%20report&body=$urlEncodedBody"

        return Intent(Intent.ACTION_VIEW).apply {
            data = url.toUri()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.surfaceMain.onPause()

        if (emulatorReady && !emulatorPaused) {
            MelonEmulator.pauseEmulation()
        }
    }

    override fun onDestroy() {
        if (emulatorReady) {
            MelonEmulator.stopEmulation()
        }

        cameraManager?.destroy()
        microphonePermissionSubject.onComplete()
        emulatorSetupDisposable?.dispose()
        delegate.dispose()
        super.onDestroy()
    }
}