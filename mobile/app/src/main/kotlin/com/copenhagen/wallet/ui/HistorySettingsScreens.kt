package com.copenhagen.wallet.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.copenhagen.wallet.*
import com.copenhagen.wallet.data.*

@Composable fun HistoryScreen(app: AppModel) = Page {
    var query by remember { mutableStateOf("") }
    Heading("기록")
    TabRow(app.historyTab) {
        listOf("승인 기록", "주문 기록").forEachIndexed { index, title ->
            Tab(app.historyTab == index, {
                app.historyTab = index; app.execute { app.refresh() }
            }, enabled = !app.busy, text = { Text(title) })
        }
    }
    if (app.historyTab == 0) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            approvalStatuses.forEach { status -> FilterChip(app.approvals.historyStatus == status, {
                app.approvals.historyStatus = status; app.execute { app.approvals.loadHistory() }
            }, { Text(statusLabel(status)) }, enabled = !app.busy, shape = RectangleShape) }
        }
        Field(query, { query = it }, "상점 검색", maxLength = 100)
        Data(app.approvals.history, app.busy, "승인 기록을 불러오지 못했어요.", { app.execute { app.approvals.loadHistory() } }) { rows ->
            val found = rows.filter { it.merchantName.contains(query, ignoreCase = true) }
            if (found.isEmpty()) Notice("승인 기록이 없어요.")
            found.forEach { value -> ApprovalTile(value) { app.openApproval(value.id) } }
        }
    } else {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("") + app.account.orders?.items.orEmpty().map { it.status } + app.account.orderStatus).distinct().forEach { status ->
                FilterChip(app.account.orderStatus == status, {
                    app.account.orderStatus = status; app.execute { app.account.loadOrders() }
                }, { Text(status.ifBlank { "전체" }) }, enabled = !app.busy, shape = RectangleShape)
            }
        }
        Data(app.account.orders, app.busy, "주문 기록을 불러오지 못했어요.", { app.execute { app.account.loadOrders() } }) { page ->
            if (page.items.isEmpty()) Notice("주문 기록이 없어요.")
            page.items.forEach { order -> Tile({ app.execute { app.show(Screen.H02, order.id); app.account.openOrder(order.id) } }) {
                Text(order.title, style = MaterialTheme.typography.titleMedium)
                Text(money(order.amountMinor), style = MaterialTheme.typography.headlineSmall)
                Text(order.status, color = Carbon.Blue)
                Text(order.createdAt, color = Carbon.Muted)
            } }
            if (page.nextCursor != null) Secondary("더 보기", !app.busy) { app.execute { app.account.loadOrders(more = true) } }
        }
    }
}
@Composable fun OrderDetailScreen(app: AppModel) = Page {
    Heading("주문 상세")
    Data(app.account.order, app.busy, "주문을 불러오지 못했어요.", { app.execute { app.refresh() } }) { order ->
        Text(order.title, style = MaterialTheme.typography.headlineSmall)
        Text(money(order.amountMinor), style = MaterialTheme.typography.headlineLarge)
        Tile { Text(order.id); Text(order.createdAt); Text(order.status, color = Carbon.Blue) }
        if (order.items.isNotEmpty()) Section("주문 상품")
        order.items.forEach { item -> Tile {
            Text(item.name); item.option?.takeIf { it.isNotBlank() }?.let { Text(it) }; Text("${item.quantity}개")
        } }
        if (order.paymentLabel.isNotBlank()) { Section("결제 정보"); Text(order.paymentLabel) }
        if (order.address.isNotBlank()) { Section("배송 정보"); Text(order.address) }
    }
}
@Composable fun SettingsScreen(app: AppModel, activity: FragmentActivity) {
    var logout by remember { mutableStateOf(false) }
    Page {
        Heading("설정")
        Tile {
            Text(app.account.profile?.name ?: app.loginId, style = MaterialTheme.typography.headlineSmall)
            app.account.profile?.let { Text(it.loginId); Text(it.email, color = Carbon.Muted) }
            if (app.account.profile == null) TextButton({ app.execute { app.account.loadProfile() } }, enabled = !app.busy) { Text("프로필 다시 불러오기") }
        }
        Section("보안")
        Secondary("PIN 변경", !app.busy) { app.show(Screen.S02) }
        Text(if (app.biometricRegistered) "생체 인증 등록됨" else "생체 인증 미등록", color = Carbon.Muted)
        if (app.biometricRegistered) Secondary("이 기기 생체 인증 끄기", !app.busy) { app.disableBiometric() }
        else Secondary("생체 인증 등록", !app.busy && app.biometrics.available(activity)) { app.enroll(activity) }
        Section("알림")
        Text("승인 요청 알림")
        Text(app.notificationError ?: if (app.notificationsRegistered) "알림 연결됨" else "알림 연결 대기 중",
            color = if (app.notificationError != null) Carbon.Error else Carbon.Muted)
        Secondary("알림 다시 연결", !app.busy) { app.execute { app.registerNotifications() } }
        Secondary("알림 설정", !app.busy) {
            activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
        }
        Section("앱")
        Secondary("연결된 AI 서비스", !app.busy) { app.open(Screen.S03) }
        Secondary("서버 설정", !app.busy) { app.show(Screen.X01) }
        Text("버전 ${BuildConfig.VERSION_NAME}", color = Carbon.Muted)
        Secondary("로그아웃", !app.busy) { logout = true }
    }
    if (logout) Confirm("로그아웃할까요?", "로그아웃", { logout = false }) { logout = false; app.logout() }
}
@Composable fun ServerScreen(app: AppModel) {
    var core by remember { mutableStateOf(app.store.core) }
    var pay by remember { mutableStateOf(app.store.pay) }
    var pg by remember { mutableStateOf(app.store.pg) }
    var confirming by remember { mutableStateOf(false) }
    Page {
        Heading("서버 설정")
        Field(core, { core = it.trim() }, "Core API", keyboard = KeyboardType.Uri, maxLength = 500, enabled = !app.busy)
        Field(pay, { pay = it.trim() }, "Pay API", keyboard = KeyboardType.Uri, maxLength = 500, enabled = !app.busy)
        Field(pg, { pg = it.trim() }, "PG API", keyboard = KeyboardType.Uri, maxLength = 500, enabled = !app.busy)
        if (app.signedIn) Text("변경하면 로그아웃돼요.", color = Carbon.Muted)
        val valid = runCatching {
            normalizeHost(core, BuildConfig.DEBUG); normalizeHost(pay, BuildConfig.DEBUG)
            if (pg.isNotBlank()) normalizeHost(pg, BuildConfig.DEBUG)
        }.isSuccess
        Action("저장", !app.busy && valid) { confirming = true }
    }
    if (confirming) Confirm("신뢰하는 서버 주소인가요?", "저장", { confirming = false }) {
        confirming = false; app.saveHosts(core, pay, pg)
    }
}
