package org.openhealth.android

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryTest {
    @Test
    fun updatesRepeatedAdvertisementsWithoutMixingTwoRings() {
        val first = DiscoveredRing("first", "Unnamed ring", -70)
        val second = DiscoveredRing("second", "Oura", -50)
        val updated = first.copy(name = "Oura", rssi = -42)

        val found = updateDiscovery(updateDiscovery(updateDiscovery(emptyList(), first), second), updated)

        assertEquals(listOf(updated, second), found)
    }

    @Test
    fun retainsSeparateUnnamedDevices() {
        val first = DiscoveredRing("first", "Unnamed ring", -60)
        val second = DiscoveredRing("second", "Unnamed ring", -61)
        assertEquals(listOf(first, second), updateDiscovery(listOf(first), second))
    }
}
