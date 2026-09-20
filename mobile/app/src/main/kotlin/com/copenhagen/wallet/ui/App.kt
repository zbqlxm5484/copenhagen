package com.copenhagen.wallet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.copenhagen.wallet.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun App(app: AppModel, activity: FragmentActivity) = CopenhagenTheme {
    val screen = app.route.screen
    BackHandler(enabled = app.busy || app.canBack) { app.back() }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Copenhagen", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                if (app.canBack) IconButton(app::back, enabled = !app.busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "뒤로") }
            }, actions = {
                if (!screen.focused && app.signedIn) IconButton({ app.execute { app.refresh() } }, enabled = !app.busy) { Icon(Icons.Outlined.Refresh, "새로고침") }
                if ((!app.signedIn || app.pin?.status != "ACTIVE") && screen != Screen.X01) IconButton({ app.show(Screen.X01) }, enabled = !app.busy) { Icon(Icons.Outlined.Settings, "서버 설정") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) },
        bottomBar = {
            if (!screen.focused && app.signedIn) Column {
                HorizontalDivider(color = Carbon.Line)
                Row(Modifier.fillMaxWidth().navigationBarsPadding()) {
                    val icons = listOf(Icons.Outlined.AccountBalanceWallet, Icons.Outlined.LocationOn, Icons.Outlined.ReceiptLong, Icons.Outlined.Settings)
                    val labels = listOf("지갑", "배송지", "기록", "설정")
                    Screen.tabs.forEachIndexed { index, tab ->
                        val active = app.tab == tab
                        Column(Modifier.weight(1f).heightIn(min = 64.dp).semantics { selected = active }
                            .clickable(enabled = !app.busy, role = Role.Tab) { app.selectTab(tab) }
                            .background(if (active) Carbon.Layer else MaterialTheme.colorScheme.surface).padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(icons[index], null, tint = if (active) Carbon.Blue else Carbon.Muted)
                            Text(labels[index], color = if (active) Carbon.Blue else Carbon.Muted, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    ) { insets -> Column(Modifier.fillMaxSize().padding(insets)) {
        if (app.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        app.error?.let { message -> Row(Modifier.fillMaxWidth().background(Carbon.Layer).padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), color = Carbon.Error, style = MaterialTheme.typography.bodyMedium)
            IconButton({ app.error = null }) { Icon(Icons.Outlined.Close, "오류 닫기") }
        } }
        key(app.route) {
            when (screen) {
                Screen.A01, Screen.A02, Screen.A03, Screen.A04, Screen.A05, Screen.A06, Screen.A07, Screen.A08, Screen.P06 -> AuthScreen(app, activity)
                Screen.W01 -> WalletScreen(app)
                Screen.W02 -> CardManagerScreen(app)
                Screen.C01 -> CardEntryScreen(app)
                Screen.C02 -> ConsentScreen(app)
                Screen.C03 -> CardResultScreen(app)
                Screen.D01 -> AddressesScreen(app)
                Screen.D02 -> AddressEditorScreen(app)
                Screen.D03 -> PostalSearchScreen(app)
                Screen.P01 -> InboxScreen(app)
                Screen.P02 -> ApprovalDetailScreen(app)
                Screen.P03, Screen.P04 -> ApprovalAuthScreen(app, activity)
                Screen.P05, Screen.P07 -> ApprovalResultScreen(app)
                Screen.H01 -> HistoryScreen(app)
                Screen.H02 -> OrderDetailScreen(app)
                Screen.S01 -> SettingsScreen(app, activity)
                Screen.S02 -> ChangePinScreen(app)
                Screen.S03 -> ConnectionsScreen(app)
                Screen.T01 -> CartScreen(app)
                Screen.G01 -> ProductSearchScreen(app)
                Screen.G02 -> ProductDetailScreen(app)
                Screen.X01 -> ServerScreen(app)
            }
        }
    } }
}
