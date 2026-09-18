package com.example.copenhagen.purchase

import com.example.copenhagen.BuildConfig
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ApiFailure(val status: Int, val code: String, message: String) : Exception(message)

/** No interceptor logs tokens, card data or response credentials. Redirects never forward credentials. */
interface ApiSession {
    var tokens: JSONObject
    val deviceId: String
}

class ApiClient(private val session: ApiSession, private val gateway: String = BuildConfig.API_BASE_URL) {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val refreshLock = Mutex()
    private val keys = mutableMapOf<String, String>()
    suspend fun request(path: String, method: String = "GET", body: JSONObject? = null,
        authenticated: Boolean = true, base: String = gateway): Any = withContext(Dispatchers.IO) {
        require(base.startsWith("https://") || (BuildConfig.DEBUG && base.startsWith("http://"))) {
            "서버 주소를 설정해주세요."
        }
        val command = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
            .digest((base + method + path + body.toString()).toByteArray(Charsets.UTF_8)))
        val key = if (method == "GET") null else synchronized(keys) {
            keys.getOrPut(command) { UUID.randomUUID().toString() }
        }
        val token = session.tokens.optString("accessToken")
        try {
            val result = send(base, path, method, body, if (authenticated) token else null, key)
            synchronized(keys) { keys.remove(command) }
            result
        } catch (error: ApiFailure) {
            if (error.status != 401 || !authenticated) throw error
            refreshLock.withLock {
                if (session.tokens.optString("accessToken") == token) {
                    val refreshed = send(gateway, "/api/core/v1/auth/token/refresh", "POST",
                        JSONObject().put("refreshToken", session.tokens.optString("refreshToken")), null, null) as JSONObject
                    val updated = session.tokens
                    updated.put("accessToken", refreshed.getString("accessToken"))
                    updated.put("refreshToken", refreshed.getString("refreshToken"))
                    session.tokens = updated
                }
            }
            send(base, path, method, body, session.tokens.optString("accessToken"), key).also {
                synchronized(keys) { keys.remove(command) }
            }
        }
    }
    private fun send(base: String, path: String, method: String, body: JSONObject?, token: String?, key: String?): Any {
        require(path.startsWith("/api/") && !path.contains(".."))
        val builder = Request.Builder().url(base.trimEnd('/') + path)
            .header("Accept", "application/json").header("X-Device-Id", session.deviceId)
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer " + token)
        if (key != null) builder.header("Idempotency-Key", key)
        val payload = if (method in listOf("GET", "DELETE")) null
            else (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType())
        client.newCall(builder.method(method, payload).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                throw ApiFailure(response.code, error.optString("code", "REQUEST_FAILED"),
                    error.optString("message", "요청에 실패했습니다. 다시 확인해주세요."))
            }
            return when {
                text.isBlank() -> JSONObject()
                text.trimStart().startsWith("[") -> JSONArray(text)
                else -> JSONObject(text)
            }
        }
    }
}
