package com.example.tng_digital

enum class MessageType(val code: String) {
    HANDSHAKE("HANDSHAKE"),
    HANDSHAKE_ACK("HANDSHAKE_ACK"),
    TX_REQUEST("TX_REQUEST"),
    TX_ACK("TX_ACK"),
    TX_CONFIRM("TX_CONFIRM"),
    TX_RECEIPT("TX_RECEIPT"),
    TX_ERROR("TX_ERROR"),
    TX_TIMEOUT("TX_TIMEOUT")
}

enum class TxStatus {
    PENDING, COMPLETED, INTERRUPTED, EXPIRED, FAILED, FLAGGED
}

data class ConsumerCertificate(
    val consumerId: String,
    val deviceId: String,
    val publicKey: String,
    val expiry: String,
    val maxOfflineSpendLimit: Double,
    val caSignature: String
)

data class VendorCertificate(
    val vendorId: String,
    val deviceId: String,
    val publicKey: String,
    val merchantName: String,
    val expiry: String,
    val caSignature: String
)

data class QrPayload(
    val version: Int,
    val vendorId: String,
    val merchantName: String,
    val serviceUuid: String,
    val timestamp: Long,
    val nonce: String,
    val vendorCertFingerprint: String,
    val signature: String
)

data class DeductionProof(
    val previousBalanceHash: String,
    val newBalanceHash: String,
    val counterBefore: Int,
    val counterAfter: Int
)

data class TxRequest(
    val txId: String,
    val consumerId: String,
    val amount: Double,
    val currency: String,
    val timestamp: Long,
    val spendingCounter: Int,
    val consumerCertificate: ConsumerCertificate,
    val signature: String
)

data class TxAck(
    val txId: String,
    val vendorId: String,
    val merchantName: String,
    val amount: Double,
    val currency: String,
    val timestamp: Long,
    val vendorCertificate: VendorCertificate,
    val signature: String
)

data class TxConfirm(
    val txId: String,
    val consumerId: String,
    val vendorId: String,
    val amount: Double,
    val currency: String,
    val newSpendingCounter: Int,
    val timestamp: Long,
    val deductionProof: DeductionProof,
    val signature: String
)

data class TxReceipt(
    val txId: String,
    val consumerId: String,
    val vendorId: String,
    val merchantName: String,
    val amount: Double,
    val currency: String,
    val completedAt: String,
    val vendorSignature: String,
    val consumerSignatureRef: String
)

data class LocalTransaction(
    val txId: String,
    val consumerId: String,
    val vendorId: String,
    val amount: Double,
    val currency: String,
    var status: TxStatus,
    val createdAt: Long,
    var completedAt: Long? = null,
    val consumerSignature: String = "",
    var vendorSignature: String = ""
)
