package com.copenhagen.wallet.data

import android.util.Log
import com.copenhagen.wallet.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class ApiError(val status: Int, val code: String) : Exception(code)
fun Throwable.userMessage(): String = when (this) {
    is ApiError -> when {
        code in setOf("INVALID_CARD_DATA") -> "카드 정보를 확인해 주세요."
        code.contains("IDEMPOTENCY") -> "이전 요청과 내용이 달라요. 결과를 확인한 뒤 다시 진행해 주세요."
        code.contains("LOGIN_ID") || code.contains("DUPLICATE_LOGIN") -> "이미 사용 중인 아이디예요."
        code.contains("EMAIL") -> "이메일을 확인해 주세요."
        code.contains("PIN") && status == 422 -> "PIN이 일치하지 않아요."
        status == 401 -> "로그인 정보를 확인해 주세요."
        status == 423 -> "계정이 잠겼어요. 잠시 후 다시 시도해 주세요."
        status == 429 -> "인증이 일시적으로 잠겼어요. 잠시 후 다시 시도해 주세요."
        status == 410 -> "요청이 만료되었어요."
        status == 409 -> "요청 상태가 변경되었어요. 다시 확인해 주세요."
        status == 403 -> "요청에 접근할 수 없어요."
        status == 404 -> "요청을 찾을 수 없어요."
        else -> "요청을 처리하지 못했어요. 다시 시도해 주세요."
    }
    is IOException -> "서버에 연결하지 못했어요. 연결 상태를 확인해 주세요."
    is kotlinx.serialization.SerializationException -> "서버 응답을 확인할 수 없어요."
    is IllegalArgumentException, is IllegalStateException -> message ?: "입력 정보를 확인해 주세요."
    else -> "요청을 처리하지 못했어요. 다시 시도해 주세요."
}

/** 요청·응답 상세는 디버그 빌드에서 민감한 값을 가려 기록한다. */
class Http(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(35, TimeUnit.SECONDS).retryOnConnectionFailure(false)
    .followRedirects(false).followSslRedirects(false).build(),
    private val log: (String) -> Unit = { if (BuildConfig.DEBUG) Log.d("CopenhagenHttp", it) }
) {
    suspend fun request(
        base: String, method: String, path: String, body: JsonElement? = null,
        token: String? = null, key: String? = null,
        headers: Map<String, String> = emptyMap(), query: Map<String, String> = emptyMap()
    ): JsonElement = withContext(Dispatchers.IO) {
        require(base.isNotBlank()) { "서버 주소를 설정해 주세요." }
        val url = base.toHttpUrl().newBuilder().addPathSegments(path.trimStart('/')).apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).apply {
            header("Accept", "application/json")
            token?.let { header("Authorization", "Bearer $it") }
            key?.let { header("Idempotency-Key", it) }
            headers.forEach { (name, value) -> header(name, value) }
            val payload = if (method in setOf("POST", "PUT", "PATCH"))
                (body?.toString() ?: "").toRequestBody("application/json".toMediaType()) else null
            method(method, payload)
        }.build()
        val endpoint = "$method ${url.encodedPath}"
        val startedAt = System.nanoTime()
        log("--> $endpoint")
        if (BuildConfig.DEBUG) {
            val requestHeaders = buildJsonObject {
                request.headers.forEach { (name, value) -> put(name, value) }
                (request.header("Content-Type") ?: request.body?.contentType()?.toString())?.let { put("Content-Type", it) }
            }
            val requestQuery = JsonObject(url.queryParameterNames.associateWith { name ->
                JsonArray(url.queryParameterValues(name).map { JsonPrimitive(it) })
            })
            log("--> $endpoint headers=${requestHeaders.redactedForLog()}")
            log("--> $endpoint query=${requestQuery.redactedForLog()}")
            if (request.body != null) log("--> $endpoint body=${body?.redactedForLog() ?: "<empty>"}")
        }
        try {
            client.newCall(request).await().use { response ->
                log("<-- $endpoint status=${response.code} ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)}ms")
                val text = response.body?.string().orEmpty()
                if (BuildConfig.DEBUG) {
                    val loggedBody = if (text.isBlank()) "<empty>" else runCatching {
                        val value = json.parseToJsonElement(text)
                        require(value is JsonObject || value is JsonArray || value == JsonNull)
                        value.redactedForLog().toString()
                    }.getOrDefault("<unstructured response; ${text.length} chars>")
                    val parts = loggedBody.chunked(1000)
                    parts.forEachIndexed { index, part ->
                        val position = if (parts.size > 1) "[${index + 1}/${parts.size}] " else ""
                        log("<-- $endpoint response=$position$part")
                    }
                }
                if (!response.isSuccessful) {
                    val error = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                    val code = error?.get("code")?.jsonPrimitive?.contentOrNull
                        ?: error?.get("errorCode")?.jsonPrimitive?.contentOrNull.orEmpty()
                    throw ApiError(response.code, code)
                }
                if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
            }
        } catch (failure: Exception) {
            // 예외 메시지/스택에는 URL·응답 원문이 포함될 수 있어 유형만 기록한다.
            log("<-- $endpoint failed=${failure.javaClass.simpleName} ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)}ms")
            throw failure
        }
    }
}
private val sensitiveLogFields = setOf(
    "authorization", "proxyauthorization", "cookie", "setcookie", "apikey",
    "password", "currentpassword", "newpassword", "pin", "currentpin", "newpin",
    "cardnumber", "cvc", "cvv", "token", "accesstoken", "refreshtoken", "providertokenref",
    "assertion", "signature", "privatekey", "secret", "fid", "deviceid", "xdeviceid"
)
private fun JsonElement.redactedForLog(): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { (name, value) ->
        if (name.filter { it.isLetterOrDigit() }.lowercase() in sensitiveLogFields) JsonPrimitive("***")
        else value.redactedForLog()
    })
    is JsonArray -> JsonArray(map { it.redactedForLog() })
    else -> this
}
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, resource, _ -> resource.close() }
        }
    })
}
