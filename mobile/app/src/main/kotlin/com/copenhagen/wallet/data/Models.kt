package com.copenhagen.wallet.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNames
import java.time.Instant

@Serializable
data class Tokens(val accessToken: String, val refreshToken: String, val expiresIn: Long) {
    override fun toString() = "Tokens([redacted])"
}
@Serializable data class PinStatus(val registered: Boolean, val status: String)
@Serializable data class Items<T>(val items: List<T>, val nextCursor: String? = null)
@Serializable
data class Card(
    @SerialName("paymentMethodId") val id: String,
    val brand: String? = null, val last4: String? = null,
    val expiryMonth: Int? = null, val expiryYear: Int? = null,
    val isDefault: Boolean, val status: String
) {
    val active get() = status == "ACTIVE"
    val label get() = "${brand.orEmpty()} •••• ${last4 ?: "----"}".trim()
}
@Serializable
data class TokenizedCard(
    val provider: String, val providerTokenRef: String, val brand: String,
    val last4: String, val expiryMonth: Int, val expiryYear: Int
) { override fun toString() = "TokenizedCard([redacted])" }
@Serializable data class BiometricCredential(val credentialId: String)
@Serializable data class Challenge(val challengeId: String, val challenge: String, val expiresAt: String)
@Serializable data class ItemSummary(val name: String, val option: String? = null, val quantity: Int = 1)
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Approval(
    @SerialName("approvalId") val id: String,
    val merchantName: String, val checkoutId: String,
    // 목록은 amountMinor, 상세는 totalAmountMinor를 반환한다.
    @JsonNames("totalAmountMinor") val amountMinor: Long, val currency: String, val status: String,
    val requiredAction: String? = null, val expiresAt: String,
    val selectedPaymentMethodId: String? = null, val checkoutHash: String? = null,
    val summary: List<ItemSummary> = emptyList(), val biometricChallenge: Challenge? = null
) {
    val actionable get() = status in setOf("PENDING", "PAYMENT_METHOD_REQUIRED")
    fun expired(now: Instant = Instant.now()) = !Instant.parse(expiresAt).isAfter(now)
}
@Serializable
data class ApprovalResult(val approvalId: String, val status: String, val requiredAction: String? = null)

// Core 응답 중 상세 필드가 미확정인 세 모델. 수정 위치는 docs/CORE-CONTRACT.md 참고.
@Serializable
data class Address(
    @SerialName("addressId") val id: String, val recipient: String, val phone: String,
    val postalCode: String, val address: String, val detail: String = "", val memo: String = "",
    val isDefault: Boolean = false
)
@Serializable
data class AddressForm(
    val recipient: String = "", val phone: String = "", val postalCode: String = "",
    val address: String = "", val detail: String = "", val memo: String = ""
) {
    val valid get() = recipient.isNotBlank() && phone.isNotBlank() && postalCode.isNotBlank() && address.isNotBlank()
}
@Serializable data class Profile(val name: String, val loginId: String, val email: String)
@Serializable
data class Order(
    @SerialName("orderId") val id: String, val title: String, val status: String,
    val amountMinor: Long, val currency: String, val createdAt: String,
    val items: List<ItemSummary> = emptyList(), val address: String = "", val paymentLabel: String = ""
)
