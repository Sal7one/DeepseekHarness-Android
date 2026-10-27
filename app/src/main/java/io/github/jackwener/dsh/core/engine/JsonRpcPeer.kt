package io.github.jackwener.dsh.core.engine

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class JsonRpcException(val code: Int, message: String, val data: JsonElement? = null) : Exception(message)

val RpcJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/**
 * Newline-delimited JSON-RPC 2.0 over a byte-stream pair. Used for both the dsh SDK channel
 * (stdio) and the capability channel (socketpair). See docs/ENGINE-CONTRACT.md.
 */
class JsonRpcPeer(
    private val name: String,
    private val input: InputStream,
    private val output: OutputStream,
    private val scope: CoroutineScope,
    private val onRequest: suspend (method: String, params: JsonObject) -> JsonElement,
    private val onNotification: suspend (method: String, params: JsonObject) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val writeLock = Mutex()

    fun start() {
        scope.launch(Dispatchers.IO) {
            try {
                input.bufferedReader().useLines { lines -> lines.forEach { if (it.isNotBlank()) dispatch(it) } }
            } catch (e: Exception) {
                Log.w(TAG, "$name read loop ended", e)
            } finally {
                pending.values.forEach { it.completeExceptionally(JsonRpcException(-32000, "$name channel closed")) }
                pending.clear()
                onClosed()
            }
        }
    }

    suspend fun request(method: String, params: JsonElement = JsonObject(emptyMap())): JsonElement {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
        return deferred.await()
    }

    suspend fun notify(method: String, params: JsonElement = JsonObject(emptyMap())) =
        send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", params) })

    private suspend fun send(msg: JsonObject) = writeLock.withLock {
        val bytes = (msg.toString() + "\n").toByteArray()
        kotlinx.coroutines.withContext(Dispatchers.IO) { output.write(bytes); output.flush() }
    }

    private fun dispatch(line: String) {
        val msg = runCatching { RpcJson.parseToJsonElement(line).jsonObject }.getOrElse {
            Log.w(TAG, "$name non-JSON line: ${line.take(200)}"); return
        }
        val id = msg["id"]
        val method = msg["method"]?.jsonPrimitive?.contentOrNull
        val params = (msg["params"] as? JsonObject) ?: JsonObject(emptyMap())
        when {
            method != null && id != null && id !is JsonNull -> scope.launch {
                val response = try {
                    buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", onRequest(method, params)) }
                } catch (e: JsonRpcException) {
                    errorFrame(id, e.code, e.message ?: "error")
                } catch (e: Exception) {
                    Log.e(TAG, "$name handler $method failed", e)
                    errorFrame(id, -32603, e.message ?: e.javaClass.simpleName)
                }
                send(response)
            }
            method != null -> scope.launch { runCatching { onNotification(method, params) }.onFailure { Log.e(TAG, "$name notify $method", it) } }
            id != null -> {
                val deferred = pending.remove((id as? JsonPrimitive)?.longOrNull ?: return) ?: return
                val err = msg["error"] as? JsonObject
                if (err != null) deferred.completeExceptionally(
                    JsonRpcException(err["code"]?.jsonPrimitive?.longOrNull?.toInt() ?: -1,
                        err["message"]?.jsonPrimitive?.contentOrNull ?: "error", err["data"]))
                else deferred.complete(msg["result"] ?: JsonNull)
            }
        }
    }

    private fun errorFrame(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    }

    private companion object { const val TAG = "JsonRpc" }
}
