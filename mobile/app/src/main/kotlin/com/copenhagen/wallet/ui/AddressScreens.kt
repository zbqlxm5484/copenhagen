package com.copenhagen.wallet.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.copenhagen.wallet.*

@Composable fun AddressesScreen(app: AppModel) = Page {
    Heading("배송지")
    Data(app.account.addresses, app.busy, "배송지를 불러오지 못했어요.", { app.execute { app.account.loadAddresses() } }) { rows ->
        if (rows.isEmpty()) Notice("등록된 배송지가 없어요.")
        rows.sortedByDescending { it.isDefault }.forEach { address -> Tile {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(address.recipient, style = MaterialTheme.typography.titleMedium)
                if (address.isDefault) Text("기본", color = Carbon.Blue)
            }
            Text(address.phone)
            Text("[${address.postalCode}] ${address.address}\n${address.detail}")
            if (address.memo.isNotBlank()) Text(address.memo, color = Carbon.Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ app.execute { app.account.editAddress(address.id); app.show(Screen.D02, address.id) } }, enabled = !app.busy) { Text("수정") }
                TextButton({ app.execute { app.account.defaultAddress(address.id) } }, enabled = !app.busy && !address.isDefault) { Text("기본 배송지로 지정") }
            }
        } }
    }
    Action("배송지 추가", !app.busy && app.account.addresses.orEmpty().size < 20, "E22") {
        app.account.newAddress(); app.show(Screen.D02)
    }
}
@Composable fun AddressEditorScreen(app: AppModel) {
    var deleting by remember { mutableStateOf(false) }
    val account = app.account
    val form = account.form
    Page {
        Heading(if (account.addressId == null) "배송지 추가" else "배송지 수정")
        Field(form.recipient, { account.form = form.copy(recipient = it) }, "수령인", enabled = !app.busy, maxLength = 40)
        Field(form.phone, { account.form = form.copy(phone = it) }, "연락처", enabled = !app.busy, keyboard = KeyboardType.Phone, maxLength = 20)
        Secondary("주소 검색", !app.busy) { app.show(Screen.D03) }
        Field(form.postalCode, { account.form = form.copy(postalCode = it) }, "우편번호", enabled = !app.busy, keyboard = KeyboardType.Number, maxLength = 5)
        Field(form.address, { account.form = form.copy(address = it) }, "주소", enabled = !app.busy)
        Field(form.detail, { account.form = form.copy(detail = it) }, "상세주소", enabled = !app.busy)
        Field(form.memo, { account.form = form.copy(memo = it) }, "배송메모", enabled = !app.busy, maxLength = 100)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(account.makeDefault, { account.makeDefault = it }, enabled = !app.busy)
            Text("기본 배송지로 지정")
        }
        Action("저장", !app.busy && form.valid, "E27") {
            app.execute { account.saveAddress(); app.show(Screen.D01, replace = true) }
        }
        if (account.addressId != null) Secondary("배송지 삭제", !app.busy) { deleting = true }
    }
    if (deleting) Confirm("배송지를 삭제할까요?", "삭제", { deleting = false }) {
        deleting = false
        app.execute { account.deleteAddress(); app.show(Screen.D01, replace = true) }
    }
}

/** 우편번호 검색은 공개 Kakao embed API만 사용한다. 앱 인증 토큰·네이티브 브리지는 노출하지 않는다. */
@SuppressLint("SetJavaScriptEnabled")
@Composable fun PostalSearchScreen(app: AppModel) {
    var failed by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        if (failed) Column(Modifier.padding(24.dp)) {
            Notice("주소 검색에 연결하지 못했어요.")
            Secondary("다시 검색") { failed = false; revision++ }
        }
        key(revision) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context -> WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        if (request.isForMainFrame && uri.scheme == "copenhagen-postcode" && uri.host == "selected") {
                            val zip = uri.getQueryParameter("zip").orEmpty()
                            val address = uri.getQueryParameter("address").orEmpty()
                            if (zip.matches(Regex("[0-9]{5}")) && address.isNotBlank() && address.length <= 200) {
                                app.account.form = app.account.form.copy(postalCode = zip, address = address)
                                app.back()
                            }
                            return true
                        }
                        // 검색 iframe은 HTTPS만 허용하고 상위 페이지 이동은 차단한다.
                        return request.isForMainFrame || uri.scheme != "https"
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) failed = true
                    }
                }
                loadDataWithBaseURL("https://postcode.map.kakao.com/", postalHtml, "text/html", "UTF-8", null)
            } }, onReset = null, onRelease = { it.stopLoading(); it.destroy() })
        }
    }
}
private val postalHtml = """
<!doctype html><html lang="ko"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body,#postcode{margin:0;width:100%;height:100%}#error{padding:24px;font:16px sans-serif}</style>
<div id="postcode"></div><div id="error" hidden>주소 검색에 연결하지 못했어요. 뒤로 가서 다시 시도해 주세요.</div>
<script src="https://t1.kakaocdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js"
onerror="document.getElementById('error').hidden=false"></script><script>
if(window.kakao && kakao.Postcode) new kakao.Postcode({width:'100%',height:'100%',oncomplete:function(data){
var address=data.address;
if(data.userSelectedType==='R'){
 var extra=[]; if(data.bname && /[동로가]${'$'}/.test(data.bname)) extra.push(data.bname);
 if(data.buildingName && data.apartment==='Y') extra.push(data.buildingName);
 if(extra.length) address+=' ('+extra.join(', ')+')';
}
location.href='copenhagen-postcode://selected?zip='+encodeURIComponent(data.zonecode)+'&address='+encodeURIComponent(address);
}}).embed(document.getElementById('postcode'));
</script></html>
""".trimIndent()
