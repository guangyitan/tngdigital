package com.example.tng_digital

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothService(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val onMessageReceived: (String) -> Unit,
    private val onStatusChanged: (String) -> Unit
) {
    private val tag = "BluetoothService"
    private val serviceName = "TngDigitalBluetooth"
    private val serviceUuid: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    @SuppressLint("MissingPermission")
    inner class AcceptThread : Thread() {
        private val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            adapter?.listenUsingRfcommWithServiceRecord(serviceName, serviceUuid)
        }

        override fun run() {
            var shouldLoop = true
            while (shouldLoop) {
                Log.d(tag, "Listening for connections...")
                val socket: BluetoothSocket? = try {
                    mmServerSocket?.accept()
                } catch (e: IOException) {
                    Log.e(tag, "Socket's accept() method failed", e)
                    shouldLoop = false
                    null
                }
                socket?.also {
                    Log.d(tag, "Connection accepted from ${it.remoteDevice.address}")
                    manageConnectedSocket(it)
                    mmServerSocket?.close()
                    shouldLoop = false
                }
            }
        }

        fun cancel() {
            try {
                mmServerSocket?.close()
            } catch (e: IOException) {
                Log.e(tag, "Could not close the connect socket", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(serviceUuid)
        }

        override fun run() {
            Log.d(tag, "Attempting to connect to ${device.address}")
            // Discovery is resource intensive and slows down connection, always cancel it
            adapter?.cancelDiscovery()

            mmSocket?.let { socket ->
                try {
                    socket.connect()
                    Log.d(tag, "Successfully connected to ${device.address}")
                    manageConnectedSocket(socket)
                } catch (e: IOException) {
                    Log.e(tag, "Could not connect to socket", e)
                    
                    // Try fallback for some devices
                    try {
                        Log.d(tag, "Trying fallback connection...")
                        val fallbackSocket = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                            .invoke(device, 1) as BluetoothSocket
                        fallbackSocket.connect()
                        Log.d(tag, "Fallback connection successful")
                        manageConnectedSocket(fallbackSocket)
                    } catch (e2: Exception) {
                        Log.e(tag, "Fallback connection also failed", e2)
                        onStatusChanged("Connection failed")
                        try {
                            socket.close()
                        } catch (closeException: IOException) {
                            Log.e(tag, "Could not close the client socket", closeException)
                        }
                    }
                }
            }
        }

        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: IOException) {
                Log.e(tag, "Could not close the client socket", e)
            }
        }
    }

    inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val mmBuffer: ByteArray = ByteArray(1024)

        override fun run() {
            onStatusChanged("Connected")
            var numBytes: Int

            while (true) {
                numBytes = try {
                    mmInStream.read(mmBuffer)
                } catch (e: IOException) {
                    Log.d(tag, "Input stream was disconnected", e)
                    onStatusChanged("Disconnected")
                    break
                }

                val message = String(mmBuffer, 0, numBytes)
                onMessageReceived(message)
            }
        }

        fun write(bytes: ByteArray) {
            try {
                mmOutStream.write(bytes)
            } catch (e: IOException) {
                Log.e(tag, "Error occurred when sending data", e)
            }
        }

        fun cancel() {
            try {
                mmSocket.close()
            } catch (e: IOException) {
                Log.e(tag, "Could not close the connect socket", e)
            }
        }
    }

    @Synchronized
    fun startServer() {
        stop()
        onStatusChanged("Listening for connections...")
        acceptThread = AcceptThread().apply { start() }
    }

    @Synchronized
    fun connectToDevice(device: BluetoothDevice) {
        stop()
        val deviceName = try {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            ) {
                device.name ?: device.address
            } else {
                device.address
            }
        } catch (e: SecurityException) {
            device.address
        }
        onStatusChanged("Connecting to $deviceName...")
        connectThread = ConnectThread(device).apply { start() }
    }

    @Synchronized
    private fun manageConnectedSocket(socket: BluetoothSocket) {
        connectedThread = ConnectedThread(socket).apply { start() }
    }

    fun sendMessage(message: String) {
        connectedThread?.write(message.toByteArray())
    }

    @Synchronized
    fun stop() {
        acceptThread?.cancel()
        acceptThread = null
        connectThread?.cancel()
        connectThread = null
        connectedThread?.cancel()
        connectedThread = null
        onStatusChanged("Bluetooth service stopped")
    }
}
