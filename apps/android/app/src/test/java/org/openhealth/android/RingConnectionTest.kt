package org.openhealth.android

import android.bluetooth.BluetoothGattCharacteristic
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.oura_core.SyncException

class RingConnectionTest {
    @Test
    fun prefersNotificationsAndFallsBackToIndications() {
        val both = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
        assertArrayEquals(byteArrayOf(1, 0), RingGatt.subscriptionValue(both))
        assertArrayEquals(byteArrayOf(2, 0), RingGatt.subscriptionValue(BluetoothGattCharacteristic.PROPERTY_INDICATE))
        assertNull(RingGatt.subscriptionValue(BluetoothGattCharacteristic.PROPERTY_WRITE))
    }

    @Test
    fun subscriptionValuesCannotBeMutatedBetweenCalls() {
        RingGatt.subscriptionValue(BluetoothGattCharacteristic.PROPERTY_NOTIFY)!![0] = 9
        assertArrayEquals(
            byteArrayOf(1, 0),
            RingGatt.subscriptionValue(BluetoothGattCharacteristic.PROPERTY_NOTIFY),
        )
    }

    @Test
    fun ringServiceExcludesTheWriteCharacteristicFromNotifications() {
        assertEquals(4, RingGatt.notify.size)
        assertTrue(RingGatt.write !in RingGatt.notify)
    }

    @Test
    fun explainsBusyRingAndUnknownDisconnects() {
        assertTrue(RingGatt.disconnectReason(133).contains("Oura app"))
        assertEquals("Bluetooth connection lost (status 99)", RingGatt.disconnectReason(99))
    }

    @Test
    fun retriesOnlyTransientConnectionFailures() {
        assertTrue(147 in RingGatt.RETRYABLE_CONNECT_STATUSES)
        assertTrue(133 in RingGatt.RETRYABLE_CONNECT_STATUSES)
        assertFalse(6 in RingGatt.RETRYABLE_CONNECT_STATUSES)
        assertTrue(RingGatt.disconnectReason(6).contains("forget it"))
        assertTrue(RingGatt.subscribeFailureReason(5).contains("bond"))
    }

    @Test
    fun lostLinkExplainsTheFailureItCaused() {
        assertEquals(
            "Connection lost: the ring disconnected",
            describe(SyncException.Failed("no serial response"), "the ring disconnected"),
        )
        assertEquals("no serial response", describe(SyncException.Failed("no serial response"), null))
        assertEquals("Cancelled: background", describe(SyncException.Interrupted("background"), null))
    }
}
