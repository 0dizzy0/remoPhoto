package com.remophoto.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafDisplayNameTest {

    @Test
    fun `extracts Android 10 raw tree directory name`() {
        assertEquals(
            "RemoPhotoApi29",
            SafDisplayName.fromUriString(
                "content://com.android.providers.downloads.documents/tree/" +
                    "raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FRemoPhotoApi29"
            )
        )
    }

    @Test
    fun `extracts Android 10 raw child directory name`() {
        assertEquals(
            "Album A",
            SafDisplayName.fromUriString(
                "content://com.android.providers.downloads.documents/document/" +
                    "raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FRemoPhotoApi29%2FAlbum%20A"
            )
        )
    }

    @Test
    fun `preserves plus signs in directory names`() {
        assertEquals(
            "Album+A",
            SafDisplayName.fromUriString(
                "content://com.android.providers.downloads.documents/document/" +
                    "raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FAlbum%2BA"
            )
        )
    }

    @Test
    fun `extracts primary storage and unicode directory names`() {
        assertEquals(
            "旅行照片",
            SafDisplayName.fromUriString(
                "content://com.android.externalstorage.documents/tree/" +
                    "primary%3APictures%2F%E6%97%85%E8%A1%8C%E7%85%A7%E7%89%87"
            )
        )
    }

    @Test
    fun `returns null when URI has no final segment`() {
        assertNull(SafDisplayName.fromUriString(""))
        assertNull(SafDisplayName.fromUriString("////"))
    }
}
