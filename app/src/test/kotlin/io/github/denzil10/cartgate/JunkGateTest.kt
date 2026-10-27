package io.github.denzil10.cartgate

import io.github.denzil10.cartgate.JunkRules.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class JunkGateTest {
    // The rules the app ships with; unit tests run from the app module's directory.
    private val defaults = File("src/main/assets/rules.json").readText()
    private val rules = JunkRules.parse(defaults)!!
    private val zepto = "com.zeptoconsumerapp"
    private val swiggy = "in.swiggy.android"
    private val zomato = "com.application.zomato"

    /** A uiautomator dump of the real app (status bar and anything locating the user removed), as the service sees it. */
    private fun screen(name: String): List<ScreenText> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(javaClass.getResourceAsStream("/junk/$name.xml"))
        val nodes = doc.getElementsByTagName("node")
        return (0 until nodes.length).flatMap { i ->
            val node = nodes.item(i) as Element
            val (top, bottom) = Regex("""\[\d+,(\d+)]\[\d+,(\d+)]""").find(node.getAttribute("bounds"))!!
                .destructured.let { (t, b) -> t.toInt() to b.toInt() }
            listOf(node.getAttribute("text"), node.getAttribute("content-desc"))
                .filter { it.isNotBlank() }
                .map { ScreenText(it, top, bottom) }
        }
    }

    @Test
    fun parsesTheShippedRules() {
        assertEquals(
            listOf("energy", "icecream", "softdrink", "juice", "chocolate", "biscuit", "cake", "chips", "noodles", "mithai", "fastfood"),
            rules.categories.map { it.id },
        )
        assertEquals(5, rules.apps.size)
        assertTrue(rules.lines.block.all { "{item}" in it })
        assertNull(JunkRules.parse(null))
        assertNull(JunkRules.parse("{not json"))
    }

    @Test
    fun zeptoCartBlocksMonsterAndSundaeAndLetsZeroSugarGo() {
        // Cart: Monster (sugar), Monster Ultra Zero Sugar, OB & GOB sundae; the Magnum row's
        // remove control is below the fold, so it is not read until the cart is scrolled.
        val gate = JunkGate.decide(rules, zepto, screen("zepto-cart"))!!
        assertEquals(
            listOf(
                CartItem("Monster", Tier.BLOCK, "a zero-sugar sports drink"),
                CartItem("Ice Cream", Tier.BLOCK, "a sugar-free ice cream"),
            ),
            gate.items,
        )
        // Top of the Instant Order / Pay Now bar.
        assertEquals(2272, gate.payTop)
    }

    @Test
    fun instamartCartReadsTheWebViewRowAndIgnoresSuggestions() {
        val gate = JunkGate.decide(rules, swiggy, screen("instamart-cart"))!!
        assertEquals(listOf(CartItem("Monster", Tier.BLOCK, "a zero-sugar sports drink")), gate.items)
        assertEquals(2118, gate.payTop)
    }

    @Test
    fun swiggyFoodCartReadsTheMinusStepper() {
        // Rustica Pizza with a − 1 + stepper; the pay button reads "Pay ₹580 using …".
        val gate = JunkGate.decide(rules, swiggy, screen("swiggy-food-cart"))!!
        assertEquals(listOf(CartItem("Pizza", Tier.BLOCK, "a grilled or tandoori option")), gate.items)
        assertEquals(2253, gate.payTop)
    }

    @Test
    fun zomatoCartReadsTheQuantityAndIgnoresMealSuggestions() {
        // Its steppers are unlabelled icons, so the quantity "1" marks the row. The sandwich
        // is not junk; "Salted Caramel Chocolate Bar" under "Complete your meal with" is not in the cart.
        val gate = JunkGate.decide(rules, zomato, screen("zomato-cart"))!!
        assertEquals(listOf(CartItem("Chocolate", Tier.BLOCK, "a square of dark chocolate")), gate.items)
        assertEquals(2304, gate.payTop)
    }

    @Test
    fun readsEveryCartLine() {
        val cart = JunkGate.read(rules, zomato, screen("zomato-cart"))!!
        // The sandwich is a line with no junk item; the cookie is one.
        assertEquals(listOf(null, Tier.BLOCK), cart.lines.map { it.item?.tier })
        assertTrue(cart.lines.all { line -> line.text.any(Char::isLetter) })
        assertEquals(2304, cart.payTop)
    }

    @Test
    fun cartWithNoReadableLineHasNoGate() {
        // The same cart after a redesign drops the quantity and steppers: pay button, no lines.
        val texts = screen("zomato-cart").filterNot { rules.remove.containsMatchIn(it.text) }
        val cart = JunkGate.read(rules, zomato, texts)!!
        assertEquals(emptyList<CartLine>(), cart.lines)
        assertNull(JunkGate.gate(cart))
    }

    @Test
    fun searchPageIsNotACart() {
        assertNull(JunkGate.decide(rules, zepto, screen("zepto-search")))
    }

    @Test
    fun unlistedAppIsLeftAlone() {
        assertNull(JunkGate.decide(rules, "com.whatsapp", screen("zepto-cart")))
    }

    @Test
    fun blocksTheBadStuff() {
        fun blocked(text: String) = JunkGate.classify(rules, text)?.tier
        listOf(
            "Red Bull Energy Drink 250 ml", "Amul Kulfi Stick", "Amul Dark Chocolate Ice Cream",
            "Coca-Cola Original Taste Soft Drink 750 ml", "Thums Up 250 ml Can", "Maaza Mango Drink 600 ml",
            "Glucon-D Tangy Orange", "Tang Orange Drink Powder", "Cadbury Dairy Milk Silk", "Oreo Original Vanilla Creme",
            "Sunfeast Dark Fantasy Choco Fills", "Britannia Chocolate Cake", "Lay’s India's Magic Masala Chips",
            "Kurkure Masala Munch", "Haldiram's Aloo Bhujia", "Maggi 2-Minute Masala Noodles", "Haldiram's Soan Papdi",
            "Gulab Jamun Tin 1 kg", "Farm Pizza", "Pepsi 2 L",
        ).forEach { assertEquals(it, Tier.BLOCK, blocked(it)) }
    }

    @Test
    fun namesTheItemByTheBrandReadFirst() {
        // Zepto's Thums Up row ends with "The Coca-Cola Company".
        assertEquals("Thums Up", JunkGate.classify(rules, "Thums Up | Cola Sparkling Soft Drink Pet Bottle | The Coca-Cola Company")?.name)
    }

    @Test
    fun letsTheMildOnesGo() {
        fun tier(text: String) = JunkGate.classify(rules, text)?.tier
        // Named junk in a mild form: matched, then let go.
        listOf(
            "Coca-Cola Coke Zero 300 ml", "Diet Coke Can", "Red Bull Sugarfree 250 ml", "Monster Energy Ultra, Zero Sugar",
            "Kwality Wall's Sugar Free Frozen Dessert", "NIC No Added Sugar Ice Cream", "Amul Dark Chocolate 55%",
            "McVitie's Digestive Cookies", "Lay's Baked Chips", "Puffed Rice Cake",
            "Paper Boat Coconut Water",
        ).forEach { assertEquals(it, Tier.ALLOW, tier(it)) }
        // Not junk at all.
        listOf(
            "Gatorade Blue Bolt Zero Sugar", "Gatorade Orange 500 ml", "Britannia Marie Gold", "Amul Cheese Slices", "Real Good Chicken",
            "Maggi Masala-ae-Magic", "Testing kit", "Farmley Roasted Makhana", "Amul Masti Buttermilk",
        ).forEach { assertNull(it, tier(it)) }
    }

    @Test
    fun cartWithOnlyMildItemsHasNoGate() {
        val texts = listOf(
            ScreenText("Coca-Cola Coke Zero 300 ml", 1000, 1100),
            ScreenText("Remove", 1030, 1060),
            ScreenText("Lay's Baked Chips", 1200, 1300),
            ScreenText("Remove", 1230, 1260),
            ScreenText("Pay Now", 2272, 2416),
        )
        assertNull(JunkGate.decide(rules, zepto, texts))
    }
}
