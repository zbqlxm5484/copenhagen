package com.copenhagen.wallet

import android.app.Application
import androidx.compose.runtime.*
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.copenhagen.wallet.data.*
import com.copenhagen.wallet.features.*
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

// 기존 와이어프레임 ID를 그대로 검색할 수 있게 유지한다.
enum class Screen(val title: String, val focused: Boolean = true) {
    A01("로그인"), A02("로그인"), A03("회원가입"), A04("보안 확인"), A08("본인 확인"),
    A05("PIN 등록"), A06("PIN 확인"), A07("생체 인증"),
    W01("나의 지갑", false), W02("카드 관리", false), C01("카드 등록"), C02("약관 동의"), C03("카드 등록"),
    T01("장바구니"),
    G01("상품 탐색"), G02("상품 상세"),
    D01("배송지", false), D02("배송지 편집"), D03("주소 검색"),
    P01("승인 요청", false), P02("승인 요청"), P03("PIN 인증"), P04("생체 인증"),
    P05("승인 결과"), P06("인증 잠김"), P07("처리 확인"),
    H01("기록", false), H02("주문 상세", false), S01("설정", false), S02("PIN 변경"), S03("연결된 AI 서비스"), X01("서버 설정");
    companion object { val tabs = listOf(W01, D01, H01, S01) }
}
data class Route(val screen: Screen, val id: String? = null)

/** 앱 수명·탭·로그인만 소유한다. 카드/승인/Core 상태는 각각 features/에 있다. */
class AppModel(application: Application) : AndroidViewModel(application) {
    val store = Store(application)
    val api = Api(store)
    val cards = Cards(api)
    val approvals = Approvals(api)
    val account = Account(api)
    val connections = Connections { method, path -> api.core(method, path) }
    val cart = Cart(api::core)
    val catalog = Catalog(api::core)
    val products = ProductDetails(api::core)
    val biometrics = Biometrics()
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var signedIn by mutableStateOf(store.tokens != null); private set
    var notificationsRegistered by mutableStateOf(false); private set
    var notificationError by mutableStateOf<String?>(null); private set
    var loginId by mutableStateOf(store.loginId)
    var pin by mutableStateOf<PinStatus?>(null); private set
    var biometricRegistered by mutableStateOf(store.credentialId != null); private set
    var secretRevision by mutableIntStateOf(0); private set
    var tab by mutableStateOf(Screen.W01); private set
    var historyTab by mutableIntStateOf(0)
    private var foreground = true
    private var routes by mutableStateOf(listOf(Route(if (signedIn) Screen.A04 else Screen.A01)))
    private val tabRoutes = mutableMapOf<Screen, List<Route>>()
    private var firstPin = ""
    private var pinKey = ""
    private var incomingApproval: String? = null
    val route get() = routes.last()
    val canBack get() = routes.size > 1 || (!route.screen.focused && tab != Screen.W01)

    init { if (signedIn) execute { registerNotifications(); checkPin() } }

    fun execute(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SessionExpired) { resetSession(); error = "다시 로그인해 주세요." }
            catch (failure: Exception) {
                error = failure.userMessage()
                if (failure is ApiError && failure.status == 429 && failure.code.contains("PIN")) show(Screen.P06)
            } finally {
                busy = false
                if (!foreground) forgetSecrets()
                val id = incomingApproval
                if (signedIn && pin?.status == "ACTIVE" && id != null) {
                    incomingApproval = null; openApproval(id)
                }
            }
        }
    }
    fun show(screen: Screen, id: String? = null, replace: Boolean = false) {
        error = null
        val next = Route(screen, id)
        val previous = if (replace) routes.dropLast(1) else routes
        routes = if (previous.lastOrNull() == next) previous else previous + next
    }
    private fun root(screen: Screen = Screen.W01) { tab = screen; routes = listOf(Route(screen)); tabRoutes[screen] = routes }
    fun back() {
        if (busy) return
        error = null; secretRevision++
        if (route.screen in listOf(Screen.P05, Screen.P07)) { closeApproval(); return }
        if (route.screen == Screen.C02 && cards.awaitingPay) {
            cards.clearDraft(); root(); show(Screen.W02); execute { cards.load() }; return
        }
        when (route.screen) {
            Screen.C01, Screen.C02 -> cards.clearDraft()
            Screen.A06 -> firstPin = ""
            Screen.P02 -> approvals.close()
            Screen.P03, Screen.P04, Screen.P07 -> approvals.clearAttempt()
            Screen.G02 -> products.clear()
            else -> Unit
        }
        if (route.screen == Screen.A06) { show(Screen.A05, replace = true); return }
        if (route.screen == Screen.C02) { show(Screen.C01, replace = true); return }
        if (routes.size > 1) routes = routes.dropLast(1)
        else if (tab != Screen.W01 && !route.screen.focused) { selectTab(Screen.W01); return }
        if (route.screen == Screen.P02) execute { refresh() }
    }
    fun selectTab(screen: Screen) {
        if (busy) return
        tabRoutes[tab] = routes
        tab = screen; routes = tabRoutes[screen] ?: listOf(Route(screen))
        execute { refresh() }
    }
    suspend fun refresh() {
        when (route.screen) {
            Screen.W01 -> { cards.load(); approvals.loadInbox() }
            Screen.W02 -> cards.load()
            Screen.P01 -> approvals.loadInbox()
            Screen.P02 -> approvals.open(requireNotNull(approvals.pendingId))
            Screen.P05, Screen.P07 -> approvals.reconcile()
            Screen.D01 -> account.loadAddresses()
            Screen.H01 -> if (historyTab == 0) approvals.loadHistory() else account.loadOrders()
            Screen.H02 -> account.openOrder(requireNotNull(route.id))
            Screen.S01 -> { pin = api.pay("GET", "/users/me/payment-pin").read(); account.loadProfile() }
            Screen.S03 -> connections.load()
            Screen.T01 -> cart.load()
            Screen.G01 -> catalog.search(catalog.page ?: 1)
            Screen.G02 -> products.reload()
            Screen.A04, Screen.P06 -> checkPin()
            else -> Unit
        }
    }
    fun open(screen: Screen) { if (!busy) { show(screen); execute { refresh() } } }
    fun openProduct(productId: String, merchantId: String) = execute {
        show(Screen.G02)
        products.load(ProductSelection(productId, merchantId))
    }
    fun login(password: String, reauthenticate: Boolean = false) = execute {
        require(password.isNotBlank()) { "비밀번호를 입력해 주세요." }
        if (signedIn && loginId != store.loginId) {
            val result = closeSession()
            if (result.warning != null) { error = result.warning; return@execute }
        }
        if (loginId != store.loginId) { biometrics.clear(); store.credentialId = null; biometricRegistered = false }
        api.login(loginId, password)
        signedIn = true
        show(Screen.A04, replace = true)
        registerNotifications()
        checkPin(freshLogin = reauthenticate)
    }
    suspend fun registerNotifications() {
        if (!signedIn) return
        notificationsRegistered = false; notificationError = null
        try {
            check(FirebaseApp.getApps(getApplication()).isNotEmpty())
            val fid = withTimeoutOrNull(10_000) {
                FirebaseMessaging.getInstance().register().await()
                FirebaseInstallations.getInstance().id.await()
            } ?: error("Firebase registration timed out")
            api.pay("PUT", "/notification-installations", buildJsonObject { put("fid", fid) })
            notificationsRegistered = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (expired: SessionExpired) { throw expired }
        catch (_: Exception) { notificationError = "알림 연결에 실패했어요. 다시 연결해 주세요." }
    }
    suspend fun checkPin(freshLogin: Boolean = false) {
        val status = api.pay("GET", "/users/me/payment-pin").read<PinStatus>()
        pin = status
        when (status.status) {
            "ACTIVE" -> enterHome()
            "NOT_REGISTERED" -> show(Screen.A05, replace = true)
            "LOCKED" -> show(Screen.P06, replace = true)
            "REVOKED" -> show(if (freshLogin) Screen.A05 else Screen.A08, replace = true)
            else -> throw IllegalStateException("PIN 상태를 확인할 수 없어요.")
        }
    }
    fun setFirstPin(value: String) {
        if (!validPin(value)) { error = "PIN 6자리를 입력해 주세요."; return }
        firstPin = value; pinKey = newKey(); show(Screen.A06)
    }
    fun confirmPin(value: String) = execute {
        require(validPin(value) && value == firstPin) { "PIN이 일치하지 않아요." }
        api.pay("POST", "/users/me/payment-pin", buildJsonObject { put("pin", value) }, pinKey)
        firstPin = ""; pinKey = ""; pin = PinStatus(true, "ACTIVE")
        show(Screen.A07, replace = true)
    }
    fun changePin(current: String, next: String, key: String) = execute {
        require(validPin(current) && validPin(next) && current != next) { "현재 PIN과 다른 6자리 PIN을 입력해 주세요." }
        api.pay("PUT", "/users/me/payment-pin", buildJsonObject { put("currentPin", current); put("newPin", next) }, key)
        secretRevision++; root(Screen.S01)
    }
    fun disableBiometric() {
        biometrics.clear(); store.credentialId = null; biometricRegistered = false
    }
    fun enroll(activity: FragmentActivity) = execute {
        val setup = route.screen == Screen.A07
        biometrics.enroll(activity, api, store)
        biometricRegistered = true
        if (setup) enterHome()
    }
    suspend fun enterHome() {
        root()
        val pending = approvals.pendingId
        if (pending != null) { approvals.open(pending); cards.load(); show(Screen.P02, pending) }
        else refresh()
    }
    fun finishCard() = execute {
        cards.load()
        val pending = approvals.pendingId
        if (pending != null) {
            val card = requireNotNull(cards.registered)
            approvals.open(pending)
            val request = requireNotNull(approvals.current)
            if (request.actionable && !request.expired()) approvals.selectCard(card.id)
            root(); show(Screen.P02, pending)
        } else root()
    }
    fun openApproval(id: String) = execute {
        approvals.open(id); cards.load(); show(Screen.P02, id)
    }
    fun beginApproval() = execute {
        val request = requireNotNull(approvals.current)
        approvals.open(request.id)
        val latest = requireNotNull(approvals.current)
        require(latest.actionable && !latest.expired()) { "승인 요청 상태를 다시 확인해 주세요." }
        require(!latest.selectedPaymentMethodId.isNullOrBlank()) { "결제 카드를 선택해 주세요." }
        val useBiometric = latest.requiredAction == "BIOMETRIC" && latest.biometricChallenge != null && biometricRegistered
        show(if (useBiometric) Screen.P04 else Screen.P03)
    }
    fun approvePin(value: String) = execute { submitApproval(pinApprovalBody(value)) }
    fun approveBiometric(activity: FragmentActivity) = execute {
        try {
            val challenge = requireNotNull(approvals.current?.biometricChallenge)
            val signature = biometrics.sign(activity, challenge)
            submitApproval(biometricApprovalBody(challenge.challengeId, signature))
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (failure is android.security.keystore.KeyPermanentlyInvalidatedException) disableBiometric()
            if (!approvals.uncertain) show(Screen.P03, replace = true)
            throw failure
        }
    }
    private suspend fun submitApproval(body: JsonObject) {
        try {
            val status = approvals.approve(body)
            show(if (status == "PENDING") Screen.P03 else Screen.P05, replace = true)
        } catch (failure: Exception) {
            if (approvals.uncertain) show(Screen.P07, replace = true)
            throw failure
        } finally { secretRevision++ }
    }
    fun closeApproval(toHistory: Boolean = false) {
        approvals.close()
        root(if (toHistory) Screen.H01 else Screen.W01)
        execute { refresh() }
    }
    fun saveHosts(core: String, pay: String, pg: String) = execute {
        val coreUrl = normalizeHost(core, BuildConfig.DEBUG)
        val payUrl = normalizeHost(pay, BuildConfig.DEBUG)
        val pgUrl = if (pg.isBlank()) "" else normalizeHost(pg, BuildConfig.DEBUG)
        val result = if (signedIn) closeSession() else { resetSession(); null }
        store.saveHosts(coreUrl, payUrl, pgUrl)
        error = result?.warning
    }
    fun logout() = execute { error = closeSession().warning }
    private suspend fun closeSession() = endSession(
        disconnect = {
            check(FirebaseApp.getApps(getApplication()).isNotEmpty())
            val fid = FirebaseInstallations.getInstance().id.await()
            require(fid.isNotBlank())
            api.pay("DELETE", "/notification-installations/$fid")
        },
        revoke = { api.logout() },
        clear = ::resetSession
    )
    private fun resetSession() {
        store.clearSession(); biometrics.clear(); cards.clear(); approvals.clear(); account.clear()
        connections.clear()
        cart.clear()
        catalog.clear()
        products.clear()
        signedIn = false; pin = null; biometricRegistered = false; firstPin = ""; pinKey = ""
        notificationsRegistered = false; notificationError = null
        incomingApproval = null; tabRoutes.clear(); routes = listOf(Route(Screen.A01)); secretRevision++
    }
    fun acceptApprovalLink(id: String) {
        if (!validId(id)) return
        if (busy) incomingApproval = id
        else if (signedIn && pin?.status == "ACTIVE") openApproval(id)
        else approvals.pendingId = id
    }
    fun foregrounded() { foreground = true }
    fun backgrounded() { foreground = false; forgetSecrets() }
    private fun forgetSecrets() {
        secretRevision++; firstPin = ""
        if (!busy) {
            cards.clearDraft(); approvals.clearAttempt()
            when (route.screen) {
                Screen.C02 -> show(Screen.C01, replace = true)
                Screen.A06 -> show(Screen.A05, replace = true)
                Screen.P03, Screen.P04 -> show(Screen.P07, replace = true)
                else -> Unit
            }
        }
    }
    override fun onCleared() { cards.clearDraft(); approvals.clearAttempt(); firstPin = "" }
}
