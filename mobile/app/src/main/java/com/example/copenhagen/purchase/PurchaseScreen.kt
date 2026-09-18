package com.example.copenhagen.purchase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.copenhagen.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun PurchaseScreen(model: PurchaseModel, biometric: BiometricApproval) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val run: (suspend () -> Unit) -> Unit = { block -> scope.launch { model.action(block) } }
    LaunchedEffect(model.loggedIn) {
        if (model.loggedIn) model.action { model.refresh(); model.registerDevice() }
    }
    LaunchedEffect(model.screen, model.route?.persisted, model.loggedIn) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (model.loggedIn && model.screen in listOf("purchase", "approval")) {
            delay(5000)
            val status = (if (model.screen == "purchase") model.purchase else model.approval)?.optString("status")
            if (status in setOf("COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "REJECTED", "EXPIRED", "CANCELLED")) break
            if (model.screen == "purchase" || status == "APPROVED") model.action { model.openRoute() }
        }
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Copenhagen", style = MaterialTheme.typography.headlineMedium)
            Text("구매 내역을 확인하고 결제를 승인하세요.")
            if (model.busy) CircularProgressIndicator()
            if (model.message.isNotBlank()) Text(model.message, color = MaterialTheme.colorScheme.error)
            if (!model.loggedIn) {
                Text("로그인 후 알림으로 받은 구매를 이어갈 수 있습니다.")
                EntryForm(listOf("아이디", "비밀번호"), setOf(1), model.busy, "로그인") {
                    run { model.login(it[0], it[1]) }
                }
            } else {
                TextButton(onClick = { model.screen = "home" }) { Text("구매 및 승인 목록") }
                when (model.screen) {
                    "home" -> {
                        Text(model.pushMessage)
                        Action("새로고침", model.busy) { run { model.refresh() } }
                        Action("알림 연결 다시 시도", model.busy) { run { model.registerDevice() } }
                        Action("배송지 관리", model.busy) { model.screen = "addresses" }
                        Action("결제수단 관리", model.busy) { model.screen = "cards" }
                        Action(if (model.pinRegistered) "결제 PIN 변경" else "결제 PIN 등록", model.busy) { model.screen = "pin" }
                        Action("이 기기의 생체 인증 등록", model.busy) {
                            run { model.registerBiometric(biometric.publicKey()) }
                        }
                        Text("진행한 구매", style = MaterialTheme.typography.titleLarge)
                        model.purchases.forEach { row ->
                            Action(statusLabel(row.optString("status")), model.busy) {
                                run { model.open("purchase", row.getString("purchaseRequestId")) }
                            }
                        }
                        Text("결제 승인", style = MaterialTheme.typography.titleLarge)
                        model.approvals.forEach { row ->
                            Action(row.optString("merchantName") + " · " + row.optLong("totalAmountMinor") + "원 · "
                                + statusLabel(row.optString("status")), model.busy) {
                                run { model.open("approval", row.getString("approvalId")) }
                            }
                        }
                        Action("로그아웃", model.busy) { run { model.logout() } }
                    }
                    "purchase" -> model.purchase?.let { current ->
                        Text(statusLabel(current.optString("status")), style = MaterialTheme.typography.titleLarge)
                        Text("요청 유효 시간: " + current.optString("expiresAt"))
                        val rows = PurchaseModel.objects(current.getJSONArray("merchantCheckouts"))
                        rows.forEach { row ->
                            Text(row.optString("merchantId") + " · " + row.optLong("totalAmount") + "원 · "
                                + statusLabel(row.optString("status")))
                            if (!row.isNull("approvalId")) Action("주문 내용과 결제 승인 확인", model.busy) {
                                run { model.open("approval", row.getString("approvalId")) }
                            }
                        }
                        if (current.optString("status") == "PENDING_ADDRESS") {
                            Text("배송지를 선택하면 같은 구매 요청으로 계속 진행합니다.")
                            AddressChoices(model, run, true)
                        }
                        if (!current.isNull("failureCode")) Text("확인 사유: " + current.optString("failureCode"))
                        Action("진행 상태 확인", model.busy) { run { model.openRoute() } }
                    }
                    "addresses" -> AddressChoices(model, run, false)
                    "address-form" -> EntryForm(listOf("수령인", "연락처", "우편번호", "주소", "상세 주소", "배송 요청"),
                        emptySet(), model.busy, "배송지 저장") { run { model.addAddress(it) } }
                    "cards" -> CardChoices(model, run, false)
                    "card-form" -> {
                        Text("개발 환경의 테스트 카드만 등록할 수 있습니다.")
                        EntryForm(listOf("테스트 카드번호", "만료 월", "만료 연도", "CVC", "첫 카드 비밀번호 4자리"),
                            setOf(0, 3, 4), model.busy, "테스트 카드 등록") { values ->
                            run { model.addCard(values) }
                        }
                    }
                    "pin" -> EntryForm(if (model.pinRegistered) listOf("현재 PIN 6자리", "새 PIN 6자리", "새 PIN 확인")
                        else listOf("PIN 6자리", "PIN 확인"), setOf(0, 1, 2), model.busy, "PIN 저장") {
                        if (it[it.lastIndex] != it[it.lastIndex - 1]) model.message = "PIN이 일치하지 않습니다."
                        else run { model.savePin(if (model.pinRegistered) it[0] else "", it[it.lastIndex]) }
                    }
                    "approval" -> model.approval?.let { current ->
                        Text(current.optString("merchantName"), style = MaterialTheme.typography.titleLarge)
                        Text(statusLabel(current.optString("status")))
                        PurchaseModel.objects(current.getJSONArray("summary")).forEach { item ->
                            Text(item.optString("name") + " " + item.optString("option") + " · "
                                + item.optInt("quantity") + "개 · " + item.optLong("amountMinor") + "원")
                        }
                        Text("최종 결제 금액 " + current.optLong("totalAmountMinor") + "원",
                            style = MaterialTheme.typography.headlineSmall)
                        Text("승인 만료: " + current.optString("expiresAt"))
                        if (current.optString("status") in listOf("PAYMENT_METHOD_REQUIRED", "PENDING")) {
                            CardChoices(model, run, true)
                            if (current.optString("status") == "PENDING") {
                                val selected = model.cards.find { it.optString("paymentMethodId") ==
                                    current.optString("selectedPaymentMethodId") }
                                Text("선택한 결제수단: " + (selected?.let(::cardLabel) ?: "카드를 선택해주세요."))
                                if (current.optString("requiredAction") == "PIN") {
                                    if (!model.pinRegistered) Action("결제 PIN 등록", model.busy) { model.screen = "pin" }
                                    else EntryForm(listOf("결제 PIN 6자리"), setOf(0), model.busy, "이 금액으로 결제 승인") {
                                        run { model.approve(PurchaseModel.json("method" to "PIN", "pin" to it[0])) }
                                    }
                                } else if (current.optString("requiredAction") == "BIOMETRIC") {
                                    Action("생체 인증으로 이 금액 결제 승인", model.busy) {
                                        val challenge = current.optJSONObject("biometricChallenge")
                                        if (challenge != null) biometric.sign(challenge.getString("challenge"), { assertion ->
                                            run { model.approve(PurchaseModel.json("method" to "BIOMETRIC",
                                                "challengeId" to challenge.getString("challengeId"), "assertion" to assertion)) }
                                        }, { model.message = it })
                                    }
                                }
                            }
                            Action("결제 거절", model.busy) { run { model.reject() } }
                        }
                        Action("최신 상태 확인", model.busy) { run { model.openRoute() } }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressChoices(model: PurchaseModel, run: (suspend () -> Unit) -> Unit, select: Boolean) {
    model.addresses.forEach { row ->
        Text(row.optString("recipient") + " · " + row.optString("address") + " " + row.optString("detail")
            + if (row.optBoolean("isDefault")) " (기본)" else "")
        Action(if (select) "이 배송지로 계속" else "기본 배송지로 지정", model.busy) {
            run { if (select) model.resume(row.getString("addressId")) else model.defaultAddress(row.getString("addressId")) }
        }
        if (!select) Action("배송지 삭제", model.busy) { run { model.deleteAddress(row.getString("addressId")) } }
    }
    Action("배송지 등록", model.busy) { model.screen = "address-form" }
}

private fun cardLabel(row: JSONObject) = row.optString("brand") + " · " + row.optString("last4")

@Composable
private fun CardChoices(model: PurchaseModel, run: (suspend () -> Unit) -> Unit, select: Boolean) {
    model.cards.forEach { row ->
        Text(cardLabel(row) + if (row.optBoolean("isDefault")) " (기본)" else "")
        Action(if (select) "이 카드 선택" else "기본 카드로 지정", model.busy) {
            run { if (select) model.selectCard(row.getString("paymentMethodId"))
                else model.defaultCard(row.getString("paymentMethodId")) }
        }
        if (!select) Action("카드 삭제", model.busy) { run { model.deleteCard(row.getString("paymentMethodId")) } }
    }
    if (BuildConfig.DEBUG && BuildConfig.PG_MOCK_BASE_URL.isNotBlank()) {
        Action("테스트 카드 등록", model.busy) { model.screen = "card-form" }
    } else Text("카드 등록은 결제수단 발급 연동이 설정된 앱에서 지원됩니다.")
}

@Composable
private fun Action(label: String, busy: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun EntryForm(labels: List<String>, secret: Set<Int>, busy: Boolean, submit: String,
    onSubmit: (List<String>) -> Unit) {
    var values by remember(labels) { mutableStateOf(labels.map { "" }) }
    labels.forEachIndexed { index, label ->
        OutlinedTextField(values[index], { value -> values = values.toMutableList().also { it[index] = value } },
            label = { Text(label) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (index in secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if ("PIN" in label || "CVC" in label)
                KeyboardType.NumberPassword else KeyboardType.Text))
    }
    Action(submit, busy) {
        onSubmit(values.toList())
        if (submit.contains("승인") || submit == "로그인") values = values.mapIndexed { i, v -> if (i in secret) "" else v }
    }
    HorizontalDivider()
}

private fun statusLabel(status: String): String = when (status) {
    "PENDING_ADDRESS" -> "배송지 선택 필요"
    "CREATING", "CREATING_CHECKOUTS" -> "주문 검증 및 승인 준비 중"
    "PAYMENT_METHOD_REQUIRED" -> "결제수단 선택 필요"
    "PENDING", "AWAITING_APPROVAL" -> "결제 승인 대기"
    "APPROVED", "COMPLETING" -> "승인 완료 · 결제 결과 확인 중"
    "COMPLETED" -> "결제 완료"
    "PARTIALLY_COMPLETED" -> "일부 판매처 결제 완료"
    "REJECTED" -> "승인 거절"
    "CANCELLED" -> "취소"
    "EXPIRED", "INVALIDATED" -> "만료"
    "FAILED" -> "결제 실패"
    else -> "진행 상태 확인 필요"
}
