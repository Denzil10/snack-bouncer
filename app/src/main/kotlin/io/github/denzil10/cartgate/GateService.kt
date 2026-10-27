package io.github.denzil10.cartgate

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * While a listed shopping app's cart holds an item the rules block, an overlay covers its pay
 * bar (JunkGate.kt). Screen text is read only when a listed app is in front; nothing is stored
 * or sent anywhere (the app has no internet permission).
 */
// recycle() is a no-op from API 33 but still frees nodes on the older phones minSdk 26 allows.
@Suppress("DEPRECATION")
class GateService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val evaluate = Runnable { evaluateGate() }
    private val logTag = "CartGate"

    private var defaults: String? = null
    private var choices: Choices? = null
    private var rules: JunkRules? = null

    private var gateView: View? = null
    /** Items and pay bar position of the gate on screen, so unchanged reads keep it as is. */
    private var gateKey: String? = null
    private var gatePayTop = 0
    /** The title stays put while the cart re-renders the same items (hide, then show again). */
    private var titleKey: String? = null
    private var gateTitle = ""
    private var lastWasJoke = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return
        val current = currentRules()
        // Another app's events matter only to take a showing gate down.
        if (current == null || (pkg !in current.apps && gateView == null)) return
        // Cart screens (Instamart's WebView above all) fill in over many content events.
        handler.removeCallbacks(evaluate)
        handler.postDelayed(evaluate, evaluateDelayMs)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(evaluate)
        hideGate()
    }

    // The system can re-bind a new instance without destroying this one; its overlay token
    // dies with the binding, so clean up here.
    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(evaluate)
        hideGate()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(evaluate)
        hideGate()
        super.onDestroy()
    }

    private fun evaluateGate() {
        val current = currentRules()
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (current == null || root == null || pkg == null || pkg !in current.apps) {
            root?.recycle()
            hideGate()
            return
        }
        val texts = mutableListOf<ScreenText>()
        collect(root, texts, Rect())
        root.recycle()
        val gate = JunkGate.decide(current, pkg, texts)
        if (gate == null) {
            hideGate()
            return
        }
        val key = gate.items.joinToString { it.name }
        if (key == gateKey && gate.payTop == gatePayTop) return
        Log.i(logTag, "gate $key payTop=${gate.payTop}")
        showGate(current, gate, key)
    }

    /** The rules from the app's defaults and this phone's choices; none until the disclosure is accepted. */
    private fun currentRules(): JunkRules? {
        val now = Choices.load(this)
        if (now != choices) {
            choices = now
            val base = defaults ?: Choices.defaults(this).also { defaults = it }
            rules = if (now.consented) JunkRules.parse(now.apply(base)) else null
            Log.i(logTag, "rules ${if (rules != null) "loaded" else "off"}")
        }
        return rules
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<ScreenText>, bounds: Rect) {
        node.getBoundsInScreen(bounds)
        node.text?.takeIf { it.isNotBlank() }?.let { out.add(ScreenText(it.toString(), bounds.top, bounds.bottom)) }
        node.contentDescription?.takeIf { it.isNotBlank() }
            ?.let { out.add(ScreenText(it.toString(), bounds.top, bounds.bottom)) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, bounds)
            child.recycle()
        }
    }

    private fun showGate(current: JunkRules, gate: Gate, key: String) {
        val lines = current.lines
        val lead = gate.items.first()
        val title = if (key == titleKey) gateTitle else pickTitle(lines).replace("{item}", lead.name)
        val body = lines.blockBody.replace("{item}", lead.name).replace("{swap}", lead.swap)

        val view = gateLayout(title, body)
        val screen = screenSize()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(screen.width(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        // Cover the pay bar to the bottom of the screen; grow upward when the card needs room.
        val top = minOf(gate.payTop - dp(12), screen.height() - view.measuredHeight).coerceAtLeast(0)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            screen.height() - top,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.y = top
        removeGateView()
        try {
            windowManager?.addView(view, params)
            gateView = view
            gateKey = key
            titleKey = key
            gateTitle = title
            gatePayTop = gate.payTop
        } catch (error: Exception) {
            Log.w(logTag, "gate overlay failed", error)
        }
    }

    /** The joke line about one time in jokeEvery, never twice running. */
    private fun pickTitle(lines: JunkRules.Lines): String {
        val joke = lines.blockJoke.isNotEmpty() && lines.jokeEvery > 0 && !lastWasJoke &&
            Random.nextInt(lines.jokeEvery) == 0
        lastWasJoke = joke
        return (if (joke) lines.blockJoke else lines.block).random()
    }

    private fun gateLayout(title: String, body: String): View {
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                this.text = title
                setTextColor(INK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                this.text = body
                setTextColor(INK_2)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, dp(4), 0, 0)
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(20), dp(20), dp(20), dp(28))
            background = GradientDrawable().apply {
                setColor(PAPER)
                cornerRadii = FloatArray(8) { if (it < 4) dp(24).toFloat() else 0f }
            }
            // Touches on the card stop here, so the pay button under it cannot be pressed.
            isClickable = true
            addView(TextView(context).apply {
                this.text = "✋"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(16) })
            addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun screenSize(): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager!!.maximumWindowMetrics.bounds
        } else {
            val metrics = resources.displayMetrics
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun hideGate() {
        removeGateView()
        gateKey = null
    }

    private fun removeGateView() {
        val view = gateView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        }
        gateView = null
    }

    private companion object {
        const val evaluateDelayMs = 250L
        const val INK = 0xFF3B2F2F.toInt()
        const val INK_2 = 0xFF6B5A55.toInt()
        const val PAPER = 0xFFFFF7F0.toInt()
    }
}
