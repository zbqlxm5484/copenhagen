package com.example.copenhagen.purchase

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.copenhagen.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

class PurchaseModel(context: Context) {
    private val context = context.applicationContext
    val session = SecureSession(context)
    private val api = ApiClient(session)
    var loggedIn by mutableStateOf(session.tokens.optString("accessToken").isNotEmpty())
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    var pushMessage by mutableStateOf("")
    var screen by mutableStateOf("home")
    var addresses by mutableStateOf(emptyList<JSONObject>())
    var cards by mutableStateOf(emptyList<JSONObject>())
    var purchases by mutableStateOf(emptyList<JSONObject>())
    var approvals by mutableStateOf(emptyList<JSONObject>())
    var purchase by mutableStateOf<JSONObject?>(null)
    var approval by mutableStateOf<JSONObject?>(null)
    var pinRegistered by mutableStateOf(false)
    var route by mutableStateOf(PurchaseRoute.parse(session.route))
        private set

    suspend fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = ""
        try { block() }
        catch (error: ApiFailure) {
            message = error.message.orEmpty()
            if (error.status == 401) {
                val pending = session.route
                session.clear()
                session.route = pending
                loggedIn = false
                purchase = null; approval = null; addresses = emptyList(); cards = emptyList()
                purchases = emptyList(); approvals = emptyList()
            }
        } catch (_: Exception) { message = "연결을 확인한 뒤 같은 요청으로 다시 시도해주세요." }
        finally { busy = false }
    }
    fun route(value: PurchaseRoute?) {
        if (value != null) { route = value; session.route = value.persisted }
    }
    suspend fun login(loginId: String, password: String) {
        val tokens = api.request("/api/core/v1/auth/login", "POST",
            json("loginId" to loginId, "password" to password, "deviceId" to session.deviceId,
                "deviceName" to Build.MODEL), false) as JSONObject
        session.tokens = tokens; loggedIn = true
        refresh()
        registerDevice()
    }
    suspend fun refresh() {
        if (!loggedIn) return
        addresses = items(api.request("/api/core/v1/addresses"))
        cards = items(api.request("/api/pay/v1/payment-methods/cards"))
        purchases = objects(api.request("/api/core/v1/purchase-requests") as JSONArray)
        approvals = items(api.request("/api/pay/v1/payment-approvals"))
        pinRegistered = (api.request("/api/pay/v1/users/me/payment-pin") as JSONObject).optBoolean("registered")
        openRoute()
    }
    suspend fun openRoute() {
        val target = route ?: return
        if (target.kind == "purchase") {
            purchase = api.request("/api/core/v1/purchase-requests/" + target.id) as JSONObject
            approval = null; screen = "purchase"
        } else {
            approval = api.request("/api/pay/v1/payment-approvals/" + target.id) as JSONObject
            screen = "approval"
        }
    }
    suspend fun open(kind: String, id: String) {
        route(PurchaseRoute.parse("$kind/$id"))
        openRoute()
    }
    suspend fun resume(addressId: String) {
        val current = purchase ?: return
        api.request("/api/core/v1/purchase-requests/" + current.getString("purchaseRequestId") + "/resume",
            "POST", json("addressId" to addressId, "cartVersion" to current.getInt("cartVersion")))
        open("purchase", current.getString("purchaseRequestId"))
    }
    suspend fun addAddress(values: List<String>) {
        val fields = listOf("recipientName", "recipientPhone", "postalCode", "addressLine1", "addressLine2", "deliveryRequest")
        val body = JSONObject()
        fields.zip(values).forEach { (field, value) -> body.put(field, value) }
        val created = api.request("/api/core/v1/addresses", "POST", body) as JSONObject
        val id = created.getString("addressId")
        api.request("/api/core/v1/addresses/$id/default", "PATCH")
        addresses = items(api.request("/api/core/v1/addresses"))
        if (purchase?.optString("status") == "PENDING_ADDRESS") resume(id) else screen = "addresses"
    }
    suspend fun defaultAddress(id: String) {
        api.request("/api/core/v1/addresses/$id/default", "PATCH")
        addresses = items(api.request("/api/core/v1/addresses"))
    }
    suspend fun deleteAddress(id: String) {
        api.request("/api/core/v1/addresses/$id", "DELETE")
        addresses = items(api.request("/api/core/v1/addresses"))
    }
    suspend fun addCard(values: List<String>) {
        check(BuildConfig.DEBUG && BuildConfig.PG_MOCK_BASE_URL.isNotEmpty()) {
            "개발용 카드 등록 주소가 설정되지 않았습니다."
        }
        val body = json("cardNumber" to values[0].filter(Char::isDigit),
            "expiryMonth" to values[1].toInt(), "expiryYear" to values[2].toInt(), "cvc" to values[3])
        if (cards.isEmpty()) body.put("cardPassword", values[4])
        val token = api.request("/api/pg/v1/payment-methods/tokenize", "POST", body,
            false, BuildConfig.PG_MOCK_BASE_URL) as JSONObject
        val registered = api.request("/api/pay/v1/payment-methods/cards", "POST",
            json("tokenizationResult" to token)) as JSONObject
        val id = registered.getString("paymentMethodId")
        api.request("/api/pay/v1/payment-methods/cards/$id/default", "PATCH")
        cards = items(api.request("/api/pay/v1/payment-methods/cards"))
        if (approval != null) selectCard(id) else screen = "cards"
    }
    suspend fun defaultCard(id: String) {
        api.request("/api/pay/v1/payment-methods/cards/$id/default", "PATCH")
        cards = items(api.request("/api/pay/v1/payment-methods/cards"))
    }
    suspend fun deleteCard(id: String) {
        api.request("/api/pay/v1/payment-methods/cards/$id", "DELETE")
        cards = items(api.request("/api/pay/v1/payment-methods/cards"))
    }
    suspend fun selectCard(id: String) {
        val current = approval ?: return
        api.request("/api/pay/v1/payment-approvals/" + current.getString("approvalId") + "/payment-method",
            "PATCH", json("paymentMethodId" to id))
        open("approval", current.getString("approvalId"))
    }
    suspend fun savePin(current: String, new: String) {
        require(new.matches(Regex("[0-9]{6}")))
        api.request("/api/pay/v1/users/me/payment-pin", if (pinRegistered) "PUT" else "POST",
            if (pinRegistered) json("currentPin" to current, "newPin" to new) else json("pin" to new))
        pinRegistered = true
        screen = if (approval == null) "home" else "approval"
        message = "결제 PIN을 저장했습니다."
    }
    suspend fun registerBiometric(publicKey: String) {
        api.request("/api/pay/v1/users/me/biometric-credentials", "POST",
            json("deviceId" to session.deviceId, "publicKey" to publicKey, "credentialType" to "PLATFORM_BIOMETRIC"))
        message = "이 기기의 생체 인증을 등록했습니다."
    }
    suspend fun approve(authentication: JSONObject) {
        val current = approval ?: return
        val response = api.request("/api/pay/v1/payment-approvals/" + current.getString("approvalId") + "/approve",
            "POST", json("paymentMethodId" to current.optString("selectedPaymentMethodId"),
                "authentication" to authentication)) as JSONObject
        // The credential/mandate fields are deliberately discarded by the app.
        message = if (response.optString("status") == "APPROVED") "승인했습니다. 결제 결과를 확인하고 있습니다."
            else "인증 상태를 다시 확인해주세요."
        open("approval", current.getString("approvalId"))
    }
    suspend fun reject() {
        val current = approval ?: return
        api.request("/api/pay/v1/payment-approvals/" + current.getString("approvalId") + "/reject",
            "POST", json("reason" to "USER_REJECTED"))
        open("approval", current.getString("approvalId"))
    }
    suspend fun registerDevice() {
        try {
            if (BuildConfig.FIREBASE_APP_ID.isBlank()) {
                pushMessage = "푸시 설정이 없습니다. 구매 및 승인 목록에서 진행 상태를 확인할 수 있습니다."
                return
            }
            if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context,
                FirebaseOptions.Builder().setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY).setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build())
            FirebaseMessaging.getInstance().isAutoInitEnabled = true
            val fid = FirebaseInstallations.getInstance().id.await()
            api.request("/api/pay/v1/notification-installations", "PUT", json("fid" to fid))
            pushMessage = if (androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
                "이 기기에 구매 알림을 받을 수 있습니다." else "알림 권한이 꺼져 있습니다. 승인 목록에서 확인해주세요."
        } catch (_: Exception) { pushMessage = "기기 등록에 실패했습니다. 알림 연결을 다시 시도해주세요." }
    }
    suspend fun logout() {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                val fid = FirebaseInstallations.getInstance().id.await()
                api.request("/api/pay/v1/notification-installations/$fid", "DELETE")
            }
            api.request("/api/core/v1/auth/logout", "POST",
                json("refreshToken" to session.tokens.optString("refreshToken")), false)
        } finally {
            session.clear(); route = null; loggedIn = false; screen = "home"
            purchase = null; approval = null; addresses = emptyList(); cards = emptyList()
            purchases = emptyList(); approvals = emptyList()
        }
    }
    companion object {
        fun json(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
            pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }
        fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).map(array::getJSONObject)
        fun items(value: Any): List<JSONObject> = objects((value as JSONObject).getJSONArray("items"))
    }
}
