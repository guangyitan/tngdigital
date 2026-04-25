package com.example.tng_digital

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {

    private const val TAG = "ApiClient"
    private const val BACKEND_URL = "http://finhack-alb-2062571595.ap-southeast-5.elb.amazonaws.com"
    private const val TIMEOUT_MS = 10_000

    // ─── Session Init (GET with JSON body per spec) ──────────────────────────

    suspend fun initSession(req: SessionInitRequest): SessionInitResponse {
        return withContext(Dispatchers.IO) {
            val conn = openConnection("$BACKEND_URL/session/init", "GET")
            // Spec sends JSON body even on GET
            conn.doOutput = true
            val body = JSONObject().apply {
                put("deviceId", req.deviceId)
                put("role", req.role)
            }
            conn.outputStream.bufferedWriter().use { it.write(body.toString()) }

            val code = conn.responseCode
            if (code != 200) {
                val errBody = readErrorBody(conn)
                Log.w(TAG, "Session init HTTP $code: $errBody")
                throw ApiException("Unable to initialize session. Please check your connection and try again.")
            }

            val json = try {
                JSONObject(conn.inputStream.bufferedReader().readText())
            } catch (e: Exception) {
                throw ApiException("Unable to initialize session. Received an unexpected response from the server.")
            }

            SessionInitResponse(
                userId = json.optString("userId", req.deviceId),
                displayName = json.optString("displayName", req.deviceId),
                offlineBalance = json.optDouble("offlineBalance", 1000.0),
                status = json.optString("status", "active"),
                merchantName = json.optString("merchantName", null)
            )
        }
    }

    // ─── Push Transactions (POST) ────────────────────────────────────────────

    suspend fun pushTransactions(req: SyncRequest): SyncResponse {
        return withContext(Dispatchers.IO) {
            val conn = openConnection("$BACKEND_URL/sync/push", "POST")
            conn.doOutput = true

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
                        put("signature", item.tx.signature)
                        put("userPubKey", item.tx.userPubKey)
                        put("cert", item.tx.cert)
                        put("ackSignature", item.tx.ackSignature)
                        put("merchantPubKey", item.tx.merchantPubKey)
                    })
                })
            }
            val body = JSONObject().apply {
                put("deviceId", req.deviceId)
                put("transactions", txArray)
            }
            conn.outputStream.bufferedWriter().use { it.write(body.toString()) }

            val code = conn.responseCode
            if (code != 200) {
                val errBody = readErrorBody(conn)
                Log.w(TAG, "Push HTTP $code: $errBody")
                throw ApiException("Unable to sync transactions. Please check your connection and try again.")
            }

            val json = try {
                JSONObject(conn.inputStream.bufferedReader().readText())
            } catch (e: Exception) {
                // If server returns success but unexpected body, treat all as synced
                Log.w(TAG, "Push: unexpected response body, treating all as synced")
                val allIds = req.transactions.map { it.txId }
                return@withContext SyncResponse(syncedTxIds = allIds, failedTxIds = emptyList())
            }

            val synced = mutableListOf<String>()
            val failed = mutableListOf<String>()
            json.optJSONArray("syncedTxIds")?.let { arr ->
                for (i in 0 until arr.length()) synced.add(arr.getString(i))
            }
            json.optJSONArray("failedTxIds")?.let { arr ->
                for (i in 0 until arr.length()) failed.add(arr.getString(i))
            }

            // If server returned 200 but no syncedTxIds, assume all synced
            if (synced.isEmpty() && failed.isEmpty()) {
                val allIds = req.transactions.map { it.txId }
                return@withContext SyncResponse(syncedTxIds = allIds, failedTxIds = emptyList())
            }

            SyncResponse(syncedTxIds = synced, failedTxIds = failed)
        }
    }

    // ─── Pull Account (GET with query params) ────────────────────────────────

    suspend fun pullAccount(req: PullRequest): PullResponse {
        return withContext(Dispatchers.IO) {
            val conn = openConnection(
                "$BACKEND_URL/account?deviceId=${req.deviceId}&role=${req.role}",
                "GET"
            )

            val code = conn.responseCode
            if (code != 200) {
                val errBody = readErrorBody(conn)
                Log.w(TAG, "Pull HTTP $code: $errBody")
                throw ApiException("Unable to retrieve account data. Please check your connection and try again.")
            }

            val json = try {
                JSONObject(conn.inputStream.bufferedReader().readText())
            } catch (e: Exception) {
                throw ApiException("Unable to retrieve account data. Received an unexpected response from the server.")
            }

            val txs = mutableListOf<ServerTransaction>()
            json.optJSONArray("transactions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    txs.add(ServerTransaction(
                        id = t.optString("id", ""),
                        amount = t.optDouble("amount", 0.0),
                        currency = t.optString("currency", "MYR"),
                        timestamp = t.optLong("timestamp", 0L),
                        fromUserId = t.optString("fromUserId", ""),
                        toMerchantId = t.optString("toMerchantId", ""),
                        status = t.optString("status", "completed"),
                        syncStatus = t.optString("syncStatus", "synced"),
                        signature = t.optString("signature", ""),
                        userPubKey = t.optString("userPubKey", ""),
                        cert = t.optString("cert", ""),
                        ackSignature = t.optString("ackSignature", ""),
                        merchantPubKey = t.optString("merchantPubKey", "")
                    ))
                }
            }

            // Also mark local queue items as synced based on server data
            val serverSyncedIds = txs.filter { it.syncStatus == "synced" }.map { it.id }
            if (serverSyncedIds.isNotEmpty()) {
                SyncQueue.markSynced(serverSyncedIds)
            }

            PullResponse(
                offlineBalance = json.optDouble("offlineBalance", -1.0),
                transactions = txs
            )
        }
    }

    // ─── AI Insights (GET) ─────────────────────────────────────────────────

    suspend fun getInsights(): String {
        return withContext(Dispatchers.IO) {
            val conn = openConnection("$BACKEND_URL/api/bedrock/invoke", "GET")
            val code = conn.responseCode
            if (code != 200) {
                val errBody = readErrorBody(conn)
                Log.w(TAG, "Insights HTTP $code: $errBody")
                throw ApiException("Unable to generate insights at this time. Please try again later.")
            }
            val json = try {
                JSONObject(conn.inputStream.bufferedReader().readText())
            } catch (e: Exception) {
                throw ApiException("Unable to generate insights. Received an unexpected response from the server.")
            }
            json.optString("message", "No insights available.")
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun openConnection(urlStr: String, method: String): HttpURLConnection {
        val url = URL(urlStr)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
    }

    private fun readErrorBody(conn: HttpURLConnection): String {
        return try {
            conn.errorStream?.bufferedReader()?.readText() ?: "no error body"
        } catch (e: Exception) {
            "could not read error body"
        }
    }

    class ApiException(message: String) : Exception(message)
}
