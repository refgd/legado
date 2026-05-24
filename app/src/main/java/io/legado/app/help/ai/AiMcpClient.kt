package io.legado.app.help.ai

import io.legado.app.BuildConfig
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.webBook.RustAnalyzerBridge
import io.legado.app.model.webBook.RustRawFetchResult
import io.legado.app.ui.main.ai.AiMcpServerConfig
import io.legado.app.utils.GSON
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

object AiMcpClient {

    private const val PROTOCOL_VERSION = "2025-06-18"
    private const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"
    private const val HEADER_SESSION_ID = "Mcp-Session-Id"
    private const val TOOL_CACHE_TTL_MS = 60_000L

    private data class SessionState(
        val sessionId: String?,
        val protocolVersion: String,
        val configFingerprint: String
    )

    private data class CachedTools(
        val fingerprint: String,
        val createdAt: Long,
        val tools: List<AiResolvedTool>
    )

    private data class McpToolDescriptor(
        val name: String,
        val title: String,
        val description: String,
        val inputSchema: JSONObject
    )

    private val sessionMap = mutableMapOf<String, SessionState>()
    private val toolCache = mutableMapOf<String, CachedTools>()

    suspend fun resolveTools(servers: List<AiMcpServerConfig>): List<AiResolvedTool> {
        val result = mutableListOf<AiResolvedTool>()
        val usedNames = mutableSetOf<String>()
        servers.filter { it.enabled }.forEach { server ->
            val fingerprint = server.fingerprint()
            val cached = toolCache[server.id]
            if (cached != null
                && cached.fingerprint == fingerprint
                && System.currentTimeMillis() - cached.createdAt < TOOL_CACHE_TTL_MS
            ) {
                cached.tools.forEach {
                    if (usedNames.add(it.name)) result += it
                }
                return@forEach
            }
            val localNames = usedNames.toMutableSet()
            val tools = try {
                listTools(server).mapIndexed { index, descriptor ->
                    val alias = buildToolAlias(server, descriptor.name, index, localNames)
                    localNames += alias
                    buildResolvedTool(server, alias, descriptor)
                }
            } catch (e: Exception) {
                sessionMap.remove(server.id)
                throw NoStackTraceException(
                    "AiMcpClient failed to resolve tools for ${server.name}: ${e.localizedMessage}"
                )
            }
            toolCache[server.id] = CachedTools(
                fingerprint = fingerprint,
                createdAt = System.currentTimeMillis(),
                tools = tools
            )
            tools.forEach {
                if (usedNames.add(it.name)) result += it
            }
        }
        return result
    }

    private suspend fun listTools(server: AiMcpServerConfig): List<McpToolDescriptor> {
        val session = ensureSession(server)
        val tools = mutableListOf<McpToolDescriptor>()
        var cursor: String? = null
        do {
            val requestId = nextRequestId()
            val params = JSONObject()
            cursor?.let { params.put("cursor", it) }
            val response = postJsonRpc(
                server = server,
                session = session,
                body = jsonRpcRequest("tools/list", params, requestId),
                requestId = requestId
            )
            val result = response.optJSONObject("result")
                ?: throw NoStackTraceException("AiMcpClient tools/list missing result for ${server.name}")
            val toolArray = result.optJSONArray("tools")
                ?: throw NoStackTraceException("AiMcpClient tools/list missing tools array for ${server.name}")
            for (index in 0 until toolArray.length()) {
                val tool = toolArray.optJSONObject(index)
                    ?: throw NoStackTraceException("AiMcpClient tools/list item $index is not an object for ${server.name}")
                tools += McpToolDescriptor(
                    name = tool.optString("name").ifBlank {
                        throw NoStackTraceException("AiMcpClient tools/list item $index has blank name for ${server.name}")
                    },
                    title = tool.optString("title"),
                    description = tool.optString("description"),
                    inputSchema = tool.optJSONObject("inputSchema")
                        ?: throw NoStackTraceException("AiMcpClient tools/list item $index missing inputSchema for ${server.name}")
                )
            }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
        } while (cursor != null)
        return tools
    }

    private fun buildResolvedTool(
        server: AiMcpServerConfig,
        alias: String,
        descriptor: McpToolDescriptor
    ): AiResolvedTool {
        val description = buildString {
            append("MCP ")
            append(server.name)
            if (descriptor.title.isNotBlank() && descriptor.title != descriptor.name) {
                append(" - ")
                append(descriptor.title)
            }
            if (descriptor.description.isNotBlank()) {
                append(": ")
                append(descriptor.description)
            }
        }
        return AiResolvedTool(
            name = alias,
            definition = JSONObject().apply {
                put("type", "function")
                put(
                    "function",
                    JSONObject().apply {
                        put("name", alias)
                        put("description", description)
                        put("parameters", sanitizeSchema(descriptor.inputSchema))
                    }
                )
            },
            execute = { args ->
                callTool(server, descriptor.name, args)
            }
        )
    }

    suspend fun callTool(
        server: AiMcpServerConfig,
        toolName: String,
        arguments: JSONObject?
    ): String {
        val session = ensureSession(server)
        val requestId = nextRequestId()
        val response = postJsonRpc(
            server = server,
            session = session,
            body = jsonRpcRequest(
                method = "tools/call",
                params = JSONObject().apply {
                    put("name", toolName)
                    put("arguments", arguments ?: JSONObject())
                },
                id = requestId
            ),
            requestId = requestId
        )
        return response.optJSONObject("result")?.toString()
            ?: throw NoStackTraceException("AiMcpClient tools/call missing result for ${server.name}: $toolName")
    }

    private suspend fun ensureSession(server: AiMcpServerConfig): SessionState {
        val fingerprint = server.fingerprint()
        val current = sessionMap[server.id]
        if (current != null && current.configFingerprint == fingerprint) {
            return current
        }
        val requestId = nextRequestId()
        val initializeBody = jsonRpcRequest(
            method = "initialize",
            params = JSONObject().apply {
                put("protocolVersion", PROTOCOL_VERSION)
                put(
                    "clientInfo",
                    JSONObject().apply {
                        put("name", "Legado")
                        put("version", BuildConfig.VERSION_NAME)
                    }
                )
                put("capabilities", JSONObject())
            },
            id = requestId
        )
        val rawResponse = postJsonRpcRaw(
            server = server,
            session = null,
            body = initializeBody.toString(),
            rulePath = "AiMcpClient.initialize"
        )
        val rpcResponse = readJsonRpcResponse(rawResponse, requestId)
        val result = rpcResponse.optJSONObject("result")
            ?: throw NoStackTraceException("AiMcpClient initialize missing result for ${server.name}")
        val session = SessionState(
            sessionId = rawResponse.header(HEADER_SESSION_ID).takeIf { it.isNotBlank() },
            protocolVersion = result.optString("protocolVersion").ifBlank { PROTOCOL_VERSION },
            configFingerprint = fingerprint
        )
        sessionMap[server.id] = session
        toolCache.remove(server.id)
        sendInitializedNotification(server, session)
        return session
    }

    private suspend fun sendInitializedNotification(
        server: AiMcpServerConfig,
        session: SessionState
    ) {
        val rawResponse = postJsonRpcRaw(
            server = server,
            session = session,
            body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            }.toString(),
            rulePath = "AiMcpClient.initialized"
        )
        if (rawResponse.code !in 200..299) {
            throw NoStackTraceException(
                "AiMcpClient initialized notification failed for ${server.name}: ${rawResponse.code} ${rawResponse.message}"
            )
        }
    }

    private suspend fun postJsonRpc(
        server: AiMcpServerConfig,
        session: SessionState,
        body: JSONObject,
        requestId: String
    ): JSONObject {
        return runCatching {
            readJsonRpcResponse(
                postJsonRpcRaw(
                    server = server,
                    session = session,
                    body = body.toString(),
                    rulePath = "AiMcpClient.postJsonRpc"
                ),
                requestId
            )
        }.recoverCatching {
            sessionMap.remove(server.id)
            toolCache.remove(server.id)
            val freshSession = ensureSession(server)
            readJsonRpcResponse(
                postJsonRpcRaw(
                    server = server,
                    session = freshSession,
                    body = body.toString(),
                    rulePath = "AiMcpClient.postJsonRpc.retry"
                ),
                requestId
            )
        }.getOrThrow()
    }

    private fun postJsonRpcRaw(
        server: AiMcpServerConfig,
        session: SessionState?,
        body: String,
        rulePath: String
    ): RustRawFetchResult {
        val headers = buildMap {
            put("Accept", "application/json, text/event-stream")
            put("Content-Type", "application/json")
            session?.let {
                put(HEADER_PROTOCOL_VERSION, it.protocolVersion)
                it.sessionId?.let { sessionId -> put(HEADER_SESSION_ID, sessionId) }
            }
            server.apiKey.trim().takeIf { it.isNotBlank() }?.let {
                put("Authorization", "Bearer $it")
            }
        }
        return RustAnalyzerBridge.fetchRawResponse(
            source = BookSource(
                bookSourceUrl = server.endpoint,
                bookSourceName = "AI MCP ${server.name}",
                bookSourceType = BookSourceType.default,
                header = GSON.toJson(headers)
            ),
            url = "${server.endpoint},${
                GSON.toJson(
                    mapOf(
                        "method" to "POST",
                        "body" to body
                    )
                )
            }",
            rulePath = rulePath
        )
    }

    private fun readJsonRpcResponse(response: RustRawFetchResult, requestId: String): JSONObject {
        val payload = response.body.toString(Charsets.UTF_8)
        if (response.code !in 200..299) {
            throw IllegalStateException(
                "MCP ${response.code} ${response.message}: ${extractJsonRpcError(payload)}"
            )
        }
        if (payload.isBlank()) {
            throw NoStackTraceException("AiMcpClient JSON-RPC response is blank for request $requestId")
        }
        val isSse = response.header("Content-Type").contains("text/event-stream", ignoreCase = true)
        val rpcResponse = if (isSse) {
            parseSsePayload(payload, requestId)
        } else {
            JSONObject(payload)
        }
        rpcResponse.optJSONObject("error")?.let { error ->
            throw IllegalStateException(
                error.optString("message").ifBlank { error.toString() }
            )
        }
        return rpcResponse
    }

    private fun parseSsePayload(payload: String, requestId: String): JSONObject {
        val eventData = StringBuilder()
        payload.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.startsWith("data:") -> {
                    eventData.append(line.removePrefix("data:").trim()).append('\n')
                }

                line.isBlank() -> {
                    if (eventData.isNotEmpty()) {
                        val json = JSONObject(eventData.toString().trim())
                        if (json.opt("id")?.toString() == requestId) {
                            return json
                        }
                        eventData.clear()
                    }
                }
            }
        }
        if (eventData.isNotEmpty()) {
            val json = JSONObject(eventData.toString().trim())
            if (json.opt("id")?.toString() == requestId) {
                return json
            }
        }
        throw IllegalStateException("MCP SSE response missing matching id")
    }

    private fun sanitizeSchema(schema: JSONObject): JSONObject {
        val result = JSONObject(schema.toString())
        if (result.optString("type").isBlank()) {
            result.put("type", "object")
        }
        if (!result.has("properties")) {
            result.put("properties", JSONObject())
        }
        return result
    }

    private fun buildToolAlias(
        server: AiMcpServerConfig,
        toolName: String,
        index: Int,
        usedNames: Collection<String>
    ): String {
        val serverSlug = slug(server.name).take(16).ifBlank { "server" }
        val toolSlug = slug(toolName).take(32).ifBlank { "tool" }
        val base = "mcp_${serverSlug}_${toolSlug}"
        var candidate = base.take(64)
        if (candidate !in usedNames) return candidate
        val suffix = "_${server.id.filter { it.isLetterOrDigit() }.takeLast(6)}_${index + 1}"
        candidate = (base.take(64 - suffix.length) + suffix).take(64)
        return candidate
    }

    private fun slug(value: String): String {
        return value.lowercase(Locale.getDefault())
            .map { ch ->
                when {
                    ch.isLetterOrDigit() -> ch
                    else -> '_'
                }
            }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .trim('_')
    }

    private fun AiMcpServerConfig.fingerprint(): String {
        return listOf(id, name.trim(), endpoint.trim(), apiKey.trim(), enabled).joinToString("|")
    }

    private fun jsonRpcRequest(method: String, params: JSONObject, id: String): JSONObject {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
    }

    private fun nextRequestId(): String = UUID.randomUUID().toString()

    private fun extractJsonRpcError(payload: String): String {
        return runCatching {
            JSONObject(payload).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty().ifBlank { payload }
    }

    private fun RustRawFetchResult.header(name: String): String {
        headers[name]?.let { return it }
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.let { return it.value }
        headersList.firstOrNull {
            it.size >= 2 && it[0].equals(name, ignoreCase = true)
        }?.let { return it[1] }
        return ""
    }
}
