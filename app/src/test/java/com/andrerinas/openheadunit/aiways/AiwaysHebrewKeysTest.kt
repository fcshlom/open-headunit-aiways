package com.andrerinas.openheadunit.aiways

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiwaysHebrewKeysTest {

    @Test
    fun `letters and space build the text, backspace takes the last character back`() {
        var t = ""
        for (k in listOf("ש", "ל", "ו", "ם", AiwaysHebrewKeys.SPACE, "ב")) t = AiwaysHebrewKeys.press(t, k)
        assertEquals("שלום ב", t)
        t = AiwaysHebrewKeys.press(t, AiwaysHebrewKeys.BACKSPACE)
        assertEquals("שלום ", t)
    }

    @Test
    fun `backspace on an empty text does nothing`() {
        assertEquals("", AiwaysHebrewKeys.press("", AiwaysHebrewKeys.BACKSPACE))
    }

    @Test
    fun `backspace removes a whole emoji, not half of it`() {
        assertEquals("א", AiwaysHebrewKeys.press("א🚗", AiwaysHebrewKeys.BACKSPACE))
    }

    @Test
    fun `the text stops growing at the phone's limit`() {
        val full = "א".repeat(AiwaysHebrewKeys.MAX_LENGTH)
        assertEquals(full, AiwaysHebrewKeys.press(full, "ב"))
    }

    @Test
    fun `the phone's address is the host the session dialled`() {
        assertEquals("192.168.1.39", AiwaysHebrewKeys.phoneHost("192.168.1.39:5277"))
        assertEquals("fe80::1", AiwaysHebrewKeys.phoneHost("fe80::1:5277"))
        assertNull(AiwaysHebrewKeys.phoneHost(null))
    }

    @Test
    fun `the layout has all 27 Hebrew letters, final forms included`() {
        val keys = AiwaysHebrewKeys.ROWS.flatten().toSet()
        val letters = (0x05D0..0x05EA).map { String(Character.toChars(it)) }
        assertTrue(letters.filterNot { it in keys }.toString(), keys.containsAll(letters))
    }
}
