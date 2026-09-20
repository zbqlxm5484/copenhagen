package com.copenhagen.wallet.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.copenhagen.wallet.data.*

object Carbon {
    val Blue = Color(0xFF0F62FE)
    val Ink = Color(0xFF161616)
    val Muted = Color(0xFF525252)
    val Layer = Color(0xFFF4F4F4)
    val Line = Color(0xFFE0E0E0)
    val Error = Color(0xFFDA1E28)
}
@Composable fun CopenhagenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Carbon.Blue, onPrimary = Color.White,
            surface = Color.White, background = Color.White, onSurface = Carbon.Ink,
            surfaceVariant = Carbon.Layer, onSurfaceVariant = Carbon.Muted, outline = Carbon.Line, error = Carbon.Error),
        shapes = Shapes(RoundedCornerShape(0.dp), RoundedCornerShape(0.dp), RoundedCornerShape(0.dp), RoundedCornerShape(0.dp), RoundedCornerShape(0.dp)),
        typography = Typography(
            headlineLarge = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Light),
            bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = .16.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = .16.sp)
        ), content = content
    )
}
@Composable fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
}
@Composable fun Heading(text: String) { Text(text, style = MaterialTheme.typography.headlineLarge) }
@Composable fun Section(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}
@Composable fun Action(text: String, enabled: Boolean = true, tag: String = "", onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag), enabled = enabled, shape = RectangleShape) {
        Text(text)
    }
}
@Composable fun Secondary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled, shape = RectangleShape) { Text(text) }
}
@Composable fun Field(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true, maxLength: Int = 200, multiline: Boolean = false
) {
    TextField(value, { onChange(it.take(maxLength)) }, modifier.fillMaxWidth(),
        label = { Text(label) }, enabled = enabled, singleLine = !multiline, shape = RectangleShape,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = !secret),
        colors = TextFieldDefaults.colors(focusedContainerColor = Carbon.Layer,
            unfocusedContainerColor = Carbon.Layer, disabledContainerColor = Carbon.Layer))
}
@Composable fun Notice(text: String) { Text(text, Modifier.fillMaxWidth().background(Carbon.Layer).padding(16.dp), color = Carbon.Muted) }
@Composable fun Tile(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, Carbon.Line)
        .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
@Composable fun CardTile(card: Card) = Tile {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(card.brand ?: "카드", style = MaterialTheme.typography.titleMedium)
        if (card.isDefault) Text("기본", color = Carbon.Blue)
    }
    Text("••••  ${card.last4 ?: "----"}", style = MaterialTheme.typography.headlineSmall)
    if (card.expiryMonth != null && card.expiryYear != null)
        Text("${card.expiryMonth.toString().padStart(2, '0')} / ${card.expiryYear}", color = Carbon.Muted)
    if (!card.active) Text(statusLabel(card.status), color = Carbon.Error)
}
@Composable fun ApprovalTile(value: Approval, click: () -> Unit) = Tile(click) {
    Text(value.merchantName, style = MaterialTheme.typography.titleMedium)
    Text(value.summary.joinToString { it.name }, color = Carbon.Muted)
    Text(money(value.amountMinor), style = MaterialTheme.typography.headlineSmall)
    Text(statusLabel(value.status), color = Carbon.Blue)
}
@Composable fun <T> Data(value: T?, busy: Boolean, empty: String, retry: () -> Unit, content: @Composable (T) -> Unit) {
    when {
        value != null -> content(value)
        busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
        else -> { Notice(empty); Secondary("다시 불러오기", onClick = retry) }
    }
}
@Composable fun Confirm(title: String, confirm: String, dismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) },
        confirmButton = { TextButton(onConfirm) { Text(confirm, color = Carbon.Error) } },
        dismissButton = { TextButton(dismiss) { Text("취소") } }, shape = RectangleShape)
}
