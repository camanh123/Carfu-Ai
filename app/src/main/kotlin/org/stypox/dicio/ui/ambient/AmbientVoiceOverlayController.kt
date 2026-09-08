package org.stypox.dicio.ui.ambient

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.stypox.dicio.io.session.CarfuLog
import org.stypox.dicio.io.session.CommandSession
import org.stypox.dicio.io.session.CommandUiState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cross-app Ambient Voice HUD via [WindowManager] when overlay permission is granted.
 *
 * Observes [CommandSession.ui] only. Never creates/restarts VoiceSession, SR, TTS, or commands.
 */
@Singleton
class AmbientVoiceOverlayController @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val commandSession: CommandSession,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var collectJob: Job? = null

    private var windowManager: WindowManager? = null
    private var hostView: FrameLayout? = null
    private var composeView: ComposeView? = null
    private var overlayOwner: OverlayLifecycleOwner? = null

    @Volatile
    private var started = false

    private var uiState by mutableStateOf(AmbientVoiceUiState(visible = false))

    fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(appContext)

    fun ensureStarted() {
        if (started) return
        started = true
        collectJob = scope.launch {
            commandSession.ui.collectLatest { ui ->
                onCommandUi(ui)
            }
        }
        CarfuLog.i(TAG, "AMBIENT_OVERLAY_CONTROLLER started")
    }

    fun shutdown() {
        collectJob?.cancel()
        collectJob = null
        started = false
        mainHandler.post { removeOverlay() }
    }

    private fun onCommandUi(ui: CommandUiState) {
        val next = AmbientVoicePresentation.fromSession(ui.phase, ui.partial)
        uiState = next
        if (!AmbientVoiceAttachPolicy.shouldAttachCrossAppOverlay(
                canDrawOverlays = canDrawOverlays(),
                hudVisible = next.visible,
            )
        ) {
            // Phase 4.1: voice continues without WindowManager Compose attach.
            if (hostView != null) removeOverlay()
            return
        }
        if (next.visible) {
            try {
                ensureOverlayAttached()
            } catch (t: Throwable) {
                CarfuLog.w(TAG, "AMBIENT_OVERLAY_ATTACH_SKIPPED ${t.javaClass.simpleName}")
                removeOverlay()
            }
        } else {
            // Keep view briefly so AnimatedVisibility can fade, then detach.
            mainHandler.postDelayed({
                if (!uiState.visible) removeOverlay()
            }, AmbientVoicePresentation.FADE_MS.toLong() + 40L)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun ensureOverlayAttached() {
        if (hostView != null) return
        try {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm
            val owner = OverlayLifecycleOwner().also { it.onCreate() }
            overlayOwner = owner

            val host = FrameLayout(appContext)
            val compose = ComposeView(appContext).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent {
                    AmbientVoiceOverlay(state = uiState)
                }
            }
            host.addView(
                compose,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            hostView = host
            composeView = compose

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 8
            }
            wm.addView(host, params)
            owner.onResume()
            CarfuLog.i(TAG, "AMBIENT_OVERLAY_ATTACHED cross_app=true")
        } catch (t: Throwable) {
            CarfuLog.w(TAG, "AMBIENT_OVERLAY_ATTACH_FAILED ${t.javaClass.simpleName}")
            removeOverlay()
        }
    }

    private fun removeOverlay() {
        val host = hostView ?: return
        val wm = windowManager
        try {
            overlayOwner?.onDestroy()
            wm?.removeViewImmediate(host)
        } catch (_: Throwable) {
        }
        hostView = null
        composeView = null
        overlayOwner = null
        CarfuLog.i(TAG, "AMBIENT_OVERLAY_DETACHED")
    }

    /**
     * Minimal Lifecycle/SavedState owners so ComposeView can run outside an Activity.
     */
    private class OverlayLifecycleOwner :
        LifecycleOwner,
        ViewModelStoreOwner,
        SavedStateRegistryOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        fun onCreate() {
            // ComposeView outside Activity requires attach → restore before CREATED.
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        fun onResume() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun onDestroy() {
            try {
                if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                    if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                        }
                        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                    }
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                }
            } catch (_: Throwable) {
            }
            store.clear()
        }
    }

    companion object {
        const val TAG = "CarfuAmbient"
    }
}
