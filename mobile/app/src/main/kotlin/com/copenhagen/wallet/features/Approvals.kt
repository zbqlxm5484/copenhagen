package com.copenhagen.wallet.features

import androidx.compose.runtime.*
import com.copenhagen.wallet.data.*
import kotlinx.serialization.json.*

class Approvals(private val api: Api) {
    var inbox by mutableStateOf<List<Approval>?>(null); private set
    var current by mutableStateOf<Approval?>(null); private set
    var result by mutableStateOf<String?>(null); private set
    var uncertain by mutableStateOf(false); private set
    var history by mutableStateOf<List<Approval>?>(null); private set
    var historyStatus by mutableStateOf("APPROVED")
    var pendingId: String? = null
    private var auth: JsonObject? = null
    private var key = ""

    suspend fun loadInbox() {
        val waiting = api.pay("GET", "/payment-approvals", query = mapOf("status" to "PENDING")).read<Items<Approval>>()
        val cardRequired = api.pay("GET", "/payment-approvals", query = mapOf("status" to "PAYMENT_METHOD_REQUIRED")).read<Items<Approval>>()
        inbox = (waiting.items + cardRequired.items).distinctBy { it.id }.sortedBy { it.expiresAt }
    }
    suspend fun loadHistory() {
        history = null
        history = api.pay("GET", "/payment-approvals", query = mapOf("status" to historyStatus)).read<Items<Approval>>().items
    }
    suspend fun open(id: String) {
        if (current?.id != id) { clearAttempt(); result = null; current = null }
        pendingId = id
        current = api.pay("GET", "/payment-approvals/$id", deviceHeader = true).read<Approval>()
    }
    suspend fun selectCard(id: String) {
        val approval = requireNotNull(current)
        require(approval.actionable && !approval.expired()) { "승인 요청이 만료되었어요." }
        api.pay("PATCH", "/payment-approvals/${approval.id}/payment-method",
            buildJsonObject { put("paymentMethodId", id) }, newKey())
        clearAttempt()
        open(approval.id)
    }
    suspend fun approve(body: JsonObject): String {
        val approval = requireNotNull(current)
        require(approval.status == "PENDING" && !approval.expired()) { "승인 요청 상태를 다시 확인해 주세요." }
        require(!approval.selectedPaymentMethodId.isNullOrBlank()) { "결제 카드를 선택해 주세요." }
        if (auth != body) { auth = body; key = newKey() }
        uncertain = true
        val response = try {
            api.pay("POST", "/payment-approvals/${approval.id}/approve", body, key).read<ApprovalResult>()
        } catch (error: ApiError) {
            if (error.status < 500) clearAttempt()
            throw error
        }
        result = response.status; uncertain = false
        clearAttempt()
        return response.status
    }
    suspend fun retry(): String {
        check(auth != null) { "PIN을 다시 입력해 주세요." }
        return approve(auth!!)
    }
    val canRetry get() = auth != null
    suspend fun reconcile(): String {
        open(requireNotNull(pendingId))
        val status = requireNotNull(current).status
        result = status
        if (status !in listOf("PENDING", "PAYMENT_METHOD_REQUIRED")) clearAttempt()
        return status
    }
    suspend fun reject(): String {
        val approval = requireNotNull(current)
        val response = api.pay("POST", "/payment-approvals/${approval.id}/reject",
            buildJsonObject { put("reason", "USER_REJECTED") }, newKey()).read<ApprovalResult>()
        result = response.status
        clearAttempt()
        return response.status
    }
    fun clearAttempt() { auth = null; key = ""; uncertain = false }
    fun close() { clearAttempt(); current = null; pendingId = null; result = null }
    fun clear() { close(); inbox = null; history = null }
}
