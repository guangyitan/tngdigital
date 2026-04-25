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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.tng_digital.ui.theme.TngdigitalTheme

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
            Text("Bluetooth not supported on this device")
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
            ).also { bluetoothService = it }
        }

        // Handle navigation after successful pairing
        onPairingSuccess = {
            role = "Receiver"
            service.startServer()
            isConnecting = true
        }

        Column(modifier = Modifier.padding(16.dp)) {
            if (isConnecting) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Connecting...")
                    }
                }
            }

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
                                        
                                        if (device.bondState == BluetoothDevice.BOND_NONE) {
                                            Log.d("MainActivity", "Device not paired. Creating bond...")
                                            device.createBond()
                                            // Don't set role yet, wait for bond success via onPairingSuccess
                                        } else {
                                            Log.d("MainActivity", "Device already paired. Connecting...")
                                            role = "Receiver"
                                            isConnecting = true
                                            service.startServer()
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
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun DeviceList(adapter: BluetoothAdapter, onDeviceSelected: (BluetoothDevice) -> Unit) {
        // Using remember(adapter.bondedDevices) doesn't work as expected because bondedDevices is a Set,
        // but its reference might not change. However, when returning to this screen (role == null -> Sender),
        // DeviceList is recomposed and bondedDevices is re-read.
        val pairedDevices = adapter.bondedDevices.toList()
        
        if (pairedDevices.isEmpty()) {
            Text("No paired devices found. Please search and pair first.")
        } else {
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
