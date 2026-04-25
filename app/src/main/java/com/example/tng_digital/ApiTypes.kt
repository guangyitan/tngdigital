package com.example.tng_digital

// ─── Session Init ─────────────────────────────────────────────────────────────

data class SessionInitRequest(
    val deviceId: String,
    val role: String  // "user" | "merchant"
)

data class SessionInitResponse(
    val userId: String,
    val displayName: String,
    val offlineBalance: Double,
    val status: String,  // "active"
    val merchantName: String? = null
)

// ─── Sync Push ────────────────────────────────────────────────────────────────

data class SyncQueueItem(
    val txId: String,
    val side: String,  // "user" | "merchant"
    val tx: ServerTransaction,
    val queuedAt: Long
)

data class SyncRequest(
    val deviceId: String,
    val transactions: List<SyncQueueItem>
)

data class SyncResponse(
    val syncedTxIds: List<String>,
    val failedTxIds: List<String>
)

// ─── Account Pull ─────────────────────────────────────────────────────────────

data class PullRequest(
    val deviceId: String,
    val role: String  // "user" | "merchant"
)

data class PullResponse(
    val offlineBalance: Double,
    val transactions: List<ServerTransaction>
)

// ─── Server Transaction ───────────────────────────────────────────────────────

data class ServerTransaction(
    val id: String,
    val amount: Double,
    val currency: String,
    val timestamp: Long,
    val fromUserId: String,
    val toMerchantId: String,
    val status: String,       // "pending" | "completed" | "failed"
    val syncStatus: String,   // "pending_sync" | "synced"
    val signature: String = "",
    val userPubKey: String = "",
    val cert: String = "",
    val ackSignature: String = "",
    val merchantPubKey: String = ""
)
