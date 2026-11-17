package org.openhealth.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.oura_core.coreVersion
import uniffi.oura_core.hourlyHrJsonHours
import uniffi.oura_core.summaryJsonHours

private enum class KeyDialog { WARN, SHOW, RESTORE }

class MainActivity : ComponentActivity() {
    private val scope = MainScope()
    private lateinit var keys: RingKeyStore
    private lateinit var scanner: RingScanner
    private var rings by mutableStateOf<List<DiscoveredRing>>(emptyList())
    private var status by mutableStateOf("Ready to scan.")
    private var selectedAddress by mutableStateOf<String?>(null)
    private var job by mutableStateOf<Job?>(null)
    private var jobStatus by mutableStateOf<String?>(null)
    private var result by mutableStateOf<List<String>>(emptyList())
    private var credential by mutableStateOf<RingCredential?>(null)
    private var credentialError by mutableStateOf<String?>(null)
    private var hasPendingKey by mutableStateOf(false)
    private var confirmPairing by mutableStateOf(false)
    private var keyDialog by mutableStateOf<KeyDialog?>(null)
    private var restoreText by mutableStateOf("")
    private var summary by mutableStateOf<Dashboard?>(null)
    private val database by lazy { File(filesDir, "ring.db") }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it } && granted.size == 2) {
            scanner.start()
        } else {
            status = "Nearby devices permission is required. Allow it in Android settings and try again."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark, transparent system bars regardless of the system theme (PRD VIS-01).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        keys = RingKeyStore(noBackupFilesDir, AndroidKeystoreCipher())
        loadCredential()
        loadSummary()
        scanner = RingScanner(
            applicationContext,
            onResult = { ring -> rings = updateDiscovery(rings, ring) },
            onStatus = { status = it },
        )
        val coreStatus = try {
            "Rust core: ${coreVersion()}"
        } catch (e: UnsatisfiedLinkError) {
            "Rust core failed to load: ${e.message}"
        } catch (e: ExceptionInInitializerError) {
            "Rust core initialization failed: ${e.message}"
        }
        setContent {
            OpenHealthTheme {
                Surface(color = Palette.Background, modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().safeDrawingPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Open Health", style = MaterialTheme.typography.headlineMedium)
                        if (coreStatus.startsWith("Rust core failed") || coreStatus.contains("initialization failed")) {
                            Text(coreStatus, color = Palette.Danger)
                        }
                        credentialError?.let { Text("Saved key problem: $it", color = Palette.Danger) }
                        val paired = credential
                        if (paired != null) {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) { PairedScreen(paired) }
                        } else {
                            ScanControls()
                            selectedAddress?.let { RingPanel(it) }
                            RingList()
                        }
                    }
                    if (confirmPairing) PairingDialog()
                    when (keyDialog) {
                        KeyDialog.WARN -> BackupWarningDialog()
                        KeyDialog.SHOW -> credential?.let { BackupKeyDialog(it) }
                        KeyDialog.RESTORE -> RestoreDialog()
                        null -> Unit
                    }
                }
            }
        }
    }

    @Composable
    private fun ScanControls() {
        Panel("Find your ring", status) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = job == null,
                    onClick = {
                        rings = emptyList()
                        selectedAddress = null
                        clearResult()
                        permissions.launch(
                            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                        )
                    },
                ) { Text("Scan") }
                OutlinedButton(onClick = { scanner.stop() }) { Text("Stop") }
            }
        }
    }

    @Composable
    private fun RingList() {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rings, key = { it.address }) { ring ->
                val selected = ring.address == selectedAddress
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = job == null) {
                        selectedAddress = ring.address
                        clearResult()
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) Palette.SurfaceRaised else Palette.Surface,
                    ),
                    border = if (selected) BorderStroke(1.dp, Palette.Accent) else null,
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(ring.name + if (selected) "  ·  selected" else "", style = MaterialTheme.typography.titleSmall)
                        Text("${ring.address} · ${ring.rssi} dBm", style = MaterialTheme.typography.bodySmall,
                            color = Palette.TextSecondary)
                    }
                }
            }
        }
    }

    @Composable
    private fun RingPanel(address: String) {
        Panel("Ring $address") {
            Text(
                "Read ring info works on a ring still paired with the Oura app (force-stop it first). " +
                    "On a factory-reset ring, use Pair instead: its first connection must be the pairing.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
            )
            if (hasPendingKey) {
                Text(
                    "A previous pairing attempt did not finish. Pairing again reuses the same saved key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MetricColors.RestingHr,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = job == null, onClick = { readInfo(address) }) { Text("Read ring info") }
                Button(enabled = job == null, onClick = { confirmPairing = true }) { Text("Pair this ring") }
            }
            TextButton(enabled = job == null, onClick = {
                restoreText = ""
                keyDialog = KeyDialog.RESTORE
            }) { Text("Restore with backup key") }
            JobStatus()
        }
    }

    @Composable
    private fun PairedScreen(paired: RingCredential) {
        StatusHeader(paired.serial, summary, busy = job != null, onSync = { sync(paired) })
        if (job != null || jobStatus != null || result.isNotEmpty()) {
            Panel { JobStatus() }
        }
        val dashboard = summary
        if (dashboard == null) {
            Panel("No data yet", "Tap Sync after wearing the ring for a while.") {
                Text(
                    "Just paired? Open Ring & key below and tap Turn on measurements once: a newly " +
                        "paired ring records nothing until heart rate and SpO2 are enabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
            }
        } else {
            DashboardContent(dashboard)
        }
        Panel("Ring & key", "Serial ${paired.serial} · ${paired.address}") {
            Text(
                "The ring key is stored only on this phone. Back it up: uninstalling Open Health " +
                    "or clearing its data otherwise means factory-resetting the ring to pair again.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = job == null, onClick = { measure(paired) }) { Text("Turn on measurements") }
                OutlinedButton(enabled = job == null, onClick = { verify(paired) }) { Text("Check pairing") }
            }
            OutlinedButton(onClick = { keyDialog = KeyDialog.WARN }) { Text("Back up key") }
        }
    }

    @Composable
    private fun JobStatus() {
        if (job != null) OutlinedButton(onClick = { job?.cancel() }) { Text("Cancel") }
        jobStatus?.let { Text(it) }
        result.forEach { Text(it, color = Palette.TextSecondary) }
    }

    @Composable
    private fun PairingDialog() {
        AlertDialog(
            onDismissRequest = { confirmPairing = false },
            title = { Text("Pair this ring?") },
            text = {
                Text(
                    "Only pair a factory-reset ring.\n\n" +
                        "• Forget the ring in Android Bluetooth settings first.\n" +
                        "• Accept the Android pairing prompt if it appears.\n" +
                        "• Afterwards, do not set the ring up in the Oura app: that replaces this key.\n" +
                        "• The key is saved only on this phone. Uninstalling Open Health or clearing its " +
                        "data means factory-resetting the ring again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPairing = false
                    selectedAddress?.let(::pair)
                }) { Text("Pair") }
            },
            dismissButton = { TextButton(onClick = { confirmPairing = false }) { Text("Cancel") } },
        )
    }

    @Composable
    private fun BackupWarningDialog() {
        AlertDialog(
            onDismissRequest = { keyDialog = null },
            title = { Text("Show the ring key?") },
            text = {
                Text(
                    "Anyone with this key can connect to your ring and read its data.\n\n" +
                        "Store it in a password manager or another private place. It is the only way to " +
                        "restore Open Health without resetting the ring. Screenshots are blocked while " +
                        "it is shown."
                )
            },
            confirmButton = { TextButton(onClick = { keyDialog = KeyDialog.SHOW }) { Text("Show key") } },
            dismissButton = { TextButton(onClick = { keyDialog = null }) { Text("Cancel") } },
        )
    }

    @Composable
    private fun BackupKeyDialog(paired: RingCredential) {
        // Keeps the key out of screenshots, screen recordings, and the recent-apps preview.
        DisposableEffect(Unit) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        var copied by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { keyDialog = null },
            title = { Text("Ring key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ring ${paired.serial}")
                    Text(
                        formatKeyForDisplay(paired.keyHex),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "Same format as the desktop tool's key file. Spaces are optional when restoring.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (copied) {
                        Text(
                            "Copied. Paste it into your password manager, then copy something else to " +
                                "replace it on the clipboard.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { keyDialog = null }) { Text("Done") } },
            dismissButton = {
                TextButton(onClick = {
                    copySensitive(paired.keyHex)
                    copied = true
                }) { Text("Copy") }
            },
        )
    }

    @Composable
    private fun RestoreDialog() {
        AlertDialog(
            onDismissRequest = { keyDialog = null },
            title = { Text("Restore with backup key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Use the key saved from Open Health or the desktop tool for this ring. It is " +
                            "tested with the ring before it is saved.",
                    )
                    OutlinedTextField(
                        value = restoreText,
                        onValueChange = { restoreText = it },
                        label = { Text("Backup key") },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = normalizeKeyInput(restoreText) != null,
                    onClick = {
                        keyDialog = null
                        val text = restoreText
                        restoreText = ""
                        selectedAddress?.let { restore(it, text) }
                    },
                ) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = {
                    restoreText = ""
                    keyDialog = null
                }) { Text("Cancel") }
            },
        )
    }

    private fun copySensitive(text: String) {
        val clip = ClipData.newPlainText("Ring key", text)
        // Hides the clipboard preview on Android 13+ (ClipDescription.EXTRA_IS_SENSITIVE);
        // the literal keeps it API 31-safe, and older versions ignore the extra.
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }

    private fun restore(address: String, text: String) = runRingJob("Restore") { onStage ->
        val restored = restoreRing(applicationContext, address, text, keys, onStage)
        loadCredential()
        listOf("Restored the key for ring ${restored.serial}. It is saved on this phone.")
    }

    private fun readInfo(address: String) = runRingJob("Read") { onStage ->
        val info = readRingIdentity(applicationContext, address, onStage)
        listOf(
            "Serial: ${info.serial}",
            "Hardware: ${info.hardwareId ?: "unknown"}",
            "Firmware: ${info.firmwareVersion}",
            "API ${info.apiVersion} · Bluetooth stack ${info.btStackVersion}",
        )
    }

    private fun pair(address: String) = runRingJob("Pairing") { onStage ->
        val paired = pairRing(applicationContext, address, keys, onStage)
        loadCredential()
        val how = if (paired.recovered) "Recovered an interrupted pairing" else "Paired"
        listOf("$how with ring ${paired.credential.serial}. The key is saved on this phone.")
    }

    private fun verify(paired: RingCredential) = runRingJob("Check") { onStage ->
        val report = checkPairing(applicationContext, paired, onStage)
        listOf(
            "Authenticated with ring ${report.serial}.",
            "Battery: ${report.batteryPercent?.let { "$it%" } ?: "not reported"}",
        )
    }

    private fun measure(paired: RingCredential) = runRingJob("Measurement setup") { onStage ->
        val features = enableMeasurements(applicationContext, paired, onStage)
        if (features.isEmpty()) {
            listOf("The ring accepted the request but did not report feature status.")
        } else {
            features.map { "${it.name}: ${if (it.enabled) "on" else "off"}" } +
                "The ring decides when to measure; allow some time before data appears."
        }
    }

    private fun sync(paired: RingCredential) = runRingJob("Sync") { onStage ->
        val report = syncRing(applicationContext, paired, database, onStage)
        loadSummary()
        listOf("Received ${report.eventsSynced} events (${report.inserted} new).")
    }

    private fun loadSummary() {
        if (!database.exists()) {
            summary = null
            return
        }
        scope.launch {
            summary = withContext(Dispatchers.Default) {
                val offset = currentUtcOffsetHours()
                val dashboard = parseSummary(summaryJsonHours(database.path, offset))
                dashboard.copy(hours = parseHourly(hourlyHrJsonHours(database.path, offset, 2u)))
            }
        }
    }

    private fun runRingJob(name: String, block: suspend (onStage: (String) -> Unit) -> List<String>) {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            jobStatus = "Nearby devices permission is required. Tap Scan to grant it."
            return
        }
        scanner.stop()
        clearResult()
        job = scope.launch {
            try {
                result = block { stage -> runOnUiThread { if (job != null) jobStatus = stage } }
                jobStatus = "$name finished. Disconnected."
            } catch (e: RingConnectionException) {
                jobStatus = e.message
            } catch (e: CancellationException) {
                jobStatus = "Cancelled. Disconnected."
                throw e
            } finally {
                job = null
                refreshPendingState()
            }
        }
    }

    private fun clearResult() {
        jobStatus = null
        result = emptyList()
    }

    private fun loadCredential() {
        try {
            credential = keys.active()
            credentialError = null
        } catch (e: CredentialException) {
            credential = null
            credentialError = e.message
        }
        refreshPendingState()
    }

    private fun refreshPendingState() {
        hasPendingKey = try {
            keys.pendingKey() != null
        } catch (e: CredentialException) {
            credentialError = e.message
            true
        }
    }

    override fun onStop() {
        scanner.stop("Scan stopped because the app left the foreground.")
        job?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
