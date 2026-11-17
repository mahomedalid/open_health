package org.openhealth.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

internal class RingScanner(
    private val context: Context,
    private val onResult: (DiscoveredRing) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val service = ParcelUuid(UUID.fromString("98ed0001-a541-11e4-b6a0-0002a5d5c51b"))
    private var active = false
    private var callback: ScanCallback? = null
    private val timeout = Runnable { stop("Scan complete.") }

    @SuppressLint("MissingPermission")
    fun start() {
        if (active) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            onStatus("This device does not support Bluetooth Low Energy.")
            return
        }
        if (!hasPermissions()) {
            onStatus("Nearby devices permission is required to scan.")
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Turn on Bluetooth before scanning.")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onStatus("Bluetooth scanner is unavailable.")
            return
        }
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handler.post {
                    if (!active || callback !== this) return@post
                    try {
                        onResult(
                            DiscoveredRing(
                                result.device.address,
                                result.scanRecord?.deviceName ?: "Unnamed ring",
                                result.rssi,
                            )
                        )
                    } catch (_: SecurityException) {
                        stop("Bluetooth permission was revoked during scanning.")
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post {
                    if (active && callback === this) stop("Bluetooth scan failed (code $errorCode).")
                }
            }
        }
        callback = scanCallback
        active = true
        try {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(service).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback,
            )
            onStatus("Scanning for 30 seconds. No connection will be made.")
            handler.postDelayed(timeout, 30_000)
        } catch (_: SecurityException) {
            stop("Bluetooth permission was denied or revoked.")
        } catch (e: IllegalStateException) {
            stop("Bluetooth scan could not start: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop(message: String = "Scan stopped.") {
        handler.removeCallbacks(timeout)
        val previous = callback
        callback = null
        val wasActive = active
        active = false
        if (previous != null && hasPermissions()) {
            try {
                context.getSystemService(BluetoothManager::class.java)
                    ?.adapter?.bluetoothLeScanner?.stopScan(previous)
            } catch (_: SecurityException) {
                onStatus("Bluetooth permission was revoked while stopping the scan.")
                return
            }
        }
        if (wasActive) onStatus(message)
    }

    private fun hasPermissions(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}
