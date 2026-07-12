package com.remophoto.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyStoreManagerTest {
    private val manager = KeyStoreManager(
        InstrumentationRegistry.getInstrumentation().targetContext
    )

    @After
    fun cleanUp() {
        runCatching { manager.deleteCredential(CONNECTION_ID) }
    }

    @Test
    fun storeReadReplaceAndDeleteCredential() {
        val first = "初始密码".toCharArray()
        val replacement = "replacement".toCharArray()

        manager.storeCredential(CONNECTION_ID, first)
        assertTrue(manager.hasCredential(CONNECTION_ID))
        manager.getCredential(CONNECTION_ID)!!.useAndClear { read ->
            assertArrayEquals(first, read)
        }

        manager.storeCredential(CONNECTION_ID, replacement)
        manager.getCredential(CONNECTION_ID)!!.useAndClear { read ->
            assertArrayEquals(replacement, read)
        }

        manager.deleteCredential(CONNECTION_ID)
        assertFalse(manager.hasCredential(CONNECTION_ID))
    }

    private inline fun CharArray.useAndClear(block: (CharArray) -> Unit) {
        try {
            block(this)
        } finally {
            fill('\u0000')
        }
    }

    private companion object {
        const val CONNECTION_ID = 9_223_372_036_854_000L
    }
}
