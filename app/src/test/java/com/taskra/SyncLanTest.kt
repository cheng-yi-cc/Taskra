package com.taskra

import com.taskra.sync.privateLanIpv4
import com.taskra.sync.sameIpv4Subnet
import org.junit.Assert.*
import org.junit.Test

class SyncLanTest {
    @Test fun onlyPrivateIpv4AndSameWifiSubnetCanUseLan() {
        val server = requireNotNull(privateLanIpv4("192.168.31.55"))
        assertTrue(sameIpv4Subnet(server, requireNotNull(privateLanIpv4("192.168.31.188")), 24))
        assertFalse(sameIpv4Subnet(server, requireNotNull(privateLanIpv4("192.168.32.1")), 24))
        assertFalse(sameIpv4Subnet(server, server, 0))
        assertNull(privateLanIpv4("127.0.0.1"))
        assertNull(privateLanIpv4("1.1.1.1"))
        assertNull(privateLanIpv4("192.168.031.55"))
        assertNull(privateLanIpv4("192.168.31.256"))
        assertNull(privateLanIpv4("server.local"))
    }
}
