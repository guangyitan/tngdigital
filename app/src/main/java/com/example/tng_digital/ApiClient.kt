package com.example.tng_digital

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {

    private const val TAG = "ApiClient"
    private const val USE_MOCK = true
    private const val BACKEND_URL = "http://localhost:3000"

    // ─── Session Init ─────────────────────────────────────────────────────────

    suspend fun initSession(req: SessionInitRequest): SessionInitResponse {
        if (USE_MOCK) return mockInitSession(req)
        return withContext(Dispatchers.IO) {
            val url = URL("$BACKEND_URL/session/init")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            val body = JSONObject().apply {
                put("deviceId", req.deviceId)
                put("role", req.role)
            }
            conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
            if (conn.responseCode != 200) throw Exception("Session init failed: ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            SessionInitResponse(
                userId = json.getString("userId"),
                displayName = json.getString("displayName"),
                offlineBalance = json.getDouble("offlineBalance"),
                status = json.getString("status"),
                merchantName = json.optString("merchantName", null)
            )
        }
    }

    // ─── Push Transactions ────────────────────────────────────────────────────

    suspend fun pushTransactions(req: SyncRequest): SyncResponse {
        if (USE_MOCK) return mockPushTransactions(req)
        return withContext(Dispatchers.IO) {
            val url = URL("$BACKEND_URL/sync/push")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            val txArray = JSONArray()
            req.transactions.forEach { item ->
                txArray.put(JSONObject().apply {
                    put("txId", item.txId)
                    put("side", item.side)
                    put("queuedAt", item.queuedAt)
                    put("tx", JSONObject().apply {
                        put("id", item.tx.id)
                        put("amount", item.tx.amount)
                        put("currency", item.tx.currency)
                        put("timestamp", item.tx.timestamp)
                        put("fromUserId", item.tx.fromUserId)
                        put("toMerchantId", item.tx.toMerchantId)
                        put("status", item.tx.status)
                        put("syncStatus", item.tx.syncStatus)
                    })
                })
            }
            val body = JSONObject().apply {
                put("deviceId", req.deviceId)
                put("transactions", txArray)
            }
            conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
            if (conn.responseCode != 200) throw Exception("Push failed: ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val synced = mutableListOf<String>()
            val failed = mutableListOf<String>()
            json.getJSONArray("syncedTxIds").let { arr ->
                for (i in 0 until arr.length()) synced.add(arr.getString(i))
            }
            json.getJSONArray("failedTxIds").let { arr ->
                for (i in 0 until arr.length()) failed.add(arr.getString(i))
            }
            SyncResponse(syncedTxIds = synced, failedTxIds = failed)
        }
    }

    // ─── Pull Account ─────────────────────────────────────────────────────────

    suspend fun pullAccount(req: PullRequest): PullResponse {
        if (USE_MOCK) return mockPullAccount(req)
        return withContext(Dispatchers.IO) {
            val url = URL("$BACKEND_URL/account?deviceId=${req.deviceId}&role=${req.role}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Content-Type", "application/json")
            }
            if (conn.responseCode != 200) throw Exception("Pull failed: ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val txs = mutableListOf<ServerTransaction>()
            json.getJSONArray("transactions").let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    txs.add(ServerTransaction(
                        id = t.getString("id"),
                        amount = t.getDouble("amount"),
                        currency = t.getString("currency"),
                        timestamp = t.getLong("timestamp"),
                        fromUserId = t.getString("fromUserId"),
                        toMerchantId = t.getString("toMerchantId"),
                        status = t.getString("status"),
                        syncStatus = t.getString("syncStatus")
                    ))
                }
            }
            PullResponse(
                offlineBalance = json.getDouble("offlineBalance"),
                transactions = txs
            )
        }
    }

    // ─── Mock Implementations ─────────────────────────────────────────────────

    private suspend fun mockInitSession(req: SessionInitRequest): SessionInitResponse {
        delay(800)
        val last6 = req.deviceId.takeLast(6)
        return if (req.role == "merchant") {
            SessionInitResponse(
                userId = req.deviceId,
                displayName = req.deviceId,
                offlineBalance = 0.0,
                status = "active",
                merchantName = "Store $last6"
            )
        } else {
            SessionInitResponse(
                userId = req.deviceId,
                displayName = "User $last6",
                offlineBalance = 1000.0,
                status = "active"
            )
        }
    }

    private suspend fun mockPushTransactions(req: SyncRequest): SyncResponse {
        delay(1000)
        val syncedTxIds = req.transactions.map { it.txId }
        return SyncResponse(syncedTxIds = syncedTxIds, failedTxIds = emptyList())
    }

    private suspend fun mockPullAccount(req: PullRequest): PullResponse {
        delay(600)
        val queue = SyncQueue.getQueue(req.role)
        return PullResponse(
            offlineBalance = if (req.role == "merchant") 0.0 else 1000.0,
            transactions = queue.map { it.tx.copy(syncStatus = "synced") }
        )
    }
}
