package org.openhealth.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "OpenHealthBle"
private const val SCAN_TIMEOUT_MS = 20_000L

/** Setup failure; [retryable] when nothing was sent and a fresh attempt may succeed. */
internal class LinkSetupException(message: String, val retryable: Boolean = false) : IllegalStateException(message)

/**
 * One BLE connection to a ring: the Android counterpart to iOS `BLETransport`.
 *
 * It only moves bytes. Rust drives the protocol through [write]; inbound
 * notifications go to [onFrame]. Any failure after setup is reported once through
 * [onLost] so the Rust session can be cancelled instead of waiting for replies.
 * Android allows a single outstanding GATT operation, so writes are queued and each
 * waits for its completion callback.
 */
@SuppressLint("MissingPermission") // The caller verifies BLUETOOTH_CONNECT; SecurityException is handled.
internal class GattLink(
    private val context: Context,
    private val address: String,
    private val onFrame: (ByteArray) -> Unit,
    private val onLost: (String) -> Unit,
    private val onStage: (String) -> Unit,
) {
    private val lock = Any()
    private val ready = CompletableDeferred<Unit>()
    private val pendingWrites = ArrayDeque<ByteArray>()
    private val pendingSubscriptions = ArrayDeque<BluetoothGattCharacteristic>()
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var writing = false
    private var closed = false
    private var lostReported = false
    private var bondReceiver: BroadcastReceiver? = null

    /**
     * Scans for the ring, then connects and subscribes; completes when frames can
     * flow in both directions. Connecting to the device object from a fresh scan
     * (rather than a remembered address string) gives Android the ring's current
     * address type, avoiding connection timeouts (status 147) on private addresses.
     */
    suspend fun connect() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw LinkSetupException("Bluetooth is unavailable on this device")
        if (!adapter.isEnabled) throw LinkSetupException("Turn on Bluetooth and retry")
        val device = try {
            findAdvertisingDevice(adapter)
        } catch (e: SecurityException) {
            throw LinkSetupException("Nearby devices permission was denied or revoked")
        }
        Log.i(TAG, "found ring; bondState=${device.bondState}; connecting")
        onStage("Connecting to the ring...")
        try {
            synchronized(lock) {
                if (closed) throw LinkSetupException("Connection was cancelled")
                gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    ?: throw LinkSetupException("Android could not start a Bluetooth connection", retryable = true)
            }
        } catch (e: SecurityException) {
            throw LinkSetupException("Nearby devices permission was denied or revoked")
        }
        ready.await()
        Log.i(TAG, "link ready")
    }

    private suspend fun findAdvertisingDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val scanner = adapter.bluetoothLeScanner
            ?: throw LinkSetupException("Bluetooth scanner is unavailable")
        onStage("Looking for the ring...")
        val found = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val done = AtomicBoolean(false)
                val scanCallback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (!result.device.address.equals(address, ignoreCase = true)) return
                        if (!done.compareAndSet(false, true)) return
                        stopScanQuietly(scanner, this)
                        continuation.resume(result.device)
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (!done.compareAndSet(false, true)) return
                        continuation.resumeWithException(
                            LinkSetupException("Bluetooth scan failed (code $errorCode)", retryable = true)
                        )
                    }
                }
                continuation.invokeOnCancellation {
                    if (done.compareAndSet(false, true)) stopScanQuietly(scanner, scanCallback)
                }
                scanner.startScan(
                    listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(RingGatt.service)).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    scanCallback,
                )
            }
        }
        return found ?: throw LinkSetupException(
            "The ring is not advertising nearby. Keep it on the charger next to the phone. " +
                "A ring connected to another app stops advertising, so make sure the Oura app is force-stopped."
        )
    }

    private fun stopScanQuietly(scanner: BluetoothLeScanner, callback: ScanCallback) {
        try {
            scanner.stopScan(callback)
        } catch (_: Exception) {
            // Bluetooth turned off or permission revoked: the scan is already gone.
        }
    }

    /** Queues one request frame; called from Rust threads via `BleWriter`. */
    fun write(data: ByteArray) {
        synchronized(lock) {
            if (closed || lostReported) return
            if (!ready.isCompleted || writeCharacteristic == null) {
                reportLost("tried to send a request before the connection was ready")
                return
            }
            pendingWrites.addLast(data.copyOf())
            if (!writing) writeNextLocked()
        }
    }

    /** Disconnects and releases the Android GATT client. Safe to call more than once. */
    fun close() {
        val current = synchronized(lock) {
            if (closed) return
            closed = true
            pendingWrites.clear()
            pendingSubscriptions.clear()
            gatt.also { gatt = null }
        }
        if (!ready.isCompleted) ready.completeExceptionally(LinkSetupException("Connection closed"))
        unregisterBondReceiver()
        try {
            current?.disconnect()
            current?.close()
        } catch (_: SecurityException) {
            // Permission revoked: the system tears the connection down with the app.
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connection state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                onStage("Connected. Negotiating packet size...")
                guarded { if (!gatt.requestMtu(RingGatt.REQUESTED_MTU)) discover(gatt) }
            } else {
                // Before the link is ready nothing was sent, so transient failures are safe to retry.
                fail(RingGatt.disconnectReason(status), retryable = status in RingGatt.RETRYABLE_CONNECT_STATUSES)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // A refused MTU still leaves a usable link; discovery reports any real failure.
            guarded { discover(gatt) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("could not read the ring's Bluetooth services (status $status)")
                return
            }
            val service = gatt.getService(RingGatt.service)
            val write = service?.getCharacteristic(RingGatt.write)
            val notify = service?.characteristics.orEmpty().filter {
                it.uuid in RingGatt.notify && RingGatt.subscriptionValue(it.properties) != null
            }
            if (service == null || write == null || notify.isEmpty()) {
                fail("this device does not expose the Oura ring service")
                return
            }
            synchronized(lock) {
                writeCharacteristic = write
                pendingSubscriptions.addAll(notify)
            }
            guarded { ensureBondThenSubscribe(gatt) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(RingGatt.subscribeFailureReason(status))
                return
            }
            guarded { subscribeNext(gatt) }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("the ring rejected a request (status $status)")
                return
            }
            synchronized(lock) {
                writing = false
                if (!closed) writeNextLocked()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            deliver(value)
        }

        @Deprecated("Used before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                characteristic.value?.let { deliver(it.copyOf()) }
            }
        }
    }

    private fun discover(gatt: BluetoothGatt) {
        onStage("Discovering ring services...")
        if (!gatt.discoverServices()) fail("could not start Bluetooth service discovery")
    }

    /**
     * A factory-reset ring rejects subscriptions and writes until the link is
     * encrypted, which on Android requires an explicit bond. An already-bonded phone
     * (for example one shared with the official app) skips straight to subscribing.
     */
    private fun ensureBondThenSubscribe(gatt: BluetoothGatt) {
        val device = gatt.device
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            onStage("Subscribing to ring notifications...")
            subscribeNext(gatt)
            return
        }
        onStage("Bonding with the ring. Accept the Android pairing prompt if one appears...")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (bondDevice(intent)?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> {
                        unregisterBondReceiver()
                        onStage("Bonded. Subscribing to ring notifications...")
                        guarded { subscribeNext(gatt) }
                    }
                    BluetoothDevice.BOND_NONE -> {
                        unregisterBondReceiver()
                        fail("Bluetooth pairing with the ring was declined or failed")
                    }
                }
            }
        }
        synchronized(lock) {
            if (closed) return
            bondReceiver = receiver
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        // Re-check after registering so a bond that completed in between is not missed.
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                unregisterBondReceiver()
                subscribeNext(gatt)
            }
            BluetoothDevice.BOND_NONE -> if (!device.createBond()) {
                unregisterBondReceiver()
                fail("Android could not start Bluetooth pairing with the ring")
            }
        }
    }

    private fun bondDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun unregisterBondReceiver() {
        val receiver = synchronized(lock) { bondReceiver.also { bondReceiver = null } } ?: return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    private fun subscribeNext(gatt: BluetoothGatt) {
        val next = synchronized(lock) {
            if (closed) return
            pendingSubscriptions.removeFirstOrNull()
        }
        if (next == null) {
            ready.complete(Unit)
            return
        }
        val value = RingGatt.subscriptionValue(next.properties)
        val descriptor = next.getDescriptor(RingGatt.clientConfig)
        if (value == null || descriptor == null || !gatt.setCharacteristicNotification(next, true)) {
            fail("could not enable notifications on ${next.uuid}")
            return
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            writeDescriptorLegacy(gatt, descriptor, value)
        }
        if (!started) fail("could not enable notifications on ${next.uuid}")
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptorLegacy(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        descriptor.value = value
        return gatt.writeDescriptor(descriptor)
    }

    @Suppress("DEPRECATION")
    private fun writeCharacteristicLegacy(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
    ): Boolean {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = data
        return gatt.writeCharacteristic(characteristic)
    }

    /** Must hold [lock]. Starts the next queued write if the link is idle. */
    private fun writeNextLocked() {
        val target = gatt ?: return
        val characteristic = writeCharacteristic ?: return
        val data = pendingWrites.removeFirstOrNull() ?: return
        writing = true
        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                target.writeCharacteristic(
                    characteristic,
                    data,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                writeCharacteristicLegacy(target, characteristic, data)
            }
        } catch (_: SecurityException) {
            false
        }
        if (!started) {
            writing = false
            pendingWrites.clear()
            reportLost("could not send a request to the ring")
        }
    }

    private fun deliver(frame: ByteArray) {
        if (synchronized(lock) { closed }) return
        onFrame(frame)
    }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (_: SecurityException) {
            fail("Nearby devices permission was revoked")
        }
    }

    private fun fail(reason: String, retryable: Boolean = false) {
        Log.w(TAG, "link failure: $reason")
        if (!ready.isCompleted) ready.completeExceptionally(LinkSetupException(reason, retryable))
        synchronized(lock) { reportLost(reason) }
    }

    /** Must hold [lock]. Reports only the first failure of an open link. */
    private fun reportLost(reason: String) {
        if (closed || lostReported) return
        lostReported = true
        onLost(reason)
    }
}
