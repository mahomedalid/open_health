package org.openhealth.android

data class DiscoveredRing(val address: String, val name: String, val rssi: Int)

internal fun updateDiscovery(rings: List<DiscoveredRing>, ring: DiscoveredRing): List<DiscoveredRing> {
    val index = rings.indexOfFirst { it.address == ring.address }
    if (index < 0) return rings + ring
    return rings.toMutableList().apply { this[index] = ring }
}
