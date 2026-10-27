package io.github.denzil10.cartgate

import org.json.JSONArray
import org.json.JSONObject

/** The rules in effect: `assets/rules.json` with the choices made on the phone applied (Choices.kt). */
data class JunkRules(
    val categories: List<Category>,
    /** Sugar-free wording lets an item through in any category. */
    val allowTerms: List<Regex>,
    val lines: Lines,
    /** The pay button, the same wording across apps; a screen showing it is a cart. */
    val pay: Regex,
    /** A cart line's own control: a remove/decrease label, a bare − or its quantity. */
    val remove: Regex,
    val apps: Set<String>,
) {
    enum class Tier { ALLOW, BLOCK }

    /** [terms] block; [allow] names the mild kinds of the same thing (rice cake, Coke Zero). */
    data class Category(
        val id: String,
        val terms: List<Pair<String, Regex>>,
        val allow: List<Regex>,
        val swap: String,
    )

    data class Lines(
        val block: List<String>,
        val blockJoke: List<String>,
        val blockBody: String,
        val jokeEvery: Int,
    )

    companion object {
        fun parse(json: String?): JunkRules? {
            if (json.isNullOrBlank()) return null
            return try {
                val root = JSONObject(json)
                val categories = root.getJSONArray("categories").objects().map {
                    Category(
                        id = it.getString("id"),
                        terms = it.getJSONArray("terms").strings().map { term -> term to wordRegex(term) },
                        allow = it.getJSONArray("allow").strings().map(::wordRegex),
                        swap = it.getString("swap"),
                    )
                }
                val lines = root.getJSONObject("lines")
                JunkRules(
                    categories = categories,
                    allowTerms = root.getJSONArray("allowTerms").strings().map(::wordRegex),
                    lines = Lines(
                        block = lines.getJSONArray("block").strings(),
                        blockJoke = lines.getJSONArray("blockJoke").strings(),
                        blockBody = lines.getString("blockBody"),
                        jokeEvery = lines.optInt("jokeEvery", 0),
                    ),
                    pay = Regex(root.getString("pay")),
                    remove = Regex(root.getString("remove")),
                    apps = root.getJSONArray("apps").strings().toSet(),
                )
            } catch (error: Exception) {
                null
            }
        }

        /** Whole words only: "sting" must not match "testing". */
        private fun wordRegex(term: String) =
            Regex("(?<![\\p{L}\\p{N}])${Regex.escape(normalize(term))}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

        /** Apps write "Lay’s" and "Coca-Cola"; terms say "lay's" and "coca cola". */
        fun normalize(text: String) = text.replace('’', '\'').replace('‘', '\'').replace('-', ' ')

        internal fun JSONArray.strings() = (0 until length()).map { getString(it) }
        internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    }
}

/** A text or content description on screen, with its vertical span in screen pixels. */
data class ScreenText(val text: String, val top: Int, val bottom: Int)

data class CartItem(val name: String, val tier: JunkRules.Tier, val swap: String)

/** Cover the screen from [payTop] down while blocked [items] are in the cart; the first names it. */
data class Gate(val items: List<CartItem>, val payTop: Int)

/** A cart line: its row's text and, when the row names junk, the item. */
data class CartLine(val text: String, val item: CartItem?)

/** A cart screen: its lines (none when the app's rows cannot be read) and its pay button. */
data class Cart(val lines: List<CartLine>, val payTop: Int, val pay: String)

object JunkGate {
    /** Rows taller than this are containers, not a cart line. */
    private const val MAX_ROW_PX = 400

    /** A remove control belongs to the text it sits in, or one within this many pixels. */
    private const val ROW_SLACK_PX = 60

    /**
     * The gate for [pkg]'s screen, or null. A screen is a cart when it shows a pay button;
     * an item counts only when a remove control or quantity sits on its row, so suggestions
     * ("You might also like", "Complete your meal") never trigger the gate.
     */
    fun decide(rules: JunkRules, pkg: String, texts: List<ScreenText>): Gate? {
        return gate(read(rules, pkg, texts) ?: return null)
    }

    fun gate(cart: Cart): Gate? {
        val items = cart.lines.mapNotNull { it.item }.distinct().filter { it.tier == JunkRules.Tier.BLOCK }
        return if (items.isEmpty()) null else Gate(items, cart.payTop)
    }

    /** [pkg]'s screen as a cart, or null when it shows no pay button. */
    fun read(rules: JunkRules, pkg: String, texts: List<ScreenText>): Cart? {
        if (pkg !in rules.apps) return null
        val pay = rules.pay
        val payText = texts.filter { pay.containsMatchIn(it.text) }.minByOrNull { it.top } ?: return null
        val payTop = payText.top
        val above = texts.filter { it.bottom <= payTop && !pay.containsMatchIn(it.text) }
        val (removes, rows) = above.partition { rules.remove.containsMatchIn(it.text) }
        val lines = removes.mapNotNull { control ->
            val centre = (control.top + control.bottom) / 2
            val near = rows.filter { it.bottom - it.top <= MAX_ROW_PX }
                .groupBy { gap(centre, it) }
                .filterKeys { it <= ROW_SLACK_PX }
                .toSortedMap().values
                .map { row -> row.joinToString(" ") { it.text.trim() } }
            // The nearest text that names a product: the closest may be the row's other
            // control (Instamart's "Add 1 item to cart" shares the remove button's span).
            near.firstNotNullOfOrNull { text -> classify(rules, text)?.let { CartLine(text, it) } }
                ?: near.firstOrNull { text -> text.any(Char::isLetter) }?.let { CartLine(it, null) }
        }.distinct()
        return Cart(lines, payTop, payText.text)
    }

    /** The first category naming [text] decides; null when it is not junk at all. */
    fun classify(rules: JunkRules, text: String): CartItem? {
        val plain = JunkRules.normalize(text)
        for (category in rules.categories) {
            // The term read first names it: "Thums Up | … The Coca-Cola Company" is Thums Up.
            val term = category.terms.mapNotNull { (name, regex) -> regex.find(plain)?.let { name to it.range.first } }
                .minByOrNull { it.second }?.first ?: continue
            val mild = (category.allow + rules.allowTerms).any { it.containsMatchIn(plain) }
            return CartItem(titleCase(term), if (mild) JunkRules.Tier.ALLOW else JunkRules.Tier.BLOCK, category.swap)
        }
        return null
    }

    private fun gap(y: Int, row: ScreenText) = when {
        y < row.top -> row.top - y
        y > row.bottom -> y - row.bottom
        else -> 0
    }

    private fun titleCase(term: String) =
        term.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}
