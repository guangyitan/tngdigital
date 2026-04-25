package com.example.tng_digital

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothService(
    private val adapter: BluetoothAdapter?,
    onMessageReceived: (String) -> Unit = {},
    onStatusChanged: (String) -> Unit = {}
) {
    var onMessageReceived: (String) -> Unit = onMessageReceived
    var onStatusChanged: (String) -> Unit = onStatusChanged
    private val tag = "BluetoothService"
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun postMain(action: () -> Unit) = mainHandler.post(action)
    private val serviceName = "TngDigitalBluetooth"
    private val serviceUuid: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    @SuppressLint("MissingPermission")
    inner class AcceptThread : Thread() {
        private val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            adapter?.listenUsingInsecureRfcommWithServiceRecord(serviceName, serviceUuid)
        }

        override fun run() {
            var shouldLoop = true
            while (shouldLoop) {
                val socket: BluetoothSocket? = try {
                    mmServerSocket?.accept()
                } catch (e: IOException) {
                    Log.e(tag, "Socket's accept() method failed", e)
                    shouldLoop = false
                    null
                }
                socket?.also {
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
    inner class ConnectThread(device: BluetoothDevice) : Thread() {
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(serviceUuid)
        }

        override fun run() {
            adapter?.cancelDiscovery()

            mmSocket?.let { socket ->
                try {
                    socket.connect()
                    manageConnectedSocket(socket)
                } catch (e: IOException) {
                    Log.e(tag, "Could not connect to socket", e)
                    postMain { onStatusChanged("Connection failed") }
                    try {
                        socket.close()
                    } catch (closeException: IOException) {
                        Log.e(tag, "Could not close the client socket", closeException)
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
        private val readBuffer = ByteArray(4096)

        override fun run() {
            postMain { onStatusChanged("Connected") }
            val accumulator = StringBuilder()
            while (true) {
                val numBytes = try {
                    mmInStream.read(readBuffer)
                } catch (e: IOException) {
                    Log.d(tag, "Input stream was disconnected", e)
                    postMain { onStatusChanged("Disconnected") }
                    break
                }
                accumulator.append(String(readBuffer, 0, numBytes, Charsets.UTF_8))
                var newlineIdx: Int
                while (accumulator.indexOf("\n").also { newlineIdx = it } != -1) {
                    val message = accumulator.substring(0, newlineIdx).trim()
                    accumulator.delete(0, newlineIdx + 1)
                    if (message.isNotEmpty()) postMain { onMessageReceived(message) }
                }
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
        postMain { onStatusChanged("Listening for connections...") }
        acceptThread = AcceptThread().apply { start() }
    }

    @Synchronized
    fun connectToDevice(device: BluetoothDevice) {
        stop()
        val deviceName = try {
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }
        postMain { onStatusChanged("Connecting to $deviceName...") }
        connectThread = ConnectThread(device).apply { start() }
    }

    @Synchronized
    private fun manageConnectedSocket(socket: BluetoothSocket) {
        connectedThread = ConnectedThread(socket).apply { start() }
    }

    fun sendMessage(message: String) {
        connectedThread?.write("$message\n".toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    fun stop() {
        acceptThread?.cancel()
        acceptThread = null
        connectThread?.cancel()
        connectThread = null
        connectedThread?.cancel()
        connectedThread = null
        postMain { onStatusChanged("Bluetooth service stopped") }
    }
}
