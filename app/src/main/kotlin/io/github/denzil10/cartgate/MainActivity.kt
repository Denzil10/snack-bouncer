package io.github.denzil10.cartgate

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.denzil10.cartgate.JunkRules.Companion.objects
import org.json.JSONObject

/**
 * The only screen: the disclosure and consent the gate needs before Accessibility is turned on,
 * a box to test a product name, and the switches and words that make up the rules.
 */
class MainActivity : Activity() {
    private lateinit var defaults: String
    private lateinit var choices: Choices
    private lateinit var status: LinearLayout
    private lateinit var tryResult: TextView
    private var tried = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        defaults = Choices.defaults(this)
        choices = Choices.load(this)
        val root = JSONObject(defaults)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        page.addView(text("Snack Bouncer", 28f, bold = true))
        page.addView(text(
            "Covers the pay button in shopping apps while your cart holds junk food. " +
                "Remove the item and the button comes back. Mild versions (zero sugar, dark chocolate, baked) go through.",
            15f, muted = true,
        ))

        status = section(page)

        heading(page, "Try a product name")
        val tryBox = EditText(this).apply {
            hint = "Lay's Magic Masala Chips"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            addTextChangedListener(watcher { tried = it; showTry() })
        }
        page.addView(tryBox)
        tryResult = text("", 15f).also(page::addView)

        heading(page, "Block")
        for (category in root.getJSONArray("categories").objects()) {
            val id = category.getString("id")
            val sample = (0 until minOf(4, category.getJSONArray("terms").length()))
                .joinToString(", ") { category.getJSONArray("terms").getString(it) }
            page.addView(switch(category.getString("name"), "$sample…", id !in choices.offCategories) { on ->
                update(choices.copy(offCategories = if (on) choices.offCategories - id else choices.offCategories + id))
            })
        }

        heading(page, "Your own words")
        page.addView(text("Anything else to block, separated by commas. Whole words only.", 14f, muted = true))
        page.addView(EditText(this).apply {
            hint = "gol gappe, cheesecake"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(choices.words.joinToString(", "))
            addTextChangedListener(watcher { update(choices.copy(words = Choices.words(it))) })
        })

        heading(page, "Apps")
        val names = root.getJSONObject("appNames")
        for (i in 0 until root.getJSONArray("apps").length()) {
            val pkg = root.getJSONArray("apps").getString(i)
            page.addView(switch(names.optString(pkg, pkg), pkg, pkg !in choices.offApps) { on ->
                update(choices.copy(offApps = if (on) choices.offApps - pkg else choices.offApps + pkg))
            })
        }

        heading(page, "Privacy")
        page.addView(text(
            "Snack Bouncer has no internet permission: what it reads stays on this phone, and it keeps no history. " +
                "It reads screen text only while one of the apps above is open.",
            14f, muted = true,
        ))
        page.addView(Button(this).apply {
            text = "Source code"
            setOnClickListener { open(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE)), "No browser to open $SOURCE") }
        })

        setContentView(ScrollView(this).apply {
            // Edge to edge from targetSdk 35: keep the page clear of the status and navigation bars.
            fitsSystemWindows = true
            addView(page)
        })
    }

    override fun onResume() {
        super.onResume()
        showStatus()
    }

    private fun update(next: Choices) {
        choices = next
        next.save(this)
        showTry()
    }

    /** Disclosure first; the Accessibility settings button appears only after "I agree". */
    private fun showStatus() {
        status.removeAllViews()
        when {
            !choices.consented -> {
                status.addView(text("Before you turn it on", 17f, bold = true))
                status.addView(text(DISCLOSURE, 15f))
                status.addView(Button(this).apply {
                    text = "I agree, turn it on"
                    setOnClickListener {
                        update(choices.copy(consented = true))
                        showStatus()
                        openAccessibility()
                    }
                })
            }
            !serviceOn() -> {
                status.addView(text("Almost there", 17f, bold = true))
                status.addView(text("In Accessibility settings, open Snack Bouncer and switch it on.", 15f))
                status.addView(Button(this).apply { text = "Open Accessibility settings"; setOnClickListener { openAccessibility() } })
            }
            else -> {
                status.addView(text("Snack Bouncer is on", 17f, bold = true))
                status.addView(text("Open a cart in one of the apps below. To stop it, switch it off in Accessibility settings.", 15f))
                status.addView(Button(this).apply { text = "Accessibility settings"; setOnClickListener { openAccessibility() } })
            }
        }
    }

    private fun showTry() {
        val rules = JunkRules.parse(choices.apply(defaults))
        val item = if (tried.isBlank() || rules == null) null else JunkGate.classify(rules, tried)
        tryResult.text = when {
            tried.isBlank() -> ""
            item == null -> "Goes through: not on your list."
            item.tier == JunkRules.Tier.ALLOW -> "Goes through: a mild kind of ${item.name}."
            else -> "Blocked: ${item.name}. Try ${item.swap}."
        }
    }

    private fun serviceOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, GateService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Some builds (test images, locked-down phones) have no Accessibility screen: fall back to Settings. */
    private fun openAccessibility() {
        if (!open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
            open(Intent(Settings.ACTION_SETTINGS), "Open Settings, then Accessibility, then Snack Bouncer")
        }
    }

    private fun open(intent: Intent, otherwise: String? = null): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        otherwise?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        false
    }

    private fun section(page: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), 0x40808080)
        }
        page.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
    }

    private fun heading(page: LinearLayout, title: String) {
        page.addView(text(title, 18f, bold = true).apply { setPadding(0, dp(28), 0, dp(4)) })
    }

    private fun text(value: String, sp: Float, bold: Boolean = false, muted: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (muted) alpha = 0.7f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun switch(title: String, detail: String, on: Boolean, changed: (Boolean) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, dp(6))
        val label = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 16f).apply { setPadding(0, 0, 0, 0) })
            addView(text(detail, 13f, muted = true).apply { setPadding(0, 0, 0, 0) })
        }
        addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        addView(Switch(context).apply {
            isChecked = on
            contentDescription = title
            setOnCheckedChangeListener { _, checked -> changed(checked) }
        })
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun watcher(changed: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = changed(s?.toString() ?: "")
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val SOURCE = "https://github.com/Denzil10/snack-bouncer"
        const val DISCLOSURE =
            "Snack Bouncer uses Android's Accessibility service to read the text on screen while Zepto, Swiggy, " +
                "Instamart, Blinkit, Zomato or Amazon is open. It looks for the pay button and the items in your cart, " +
                "and if one is on your Block list it covers the pay button until you remove it.\n\n" +
                "It reads nothing in other apps, keeps no history and cannot send anything anywhere: " +
                "the app has no internet permission. You can switch it off any time in Accessibility settings."
    }
}
