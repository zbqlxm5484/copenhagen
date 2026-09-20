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
import com.copenhagen.wallet.data.money
import com.copenhagen.wallet.features.CartItem

@Composable fun CartScreen(app: AppModel) {
    var deleting by remember { mutableStateOf<CartItem?>(null) }
    var estimateInfo by remember { mutableStateOf(false) }
    Page {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("장바구니", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
            IconButton({ app.execute { app.cart.load() } }, enabled = !app.busy) { Icon(Icons.Outlined.Refresh, "새로고침") }
        }
        app.cart.notice?.let { Text(it, color = Carbon.Blue, style = MaterialTheme.typography.labelLarge) }
        Data(app.cart.view, app.busy, "조회 실패",
            { app.execute { app.cart.load() } }) { cart ->
            if (cart.items.isEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.ShoppingCart, null, Modifier.size(64.dp), tint = Carbon.Line)
                Text("비어 있음", color = Carbon.Muted)
            }
            cart.items.forEach { item -> key(item.cartItemId) {
                Tile {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RemoteImage(item.imageUrl, item.productName, Modifier.size(72.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.productName, style = MaterialTheme.typography.titleMedium)
                            Text(item.merchantId, color = Carbon.Muted, style = MaterialTheme.typography.labelMedium)
                            if (item.optionValueIds.isNotEmpty()) Text("옵션: ${item.optionValueIds.joinToString()}", color = Carbon.Muted, style = MaterialTheme.typography.labelMedium)
                            Text("${money(item.unitPrice.amount, item.unitPrice.currency)} × ${item.quantity}", color = Carbon.Muted)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ app.execute { app.cart.updateQuantity(item.cartItemId, item.quantity - 1) } },
                                enabled = !app.busy && item.quantity > 1) { Icon(Icons.Outlined.Remove, "수량 줄이기") }
                            Text("${item.quantity}")
                            IconButton({ app.execute { app.cart.updateQuantity(item.cartItemId, item.quantity + 1) } },
                                enabled = !app.busy && item.quantity < Int.MAX_VALUE) { Icon(Icons.Outlined.Add, "수량 늘리기") }
                        }
                        IconButton({ deleting = item }, enabled = !app.busy) { Icon(Icons.Outlined.Delete, "상품 삭제") }
                    }
                    Text(money(item.lineTotal, item.unitPrice.currency), style = MaterialTheme.typography.titleMedium)
                }
            } }
            if (cart.items.isNotEmpty()) {
                val summary = cart.summary
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("예상 금액", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton({ estimateInfo = true }) { Icon(Icons.Outlined.Info, "예상 금액 안내") }
                }
                listOf("상품" to summary.itemsTotal, "할인" to summary.discountTotal, "배송" to summary.shippingFee).forEach { (label, amount) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, color = Carbon.Muted); Text(money(amount, summary.currency))
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("예상 합계"); Text(money(summary.estimatedTotal, summary.currency), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
    deleting?.let { item -> Confirm("${item.productName} 삭제", "삭제", { deleting = null }) {
        deleting = null
        app.execute { app.cart.remove(item.cartItemId) }
    } }
    if (estimateInfo) AlertDialog(onDismissRequest = { estimateInfo = false },
        title = { Text("예상 금액") }, text = { Text("할인·배송비 미확정") },
        confirmButton = { TextButton({ estimateInfo = false }) { Text("닫기") } })
}
