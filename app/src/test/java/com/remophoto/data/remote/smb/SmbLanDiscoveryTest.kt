package com.remophoto.data.remote.smb

import org.junit.Assert.assertEquals
import org.junit.Test

class SmbLanDiscoveryTest {
    @Test
    fun `mdns result wins duplicate subnet probe and addresses are sorted`() {
        val results = mergeSmbDiscoveryResults(
            listOf(
                DiscoveredSmbServer("probe", "192.168.1.20", source = DiscoveredSmbServer.Source.SUBNET_PROBE),
                DiscoveredSmbServer("NAS", "192.168.1.20", source = DiscoveredSmbServer.Source.MDNS),
                DiscoveredSmbServer("server", "192.168.1.3", source = DiscoveredSmbServer.Source.SUBNET_PROBE),
            )
        )

        assertEquals(listOf("192.168.1.3", "192.168.1.20"), results.map { it.host })
        assertEquals("NAS", results.last().displayName)
    }
}
