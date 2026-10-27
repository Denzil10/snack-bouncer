package io.github.denzil10.cartgate

import android.content.Context
import io.github.denzil10.cartgate.JunkRules.Companion.objects
import io.github.denzil10.cartgate.JunkRules.Companion.strings
import org.json.JSONArray
import org.json.JSONObject

/** What was changed on this phone: consent, categories and apps switched off, extra words to block. */
data class Choices(
    val consented: Boolean = false,
    val offCategories: Set<String> = emptySet(),
    val offApps: Set<String> = emptySet(),
    val words: List<String> = emptyList(),
) {
    /** [defaults] (assets/rules.json) with these choices applied, in the shape [JunkRules.parse] reads. */
    fun apply(defaults: String): String {
        val root = JSONObject(defaults)
        val categories = root.getJSONArray("categories").objects().filter { it.getString("id") !in offCategories }
        // Own words come first, so they name the item even when a listed category also matches.
        val mine = if (words.isEmpty()) emptyList() else listOf(
            JSONObject().put("id", MINE).put("name", "Your words").put("terms", JSONArray(words))
                .put("allow", JSONArray()).put("swap", "something else"),
        )
        root.put("categories", JSONArray(mine + categories))
        root.put("apps", JSONArray(root.getJSONArray("apps").strings().filter { it !in offApps }))
        return root.toString()
    }

    fun save(context: Context) {
        prefs(context).edit()
            .putBoolean("consented", consented)
            .putStringSet("offCategories", offCategories)
            .putStringSet("offApps", offApps)
            .putString("words", words.joinToString(", "))
            .apply()
    }

    companion object {
        const val MINE = "mine"

        /** "Haldiram, gol gappe" → [haldiram, gol gappe]. */
        fun words(text: String) = text.split(',', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

        fun load(context: Context): Choices {
            val prefs = prefs(context)
            return Choices(
                consented = prefs.getBoolean("consented", false),
                offCategories = prefs.getStringSet("offCategories", null)?.toSet() ?: emptySet(),
                offApps = prefs.getStringSet("offApps", null)?.toSet() ?: emptySet(),
                words = words(prefs.getString("words", null) ?: ""),
            )
        }

        fun defaults(context: Context) = context.assets.open("rules.json").bufferedReader().use { it.readText() }

        private fun prefs(context: Context) = context.getSharedPreferences("choices", Context.MODE_PRIVATE)
    }
}
