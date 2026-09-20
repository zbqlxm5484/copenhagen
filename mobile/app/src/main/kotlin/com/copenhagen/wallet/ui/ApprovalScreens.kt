package com.copenhagen.wallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.fragment.app.FragmentActivity
import com.copenhagen.wallet.*
import com.copenhagen.wallet.data.*
import kotlinx.coroutines.delay
import java.time.Instant

@Composable fun InboxScreen(app: AppModel) = Page {
    Heading("승인 요청")
    Data(app.approvals.inbox, app.busy, "승인 요청을 불러오지 못했어요.", { app.execute { app.approvals.loadInbox() } }) { rows ->
        if (rows.isEmpty()) Notice("새로운 승인 요청이 없어요.")
        rows.forEach { value -> ApprovalTile(value) { app.openApproval(value.id) } }
    }
}

@Composable fun ApprovalDetailScreen(app: AppModel) {
    val approval = app.approvals.current
    var selecting by remember { mutableStateOf(false) }
    var rejecting by remember { mutableStateOf(false) }
    val now by produceState(Instant.now(), approval?.id) {
        while (true) { value = Instant.now(); delay(1000) }
    }
    Page {
        Heading(approval?.merchantName ?: "승인 요청")
        if (approval == null) {
            Notice("승인 요청을 불러오지 못했어요.")
            Secondary("다시 확인", !app.busy) { app.execute { app.refresh() } }
        } else {
            Text(money(approval.amountMinor), style = MaterialTheme.typography.headlineLarge)
            Text(statusLabel(approval.status), color = Carbon.Blue)
            approval.summary.forEach { item -> Tile {
                Text(item.name)
                item.option?.takeIf { it.isNotBlank() }?.let { Text(it, color = Carbon.Muted) }
                Text("${item.quantity}개")
            } }
            val active = approval.actionable && !approval.expired(now)
            if (active) {
                val remaining = (Instant.parse(approval.expiresAt).epochSecond - now.epochSecond).coerceAtLeast(0)
                Text("남은 시간 ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}", color = Carbon.Muted)
            } else if (approval.actionable) Notice("승인 요청이 만료되었어요.")
            val chosen = app.cards.items.orEmpty().find { it.id == approval.selectedPaymentMethodId }
            Section("결제 카드")
            if (chosen != null) CardTile(chosen) else Notice("카드를 선택해 주세요.")
            if (active) {
                Secondary("카드 선택", !app.busy) { selecting = true }
                Secondary("새 카드 등록", !app.busy) { app.show(Screen.C01) }
                Action("${money(approval.amountMinor)} 승인", !app.busy && chosen?.active == true && approval.currency == "KRW", "E30") { app.beginApproval() }
                Secondary("거절", !app.busy) { rejecting = true }
            } else {
                Action("처리 상태 확인", !app.busy) { app.execute { app.approvals.reconcile(); app.show(Screen.P05) } }
            }
        }
    }
    if (selecting) AlertDialog(onDismissRequest = { selecting = false }, title = { Text("결제 카드 선택") },
        text = { Column {
            val activeCards = app.cards.items.orEmpty().filter { it.active }
            if (activeCards.isEmpty()) Text("등록된 카드가 없어요.")
            activeCards.forEach { card -> TextButton({
                selecting = false
                app.execute { app.approvals.selectCard(card.id) }
            }, enabled = !app.busy) { Text(card.label) } }
        } }, confirmButton = { TextButton({ selecting = false }) { Text("닫기") } })
    if (rejecting) Confirm("승인 요청을 거절할까요?", "거절", { rejecting = false }) {
        rejecting = false
        app.execute { app.approvals.reject(); app.show(Screen.P05, replace = true) }
    }
}

@Composable fun ApprovalAuthScreen(app: AppModel, activity: FragmentActivity) = Page {
    var pin by remember(app.secretRevision) { mutableStateOf("") }
    val approval = app.approvals.current
    Heading(if (app.route.screen == Screen.P03) "결제 PIN 입력" else "생체 인증")
    approval?.let { Text(it.merchantName); Text(money(it.amountMinor), style = MaterialTheme.typography.headlineSmall) }
    if (app.route.screen == Screen.P03) {
        Field(pin, { pin = it.filter(Char::isDigit) }, "PIN 6자리", secret = true,
            keyboard = KeyboardType.NumberPassword, maxLength = 6, enabled = !app.busy)
        Action("승인", !app.busy && validPin(pin), "E34") {
            val entered = pin; pin = ""; app.approvePin(entered)
        }
    } else {
        Action("인증하고 승인", !app.busy, "E35") { app.approveBiometric(activity) }
        Secondary("PIN으로 인증", !app.busy) { app.show(Screen.P03, replace = true) }
    }
}

@Composable fun ApprovalResultScreen(app: AppModel) = Page {
    val status = app.approvals.result ?: app.approvals.current?.status
    val pending = status in listOf("PENDING", "PAYMENT_METHOD_REQUIRED")
    val title = when {
        app.approvals.uncertain -> "처리 확인 필요"
        status == "APPROVED" -> "승인을 전달했어요"
        status == "COMPLETED" -> "결제가 완료되었어요"
        status == "REJECTED" -> "요청을 거절했어요"
        status == "EXPIRED" -> "요청이 만료되었어요"
        status == "INVALIDATED" -> "요청이 취소되었어요"
        pending -> "승인 대기 중"
        else -> "처리 상태 확인"
    }
    Heading(title)
    app.approvals.current?.let { Text(it.merchantName); Text(money(it.amountMinor), style = MaterialTheme.typography.headlineSmall) }
    if (status == "APPROVED") Notice("결제 결과를 기다리고 있어요.")
    Action("상태 새로고침", !app.busy, "E40") { app.execute { app.approvals.reconcile() } }
    if (pending) {
        if (app.approvals.canRetry) {
            Secondary("승인 요청 다시 전송", !app.busy) {
                app.execute {
                    // 먼저 실제 상태를 읽는다. 미확정 요청은 기존 본문과 같은 키로만 재전송한다.
                    val latest = app.approvals.reconcile()
                    if (latest == "PENDING" && app.approvals.canRetry) {
                        val result = app.approvals.retry()
                        app.show(if (result == "PENDING") Screen.P03 else Screen.P05, replace = true)
                    }
                }
            }
        } else Secondary("승인 화면으로", !app.busy) { app.approvals.pendingId?.let(app::openApproval) }
    }
    Secondary("기록 보기", !app.busy) { app.closeApproval(toHistory = true) }
    Secondary("지갑으로", !app.busy) { app.closeApproval() }
}
