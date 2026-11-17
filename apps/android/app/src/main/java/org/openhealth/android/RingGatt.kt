package org.openhealth.android

import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID

/** Oura GATT layout shared with the desktop (`oura-link::ble`) and iOS clients. */
internal object RingGatt {
    val service: UUID = UUID.fromString("98ed0001-a541-11e4-b6a0-0002a5d5c51b")
    val write: UUID = UUID.fromString("98ed0002-a541-11e4-b6a0-0002a5d5c51b")

    // Ring 4 notifies on ...0003; Ring 5 adds ...0004 to ...0006.
    val notify: Set<UUID> = listOf("0003", "0004", "0005", "0006")
        .map { UUID.fromString("98ed$it-a541-11e4-b6a0-0002a5d5c51b") }
        .toSet()
    val clientConfig: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Android defaults to a 23-byte MTU (20-byte payloads), which truncates ring frames.
    const val REQUESTED_MTU = 247

    private val enableNotification = byteArrayOf(0x01, 0x00)
    private val enableIndication = byteArrayOf(0x02, 0x00)

    /** Descriptor value enabling updates, or null when the characteristic cannot push. */
    fun subscriptionValue(properties: Int): ByteArray? = when {
        properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> enableNotification.copyOf()
        properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> enableIndication.copyOf()
        else -> null
    }

    // Android's generic error (133), connection timeout (147), failed to establish (62),
    // and supervision timeout (8) are often transient on a ring that is advertising.
    val RETRYABLE_CONNECT_STATUSES = setOf(8, 62, 133, 147)

    private const val STALE_BOND_HINT =
        "If the ring was factory-reset, forget it in Android Bluetooth settings and retry."

    fun disconnectReason(status: Int): String = when (status) {
        0 -> "the ring disconnected"
        // 0x06 PIN or key missing / 0x3D MIC failure: the phone holds a bond the ring no longer has.
        6, 61 -> "this phone's Bluetooth bond with the ring is out of date (status $status). $STALE_BOND_HINT"
        8 -> "the ring stopped responding (connection timeout)"
        19 -> "the ring closed the connection"
        22 -> "the phone closed the connection"
        // 0x15 and 0x05 are how a provisioned ring rejects a central without a usable bond.
        5, 21 -> "the ring rejected this phone's Bluetooth bond (status $status). " +
            "This phone may not be bonded to the ring."
        62 -> "the connection could not be established (status 62); move closer and retry"
        133 -> "Android Bluetooth error 133. This is often temporary right after a disconnect; " +
            "if it keeps happening, make sure the Oura app is force-stopped"
        147 -> "the connection attempt timed out (status 147)"
        else -> "Bluetooth connection lost (status $status)"
    }

    fun subscribeFailureReason(status: Int): String = when (status) {
        // GATT_INSUFFICIENT_AUTHENTICATION, GATT_INSUFFICIENT_ENCRYPTION, GATT_AUTH_FAIL.
        5, 15, 137 -> "the ring requires an encrypted Bluetooth bond (status $status). $STALE_BOND_HINT"
        else -> "the ring refused notifications (status $status)"
    }
}
