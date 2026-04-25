package com.example.tng_digital

import android.util.Log

/**
 * In-memory sync queue for pending offline transactions.
 * Mirrors expo_mobile's addToSyncQueue / markSynced / getSyncQueue pattern.
 */
object SyncQueue {

    private const val TAG = "SyncQueue"
    private val queue = mutableListOf<SyncQueueItem>()

    fun addToQueue(txId: String, side: String, tx: ServerTransaction) {
        queue.add(SyncQueueItem(
            txId = txId,
            side = side,
            tx = tx,
            queuedAt = System.currentTimeMillis()
        ))
        Log.d(TAG, "Added to sync queue: $txId (side=$side). Queue size: ${queue.size}")
    }

    fun getQueue(side: String): List<SyncQueueItem> {
        return queue.filter { it.side == side }
    }

    fun getAllQueue(): List<SyncQueueItem> {
        return queue.toList()
    }

    fun markSynced(txIds: List<String>) {
        queue.removeAll { it.txId in txIds }
        Log.d(TAG, "Marked ${txIds.size} as synced. Queue size: ${queue.size}")
    }

    fun pendingCount(side: String): Int {
        return queue.count { it.side == side }
    }

    fun totalPendingCount(): Int {
        return queue.size
    }

    fun clear() {
        queue.clear()
    }
}
