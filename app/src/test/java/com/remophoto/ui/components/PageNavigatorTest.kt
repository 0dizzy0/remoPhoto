package com.remophoto.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PageNavigatorTest {
    @Test
    fun `first three pages keep stable five-slot layout`() {
        assertEquals(listOf(1, 2, 3, -1, 20), generatePageNumbers(1, 20))
        assertEquals(listOf(1, 2, 3, -1, 20), generatePageNumbers(2, 20))
        assertEquals(listOf(1, 2, 3, -1, 20), generatePageNumbers(3, 20))
    }

    @Test
    fun `middle and ending layouts retain navigation slots`() {
        assertEquals(listOf(1, -1, 10, -1, 20), generatePageNumbers(10, 20))
        assertEquals(listOf(1, -1, 18, 19, 20), generatePageNumbers(19, 20))
    }
}
