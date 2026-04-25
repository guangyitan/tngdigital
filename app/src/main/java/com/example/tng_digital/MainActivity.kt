package com.example.tng_digital

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.tng_digital.ui.theme.TngdigitalTheme
import com.google.zxing.integration.android.IntentIntegrator
import java.util.UUID

class MainActivity : FragmentActivity() {

    private val bluetoothManager by lazy { getSystemService(BluetoothManager::class.java) }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager?.adapter }

    private val discoveredDevices = mutableStateListOf<BluetoothDevice>()
    private var onPairingSuccess: (() -> Unit)? = null
    private var onQrScanned: ((String) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let { if (discoveredDevices.none { d -> d.address == it.address }) discoveredDevices.add(it) }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (device != null && bondState == BluetoothDevice.BOND_BONDED) {
                        Toast.makeText(context, "Paired with ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()
                        onPairingSuccess?.invoke()
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED ->
                    Toast.makeText(context, "Discovery finished", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (!perms.all { it.value })
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        registerReceiver(receiver, filter)
        setContent {
            TngdigitalTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TransactApp(bluetoothAdapter)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            result.contents?.let { onQrScanned?.invoke(it) }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun launchQrScanner() {
        IntentIntegrator(this).apply {
            setBeepEnabled(true)
            setOrientationLocked(false)
        }.initiateScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED })
            requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    // ─── Main Composable ─────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    @Composable
    fun TransactApp(adapter: BluetoothAdapter?) {
        if (adapter == null) { Text("Bluetooth not supported"); return }

        if (!adapter.isEnabled) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Bluetooth is turned off", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Please enable Bluetooth to use TNG offline payments.", textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Open Bluetooth Settings")
                }
            }
            return
        }

        var appRole by remember { mutableStateOf<AppRole?>(null) }
        var btStatus by remember { mutableStateOf("Disconnected") }
        var txState by remember { mutableStateOf(TransactionState.IDLE) }
        var pendingAck by remember { mutableStateOf<TxAck?>(null) }
        var completedReceipt by remember { mutableStateOf<TxReceipt?>(null) }
        var errorMsg by remember { mutableStateOf<String?>(null) }
        var scannedQr by remember { mutableStateOf<QrPayload?>(null) }
        var amountText by remember { mutableStateOf("") }
        var vendorIncomingRequest by remember { mutableStateOf<TxRequest?>(null) }
        var isScanning by remember { mutableStateOf(false) }
        var isBleScanning by remember { mutableStateOf(false) }

        val btService = remember { BluetoothService(adapter) }
        val txManager = remember(appRole) {
            appRole?.let { role ->
                TransactionManager(
                    role = role,
                    onStateChanged = { txState = it },
                    onError = { errorMsg = it },
                    onAckReceived = { pendingAck = it },
                    onTransactionComplete = { completedReceipt = it },
                    onVendorRequestReceived = { vendorIncomingRequest = it }
                ).also { mgr ->
                    mgr.sendRaw = { msg -> btService.sendMessage(msg) }
                    btService.onMessageReceived = { msg -> mgr.handleReceivedMessage(msg) }
                    btService.onStatusChanged = { status ->
                        btStatus = status
                        if (status == "Connected") mgr.onConnected()
                    }
                }
            }
        }

        onPairingSuccess = {
            btService.startServer()
        }

        when {
            appRole == null -> RoleSelectionScreen(
                onConsumer = { appRole = AppRole.CONSUMER },
                onVendor = {
                    appRole = AppRole.VENDOR
                }
            )
            appRole == AppRole.VENDOR -> VendorScreen(
                txManager = txManager,
                btStatus = btStatus,
                txState = txState,
                incomingRequest = vendorIncomingRequest,
                receipt = completedReceipt,
                errorMsg = errorMsg,
                onMakeDiscoverable = {
                    btService.startServer()
                    btService.startAdvertising(UUID.fromString(txManager?.serviceUuid ?: "8ce255c0-200a-11e0-ac64-0800200c9a66"))
                    startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                        putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                    })
                },
                onReset = {
                    btService.stop()
                    txManager?.reset()
                    vendorIncomingRequest = null
                    completedReceipt = null
                    errorMsg = null
                    appRole = null
                }
            )
            appRole == AppRole.CONSUMER -> ConsumerScreen(
                txManager = txManager,
                btAdapter = adapter,
                btStatus = btStatus,
                txState = txState,
                scannedQr = scannedQr,
                pendingAck = pendingAck,
                receipt = completedReceipt,
                errorMsg = errorMsg,
                amountText = amountText,
                onAmountChange = { amountText = it },
                isScanning = isScanning,
                isBleScanning = isBleScanning,
                discoveredDevices = discoveredDevices,
                onScanQr = {
                    onQrScanned = { content ->
                        val mgr = txManager
                        if (mgr == null) {
                            errorMsg = "Select Consumer role before scanning"
                        } else {
                            val payload = mgr.parseQrPayload(content)
                            if (payload != null) {
                                scannedQr = payload
                                isBleScanning = true
                                btService.startBleScan(UUID.fromString(payload.serviceUuid)) { device ->
                                    isBleScanning = false
                                    if (device.bondState == BluetoothDevice.BOND_NONE) {
                                        onPairingSuccess = { btService.connectToDevice(device) }
                                        @SuppressLint("MissingPermission") device.createBond()
                                    } else {
                                        btService.connectToDevice(device)
                                    }
                                }
                            }
                        }
                    }
                    launchQrScanner()
                },
                onStartDiscovery = {
                    discoveredDevices.clear()
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                        || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        adapter.startDiscovery()
                        isScanning = true
                    }
                },
                onDeviceSelected = { device ->
                    adapter.cancelDiscovery()
                    isScanning = false
                    if (device.bondState == BluetoothDevice.BOND_NONE) {
                        onPairingSuccess = { btService.connectToDevice(device) }
                        @SuppressLint("MissingPermission") device.createBond()
                    } else {
                        btService.connectToDevice(device)
                    }
                },
                onPay = {
                    val amt = amountText.toDoubleOrNull()
                    if (amt == null || amt <= 0) { errorMsg = "Enter a valid amount" }
                    else txManager?.sendTxRequest(amt)
                },
                onBiometricConfirm = { showBiometricPrompt(txManager) },
                onReset = {
                    btService.stopBleScan()
                    btService.stop()
                    txManager?.reset()
                    scannedQr = null
                    pendingAck = null
                    completedReceipt = null
                    errorMsg = null
                    amountText = ""
                    isScanning = false
                    isBleScanning = false
                    discoveredDevices.clear()
                    appRole = null
                }
            )
        }
    }

    // ─── Biometric Prompt ─────────────────────────────────────────────────────────

    private fun showBiometricPrompt(txManager: TransactionManager?) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricManager = BiometricManager.from(this)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        )
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, "Biometric not available – confirm skipped for demo", Toast.LENGTH_SHORT).show()
            txManager?.sendTxConfirm()
            return
        }
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                txManager?.sendTxConfirm()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Toast.makeText(this@MainActivity, "Auth error: $errString", Toast.LENGTH_SHORT).show()
            }
            override fun onAuthenticationFailed() {
                Toast.makeText(this@MainActivity, "Authentication failed", Toast.LENGTH_SHORT).show()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Confirm Payment")
            .setSubtitle("Authenticate to authorize this offline transaction")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    // ─── Vendor Screen ────────────────────────────────────────────────────────────

    @Composable
    fun VendorScreen(
        txManager: TransactionManager?,
        btStatus: String,
        txState: TransactionState,
        incomingRequest: TxRequest?,
        receipt: TxReceipt?,
        errorMsg: String?,
        onMakeDiscoverable: () -> Unit,
        onReset: () -> Unit
    ) {
        val qrBitmap = remember(txManager) {
            txManager?.generateVendorQrPayload()?.let { payload ->
                QRCodeUtils.generateQRCode(payload, 512)
            }
        }
        var isDiscoverable by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Vendor Mode", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Status: $btStatus", style = MaterialTheme.typography.bodyMedium)
            Text("TX State: ${txState.name}", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(12.dp))

            when {
                receipt != null -> ReceiptCard(receipt = receipt, isVendor = true)
                errorMsg != null -> ErrorCard(errorMsg)
                incomingRequest != null -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Incoming Payment", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("From: ${incomingRequest.consumerId}")
                            Text("Amount: ${incomingRequest.currency} ${"%.2f".format(incomingRequest.amount)}")
                            Spacer(modifier = Modifier.height(8.dp))
                            when (txState) {
                                TransactionState.ACK_RECEIVED -> {
                                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                                    Text("ACK sent. Waiting for consumer confirmation...", textAlign = TextAlign.Center)
                                }
                                TransactionState.COMPLETED -> Text("✓ Payment received!", fontWeight = FontWeight.Bold)
                                else -> Text("Processing... (${txState.name})")
                            }
                        }
                    }
                }
                else -> {
                    if (isDiscoverable) {
                        qrBitmap?.let { bmp ->
                            Text("Show this QR to the consumer:", style = MaterialTheme.typography.labelLarge)
                            Spacer(modifier = Modifier.height(8.dp))
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Vendor QR Code",
                                modifier = Modifier.size(240.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(txManager?.merchantName ?: "", fontWeight = FontWeight.Medium)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Waiting for consumer to connect…", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Step 1: Make your device discoverable", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                isDiscoverable = true
                                onMakeDiscoverable()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Make Device Discoverable (300s)")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("QR code will appear after becoming discoverable.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text("Back to Role Selection")
            }
        }
    }

    // ─── Consumer Screen ──────────────────────────────────────────────────────────

    @Composable
    fun ConsumerScreen(
        txManager: TransactionManager?,
        btAdapter: BluetoothAdapter,
        btStatus: String,
        txState: TransactionState,
        scannedQr: QrPayload?,
        pendingAck: TxAck?,
        receipt: TxReceipt?,
        errorMsg: String?,
        amountText: String,
        onAmountChange: (String) -> Unit,
        isScanning: Boolean,
        isBleScanning: Boolean,
        discoveredDevices: List<BluetoothDevice>,
        onScanQr: () -> Unit,
        onStartDiscovery: () -> Unit,
        onDeviceSelected: (BluetoothDevice) -> Unit,
        onPay: () -> Unit,
        onBiometricConfirm: () -> Unit,
        onReset: () -> Unit
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Consumer Mode", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Status: $btStatus", style = MaterialTheme.typography.bodyMedium)
            Text("Balance: MYR ${"%.2f".format(txManager?.localBalance ?: 500.0)}", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(12.dp))

            when {
                receipt != null -> ReceiptCard(receipt = receipt, isVendor = false)
                errorMsg != null -> ErrorCard(errorMsg)
                txState == TransactionState.CONFIRM_SENT -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Sending confirmation… waiting for receipt")
                }
                txState == TransactionState.ACK_RECEIVED && pendingAck != null -> {
                    ConfirmPaymentCard(ack = pendingAck, onConfirm = onBiometricConfirm)
                }
                txState == TransactionState.REQUEST_SENT -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Waiting for vendor acknowledgement…")
                }
                btStatus == "Connected" && txState == TransactionState.CHANNEL_READY -> {
                    Text("✓ Secure channel established", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    scannedQr?.let { Text("Vendor: ${it.merchantName}") }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = amountText, onValueChange = onAmountChange,
                        label = { Text("Amount (MYR)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onPay, modifier = Modifier.fillMaxWidth()) { Text("Send Payment Request") }
                }
                btStatus.startsWith("Connecting") || txState == TransactionState.HANDSHAKE_PENDING -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(btStatus)
                }
                scannedQr != null -> {
                    QrScannedSection(
                        qr = scannedQr,
                        isScanning = isScanning,
                        isBleScanning = isBleScanning,
                        discoveredDevices = discoveredDevices,
                        btAdapter = btAdapter,
                        onStartDiscovery = onStartDiscovery,
                        onDeviceSelected = onDeviceSelected
                    )
                }
                else -> {
                    Text("Step 1: Scan the vendor's QR code", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) {
                        Text("Scan Vendor QR Code")
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text("Back to Role Selection")
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun QrScannedSection(
        qr: QrPayload,
        isScanning: Boolean,
        isBleScanning: Boolean,
        discoveredDevices: List<BluetoothDevice>,
        btAdapter: BluetoothAdapter,
        onStartDiscovery: () -> Unit,
        onDeviceSelected: (BluetoothDevice) -> Unit
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("✓ QR Scanned", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Vendor: ${qr.merchantName}")
                Text("ID: ${qr.vendorId}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (isBleScanning) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(8.dp))
            Text("Locating vendor device via BLE…", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Will connect automatically once found.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        } else {
            Text("Step 2: Connect to the vendor's device", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Paired devices:", style = MaterialTheme.typography.labelLarge)
            val paired = btAdapter.bondedDevices?.toList() ?: emptyList()
            if (paired.isNotEmpty()) {
                LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                    items(paired) { device ->
                        Text(
                            text = "${device.name ?: "Unknown"} (${device.address})",
                            modifier = Modifier.fillMaxWidth().clickable { onDeviceSelected(device) }.padding(8.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onStartDiscovery, modifier = Modifier.fillMaxWidth()) {
                Text(if (isScanning) "Scanning…" else "Search for Nearby Devices")
            }
            if (isScanning || discoveredDevices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Nearby:", style = MaterialTheme.typography.labelSmall)
                LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                    items(discoveredDevices) { device ->
                        Text(
                            text = "${device.name ?: "Unknown"} (${device.address})",
                            modifier = Modifier.fillMaxWidth().clickable { onDeviceSelected(device) }.padding(8.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun ConfirmPaymentCard(ack: TxAck, onConfirm: () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Confirm Payment", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(ack.merchantName, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text("${ack.currency} ${"%.2f".format(ack.amount)}", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("TX: ${ack.txId.take(8)}…", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                    Text("Authenticate & Pay")
                }
            }
        }
    }

    @Composable
    fun ReceiptCard(receipt: TxReceipt, isVendor: Boolean) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (isVendor) "✓ Payment Received" else "✓ Payment Complete",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Text(receipt.merchantName, fontSize = 16.sp)
                Text("${receipt.currency} ${"%.2f".format(receipt.amount)}", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("TX: ${receipt.txId.take(16)}…", style = MaterialTheme.typography.bodySmall)
                Text("At: ${receipt.completedAt}", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Cryptographically signed & stored locally",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.secondary)
            }
        }
    }

    @Composable
    fun ErrorCard(message: String) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Error", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }

    @Composable
    fun RoleSelectionScreen(onConsumer: () -> Unit, onVendor: () -> Unit) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("TNG Digital", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Offline Transaction", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(48.dp))
            Text("Select your role:", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onConsumer, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Consumer (Pay)", fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onVendor, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Vendor (Receive)", fontSize = 16.sp)
            }
        }
    }
}
