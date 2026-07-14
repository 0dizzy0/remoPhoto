package com.remophoto.data.remote.smb

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

class SmbRepositorySyncWorkerTest {
    @Test
    fun `LAN sync does not require validated internet connectivity`() {
        val request = SmbRepositorySyncWorker.request(repositoryId = 42L)

        assertEquals(
            NetworkType.NOT_REQUIRED,
            request.workSpec.constraints.requiredNetworkType,
        )
    }
}
