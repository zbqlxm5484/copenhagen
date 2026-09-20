package com.copenhagen.wallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.copenhagen.wallet.*
import com.copenhagen.wallet.data.*

@Composable fun WalletScreen(app: AppModel) = Page {
    Heading("나의 지갑")
    Section("등록된 카드", "카드 관리") { app.open(Screen.W02) }
    Data(app.cards.items, app.busy, "카드를 불러오지 못했어요.", { app.execute { app.cards.load() } }) { cards ->
        if (cards.isEmpty()) Notice("등록된 카드가 없어요.")
        cards.sortedByDescending { it.isDefault }.firstOrNull()?.let { CardTile(it) }
    }
    Secondary("새 카드 등록", !app.busy) { app.show(Screen.C01) }
    Section("승인 요청", "전체 보기") { app.open(Screen.P01) }
    Data(app.approvals.inbox, app.busy, "승인 요청을 불러오지 못했어요.", { app.execute { app.approvals.loadInbox() } }) { rows ->
        if (rows.isEmpty()) Notice("새로운 승인 요청이 없어요.")
        rows.take(3).forEach { value -> ApprovalTile(value) { app.openApproval(value.id) } }
    }
}
@Composable fun CardManagerScreen(app: AppModel) {
    var deleting by remember { mutableStateOf<Card?>(null) }
    Page {
        Heading("카드 관리")
        Data(app.cards.items, app.busy, "카드를 불러오지 못했어요.", { app.execute { app.cards.load() } }) { cards ->
            if (cards.isEmpty()) Notice("등록된 카드가 없어요.")
            cards.forEach { card ->
                Column {
                    CardTile(card)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton({ app.execute { app.cards.makeDefault(card.id) } }, enabled = !app.busy && card.active && !card.isDefault) { Text("기본 카드로 지정") }
                        TextButton({ deleting = card }, enabled = !app.busy) { Text("삭제", color = Carbon.Error) }
                    }
                }
            }
        }
        Action("카드 추가", !app.busy) { app.show(Screen.C01) }
    }
    deleting?.let { card -> Confirm("${card.label}\n삭제할까요?", "삭제", { deleting = null }) {
        deleting = null; app.execute { app.cards.delete(card.id) }
    } }
}
@Composable fun CardEntryScreen(app: AppModel) = Page {
    var number by remember(app.secretRevision) { mutableStateOf("") }
    var month by remember(app.secretRevision) { mutableStateOf("") }
    var year by remember(app.secretRevision) { mutableStateOf("") }
    var cvc by remember(app.secretRevision) { mutableStateOf("") }
    val enabled = !app.busy && BuildConfig.DEBUG && app.store.pg.isNotBlank()
    Heading("새 카드 등록")
    if (!enabled && !app.busy) Notice("카드 등록 서버를 확인해 주세요.")
    Field(number, { number = normalizeCardNumber(it) }, "카드 번호", secret = true, keyboard = KeyboardType.NumberPassword,
        maxLength = 23, enabled = enabled)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Field(month, { month = it.filter(Char::isDigit) }, "월 (MM)", Modifier.weight(1f), keyboard = KeyboardType.Number, maxLength = 2, enabled = enabled)
        Field(year, { year = it.filter(Char::isDigit) }, "연도 (YYYY)", Modifier.weight(1f), keyboard = KeyboardType.Number, maxLength = 4, enabled = enabled)
    }
    Field(cvc, { cvc = it.filter(Char::isDigit) }, "CVC", secret = true, keyboard = KeyboardType.NumberPassword, maxLength = 4, enabled = enabled)
    val valid = validCardNumber(number) && validExpiry(month.toIntOrNull() ?: 0, year.toIntOrNull() ?: 0) && validCvc(cvc)
    Action("다음", enabled && valid, "E18") {
        app.cards.prepare(number, month.toInt(), year.toInt(), cvc)
        number = ""; month = ""; year = ""; cvc = ""
        app.show(Screen.C02)
    }
}

private val termTitles = listOf("본인확인 개인정보 수집·이용 및 위탁", "본인확인 서비스 이용약관",
    "고유식별정보 처리 동의", "통신사 이용약관", "개인정보 제3자 제공 동의")

@Composable fun ConsentScreen(app: AppModel) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Int?>(null) }
    val terms = remember { termTitles.mapIndexed { index, _ ->
        runCatching { context.assets.open("terms/${index + 1}.txt").bufferedReader().use { it.readText() }.trim() }.getOrDefault("")
    } }
    // Mock 개발 흐름은 원래 약관 선택 UI만 유지한다. 법적 동의 증빙 API를 가정하지 않는다.
    val canReadAll = terms.all { it.isNotBlank() }
    val allowed = BuildConfig.DEBUG || canReadAll
    Page {
        Heading("약관 동의")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(app.cards.consents.size == 5, { checked -> app.cards.consents = if (checked) (0..4).toSet() else emptySet() }, enabled = !app.busy && allowed)
            Text("전체 동의", style = MaterialTheme.typography.titleMedium)
        }
        termTitles.forEachIndexed { index, title ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(index in app.cards.consents, { checked ->
                    app.cards.consents = if (checked) app.cards.consents + index else app.cards.consents - index
                }, enabled = !app.busy && allowed)
                TextButton({ selected = index }, Modifier.weight(1f)) { Text("[필수] $title", Modifier.fillMaxWidth()) }
            }
            HorizontalDivider(color = Carbon.Line)
        }
        Action("동의하고 등록", !app.busy && allowed && app.cards.consents.size == 5, "E20") {
            app.execute { app.cards.register(); app.show(Screen.C03, replace = true) }
        }
    }
    selected?.let { index -> AlertDialog(onDismissRequest = { selected = null },
        title = { Text(termTitles[index]) }, text = { Text(terms[index].ifBlank { "약관을 불러올 수 없어요." }, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ selected = null }) { Text("닫기") } }) }
}
@Composable fun CardResultScreen(app: AppModel) = Page {
    Heading("카드 등록 완료")
    app.cards.registered?.let { CardTile(it) }
    Action("계속", !app.busy, "E21") { app.finishCard() }
}
