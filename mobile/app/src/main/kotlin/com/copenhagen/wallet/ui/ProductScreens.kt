package com.copenhagen.wallet.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.copenhagen.wallet.AppModel
import com.copenhagen.wallet.data.money
import com.copenhagen.wallet.features.*

@Composable fun ProductSearchScreen(app: AppModel) = Page {
    val catalog = app.catalog
    val filters = catalog.filters
    var expanded by remember { mutableStateOf(false) }
    Heading("상품 탐색")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(filters.query, { catalog.edit(filters.copy(query = it.take(200))) }, Modifier.weight(1f),
            label = { Text("검색") }, singleLine = true, enabled = !app.busy,
            trailingIcon = { IconButton({ expanded = false; app.execute { catalog.search() } }, enabled = !app.busy) { Icon(Icons.Outlined.Search, "검색") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { expanded = false; app.execute { catalog.search() } }))
        IconToggleButton(expanded, { expanded = it }, enabled = !app.busy) {
            Icon(Icons.Outlined.Tune, "검색 필터", tint = if (expanded || filters.copy(query = "") != ProductFilters()) Carbon.Blue else Carbon.Muted)
        }
    }
    if (expanded) {
        Section("판매처")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf(MerchantRef("", "전체")) + catalog.merchants).forEach { merchant ->
                FilterChip(filters.merchantId == merchant.merchantId, { catalog.edit(filters.copy(merchantId = merchant.merchantId)) },
                    { Text(merchant.name) }, enabled = !app.busy)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(filters.minPrice, { catalog.edit(filters.copy(minPrice = it)) }, "최저 가격 (원)", Modifier.weight(1f),
                keyboard = KeyboardType.Number, maxLength = 19, enabled = !app.busy)
            Field(filters.maxPrice, { catalog.edit(filters.copy(maxPrice = it)) }, "최고 가격 (원)", Modifier.weight(1f),
                keyboard = KeyboardType.Number, maxLength = 19, enabled = !app.busy)
        }
        Section("정렬")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProductSort.entries.forEach { sort ->
                FilterChip(filters.sort == sort, { catalog.edit(filters.copy(sort = sort)) }, { Text(sort.label) }, enabled = !app.busy)
            }
        }
        Action("적용", !app.busy) { expanded = false; app.execute { catalog.search() } }
    }
    if (catalog.page != null) Data(catalog.result, app.busy, "검색 실패",
        { app.execute { catalog.search(catalog.page ?: 1) } }) { result ->
        Text("${result.total}개", style = MaterialTheme.typography.titleMedium)
        if (result.failedMerchants.isNotEmpty()) Notice("조회 실패: " +
            result.failedMerchants.joinToString { id -> catalog.merchants.find { it.merchantId == id }?.name ?: id })
        if (result.products.isEmpty()) Text(if (result.status == "PARTIAL") "조회 실패" else "검색 결과 없음", color = Carbon.Muted)
        result.products.forEachIndexed { index, product -> key(index, product.productId) {
            Tile {
                RemoteImage(product.imageUrl, product.name, Modifier.fillMaxWidth().height(140.dp))
                Text(product.name, style = MaterialTheme.typography.titleMedium)
                listOf(product.brand, product.category).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotEmpty() }?.let { Text(it) }
                if (product.offers.isEmpty()) Text("판매처 없음", color = Carbon.Muted)
                product.offers.forEach { offer ->
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().clickable(enabled = !app.busy, onClickLabel = "${offer.merchantName} 상세") {
                        app.openProduct(product.productId, offer.merchantId)
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(offer.merchantName, color = Carbon.Muted)
                            Text(offer.price?.let { money(it, offer.currency) } ?: "가격 미정", style = MaterialTheme.typography.titleMedium)
                        }
                        Availability(offer.availability)
                        Icon(Icons.Outlined.ChevronRight, "상세 보기")
                    }
                }
            }
        } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton({ app.execute { catalog.search((catalog.page ?: 1) - 1) } }, enabled = !app.busy && (catalog.page ?: 1) > 1) { Icon(Icons.Outlined.ChevronLeft, "이전 페이지") }
            Text("${catalog.page}")
            IconButton({ app.execute { catalog.search((catalog.page ?: 1) + 1) } }, enabled = !app.busy && result.hasNext) { Icon(Icons.Outlined.ChevronRight, "다음 페이지") }
        }
    }
}

@Composable fun ProductDetailScreen(app: AppModel) = Page {
    val details = app.products
    var informationExpanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("상품 상세", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
        IconButton({ app.execute { details.reload() } }, enabled = !app.busy) { Icon(Icons.Outlined.Refresh, "새로고침") }
    }
    Data(details.result, app.busy, "조회 실패", { app.execute { details.reload() } }) { result ->
        if (result.status == "NOT_FOUND") {
            Text("상품 없음", color = Carbon.Muted)
        } else result.product?.let { product ->
            RemoteImage(product.imageUrl, product.name, Modifier.fillMaxWidth().height(220.dp))
            Text(product.name, style = MaterialTheme.typography.headlineSmall)
            Text(product.offer.merchantName, color = Carbon.Muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(product.offer.price?.let { money(it, product.offer.currency) } ?: "가격 미정", style = MaterialTheme.typography.titleLarge)
                Availability(product.offer.availability)
            }
            if (product.variants.isNotEmpty() || product.optionsRequired == true) Section(if (product.optionsRequired == true) "옵션 *" else "옵션")
            if (product.selectionStatus == "INVALID") Text("옵션 선택 불가", color = Carbon.Error)
            product.variants.forEach { variant ->
                FilterChip(selected = product.selectedOptionId == variant.optionId,
                    onClick = { details.selection?.let { selection ->
                        app.execute { details.load(selection.copy(optionId = variant.optionId)) }
                    } }, enabled = !app.busy,
                    label = { Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(variant.label)
                            Text(variant.price?.let { money(it, product.offer.currency) } ?: "가격 미정")
                        }
                        Availability(variant.availability)
                        Icon(when (variant.purchaseAllowed) { true -> Icons.Outlined.CheckCircle; false -> Icons.Outlined.Block; null -> Icons.Outlined.HelpOutline },
                            when (variant.purchaseAllowed) { true -> "구매 가능"; false -> "구매 불가"; null -> "구매 가능 여부 미확인" }, tint = Carbon.Muted)
                    } })
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Inventory2, "재고")
                Text(product.stockQuantity?.let { if (it == 0L) "품절" else "${it}개" } ?: "미확인")
            }
            val shipping = product.shipping
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.LocalShipping, "배송비")
                Text(if (shipping.status == "UNKNOWN" || shipping.fee == null) "미확인" else money(shipping.fee, product.offer.currency))
            }
            TextButton({ informationExpanded = !informationExpanded }) {
                Text("상품 정보")
                Icon(if (informationExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (informationExpanded) {
                if (product.brand.isNotBlank()) Text(product.brand)
                if (product.category.isNotBlank()) Text(product.category)
                Text(product.description?.takeIf { it.isNotBlank() } ?: "설명 없음")
                product.specs.forEach { spec -> Text("${spec.name}  ${spec.value}") }
                if (shipping.description.isNotBlank()) Text(shipping.description)
                if (!product.optionsComplete) Text("옵션 정보 미확정", color = Carbon.Muted)
                Text(result.checkedAt, color = Carbon.Muted)
            }
        }
    }
}

@Composable private fun Availability(value: String) {
    if (value == "IN_STOCK") Icon(Icons.Outlined.CheckCircle, "판매 가능", tint = Carbon.Blue)
    else Text(availabilityLabel(value), color = Carbon.Muted, style = MaterialTheme.typography.labelMedium)
}
