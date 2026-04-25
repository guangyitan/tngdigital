package com.example.tng_digital

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

        val service = remember {
            BluetoothService(
                adapter = adapter,
                onMessageReceived = { msg -> messages = messages + "Received: $msg" },
                onStatusChanged = { newStatus -> status = newStatus }
            ).also { bluetoothService = it }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Status: $status", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            if (role == null) {
                RoleSelection { selectedRole ->
                    role = selectedRole
                    if (selectedRole == "Receiver") {
                        service.startServer()
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
    fun RoleSelection(onRoleSelected: (String) -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("Select your role:")
            Button(onClick = { onRoleSelected("Sender") }) { Text("Sender") }
            Button(onClick = { onRoleSelected("Receiver") }) { Text("Receiver") }
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
