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
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.example.tng_digital.ui.theme.*

class MainActivity : ComponentActivity() {

    private val bluetoothManager by lazy { getSystemService(BluetoothManager::class.java) }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager?.adapter }

    private var bluetoothService: BluetoothService? = null

    private val discoveredDevices = mutableStateListOf<BluetoothDevice>()

    // Global state to handle navigation from BroadcastReceiver
    private var onPairingSuccess: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when(intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let {
                        if (discoveredDevices.none { d -> d.address == it.address }) {
                            discoveredDevices.add(it)
                        }
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (device != null && bondState == BluetoothDevice.BOND_BONDED) {
                        Toast.makeText(context, "Paired with ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()
                        onPairingSuccess?.invoke()
                        bluetoothService?.connectToDevice(device)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Toast.makeText(context, "Discovery Finished", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            // All permissions granted
        } else {
            Toast.makeText(this, "Permissions required for Bluetooth", Toast.LENGTH_SHORT).show()
        }
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BluetoothApp(bluetoothAdapter)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    @Composable
    fun BluetoothApp(adapter: BluetoothAdapter?) {
        if (adapter == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Bluetooth not supported on this device",
                    style = MaterialTheme.typography.titleMedium,
                    color = Error
                )
            }
            return
        }

        var status by remember { mutableStateOf("Disconnected") }
        var messages by remember { mutableStateOf(listOf<String>()) }
        var role by remember { mutableStateOf<String?>(null) } // "Sender" or "Receiver"
        var isScanning by remember { mutableStateOf(false) }
        var isConnecting by remember { mutableStateOf(false) }

        val service = remember {
            BluetoothService(
                adapter = adapter,
                onMessageReceived = { msg -> messages = messages + "Received: $msg" },
                onStatusChanged = { newStatus ->
                    status = newStatus
                    if (newStatus == "Connected" || newStatus == "Connection failed") {
                        isConnecting = false
                    }
                }
            ).also {
                bluetoothService = it
                // Automatically start server on home page so device is ready to receive
                it.startServer()

                // NEW: Auto-connect logic for pre-paired devices
                val pairedHuaweiDevice = adapter.bondedDevices.find { device ->
                    @SuppressLint("MissingPermission")
                    val name = device.name ?: ""
                    name.contains("HUAWEI", ignoreCase = true)
                }

                pairedHuaweiDevice?.let { device ->
                    Log.d("MainActivity", "Found pre-paired HUAWEI device: ${device.address}. Auto-connecting...")
                    isConnecting = true
                    role = "Receiver"
                    it.connectToDevice(device)
                }
            }
        }

        // Handle navigation after successful pairing
        onPairingSuccess = {
            role = "Receiver"
            isConnecting = true
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // TNG Blue Header
                TngHeader(status = status)

                // Content
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    if (role == null) {
                        // Show Messaging UI by default as "Receiver" on home page
                        MessagingUI(
                            messages = messages,
                            onSendMessage = { msg ->
                                service.sendMessage(msg)
                                messages = messages + "Sent: $msg"
                            }
                        )

                        Spacer(modifier = Modifier.height(20.dp))
                        HorizontalDivider(color = Divider, thickness = 1.dp)
                        Spacer(modifier = Modifier.height(20.dp))

                        RoleSelection(
                            onRoleSelected = { selectedRole ->
                                role = selectedRole
                                if (selectedRole == "Receiver") {
                                    service.startServer()
                                }
                            },
                            onDiscoverable = {
                                val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                                }
                                startActivity(discoverableIntent)
                            },
                            onStartDiscovery = {
                                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                                    discoveredDevices.clear()
                                    adapter.startDiscovery()
                                    isScanning = true
                                } else {
                                    Toast.makeText(this@MainActivity, "Scan permission required", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )

                        if (isScanning || discoveredDevices.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                "Nearby Devices",
                                style = MaterialTheme.typography.titleSmall,
                                color = TngBlue
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                LazyColumn(
                                    modifier = Modifier
                                        .height(200.dp)
                                        .padding(8.dp)
                                ) {
                                    // Temporary hack: Filter devices containing "HUAWEI"
                                    val filteredDevices = discoveredDevices.filter { device ->
                                        @SuppressLint("MissingPermission")
                                        val name = device.name ?: ""
                                        name.contains("HUAWEI", ignoreCase = true)
                                    }

                                    items(filteredDevices) { device ->
                                        @SuppressLint("MissingPermission")
                                        val deviceName = device.name ?: "Unknown Device"
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable {
                                                    Log.d("MainActivity", "Device selected: $deviceName (${device.address})")
                                                    adapter.cancelDiscovery()
                                                    isScanning = false

                                                    if (device.bondState == BluetoothDevice.BOND_NONE) {
                                                        Log.d("MainActivity", "Device not paired. Creating bond...")
                                                        device.createBond()
                                                        // Don't set role yet, wait for bond success via onPairingSuccess
                                                    } else {
                                                        Log.d("MainActivity", "Device already paired. Connecting...")
                                                        role = "Receiver"
                                                        isConnecting = true
                                                        service.connectToDevice(device)
                                                    }
                                                },
                                            color = BgSecondary,
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Blue dot indicator
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .background(TngBlue, RoundedCornerShape(4.dp))
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column {
                                                    Text(
                                                        text = deviceName,
                                                        style = MaterialTheme.typography.titleSmall,
                                                        color = TextPrimary
                                                    )
                                                    Text(
                                                        text = device.address,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = TextMuted
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Role badge
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = TngBlue.copy(alpha = 0.1f),
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            Text(
                                text = role ?: "",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = TngBlue,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (role == "Sender" && status != "Connected") {
                            DeviceList(adapter) { device ->
                                service.connectToDevice(device)
                            }
                        } else {
                            MessagingUI(
                                messages = messages,
                                onSendMessage = { msg ->
                                    service.sendMessage(msg)
                                    messages = messages + "Sent: $msg"
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        // Reset button - secondary style (outlined)
                        OutlinedButton(
                            onClick = {
                                service.stop()
                                role = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = TngBlue
                            ),
                            contentPadding = PaddingValues(vertical = 14.dp)
                        ) {
                            Text(
                                "Reset Role",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            // Connecting overlay
            if (isConnecting) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF1A1A2E).copy(alpha = 0.7f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(40.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    color = TngBlue,
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "Connecting...",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun TngHeader(status: String) {
        val statusColor = when {
            status == "Connected" -> Success
            status.contains("fail", ignoreCase = true) -> Error
            else -> TngYellow
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(TngBlue, TngBlueDark)
                    )
                )
                .padding(top = 48.dp, bottom = 24.dp, start = 20.dp, end = 20.dp)
        ) {
            Column {
                // Brand
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "t",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        color = TngYellow
                    )
                    Text(
                        text = "/ng",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 0.3.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "digital",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        letterSpacing = 0.3.sp
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                // Status pill
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.White.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(statusColor, RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )
                    }
                }
            }
        }
        // Yellow accent strip (TNG signature)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(TngYellow)
        )
    }

    @Composable
    fun RoleSelection(
        onRoleSelected: (String) -> Unit,
        onDiscoverable: () -> Unit,
        onStartDiscovery: () -> Unit
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Select your role",
                style = MaterialTheme.typography.titleSmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Sender card-button
                Button(
                    onClick = { onRoleSelected("Sender") },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TngYellow,
                        contentColor = TextPrimary
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp
                    )
                ) {
                    Text("Sender", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                // Receiver card-button
                Button(
                    onClick = { onRoleSelected("Receiver") },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TngBlue,
                        contentColor = Color.White
                    ),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp
                    )
                ) {
                    Text("Receiver", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "Connection Tools",
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onDiscoverable,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TngBlue),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text("Allow Discovery (Receiver)", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onStartDiscovery,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TngBlue),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text("Search for Devices (Sender)", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun DeviceList(adapter: BluetoothAdapter, onDeviceSelected: (BluetoothDevice) -> Unit) {
        val pairedDevices = adapter.bondedDevices.toList()

        if (pairedDevices.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = BgSecondary)
            ) {
                Text(
                    "No paired devices found.\nPlease search and pair first.",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Text(
                "Select a paired device",
                style = MaterialTheme.typography.titleSmall,
                color = TngBlue
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    items(pairedDevices) { device ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onDeviceSelected(device) },
                            color = BgSecondary,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Success, RoundedCornerShape(4.dp))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = device.name ?: "Unknown",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = device.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun MessagingUI(messages: List<String>, onSendMessage: (String) -> Unit) {
        var text by remember { mutableStateOf("") }

        Column {
            // Input row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "Type a message...",
                            color = TextMuted
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TngBlue,
                        unfocusedBorderColor = Border,
                        cursorColor = TngBlue,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    ),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSendMessage(text)
                            text = ""
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TngYellow,
                        contentColor = TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    Text("Send", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Messages",
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No messages yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(messages) { msg ->
                            val isSent = msg.startsWith("Sent:")
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSent) TngBlue.copy(alpha = 0.08f) else SuccessLight,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    msg,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSent) TngBlue else Success
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
