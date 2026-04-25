package com.example.tng_digital

import android.util.Log

/**
 * In-memory sync queue for offline transactions.
 * Keeps all transactions (even after sync) so history screen can display them.
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
        val idSet = txIds.toSet()
        for (i in queue.indices) {
            if (queue[i].txId in idSet) {
                queue[i] = queue[i].copy(tx = queue[i].tx.copy(syncStatus = "synced"))
            }
        }
        Log.d(TAG, "Marked ${txIds.size} as synced. Queue size: ${queue.size}")
    }

    fun pendingCount(side: String): Int {
        return queue.count { it.side == side && it.tx.syncStatus != "synced" }
    }

    fun totalPendingCount(): Int {
        return queue.count { it.tx.syncStatus != "synced" }
    }

    fun clear() {
        queue.clear()
    }
}
