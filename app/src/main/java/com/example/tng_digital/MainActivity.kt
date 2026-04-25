package com.example.tng_digital

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.tng_digital.ui.theme.TngdigitalTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {

    private val bluetoothManager by lazy { getSystemService(BluetoothManager::class.java) }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager?.adapter }
    
    private var bluetoothService: BluetoothService? = null

    // State for QR and Transfer - using delegates for compose
    private var scannedUserName by mutableStateOf<String?>(null)
    private var scannedDeviceAddress by mutableStateOf<String?>(null)

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            Toast.makeText(this, "Cancelled", Toast.LENGTH_LONG).show()
        } else {
            // Expected format: "name:UserName|address:00:11:22:33:44:55"
            val content = result.contents
            try {
                val parts = content.split("|")
                val namePart = parts.find { it.startsWith("name:") }?.substringAfter("name:")
                val addressPart = parts.find { it.startsWith("address:") }?.substringAfter("address:")
                
                if (namePart != null && addressPart != null) {
                    scannedUserName = namePart
                    scannedDeviceAddress = addressPart
                    
                    // Automatically try to connect to the device via Bluetooth
                    val device = bluetoothAdapter?.getRemoteDevice(addressPart)
                    if (device != null) {
                        bluetoothService?.connectToDevice(device)
                    }
                } else {
                    Toast.makeText(this, "Invalid QR Format", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error parsing QR: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            // All permissions granted
        } else {
            Toast.makeText(this, "Permissions required for Bluetooth and Camera", Toast.LENGTH_SHORT).show()
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
        var receivedMessages by remember { mutableStateOf(listOf<String>()) }
        var showQRCodeDialog by remember { mutableStateOf(false) }
        var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

        val service = remember {
            BluetoothService(
                context = this@MainActivity,
                adapter = adapter,
                onMessageReceived = { msg -> receivedMessages = receivedMessages + "Received: RM $msg" },
                onStatusChanged = { newStatus -> status = newStatus }
            ).also { 
                bluetoothService = it
                it.startServer() // Receiver is always ready
            }
        }

        if (showQRCodeDialog && qrBitmap != null) {
            AlertDialog(
                onDismissRequest = { showQRCodeDialog = false },
                title = { Text("Your Payment QR") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier.size(250.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("User: ${Build.MODEL}", style = MaterialTheme.typography.bodyLarge)
                    }
                },
                confirmButton = {
                    Button(onClick = { showQRCodeDialog = false }) { Text("Close") }
                }
            )
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Status: $status", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))

            if (scannedUserName == null) {
                // Home Screen
                HomeScreen(
                    onShowQR = {
                        val userName = Build.MODEL
                        // Note: In a real app, getting your own MAC is restricted. 
                        // For demo, we use a placeholder or assume devices are already paired.
                        // Ideally, you'd use a fixed UUID or BLE advertising.
                        // Here we try to get a bonded device address as a hack for the target.
                        val address = if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                             adapter.bondedDevices.firstOrNull()?.address ?: "00:00:00:00:00:00"
                        } else {
                            "00:00:00:00:00:00"
                        }
                        
                        val content = "name:$userName|address:$address"
                        qrBitmap = QRCodeUtils.generateQRCode(content)
                        showQRCodeDialog = true
                    },
                    onScanQR = {
                        val options = ScanOptions()
                        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        options.setPrompt("Scan to Transfer")
                        options.setBeepEnabled(false)
                        barcodeLauncher.launch(options)
                    }
                )
                
                if (receivedMessages.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("Notifications:", style = MaterialTheme.typography.titleSmall)
                    LazyColumn {
                        items(receivedMessages) { msg ->
                            Text(msg, modifier = Modifier.padding(4.dp))
                        }
                    }
                }
            } else {
                // Transfer Screen
                TransferUI(
                    userName = scannedUserName!!,
                    status = status,
                    onTransfer = { amount ->
                        service.sendMessage(amount)
                        Toast.makeText(this@MainActivity, "Transferring RM $amount...", Toast.LENGTH_SHORT).show()
                    },
                    onCancel = {
                        scannedUserName = null
                        scannedDeviceAddress = null
                        service.stop()
                        service.startServer()
                    }
                )
            }
        }
    }

    @Composable
    fun HomeScreen(onShowQR: () -> Unit, onScanQR: () -> Unit) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = onShowQR,
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) {
                Text("Show QR")
            }
            Button(
                onClick = onScanQR,
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) {
                Text("Scan QR")
            }
        }
    }

    @Composable
    fun TransferUI(userName: String, status: String, onTransfer: (String) -> Unit, onCancel: () -> Unit) {
        var amount by remember { mutableStateOf("") }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text("Transfer to:", style = MaterialTheme.typography.titleSmall)
            Text(userName, style = MaterialTheme.typography.headlineMedium)
            
            Spacer(modifier = Modifier.height(24.dp))
            
            OutlinedTextField(
                value = amount,
                onValueChange = { newValue -> 
                    if (newValue.all { char -> char.isDigit() || char == '.' }) {
                        amount = newValue
                    }
                },
                label = { Text("Amount (RM)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = { if (amount.isNotBlank()) onTransfer(amount) },
                enabled = status == "Connected" && amount.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Transfer")
            }
            
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
            
            if (status != "Connected") {
                Text("Connecting to device...", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
