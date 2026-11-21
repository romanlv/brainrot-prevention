package dev.brainrotprevention.platform.enforce

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.brainrotprevention.platform.log.Brp
import dev.brainrotprevention.platform.ui.BlockActions
import dev.brainrotprevention.platform.ui.BlockModel
import dev.brainrotprevention.platform.ui.BlockScreen
import dev.brainrotprevention.platform.ui.BlockTheme

/**
 * The block screen as a full-screen TYPE_ACCESSIBILITY_OVERLAY window (§10.2 step 4–5), plus non-focusable
 * covers over picture-in-picture windows (§10.2 step 3). Main thread only.
 */
class BlockOverlay(
    private val context: Context,
    private val owner: Any, // LifecycleOwner & SavedStateRegistryOwner
    private val debug: Boolean,
    private val actions: BlockActions,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private val model = mutableStateOf<BlockModel?>(null)
    private var backCallback: Any? = null
    private val covers = HashMap<String, View>()

    val isShown: Boolean get() = root != null
    val current: BlockModel? get() = model.value

    fun show(m: BlockModel, reason: String) {
        model.value = m
        if (root != null) return
        val frame = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) actions.close()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        val compose = ComposeView(context).apply {
            setContent {
                val cur = model.value
                if (cur != null) {
                    BlockTheme { BlockScreen(cur, actions, Modifier.debugTags(debug)) }
                }
            }
        }
        frame.setViewTreeLifecycleOwner(owner as LifecycleOwner)
        frame.setViewTreeSavedStateRegistryOwner(owner as SavedStateRegistryOwner)
        compose.setViewTreeLifecycleOwner(owner)
        compose.setViewTreeSavedStateRegistryOwner(owner)
        frame.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "BrainrotPreventionBlock"
        }
        try {
            wm.addView(frame, lp)
        } catch (e: RuntimeException) {
            Brp.event("error", "where" to "overlay-add", "msg" to e.toString())
            return
        }
        root = frame
        if (Build.VERSION.SDK_INT >= 33) {
            frame.post {
                dev.brainrotprevention.platform.guarded("overlay-back") {
                    val d = frame.findOnBackInvokedDispatcher()
                    if (d != null) {
                        val cb = OnBackInvokedCallback { actions.close() }
                        d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb)
                        backCallback = cb
                    }
                }
            }
        }
        Brp.event("overlay", "state" to "shown", "reason" to reason)
    }

    fun update(m: BlockModel) {
        if (root != null) model.value = m
    }

    fun hide(reason: String) {
        val r = root ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            (backCallback as? OnBackInvokedCallback)?.let { cb ->
                runCatching { r.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(cb) }
            }
        }
        backCallback = null
        try {
            wm.removeViewImmediate(r)
        } catch (_: RuntimeException) {
        }
        root = null
        model.value = null
        Brp.event("overlay", "state" to "hidden", "reason" to reason)
    }

    fun hasCover(key: String): Boolean = key in covers

    /**
     * A non-focusable, non-touchable cover over a PiP window that can't be dismissed: it hides the content, and
     * touches pass through to the PiP window, so its close button still works (a touchable cover would trap a
     * PiP that HOME can't remove).
     */
    fun cover(key: String, bounds: Rect) {
        val existing = covers[key]
        val lp = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
            title = "BrainrotPreventionCover"
        }
        try {
            if (existing != null) {
                wm.updateViewLayout(existing, lp)
            } else {
                val v = View(context).apply { setBackgroundColor(0xFF202124.toInt()) }
                wm.addView(v, lp)
                covers[key] = v
                Brp.event("overlay", "state" to "shown", "reason" to "pip-cover")
            }
        } catch (e: RuntimeException) {
            Brp.event("error", "where" to "overlay-cover", "msg" to e.toString())
        }
    }

    fun uncover(keepKeys: Set<String>) {
        val it = covers.entries.iterator()
        while (it.hasNext()) {
            val (k, v) = it.next()
            if (k in keepKeys) continue
            runCatching { wm.removeViewImmediate(v) }
            it.remove()
            Brp.event("overlay", "state" to "hidden", "reason" to "pip-cover")
        }
    }

    fun hideAll(reason: String) {
        hide(reason)
        uncover(emptySet())
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.debugTags(debug: Boolean): Modifier = if (debug) semantics { testTagsAsResourceId = true } else this
