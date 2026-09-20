package com.copenhagen.wallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.copenhagen.wallet.AppModel
import com.copenhagen.wallet.features.AgentConnection

@Composable fun ConnectionsScreen(app: AppModel) {
    var disconnect by remember { mutableStateOf<AgentConnection?>(null) }
    Page {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("AI 서비스", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
            IconButton({ app.execute { app.connections.load() } }, enabled = !app.busy) { Icon(Icons.Outlined.Refresh, "새로고침") }
        }
        Data(app.connections.items, app.busy, "조회 실패",
            { app.execute { app.connections.load() } }) { items ->
            if (items.isEmpty()) Text("연결 없음", color = Carbon.Muted)
            items.forEach { connection -> key(connection.connectionId) {
                var expanded by remember { mutableStateOf(false) }
                Tile({ expanded = !expanded }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RemoteImage(connection.logoUrl, "${connection.clientName} 로고", Modifier.size(40.dp))
                        Text(connection.clientName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        IconButton({ disconnect = connection }, enabled = !app.busy) { Icon(Icons.Outlined.LinkOff, "연결 해제") }
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "정보 접기" else "연결 정보")
                    }
                    if (expanded) {
                        Text(connection.scopes.joinToString(" · ").ifBlank { "권한 없음" })
                        Text("연결 ${connection.connectedAt}", color = Carbon.Muted, style = MaterialTheme.typography.bodySmall)
                        Text("최근 사용 ${connection.lastUsedAt ?: "—"}", color = Carbon.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } }
        }
    }
    disconnect?.let { connection ->
        Confirm("${connection.clientName} 연결 해제", "해제", { disconnect = null }) {
            disconnect = null
            app.execute { app.connections.disconnect(connection.connectionId) }
        }
    }
}
