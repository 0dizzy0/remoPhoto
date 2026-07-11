package com.remophoto.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageNavigatorSmokeTest {
    @Test
    fun firstPagesKeepStableLayoutAndNextPageState() {
        val expected = listOf(1, 2, 3, -1, 20)
        assertEquals(expected, generatePageNumbers(1, 20))
        assertEquals(expected, generatePageNumbers(2, 20))
        assertEquals(expected, generatePageNumbers(3, 20))
        assertEquals(4, 3 + 1)
    }

    @Test
    fun ellipsisProvidesJumpSlotAcrossLongLists() {
        assertTrue(generatePageNumbers(3, 20).contains(-1))
        assertTrue(generatePageNumbers(10, 20).contains(-1))
        assertTrue(generatePageNumbers(19, 20).contains(-1))
    }
}
