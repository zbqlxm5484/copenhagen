package com.copenhagen.wallet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.fragment.app.FragmentActivity
import com.copenhagen.wallet.*
import com.copenhagen.wallet.data.*

@Composable fun AuthScreen(app: AppModel, activity: FragmentActivity) = Page {
    val screen = app.route.screen
    var password by remember(app.secretRevision, screen) { mutableStateOf("") }
    when (screen) {
        Screen.A01 -> {
            Heading("반가워요")
            Field(app.loginId, { app.loginId = it }, "아이디", enabled = !app.busy, maxLength = 20)
            Action("다음", !app.busy && validLoginId(app.loginId), "E01") { app.show(Screen.A02) }
            Secondary("회원가입", !app.busy) { app.show(Screen.A03) }
        }
        Screen.A02, Screen.A08 -> {
            Heading(if (screen == Screen.A08) "본인 확인" else "로그인")
            Text(app.loginId, color = Carbon.Muted)
            Field(password, { password = it }, "비밀번호", secret = true, keyboard = KeyboardType.Password, enabled = !app.busy, maxLength = 20)
            Action(if (screen == Screen.A08) "확인" else "로그인", !app.busy && password.isNotBlank(), "E03") {
                val value = password; password = ""; app.login(value, reauthenticate = screen == Screen.A08)
            }
        }
        Screen.A03 -> Signup(app)
        Screen.A04 -> {
            Heading("보안 확인")
            Action("다시 확인", !app.busy) { app.execute { app.checkPin() } }
            Secondary("로그아웃", !app.busy) { app.logout() }
        }
        Screen.A05, Screen.A06 -> {
            Heading(if (screen == Screen.A05) "결제 PIN 등록" else "PIN 다시 입력")
            Field(password, { password = it.filter(Char::isDigit) }, "PIN 6자리", secret = true,
                keyboard = KeyboardType.NumberPassword, enabled = !app.busy, maxLength = 6)
            Action(if (screen == Screen.A05) "다음" else "등록", !app.busy && validPin(password)) {
                val value = password; password = ""
                if (screen == Screen.A05) app.setFirstPin(value) else app.confirmPin(value)
            }
        }
        Screen.A07 -> {
            Heading("생체 인증")
            Action(if (app.biometricRegistered) "등록됨" else "등록", !app.busy && !app.biometricRegistered && app.biometrics.available(activity)) {
                app.enroll(activity)
            }
            Secondary("나중에", !app.busy) { app.execute { app.enterHome() } }
        }
        Screen.P06 -> {
            Heading("인증이 잠겼어요")
            Action("다시 확인", !app.busy) { app.execute { app.checkPin() } }
            Secondary("로그아웃", !app.busy) { app.logout() }
        }
        else -> Unit
    }
}
@Composable private fun Signup(app: AppModel) {
    var id by remember { mutableStateOf("") }
    var password by remember(app.secretRevision) { mutableStateOf("") }
    var confirmation by remember(app.secretRevision) { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    Heading("회원가입")
    Field(id, { id = it }, "아이디", maxLength = 20, enabled = !app.busy)
    Field(password, { password = it }, "비밀번호", secret = true, keyboard = KeyboardType.Password, maxLength = 20, enabled = !app.busy)
    Field(confirmation, { confirmation = it }, "비밀번호 확인", secret = true, keyboard = KeyboardType.Password, maxLength = 20, enabled = !app.busy)
    Field(email, { email = it }, "이메일", keyboard = KeyboardType.Email, maxLength = 254, enabled = !app.busy)
    Field(name, { name = it }, "이름", maxLength = 20, enabled = !app.busy)
    val validation = passwordError(password, id)
    if (password.isNotBlank() && validation != null) Text(validation, color = Carbon.Error)
    Action("가입", !app.busy && validLoginId(id) && validEmail(email) && name.isNotBlank() &&
        validation == null && password == confirmation, "E04") {
        app.execute {
            app.api.signup(id, password, email.trim(), name.trim())
            password = ""; confirmation = ""; app.loginId = id
            app.show(Screen.A01, replace = true)
        }
    }
}
@Composable fun ChangePinScreen(app: AppModel) = Page {
    var current by remember(app.secretRevision) { mutableStateOf("") }
    var next by remember(app.secretRevision) { mutableStateOf("") }
    var confirmation by remember(app.secretRevision) { mutableStateOf("") }
    var key by remember { mutableStateOf(newKey()) }
    Heading("PIN 변경")
    Field(current, { current = it.filter(Char::isDigit); key = newKey() }, "현재 PIN", secret = true,
        keyboard = KeyboardType.NumberPassword, maxLength = 6, enabled = !app.busy)
    Field(next, { next = it.filter(Char::isDigit); key = newKey() }, "새 PIN", secret = true,
        keyboard = KeyboardType.NumberPassword, maxLength = 6, enabled = !app.busy)
    Field(confirmation, { confirmation = it.filter(Char::isDigit) }, "새 PIN 확인", secret = true,
        keyboard = KeyboardType.NumberPassword, maxLength = 6, enabled = !app.busy)
    Action("변경", !app.busy && validPin(current) && validPin(next) && next == confirmation && current != next) {
        app.changePin(current, next, key)
    }
}
