package io.github.denzil10.cartgate

import io.github.denzil10.cartgate.JunkRules.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChoicesTest {
    private val defaults = File("src/main/assets/rules.json").readText()

    private fun rules(choices: Choices) = JunkRules.parse(choices.apply(defaults))!!

    @Test
    fun noChangesKeepTheDefaults() {
        // Regex has no equals, so compare what the switches change.
        val base = JunkRules.parse(defaults)!!
        val same = rules(Choices())
        assertEquals(base.categories.map { it.id }, same.categories.map { it.id })
        assertEquals(base.apps, same.apps)
    }

    @Test
    fun aSwitchedOffCategoryLetsItsItemsThrough() {
        val rules = rules(Choices(offCategories = setOf("icecream")))
        assertNull(JunkGate.classify(rules, "Amul Kulfi Stick"))
        assertEquals(Tier.BLOCK, JunkGate.classify(rules, "Farm Pizza")?.tier)
    }

    @Test
    fun ownWordsBlockAndComeFirst() {
        val choices = Choices(words = Choices.words("Cheesecake,  gol gappe\nCheesecake"))
        assertEquals(listOf("cheesecake", "gol gappe"), choices.words)
        val rules = rules(choices)
        assertEquals(Choices.MINE, rules.categories.first().id)
        assertEquals(CartItem("Gol Gappe", Tier.BLOCK, "something else"), JunkGate.classify(rules, "Gol Gappe Kit 12 pc"))
        // Whole words: "cheesecake" names the item before the cake category gets to it.
        assertEquals("Cheesecake", JunkGate.classify(rules, "Blueberry Cheesecake Slice")?.name)
    }

    @Test
    fun aSwitchedOffAppIsLeftAlone() {
        val rules = rules(Choices(offApps = setOf("com.application.zomato")))
        assertFalse("com.application.zomato" in rules.apps)
        assertTrue("com.zeptoconsumerapp" in rules.apps)
    }
}
