package com.example.tng_digital

import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.SecretKey

enum class TransactionState {
    IDLE,
    HANDSHAKE_PENDING,
    CHANNEL_READY,
    REQUEST_SENT,
    ACK_RECEIVED,
    CONFIRM_SENT,
    COMPLETED,
    FAILED
}

enum class AppRole { CONSUMER, VENDOR }

class TransactionManager(
    private val role: AppRole,
    private val onStateChanged: (TransactionState) -> Unit,
    private val onError: (String) -> Unit,
    private val onAckReceived: (TxAck) -> Unit,
    private val onTransactionComplete: (TxReceipt) -> Unit,
    private val onVendorRequestReceived: (TxRequest) -> Unit = {}
) {
    private val tag = "TransactionManager"
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)

    var sendRaw: ((String) -> Unit)? = null

    private var state = TransactionState.IDLE
    private val seenTxIds = mutableSetOf<String>()
    private val seenNonces = mutableSetOf<String>()

    private val signingKeyPair = CryptoService.getOrCreateKeyPair()
    val signingPublicKeyB64 = CryptoService.publicKeyToBase64(signingKeyPair.public)

    private val ephemeralKeyPair = CryptoService.generateEphemeralKeyPair()
    private val ephemeralPublicKeyB64 = CryptoService.publicKeyToBase64(ephemeralKeyPair.public)
    private var sessionKey: SecretKey? = null

    private var pendingRequest: TxRequest? = null
    private var pendingAck: TxAck? = null

    val deviceId = "DEV-${android.os.Build.MODEL.replace(" ", "-")}"
    val merchantName = "TNG Demo Vendor"
    val serviceUuid = "8ce255c0-200a-11e0-ac64-0800200c9a66"

    val consumerId = "CSM-${System.currentTimeMillis()}"
    val vendorId = "VND-${System.currentTimeMillis()}"

    var localBalance: Double = 1000.00
    var spendingCounter: Int = 47
    val maxOfflineSpendLimit: Double = 500.00

    private val certExpiry: String
        get() = isoFmt.format(Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000))

    // ─── Entry point ────────────────────────────────────────────────────────────

    fun onConnected() {
        sendHandshake()
    }

    fun handleReceivedMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            when (json.optString("msg_type")) {
                MessageType.HANDSHAKE.code     -> handleHandshake(json)
                MessageType.HANDSHAKE_ACK.code -> handleHandshakeAck(json)
                MessageType.TX_REQUEST.code    -> handleTxRequest(json)
                MessageType.TX_ACK.code        -> handleTxAck(json)
                MessageType.TX_CONFIRM.code    -> handleTxConfirm(json)
                MessageType.TX_RECEIPT.code    -> handleTxReceipt(json)
                MessageType.TX_ERROR.code      -> {
                    val code = json.optString("error_code", "UNKNOWN")
                    val msg = json.optString("message", "Remote error")
                    Log.w(tag, "TX_ERROR received: $code – $msg")
                    onError("$code: $msg")
                    setState(TransactionState.FAILED)
                }
                else -> Log.w(tag, "Unknown msg_type in: $raw")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to handle message", e)
        }
    }

    // ─── Step 1 – ECDH Handshake ────────────────────────────────────────────────

    private fun sendHandshake() {
        val msg = JSONObject().apply {
            put("msg_type", MessageType.HANDSHAKE.code)
            put("ecdh_pub_key", ephemeralPublicKeyB64)
        }
        sendRaw?.invoke(msg.toString())
        setState(TransactionState.HANDSHAKE_PENDING)
    }

    private fun handleHandshake(json: JSONObject) {
        val theirEcdhKey = json.getString("ecdh_pub_key")
        sessionKey = CryptoService.deriveSessionKey(ephemeralKeyPair.private, theirEcdhKey)
        Log.d(tag, "Session key derived (responder)")
        val ack = JSONObject().apply {
            put("msg_type", MessageType.HANDSHAKE_ACK.code)
            put("ecdh_pub_key", ephemeralPublicKeyB64)
        }
        sendRaw?.invoke(ack.toString())
        setState(TransactionState.CHANNEL_READY)
    }

    private fun handleHandshakeAck(json: JSONObject) {
        val theirEcdhKey = json.getString("ecdh_pub_key")
        sessionKey = CryptoService.deriveSessionKey(ephemeralKeyPair.private, theirEcdhKey)
        Log.d(tag, "Session key derived (initiator)")
        setState(TransactionState.CHANNEL_READY)
    }

    // ─── Encrypt / Decrypt helpers ──────────────────────────────────────────────

    private fun sendEncrypted(json: JSONObject, code: MessageType) {
        val key = sessionKey
        val payload = if (key != null) {
            JSONObject().apply {
                put("msg_type", code)
                put("encrypted", CryptoService.encrypt(json.toString(), key))
            }.toString()
        } else {
            json.toString()
        }
        sendRaw?.invoke(payload)
    }

    private fun decryptIfNeeded(raw: String): JSONObject {
        val outer = JSONObject(raw)
        if (outer.has("encrypted")) {
            val key = sessionKey ?: return outer
            val plaintext = CryptoService.decrypt(outer.getString("encrypted"), key)
            return JSONObject(plaintext)
        }
        return outer
    }

    // ─── Step 2 – Consumer sends TX_REQUEST ─────────────────────────────────────

    fun sendTxRequest(amount: Double, currency: String = "MYR") {
        if (localBalance < amount) { sendError("", "INSUFFICIENT_FUNDS", "Balance insufficient"); return }
        if (amount > maxOfflineSpendLimit) { sendError("", "LIMIT_EXCEEDED", "Exceeds offline limit"); return }

        val txId = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis() / 1000
        val counter = spendingCounter + 1
        val sigData = "$txId|$consumerId|$amount|$currency|$ts|$counter"
        val sig = CryptoService.sign(sigData, signingKeyPair.private)

        val certJson = JSONObject().apply {
            put("consumer_id", consumerId)
            put("device_id", deviceId)
            put("public_key", signingPublicKeyB64)
            put("expiry", certExpiry)
            put("max_offline_spend_limit", maxOfflineSpendLimit)
            put("ca_signature", "SIMULATED_CA_SIG")
        }
        val msg = JSONObject().apply {
            put("msg_type", MessageType.TX_REQUEST.code)
            put("tx_id", txId)
            put("consumer_id", consumerId)
            put("amount", amount)
            put("currency", currency)
            put("timestamp", ts)
            put("spending_counter", counter)
            put("consumer_certificate", certJson)
            put("signature", sig)
        }
        pendingRequest = TxRequest(
            txId = txId, consumerId = consumerId, amount = amount, currency = currency,
            timestamp = ts, spendingCounter = counter,
            consumerCertificate = ConsumerCertificate(consumerId, deviceId, signingPublicKeyB64, certExpiry, maxOfflineSpendLimit, "SIMULATED_CA_SIG"),
            signature = sig
        )
        sendEncrypted(msg, MessageType.TX_REQUEST)
        setState(TransactionState.REQUEST_SENT)
    }

    // ─── Step 3 – Vendor receives TX_REQUEST, Step 4 – sends TX_ACK ─────────────

    private fun handleTxRequest(json: JSONObject) {
        val inner = try { decryptIfNeeded(json.toString()) } catch (e: Exception) { json }
        val txId = inner.getString("tx_id")
        val consumerId = inner.getString("consumer_id")
        val amount = inner.getDouble("amount")
        val currency = inner.getString("currency")
        val ts = inner.getLong("timestamp")
        val counter = inner.getInt("spending_counter")
        val certObj = inner.getJSONObject("consumer_certificate")
        val consumerPubKey = certObj.getString("public_key")
        val sig = inner.getString("signature")

        if (txId in seenTxIds) { sendError(txId, "SIG_INVALID", "Duplicate tx_id"); return }
        if (amount <= 0) { sendError(txId, "SIG_INVALID", "Invalid amount"); return }

        val sigData = "$txId|$consumerId|$amount|$currency|$ts|$counter"
        val valid = CryptoService.verify(sigData, sig, consumerPubKey)
        Log.d(tag, "TX_REQUEST signature valid=$valid")

        seenTxIds.add(txId)
        val req = TxRequest(
            txId = txId, consumerId = consumerId, amount = amount, currency = currency,
            timestamp = ts, spendingCounter = counter,
            consumerCertificate = ConsumerCertificate(consumerId, certObj.optString("device_id"), consumerPubKey, certObj.optString("expiry"), certObj.optDouble("max_offline_spend_limit", 0.0), ""),
            signature = sig
        )
        pendingRequest = req
        onVendorRequestReceived(req)

        val ackTs = System.currentTimeMillis() / 1000
        val certJson = JSONObject().apply {
            put("vendor_id", vendorId)
            put("device_id", deviceId)
            put("public_key", signingPublicKeyB64)
            put("merchant_name", merchantName)
            put("expiry", certExpiry)
            put("ca_signature", "SIMULATED_CA_SIG")
        }
        val ackSigData = "$txId|$vendorId|$amount|$currency|VENDOR_READY|$ackTs"
        val ackSig = CryptoService.sign(ackSigData, signingKeyPair.private)
        val ackMsg = JSONObject().apply {
            put("msg_type", MessageType.TX_ACK.code)
            put("tx_id", txId)
            put("vendor_id", vendorId)
            put("merchant_name", merchantName)
            put("amount", amount)
            put("currency", currency)
            put("status", "VENDOR_READY")
            put("timestamp", ackTs)
            put("vendor_certificate", certJson)
            put("signature", ackSig)
        }
        sendEncrypted(ackMsg, MessageType.TX_ACK)
        setState(TransactionState.ACK_RECEIVED)
    }

    // ─── Step 4 – Consumer receives TX_ACK ──────────────────────────────────────

    private fun handleTxAck(json: JSONObject) {
        val inner = try { decryptIfNeeded(json.toString()) } catch (e: Exception) { json }
        val txId = inner.getString("tx_id")
        val vendorId = inner.getString("vendor_id")
        val merchantName = inner.getString("merchant_name")
        val amount = inner.getDouble("amount")
        val currency = inner.getString("currency")
        val ts = inner.getLong("timestamp")
        val certObj = inner.getJSONObject("vendor_certificate")
        val vendorPubKey = certObj.getString("public_key")
        val sig = inner.getString("signature")

        val req = pendingRequest ?: run { onError("No pending TX"); return }
        if (txId != req.txId) { sendError(txId, "SIG_INVALID", "TX_ID mismatch"); return }
        if (amount != req.amount) { sendError(txId, "SIG_INVALID", "Amount mismatch in ACK"); return }

        val sigData = "$txId|$vendorId|$amount|$currency|VENDOR_READY|$ts"
        val valid = CryptoService.verify(sigData, sig, vendorPubKey)
        Log.d(tag, "TX_ACK signature valid=$valid")

        val ack = TxAck(
            txId = txId, vendorId = vendorId, merchantName = merchantName,
            amount = amount, currency = currency, timestamp = ts,
            vendorCertificate = VendorCertificate(vendorId, certObj.optString("device_id"), vendorPubKey, merchantName, certObj.optString("expiry"), ""),
            signature = sig
        )
        pendingAck = ack
        setState(TransactionState.ACK_RECEIVED)
        onAckReceived(ack)
    }

    // ─── Step 5 – Consumer sends TX_CONFIRM (called after biometric succeeds) ───

    fun sendTxConfirm() {
        val req = pendingRequest ?: return
        val ack = pendingAck ?: return

        val newCounter = req.spendingCounter + 1
        val prevBalHash = CryptoService.sha256Hash("$localBalance")
        val newBalance = localBalance - req.amount
        val newBalHash = CryptoService.sha256Hash("$newBalance")
        val ts = System.currentTimeMillis() / 1000

        val sigData = "${req.txId}|$consumerId|${ack.vendorId}|${req.amount}|${req.currency}|$newCounter|$ts|$prevBalHash|$newBalHash"
        val sig = CryptoService.sign(sigData, signingKeyPair.private)

        val proofJson = JSONObject().apply {
            put("previous_balance_hash", prevBalHash)
            put("new_balance_hash", newBalHash)
            put("counter_before", req.spendingCounter)
            put("counter_after", newCounter)
        }
        val msg = JSONObject().apply {
            put("msg_type", MessageType.TX_CONFIRM.code)
            put("tx_id", req.txId)
            put("consumer_id", consumerId)
            put("vendor_id", ack.vendorId)
            put("amount", req.amount)
            put("currency", req.currency)
            put("new_spending_counter", newCounter)
            put("timestamp", ts)
            put("deduction_proof", proofJson)
            put("signature", sig)
        }

        localBalance = newBalance
        spendingCounter = newCounter

        sendEncrypted(msg, MessageType.TX_CONFIRM)
        setState(TransactionState.CONFIRM_SENT)
    }

    // ─── Step 6 – Vendor receives TX_CONFIRM, sends TX_RECEIPT ──────────────────

    private fun handleTxConfirm(json: JSONObject) {
        val inner = try { decryptIfNeeded(json.toString()) } catch (e: Exception) { json }
        val txId = inner.getString("tx_id")
        val amount = inner.getDouble("amount")
        val newCounter = inner.getInt("new_spending_counter")
        val ts = inner.getLong("timestamp")
        val consumerSig = inner.getString("signature")

        val req = pendingRequest ?: run { sendError(txId, "SIG_INVALID", "No pending TX"); return }
        if (txId != req.txId) { sendError(txId, "SIG_INVALID", "TX_ID mismatch"); return }
        if (amount != req.amount) { sendError(txId, "SIG_INVALID", "Amount mismatch in CONFIRM"); return }
        if (newCounter != req.spendingCounter + 1) { sendError(txId, "SIG_INVALID", "Invalid spending counter"); return }

        val completedAt = isoFmt.format(Date())
        val receiptSigData = "$txId|$vendorId|$amount|COMPLETED|$completedAt"
        val receiptSig = CryptoService.sign(receiptSigData, signingKeyPair.private)

        val receiptMsg = JSONObject().apply {
            put("msg_type", MessageType.TX_RECEIPT.code)
            put("tx_id", txId)
            put("consumer_id", req.consumerId)
            put("vendor_id", vendorId)
            put("merchant_name", merchantName)
            put("amount", amount)
            put("currency", req.currency)
            put("status", "COMPLETED")
            put("completed_at", completedAt)
            put("vendor_signature", receiptSig)
            put("consumer_signature_ref", consumerSig)
        }
        sendEncrypted(receiptMsg, MessageType.TX_RECEIPT)

        val receipt = TxReceipt(
            txId = txId, consumerId = req.consumerId, vendorId = vendorId,
            merchantName = merchantName, amount = amount, currency = req.currency,
            completedAt = completedAt, vendorSignature = receiptSig, consumerSignatureRef = consumerSig
        )
        // Queue for server sync (vendor side)
        queueForSync(receipt, "merchant")
        setState(TransactionState.COMPLETED)
        onTransactionComplete(receipt)
    }

    // ─── Consumer receives TX_RECEIPT ────────────────────────────────────────────

    private fun handleTxReceipt(json: JSONObject) {
        val inner = try { decryptIfNeeded(json.toString()) } catch (e: Exception) { json }
        val req = pendingRequest ?: return
        val ack = pendingAck ?: return
        val receipt = TxReceipt(
            txId = inner.getString("tx_id"),
            consumerId = inner.getString("consumer_id"),
            vendorId = inner.getString("vendor_id"),
            merchantName = inner.getString("merchant_name"),
            amount = inner.getDouble("amount"),
            currency = inner.getString("currency"),
            completedAt = inner.getString("completed_at"),
            vendorSignature = inner.getString("vendor_signature"),
            consumerSignatureRef = inner.getString("consumer_signature_ref")
        )
        // Queue for server sync (consumer side)
        queueForSync(receipt, "user")
        setState(TransactionState.COMPLETED)
        onTransactionComplete(receipt)
    }

    // ─── QR Payload helpers ──────────────────────────────────────────────────────

    fun generateVendorQrPayload(): String {
        val ts = System.currentTimeMillis() / 1000
        val nonce = UUID.randomUUID().toString()
        val fingerprint = CryptoService.sha256Hash(signingPublicKeyB64).take(16)
        val sigData = "$vendorId|$merchantName|$serviceUuid|$ts|$nonce"
        val sig = CryptoService.sign(sigData, signingKeyPair.private)
        return JSONObject().apply {
            put("version", 1)
            put("vendor_id", vendorId)
            put("merchant_name", merchantName)
            put("service_uuid", serviceUuid)
            put("timestamp", ts)
            put("nonce", nonce)
            put("vendor_cert_fingerprint", fingerprint)
            put("signature", sig)
        }.toString()
    }

    fun parseQrPayload(content: String): QrPayload? {
        return try {
            val j = JSONObject(content)
            QrPayload(
                version = j.getInt("version"),
                vendorId = j.getString("vendor_id"),
                merchantName = j.getString("merchant_name"),
                serviceUuid = j.getString("service_uuid"),
                timestamp = j.getLong("timestamp"),
                nonce = j.getString("nonce"),
                vendorCertFingerprint = j.getString("vendor_cert_fingerprint"),
                signature = j.getString("signature")
            )
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse QR payload", e)
            null
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private fun sendError(txId: String, code: String, message: String) {
        val msg = JSONObject().apply {
            put("msg_type", MessageType.TX_ERROR.code)
            put("tx_id", txId)
            put("error_code", code)
            put("message", message)
        }
        sendRaw?.invoke(msg.toString())
        setState(TransactionState.FAILED)
        onError("$code: $message")
    }

    private fun setState(newState: TransactionState) {
        state = newState
        onStateChanged(newState)
    }

    // ─── Sync Queue Integration ──────────────────────────────────────────────

    private fun queueForSync(receipt: TxReceipt, side: String) {
        val req = pendingRequest
        val certJson = req?.consumerCertificate?.let {
            JSONObject().apply {
                put("consumer_id", it.consumerId)
                put("device_id", it.deviceId)
                put("public_key", it.publicKey)
                put("expiry", it.expiry)
                put("max_offline_spend_limit", it.maxOfflineSpendLimit)
                put("ca_signature", it.caSignature)
            }.toString()
        } ?: ""
        val serverTx = ServerTransaction(
            id = receipt.txId,
            amount = receipt.amount,
            currency = receipt.currency,
            timestamp = System.currentTimeMillis(),
            fromUserId = receipt.consumerId,
            toMerchantId = receipt.vendorId,
            status = "completed",
            syncStatus = "pending_sync",
            signature = receipt.consumerSignatureRef,
            userPubKey = req?.consumerCertificate?.publicKey ?: signingPublicKeyB64,
            cert = certJson,
            ackSignature = receipt.vendorSignature,
            merchantPubKey = signingPublicKeyB64
        )
        SyncQueue.addToQueue(receipt.txId, side, serverTx)
    }

    fun reset() {
        pendingRequest = null
        pendingAck = null
        seenTxIds.clear()
        setState(TransactionState.IDLE)
    }

    fun currentState() = state
}
