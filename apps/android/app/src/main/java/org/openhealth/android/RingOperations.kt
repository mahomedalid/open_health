package org.openhealth.android

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import uniffi.oura_core.BleWriter
import uniffi.oura_core.DeviceInfoReport
import uniffi.oura_core.FeatureReport
import uniffi.oura_core.RingSession
import uniffi.oura_core.SyncException
import uniffi.oura_core.SyncProgressListener
import uniffi.oura_core.SyncReport
import uniffi.oura_core.VerifyReport

// Long enough for the user to accept an Android Bluetooth pairing prompt.
private const val CONNECT_TIMEOUT_MS = 60_000L
private const val OPERATION_TIMEOUT_MS = 30_000L

// A first sync can drain weeks of history; the user can cancel at any time.
private const val SYNC_TIMEOUT_MS = 15 * 60_000L
private const val CONNECT_ATTEMPTS = 3
private const val RETRY_DELAY_MS = 1_500L

internal class RingConnectionException(message: String) : Exception(message)

internal data class PairingResult(val credential: RingCredential, val recovered: Boolean)

/** Read-only: no auth key is used and nothing is written to the ring's settings. */
internal suspend fun readRingIdentity(
    context: Context,
    address: String,
    onStage: (String) -> Unit,
): DeviceInfoReport = withRingSession(context, address, onStage) { session, _ ->
    onStage("Reading serial number and firmware...")
    session.deviceInfo()
}

/**
 * Installs a pairing key on a factory-reset ring. The key is saved to [keys] before
 * the connection starts; it becomes the active credential only after the ring
 * authenticates with it. If the ring rejects installation (it already holds a key),
 * the saved key is tried, which recovers a pairing interrupted after installation.
 */
internal suspend fun pairRing(
    context: Context,
    address: String,
    keys: RingKeyStore,
    onStage: (String) -> Unit,
): PairingResult {
    val key = try {
        keys.active()?.let { throw RingConnectionException("This phone is already paired with ring ${it.serial}.") }
        keys.getOrCreatePendingKey()
    } catch (e: CredentialException) {
        throw RingConnectionException(e.message ?: "The ring key could not be prepared")
    }
    return withRingSession(context, address, onStage) { session, lostReason ->
        onStage("Installing this phone's key on the ring...")
        try {
            val report = session.pair(key)
            keys.promote(report.keyHex, report.serial, address)
            PairingResult(RingCredential(report.serial, address, report.keyHex), recovered = false)
        } catch (installError: SyncException.Failed) {
            if (lostReason() != null) throw installError
            onStage("The ring already has a key. Checking whether it is this phone's...")
            val verified = try {
                session.verifyKey(key)
            } catch (verifyError: SyncException.Failed) {
                throw RingConnectionException(
                    "The ring did not accept a new key and does not recognize this phone's key. " +
                        "It is probably still paired with another app: factory-reset it, forget it in " +
                        "Android Bluetooth settings, and retry. Details: ${installError.v1}"
                )
            }
            keys.promote(key, verified.serial, address)
            PairingResult(RingCredential(verified.serial, address, key), recovered = true)
        }
    }
}

/**
 * Restores a backed-up key: it is saved only after the selected ring authenticates
 * with it, and only when this phone has no saved key, so a working key is never replaced.
 */
internal suspend fun restoreRing(
    context: Context,
    address: String,
    backupText: String,
    keys: RingKeyStore,
    onStage: (String) -> Unit,
): RingCredential {
    val key = normalizeKeyInput(backupText)
        ?: throw RingConnectionException("A backup key is 32 characters, using 0-9 and a-f.")
    try {
        keys.active()?.let { throw RingConnectionException("This phone is already paired with ring ${it.serial}.") }
    } catch (e: CredentialException) {
        throw RingConnectionException(e.message ?: "The saved key could not be read")
    }
    return withRingSession(context, address, onStage) { session, _ ->
        onStage("Checking the backup key with the ring...")
        val verified = try {
            session.verifyKey(key)
        } catch (e: SyncException.Failed) {
            throw RingConnectionException("This ring does not accept that key. Details: ${e.v1}")
        }
        keys.promote(key, verified.serial, address)
        RingCredential(verified.serial, address, key)
    }
}

/** Accepts a key typed or pasted with spaces, dashes, or upper case; null if malformed. */
internal fun normalizeKeyInput(text: String): String? {
    val key = text.filter { !it.isWhitespace() && it != '-' && it != ':' }.lowercase()
    return key.takeIf { it.hexToBytes() != null }
}

/** Formats a key in groups of four for reading aloud or typing. */
internal fun formatKeyForDisplay(keyHex: String): String = keyHex.chunked(4).joinToString(" ")

/** Authenticates with the saved key and reads the battery; changes nothing on the ring. */
internal suspend fun checkPairing(
    context: Context,
    credential: RingCredential,
    onStage: (String) -> Unit,
): VerifyReport = withRingSession(context, credential.address, onStage) { session, _ ->
    onStage("Authenticating with the saved key...")
    val report = session.verifyKey(credential.keyHex)
    if (!report.serial.equals(credential.serial, ignoreCase = true)) {
        throw RingConnectionException(
            "Connected to ring ${report.serial}, but this phone is paired with ${credential.serial}."
        )
    }
    report
}

/** Turns on daytime heart rate and SpO2 measurement and reports each feature's mode. */
internal suspend fun enableMeasurements(
    context: Context,
    credential: RingCredential,
    onStage: (String) -> Unit,
): List<FeatureReport> = withRingSession(context, credential.address, onStage) { session, _ ->
    onStage("Turning on heart rate and SpO2 measurement...")
    session.enableMeasurements(credential.keyHex)
}

/**
 * Drains the ring's stored history into [database]. The Rust sync checkpoints after
 * every batch, so a cancelled or failed sync resumes where it stopped next time.
 */
internal suspend fun syncRing(
    context: Context,
    credential: RingCredential,
    database: File,
    onStage: (String) -> Unit,
): SyncReport = withRingSession(context, credential.address, onStage, SYNC_TIMEOUT_MS) { session, _ ->
    database.parentFile?.mkdirs()
    session.sync(database.path, credential.keyHex, object : SyncProgressListener {
        override fun onProgress(stage: String, bytesLeft: ULong, eventsSynced: UInt) {
            onStage(syncStageMessage(stage, bytesLeft, eventsSynced))
        }
    })
}

internal fun syncStageMessage(stage: String, bytesLeft: ULong, eventsSynced: UInt): String = when (stage) {
    "auth" -> "Authenticating..."
    "setup" -> "Preparing the ring for sync..."
    "time" -> "Setting the ring clock..."
    "rebase" -> "The ring restarted its history; reading it from the beginning..."
    "sync" -> buildString {
        append("Syncing history: $eventsSynced events")
        if (bytesLeft > 0u) append(", ${(bytesLeft.toLong() + 1023) / 1024} KB left")
    }
    else -> stage
}

/**
 * Connects a [GattLink] to a fresh [RingSession], runs [operation], and always
 * disconnects. Link failures cancel the Rust session; errors become
 * [RingConnectionException] with a user-facing message.
 */
private suspend fun <T> withRingSession(
    context: Context,
    address: String,
    onStage: (String) -> Unit,
    operationTimeoutMs: Long = OPERATION_TIMEOUT_MS,
    operation: suspend (RingSession, lostReason: () -> String?) -> T,
): T {
    var attempt = 1
    while (true) {
        val connection = RingConnection(context, address, onStage)
        try {
            try {
                withTimeout(CONNECT_TIMEOUT_MS) { connection.link.connect() }
            } catch (e: TimeoutCancellationException) {
                throw RingConnectionException(
                    "Timed out connecting. Keep the ring close, force-stop the Oura app, and retry."
                )
            } catch (e: LinkSetupException) {
                if (!e.retryable || attempt >= CONNECT_ATTEMPTS) {
                    throw RingConnectionException(e.message ?: "Connection failed")
                }
                Log.i("OpenHealthBle", "attempt $attempt failed (${e.message}); retrying")
                connection.close()
                delay(RETRY_DELAY_MS * attempt)
                attempt++
                onStage("Retrying connection (attempt $attempt of $CONNECT_ATTEMPTS)...")
                continue
            }
            return try {
                withTimeout(operationTimeoutMs) { operation(connection.session, connection.lostReason::get) }
            } catch (e: TimeoutCancellationException) {
                connection.session.cancel("timed out")
                throw RingConnectionException("Connected, but the ring did not answer in time.")
            } catch (e: SyncException) {
                throw RingConnectionException(describe(e, connection.lostReason.get()))
            } catch (e: CredentialException) {
                throw RingConnectionException(e.message ?: "The ring key could not be saved")
            }
        } finally {
            connection.close()
        }
    }
}

/** One BLE link wired to one Rust session; link failures cancel the session. */
private class RingConnection(context: Context, address: String, onStage: (String) -> Unit) {
    val lostReason = AtomicReference<String?>(null)
    private lateinit var rust: RingSession
    val link = GattLink(
        context,
        address,
        onFrame = { rust.pushFrame(it) },
        onLost = { reason ->
            lostReason.compareAndSet(null, reason)
            rust.cancel(reason)
        },
        onStage = onStage,
    )
    val session: RingSession = RingSession(object : BleWriter {
        override fun write(data: ByteArray) = link.write(data)
    }).also { rust = it }
    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        session.cancel("finished")
        link.close()
        session.close()
    }
}

/** A lost link explains any Rust-side failure better than the protocol error it caused. */
internal fun describe(error: SyncException, lostReason: String?): String = when {
    lostReason != null -> "Connection lost: $lostReason"
    error is SyncException.Interrupted -> "Cancelled: ${error.reason}"
    error is SyncException.Failed -> error.v1
    error is SyncException.Storage -> "Storage error: ${error.detail}"
    else -> error.toString()
}
