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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.tng_digital.ui.theme.*
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        if (adapter == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Bluetooth not supported", color = TngTextSecondary, style = MaterialTheme.typography.titleMedium)
            }
            return
        }

        if (!adapter.isEnabled) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TngBgSecondary)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Bluetooth icon circle
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(TngErrorLight, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("!", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = TngError)
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "Bluetooth is turned off",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TngTextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Please enable Bluetooth to use TNG offline payments.",
                    textAlign = TextAlign.Center,
                    color = TngTextSecondary,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(32.dp))
                TngPrimaryButton(
                    text = "Open Bluetooth Settings",
                    onClick = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                )
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
        var showHistory by remember { mutableStateOf(false) }

        // API / Sync state
        var sessionInitialized by remember { mutableStateOf(false) }
        var sessionDisplayName by remember { mutableStateOf("") }
        var isPushing by remember { mutableStateOf(false) }
        var isPulling by remember { mutableStateOf(false) }
        var syncMessage by remember { mutableStateOf<String?>(null) }
        val coroutineScope = rememberCoroutineScope()

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

        // Session init on role selection
        LaunchedEffect(appRole) {
            if (appRole != null && !sessionInitialized) {
                try {
                    val role = if (appRole == AppRole.CONSUMER) "user" else "merchant"
                    val deviceId = txManager?.deviceId ?: "unknown"
                    val response = ApiClient.initSession(SessionInitRequest(deviceId = deviceId, role = role))
                    sessionDisplayName = response.merchantName ?: response.displayName
                    if (appRole == AppRole.CONSUMER) {
                        txManager?.localBalance = response.offlineBalance
                    }
                    sessionInitialized = true
                    syncMessage = "Session initialized: $sessionDisplayName"
                } catch (e: Exception) {
                    // Offline — use local values silently
                    sessionInitialized = true
                }
            }
        }

        // Sync handlers
        val onPush: () -> Unit = {
            coroutineScope.launch {
                isPushing = true
                syncMessage = null
                try {
                    val side = if (appRole == AppRole.CONSUMER) "user" else "merchant"
                    val deviceId = txManager?.deviceId ?: "unknown"
                    val queue = SyncQueue.getQueue(side)
                    if (queue.isEmpty()) {
                        syncMessage = "Nothing to push. No pending transactions."
                    } else {
                        val res = ApiClient.pushTransactions(SyncRequest(deviceId = deviceId, transactions = queue))
                        SyncQueue.markSynced(res.syncedTxIds)
                        syncMessage = "${res.syncedTxIds.size} transaction(s) pushed to server."
                    }
                } catch (e: Exception) {
                    syncMessage = "Push failed. Could not reach server."
                } finally {
                    isPushing = false
                }
            }
        }

        val onPull: () -> Unit = {
            coroutineScope.launch {
                isPulling = true
                syncMessage = null
                try {
                    val role = if (appRole == AppRole.CONSUMER) "user" else "merchant"
                    val deviceId = txManager?.deviceId ?: "unknown"
                    val res = ApiClient.pullAccount(PullRequest(deviceId = deviceId, role = role))
                    if (appRole == AppRole.CONSUMER) {
                        txManager?.localBalance = res.offlineBalance
                    }
                    syncMessage = "Updated. Balance: MYR ${"%.2f".format(res.offlineBalance)}, ${res.transactions.size} tx(s) synced."
                } catch (e: Exception) {
                    syncMessage = "Pull failed. Could not reach server."
                } finally {
                    isPulling = false
                }
            }
        }

        when {
            appRole == null -> RoleSelectionScreen(
                onConsumer = { appRole = AppRole.CONSUMER },
                onVendor = {
                    appRole = AppRole.VENDOR
                }
            )
            showHistory -> TransactionHistoryScreen(
                side = if (appRole == AppRole.CONSUMER) "user" else "merchant",
                isPushing = isPushing,
                isPulling = isPulling,
                syncMessage = syncMessage,
                onPush = onPush,
                onPull = onPull,
                onDismissSync = { syncMessage = null },
                onBack = { showHistory = false }
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
                    sessionInitialized = false
                    syncMessage = null
                    appRole = null
                },
                onShowHistory = { showHistory = true }
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
                discoveredDevices = discoveredDevices,
                onScanQr = {
                    onQrScanned = { content ->
                        txManager?.parseQrPayload(content)?.let { payload ->
                            scannedQr = payload
                        } ?: run { errorMsg = "Invalid QR code" }
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
                    btService.stop()
                    txManager?.reset()
                    scannedQr = null
                    pendingAck = null
                    completedReceipt = null
                    errorMsg = null
                    amountText = ""
                    isScanning = false
                    discoveredDevices.clear()
                    sessionInitialized = false
                    syncMessage = null
                    appRole = null
                },
                onShowHistory = { showHistory = true }
            )
        }
    }

    // ─── Biometric Prompt ─────────────────────────────────────────────────────────

    private fun showBiometricPrompt(txManager: TransactionManager?) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricManager = BiometricManager.from(this)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
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

    // ─── Shared UI Components ─────────────────────────────────────────────────────

    @Composable
    fun TngPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
        Button(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = TngYellow,
                contentColor = TngTextPrimary
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 4.dp,
                pressedElevation = 2.dp
            )
        ) {
            Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    fun TngSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(16.dp),
            border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                brush = Brush.linearGradient(listOf(TngBlue, TngBlue))
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = TngBlue
            )
        ) {
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    fun TngCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = TngBgCard),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                content = content
            )
        }
    }

    @Composable
    fun TngScreenHeader(title: String, subtitle: String? = null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(TngBlue, TngBlueDark)
                    ),
                    shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                )
                .padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Column {
                Text(
                    title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        subtitle,
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }

    @Composable
    fun StatusPill(label: String, value: String) {
        Row(
            modifier = Modifier
                .background(
                    Color.White.copy(alpha = 0.12f),
                    RoundedCornerShape(999.dp)
                )
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(999.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(value, color = TngYellow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    // ─── Transaction History Screen ─────────────────────────────────────────────

    @Composable
    fun TransactionHistoryScreen(
        side: String,
        isPushing: Boolean,
        isPulling: Boolean,
        syncMessage: String?,
        onPush: () -> Unit,
        onPull: () -> Unit,
        onDismissSync: () -> Unit,
        onBack: () -> Unit
    ) {
        val transactions = remember { SyncQueue.getQueue(side) }
        val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(TngBgSecondary)
        ) {
            // Blue gradient header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(TngBlue, TngBlueDark))
                    )
                    .padding(top = 48.dp, bottom = 24.dp, start = 20.dp, end = 20.dp)
            ) {
                Column {
                    Text(
                        "< Back",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        modifier = Modifier.clickable { onBack() }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Transaction History",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${transactions.size} pending transaction(s)",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                }
            }

            // Transaction list
            if (transactions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "No transactions yet",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TngTextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Completed transactions will appear here",
                            fontSize = 14.sp,
                            color = TngTextMuted
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(transactions) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${item.tx.currency} ${"%.2f".format(item.tx.amount)}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = TngTextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        dateFormat.format(Date(item.tx.timestamp)),
                                        fontSize = 12.sp,
                                        color = TngTextMuted
                                    )
                                    Text(
                                        "ID: ${item.txId.take(12)}...",
                                        fontSize = 11.sp,
                                        color = TngTextMuted,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                // Sync status badge
                                val (badgeColor, badgeText) = when (item.tx.syncStatus) {
                                    "synced" -> Pair(TngSuccess, "Synced")
                                    else -> Pair(TngWarning, "Pending")
                                }
                                Box(
                                    modifier = Modifier
                                        .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        badgeText,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = badgeColor
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Sync section at bottom
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                SyncSection(
                    side = side,
                    isPushing = isPushing,
                    isPulling = isPulling,
                    syncMessage = syncMessage,
                    onPush = onPush,
                    onPull = onPull,
                    onDismissSync = onDismissSync
                )
            }
        }
    }

    // ─── Sync Section ─────────────────────────────────────────────────────────────

    @Composable
    fun SyncSection(
        side: String,
        isPushing: Boolean,
        isPulling: Boolean,
        syncMessage: String?,
        onPush: () -> Unit,
        onPull: () -> Unit,
        onDismissSync: () -> Unit
    ) {
        val pendingCount = SyncQueue.pendingCount(side)

        // Sync message banner
        if (syncMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TngBlue.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        syncMessage,
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        color = TngBlue
                    )
                    Text(
                        "x",
                        modifier = Modifier
                            .clickable { onDismissSync() }
                            .padding(4.dp),
                        color = TngBlue,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Push / Pull buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onPush,
                enabled = !isPushing && !isPulling,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TngBlue,
                    contentColor = Color.White
                )
            ) {
                if (isPushing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        "Push${if (pendingCount > 0) " ($pendingCount)" else ""}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Button(
                onClick = onPull,
                enabled = !isPushing && !isPulling,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TngBlueLight,
                    contentColor = Color.White
                )
            ) {
                if (isPulling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Pull", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
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
        onReset: () -> Unit,
        onShowHistory: () -> Unit
    ) {
        val qrBitmap = remember(txManager) {
            txManager?.generateVendorQrPayload()?.let { payload ->
                QRCodeUtils.generateQRCode(payload, 512)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(TngBgSecondary),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Blue header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(listOf(TngBlue, TngBlueDark)),
                        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("Vendor Mode", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusPill(label = "Status", value = btStatus)
                        StatusPill(label = "TX", value = txState.name)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    receipt != null -> ReceiptCard(receipt = receipt, isVendor = true)
                    errorMsg != null -> ErrorCard(errorMsg)
                    incomingRequest != null -> {
                        TngCard {
                            // Yellow accent strip
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .background(TngYellow, RoundedCornerShape(2.dp))
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "INCOMING PAYMENT",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TngTextMuted,
                                letterSpacing = 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("From: ${incomingRequest.consumerId}", color = TngTextSecondary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "${incomingRequest.currency} ${"%.2f".format(incomingRequest.amount)}",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TngTextPrimary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            when (txState) {
                                TransactionState.ACK_RECEIVED -> {
                                    CircularProgressIndicator(
                                        color = TngBlue,
                                        modifier = Modifier.align(Alignment.CenterHorizontally)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "ACK sent. Waiting for consumer confirmation...",
                                        textAlign = TextAlign.Center,
                                        color = TngTextSecondary
                                    )
                                }
                                TransactionState.COMPLETED -> {
                                    Text(
                                        "Payment received!",
                                        fontWeight = FontWeight.Bold,
                                        color = TngSuccess
                                    )
                                }
                                else -> Text(
                                    "Processing... (${txState.name})",
                                    color = TngTextSecondary
                                )
                            }
                        }
                    }
                    else -> {
                        // QR Code display card
                        TngCard {
                            Text(
                                "SHOW QR TO CONSUMER",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TngTextMuted,
                                letterSpacing = 1.5.sp,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            qrBitmap?.let { bmp ->
                                Box(
                                    modifier = Modifier
                                        .size(220.dp)
                                        .align(Alignment.CenterHorizontally)
                                        .border(1.dp, TngBorder, RoundedCornerShape(16.dp))
                                        .padding(12.dp)
                                ) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "Vendor QR Code",
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                txManager?.merchantName ?: "",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 17.sp,
                                color = TngTextPrimary,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        TngPrimaryButton(
                            text = "Make Device Discoverable (300s)",
                            onClick = onMakeDiscoverable
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Waiting for consumer to connect...",
                            style = MaterialTheme.typography.bodySmall,
                            color = TngTextMuted,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Bottom buttons
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                TngPrimaryButton(text = "Transaction History", onClick = onShowHistory)
                Spacer(modifier = Modifier.height(8.dp))
                TngSecondaryButton(text = "Back to Role Selection", onClick = onReset)
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
        discoveredDevices: List<BluetoothDevice>,
        onScanQr: () -> Unit,
        onStartDiscovery: () -> Unit,
        onDeviceSelected: (BluetoothDevice) -> Unit,
        onPay: () -> Unit,
        onBiometricConfirm: () -> Unit,
        onReset: () -> Unit,
        onShowHistory: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(TngBgSecondary),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Blue header with balance
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(listOf(TngBlue, TngBlueDark)),
                        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("Consumer Mode", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusPill(label = "Status", value = btStatus)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    // Balance card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "AVAILABLE BALANCE",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.6f),
                                letterSpacing = 1.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "MYR ${"%.2f".format(txManager?.localBalance ?: 500.0)}",
                                fontSize = 30.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                    }
                    // Yellow accent strip
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(TngYellow, RoundedCornerShape(2.dp))
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    receipt != null -> ReceiptCard(receipt = receipt, isVendor = false)
                    errorMsg != null -> ErrorCard(errorMsg)
                    txState == TransactionState.CONFIRM_SENT -> {
                        TngCard {
                            CircularProgressIndicator(
                                color = TngBlue,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Sending confirmation...\nWaiting for receipt",
                                textAlign = TextAlign.Center,
                                color = TngTextSecondary
                            )
                        }
                    }
                    txState == TransactionState.ACK_RECEIVED && pendingAck != null -> {
                        ConfirmPaymentCard(ack = pendingAck, onConfirm = onBiometricConfirm)
                    }
                    txState == TransactionState.REQUEST_SENT -> {
                        TngCard {
                            CircularProgressIndicator(
                                color = TngBlue,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Waiting for vendor acknowledgement...",
                                textAlign = TextAlign.Center,
                                color = TngTextSecondary
                            )
                        }
                    }
                    btStatus == "Connected" && txState == TransactionState.CHANNEL_READY -> {
                        TngCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(TngSuccessLight, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("✓", color = TngSuccess, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "Secure channel established",
                                    color = TngSuccess,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                            scannedQr?.let {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Vendor: ${it.merchantName}", color = TngTextSecondary)
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = amountText,
                                onValueChange = onAmountChange,
                                label = { Text("Amount (MYR)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = TngTextPrimary,
                                    unfocusedTextColor = TngTextPrimary,
                                    focusedBorderColor = TngBlue,
                                    unfocusedBorderColor = TngBorder,
                                    cursorColor = TngBlue,
                                    focusedLabelColor = TngBlue,
                                    unfocusedLabelColor = TngTextSecondary
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        TngPrimaryButton(text = "Send Payment Request", onClick = onPay)
                    }
                    btStatus.startsWith("Connecting") || txState == TransactionState.HANDSHAKE_PENDING -> {
                        TngCard {
                            CircularProgressIndicator(
                                color = TngBlue,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(btStatus, textAlign = TextAlign.Center, color = TngTextSecondary)
                        }
                    }
                    scannedQr != null -> {
                        QrScannedSection(
                            qr = scannedQr,
                            isScanning = isScanning,
                            discoveredDevices = discoveredDevices,
                            btAdapter = btAdapter,
                            onStartDiscovery = onStartDiscovery,
                            onDeviceSelected = onDeviceSelected
                        )
                    }
                    else -> {
                        TngCard {
                            Text(
                                "Step 1",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TngBlue,
                                letterSpacing = 1.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Scan the vendor's QR code",
                                style = MaterialTheme.typography.titleMedium,
                                color = TngTextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Point your camera at the vendor's QR code to begin",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TngTextMuted
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        TngPrimaryButton(text = "Scan Vendor QR Code", onClick = onScanQr)
                    }
                }
            }

            // Bottom buttons
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                TngPrimaryButton(text = "Transaction History", onClick = onShowHistory)
                Spacer(modifier = Modifier.height(8.dp))
                TngSecondaryButton(text = "Back to Role Selection", onClick = onReset)
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun QrScannedSection(
        qr: QrPayload,
        isScanning: Boolean,
        discoveredDevices: List<BluetoothDevice>,
        btAdapter: BluetoothAdapter,
        onStartDiscovery: () -> Unit,
        onDeviceSelected: (BluetoothDevice) -> Unit
    ) {
        // QR scanned status card
        TngCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(TngSuccessLight, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", color = TngSuccess, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("QR Scanned", fontWeight = FontWeight.Bold, color = TngSuccess)
                    Text("Vendor: ${qr.merchantName}", color = TngTextSecondary, fontSize = 13.sp)
                    Text("ID: ${qr.vendorId}", style = MaterialTheme.typography.bodySmall, color = TngTextMuted, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Step 2 instruction
        TngCard {
            Text(
                "Step 2",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = TngBlue,
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Connect to the vendor's device",
                style = MaterialTheme.typography.titleMedium,
                color = TngTextPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Paired devices
            Text(
                "PAIRED DEVICES",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = TngTextMuted,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            val paired = btAdapter.bondedDevices?.toList() ?: emptyList()
            if (paired.isNotEmpty()) {
                LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                    items(paired) { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onDeviceSelected(device) }
                                .background(TngBgSecondary, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(TngBlue, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("B", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    device.name ?: "Unknown",
                                    fontWeight = FontWeight.SemiBold,
                                    color = TngTextPrimary,
                                    fontSize = 14.sp
                                )
                                Text(device.address, fontSize = 11.sp, color = TngTextMuted, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            } else {
                Text("No paired devices found", color = TngTextMuted, fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        TngPrimaryButton(
            text = if (isScanning) "Scanning..." else "Search for Nearby Devices",
            onClick = onStartDiscovery
        )

        if (isScanning || discoveredDevices.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            TngCard {
                Text(
                    "NEARBY DEVICES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TngTextMuted,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (isScanning && discoveredDevices.isEmpty()) {
                    CircularProgressIndicator(
                        color = TngBlue,
                        modifier = Modifier.size(24.dp).align(Alignment.CenterHorizontally),
                        strokeWidth = 2.dp
                    )
                }
                LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                    items(discoveredDevices) { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onDeviceSelected(device) }
                                .background(TngBgSecondary, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(TngBlueLight, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("B", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    device.name ?: "Unknown",
                                    fontWeight = FontWeight.SemiBold,
                                    color = TngTextPrimary,
                                    fontSize = 14.sp
                                )
                                Text(device.address, fontSize = 11.sp, color = TngTextMuted, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }
    }

    @Composable
    fun ConfirmPaymentCard(ack: TxAck, onConfirm: () -> Unit) {
        TngCard {
            // Yellow accent strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(TngYellow, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "CONFIRM PAYMENT",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = TngTextMuted,
                letterSpacing = 1.5.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                ack.merchantName,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = TngTextPrimary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "${ack.currency} ${"%.2f".format(ack.amount)}",
                fontSize = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TngTextPrimary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "TX: ${ack.txId.take(8)}...",
                style = MaterialTheme.typography.bodySmall,
                color = TngTextMuted,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(20.dp))
            TngPrimaryButton(text = "Authenticate & Pay", onClick = onConfirm)
        }
    }

    @Composable
    fun ReceiptCard(receipt: TxReceipt, isVendor: Boolean) {
        TngCard {
            // Success icon
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .align(Alignment.CenterHorizontally)
                    .background(TngSuccessLight, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = TngSuccess)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                if (isVendor) "Payment Received" else "Payment Complete",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TngSuccess,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(12.dp))
            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(TngDivider)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                receipt.merchantName,
                fontSize = 16.sp,
                color = TngTextPrimary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${receipt.currency} ${"%.2f".format(receipt.amount)}",
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TngTextPrimary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(12.dp))
            // Divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(TngDivider)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "TX: ${receipt.txId.take(16)}...",
                style = MaterialTheme.typography.bodySmall,
                color = TngTextMuted,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "At: ${receipt.completedAt}",
                style = MaterialTheme.typography.bodySmall,
                color = TngTextMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Cryptographically signed & stored locally",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = TngTextMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }

    @Composable
    fun ErrorCard(message: String) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = TngErrorLight)
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(TngError, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Error", fontWeight = FontWeight.Bold, color = TngError, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(message, color = TngError.copy(alpha = 0.8f))
                }
            }
        }
    }

    @Composable
    fun RoleSelectionScreen(onConsumer: () -> Unit, onVendor: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(TngBlue, TngBlueDark),
                        startY = 0f,
                        endY = 600f
                    )
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.3f))

            // Logo area
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "TNG",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    color = TngBlue
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "TNG Digital",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Offline Transaction",
                fontSize = 15.sp,
                color = Color.White.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.weight(0.3f))

            // Role selection card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "SELECT YOUR ROLE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TngTextMuted,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    TngPrimaryButton(text = "Consumer (Pay)", onClick = onConsumer)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onVendor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TngBlue
                        )
                    ) {
                        Text("Vendor (Receive)", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.4f))
        }
    }
}
