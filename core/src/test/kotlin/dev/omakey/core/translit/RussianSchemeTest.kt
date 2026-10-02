package dev.omakey.core.translit

import org.junit.Assert.assertEquals
import org.junit.Test

class RussianSchemeTest {

    @Test
    fun `romanizes the way people type translit`() {
        assertEquals("privet", RussianScheme.romanize("привет"))
        assertEquals("khorosho", RussianScheme.romanize("хорошо"))
        assertEquals("shchi", RussianScheme.romanize("щи"))
        assertEquals("pozhaluysta", RussianScheme.romanize("пожалуйста"))
        assertEquals("obekt", RussianScheme.romanize("объект")) // hard and soft signs aren't typed
        assertEquals("yolka", RussianScheme.romanize("Ёлка"))
    }

    private fun sameKey(vararg spellings: String) {
        val keys = spellings.map(RussianScheme::skeleton).toSet()
        assertEquals("${spellings.toList()} → $keys", 1, keys.size)
    }

    @Test
    fun `common spelling variants share a retrieval key`() {
        sameKey("horosho", "khorosho", "xorosho", RussianScheme.romanize("хорошо"))
        sameKey("schas", "shchas", "shhas", RussianScheme.romanize("щас"))
        sameKey("ya", "ja", "a")
        sameKey("yolka", "jolka", "elka", RussianScheme.romanize("ёлка"))
        sameKey("moy", "moj", "moi", RussianScheme.romanize("мой"))
        sameKey("tsar", "car", "tzar", RussianScheme.romanize("царь"))
        sameKey("maria", "mariya", RussianScheme.romanize("мария"))
    }

    @Test
    fun `different sounds keep different keys`() {
        val keys = listOf("shar", "zhar", "char", "sar", "zar", "khar").map(RussianScheme::skeleton).toSet()
        assertEquals(6, keys.size)
    }

    @Test
    fun `literal transliteration`() {
        assertEquals("привет", RussianScheme.literal("privet"))
        assertEquals("хорошо", RussianScheme.literal("khorosho"))
        assertEquals("хорошо", RussianScheme.literal("horosho"))
        assertEquals("щи", RussianScheme.literal("shchi"))
        assertEquals("мы", RussianScheme.literal("my"))   // y after a consonant: ы
        assertEquals("мой", RussianScheme.literal("moy")) // after a vowel: й
        assertEquals("я", RussianScheme.literal("ya"))
        assertEquals("жизнь", RussianScheme.literal("zhizn") + "ь")
    }

    @Test
    fun `skeleton is ASCII`() {
        for (word in listOf("щётка", "объявление", "Чебурашка", "юность", "цыплёнок")) {
            val key = RussianScheme.skeleton(RussianScheme.romanize(word))
            assert(key.all { it.code < 128 }) { "$word → $key" }
        }
    }
}
