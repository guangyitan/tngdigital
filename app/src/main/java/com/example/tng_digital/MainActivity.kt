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
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.tng_digital.ui.theme.TngdigitalTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {

    private val bluetoothManager by lazy { getSystemService(BluetoothManager::class.java) }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager?.adapter }
    
    private var bluetoothService: BluetoothService? = null

    private val discoveredDevices = mutableStateListOf<BluetoothDevice>()

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
                        bluetoothService?.connectToDevice(device)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Toast.makeText(context, "Discovery Finished", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        val scannedContent = result.contents
        android.util.Log.d("MainActivity", "Scan result received: $scannedContent")
        
        if (scannedContent == null) {
            Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        Toast.makeText(this, "Scanned: $scannedContent", Toast.LENGTH_LONG).show()

        try {
            if (BluetoothAdapter.checkBluetoothAddress(scannedContent)) {
                android.util.Log.d("MainActivity", "Valid Bluetooth address found: $scannedContent")
                val adapter = bluetoothAdapter
                if (adapter == null) {
                    android.util.Log.e("MainActivity", "BluetoothAdapter is null")
                    Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
                
                val device = adapter.getRemoteDevice(scannedContent)
                if (device != null) {
                    android.util.Log.d("MainActivity", "Connecting to device: ${device.address}")
                    bluetoothService?.connectToDevice(device)
                } else {
                    android.util.Log.e("MainActivity", "getRemoteDevice returned null")
                    Toast.makeText(this, "Device not found", Toast.LENGTH_SHORT).show()
                }
            } else {
                android.util.Log.e("MainActivity", "Invalid Bluetooth address: $scannedContent")
                Toast.makeText(this, "Invalid device address in QR: $scannedContent", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error processing scan result", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
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
        permissions.add(Manifest.permission.CAMERA)
        
        if (permissions.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    @Composable
    fun BluetoothApp(adapter: BluetoothAdapter?) {
        if (adapter == null) {
            Text("Bluetooth not supported on this device")
            return
        }

        var status by remember { mutableStateOf("Disconnected") }
        var messages by remember { mutableStateOf(listOf<String>()) }
        var role by remember { mutableStateOf<String?>(null) } // "Sender" or "Receiver"
        var showQRCode by remember { mutableStateOf(false) }
        var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
        var isScanning by remember { mutableStateOf(false) }

        val service = remember {
            BluetoothService(
                adapter = adapter,
                onMessageReceived = { msg -> messages = messages + "Received: $msg" },
                onStatusChanged = { newStatus -> status = newStatus }
            ).also { bluetoothService = it }
        }

        if (showQRCode && qrBitmap != null) {
            AlertDialog(
                onDismissRequest = { showQRCode = false },
                title = { Text("Scanner to Connect") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "Connection QR",
                            modifier = Modifier.size(250.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Ask the Sender to scan this QR code", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    Button(onClick = { showQRCode = false }) {
                        Text("Close")
                    }
                }
            )
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Status: $status", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            if (role == null) {
                RoleSelection(
                    onRoleSelected = { selectedRole ->
                        role = selectedRole
                        if (selectedRole == "Receiver") {
                            service.startServer()
                        }
                    },
                    onScanQR = {
                        val options = ScanOptions()
                        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        options.setPrompt("Scan Receiver QR Code")
                        options.setCameraId(0)
                        options.setBeepEnabled(false)
                        barcodeLauncher.launch(options)
                    },
                    onShowQR = {
                        val address = if (ActivityCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                        ) {
                            adapter.bondedDevices.firstOrNull()?.address ?: "00:00:00:00:00:00"
                        } else {
                            "00:00:00:00:00:00"
                        }
                        android.util.Log.d("MainActivity", "Generated QR content: $address")
                        Toast.makeText(this@MainActivity, "QR Content: $address", Toast.LENGTH_LONG).show()
                        qrBitmap = QRCodeUtils.generateQRCode(address)
                        showQRCode = true
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
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Nearby Devices:", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(modifier = Modifier.height(200.dp)) {
                        items(discoveredDevices) { device ->
                            @SuppressLint("MissingPermission")
                            val deviceName = device.name ?: "Unknown Device"
                            Text(
                                text = "$deviceName (${device.address})",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { 
                                        Log.d("MainActivity", "Device selected: $deviceName (${device.address})")
                                        adapter.cancelDiscovery()
                                        isScanning = false
                                        role = "Sender"
                                        
                                        if (device.bondState == BluetoothDevice.BOND_NONE) {
                                            Log.d("MainActivity", "Device not paired. Creating bond...")
                                            device.createBond()
                                        } else {
                                            Log.d("MainActivity", "Device already paired. Connecting...")
                                            service.connectToDevice(device) 
                                        }
                                    }
                                    .padding(8.dp)
                            )
                        }
                    }
                }
            } else {
                Text(text = "Role: $role", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(16.dp))

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
                
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { 
                    service.stop()
                    role = null 
                }) {
                    Text("Reset Role")
                }
            }
        }
    }

    @Composable
    fun RoleSelection(
        onRoleSelected: (String) -> Unit, 
        onScanQR: () -> Unit, 
        onShowQR: () -> Unit,
        onDiscoverable: () -> Unit,
        onStartDiscovery: () -> Unit
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("Select your role:")
            Button(onClick = { onRoleSelected("Sender") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)) { Text("Sender") }
            Button(onClick = { onRoleSelected("Receiver") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)) { Text("Receiver") }
            
            Spacer(modifier = Modifier.height(16.dp))
            Text("Connection Tools:")
            
            Button(onClick = onDiscoverable, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)) { 
                Text("Allow Discovery (Receiver)") 
            }
            Button(onClick = onStartDiscovery, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)) { 
                Text("Search for Devices (Sender)") 
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(onClick = onShowQR, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)) { 
                Text("Show QR Code") 
            }
            OutlinedButton(onClick = onScanQR, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)) { 
                Text("Scan QR to Connect") 
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun DeviceList(adapter: BluetoothAdapter, onDeviceSelected: (BluetoothDevice) -> Unit) {
        val pairedDevices = adapter.bondedDevices.toList()
        
        Text("Select a paired device to connect:")
        LazyColumn {
            items(pairedDevices) { device ->
                Text(
                    text = "${device.name ?: "Unknown"} (${device.address})",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDeviceSelected(device) }
                        .padding(8.dp)
                )
            }
        }
    }

    @Composable
    fun MessagingUI(messages: List<String>, onSendMessage: (String) -> Unit) {
        var text by remember { mutableStateOf("") }

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") }
                )
                Button(onClick = {
                    if (text.isNotBlank()) {
                        onSendMessage(text)
                        text = ""
                    }
                }) {
                    Text("Send")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Messages:")
            LazyColumn(modifier = Modifier.fillMaxHeight(0.7f)) {
                items(messages) { msg ->
                    Text(msg, modifier = Modifier.padding(4.dp))
                }
            }
        }
    }
}
