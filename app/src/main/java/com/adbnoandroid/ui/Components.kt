package com.adbnoandroid

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Cartão padrão do app (superfície arredondada com título opcional). */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    trailing?.invoke()
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

/** Etiqueta pequena e colorida (status, modo, protocolo…). */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (filled) color else color.copy(alpha = 0.14f),
        border = if (filled) null else androidx.compose.foundation.BorderStroke(
            1.dp, color.copy(alpha = 0.45f)
        )
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (filled) Color.White else color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Bolinha de status. */
@Composable
fun StatusDot(status: DeviceStatus, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Box(
        modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun statusColor(status: DeviceStatus): Color {
    val c = LocalAdbColors.current
    return when (status) {
        DeviceStatus.ONLINE -> c.success
        DeviceStatus.RECOVERY -> c.warning
        DeviceStatus.SIDELOAD -> c.accent
        DeviceStatus.FASTBOOT -> c.info
        DeviceStatus.NEEDS_AUTH, DeviceStatus.UNAUTHORIZED -> c.warning
        DeviceStatus.CHECKING -> c.info
        DeviceStatus.ERROR -> c.error
        DeviceStatus.OFFLINE -> c.error
        DeviceStatus.UNKNOWN -> LocalContentColor.current
    }
}

fun statusLabel(status: DeviceStatus): String = when (status) {
    DeviceStatus.ONLINE -> "online"
    DeviceStatus.RECOVERY -> "recovery"
    DeviceStatus.SIDELOAD -> "sideload"
    DeviceStatus.FASTBOOT -> "fastboot"
    DeviceStatus.NEEDS_AUTH -> "aguardando autorização"
    DeviceStatus.UNAUTHORIZED -> "não autorizado"
    DeviceStatus.CHECKING -> "verificando…"
    DeviceStatus.ERROR -> "erro"
    DeviceStatus.OFFLINE -> "offline"
    DeviceStatus.UNKNOWN -> "desconhecido"
}

@Composable
fun modeColor(mode: DeviceMode): Color {
    val c = LocalAdbColors.current
    return when (mode) {
        DeviceMode.ADB -> c.success
        DeviceMode.RECOVERY -> c.warning
        DeviceMode.SIDELOAD -> c.accent
        DeviceMode.FASTBOOT -> c.info
        DeviceMode.OFFLINE -> c.error
        DeviceMode.UNKNOWN -> LocalContentColor.current
    }
}

/** Aviso importante (âmbar) ou erro (vermelho). */
@Composable
fun NoticeBox(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.WARNING
) {
    val c = LocalAdbColors.current
    val color = when (tone) {
        NoticeTone.WARNING -> c.warning
        NoticeTone.ERROR -> c.error
        NoticeTone.INFO -> c.info
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.10f)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (tone == NoticeTone.ERROR) Icons.Filled.Warning else Icons.Filled.Info,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

enum class NoticeTone { WARNING, ERROR, INFO }

/**
 * Console monoespaçado com cores por tipo de linha:
 * vermelho = erro, âmbar = aviso, verde = sucesso, cinza = resto.
 * O texto é selecionável (copiar trechos) e rolável.
 */
@Composable
fun ConsoleView(
    text: String,
    modifier: Modifier = Modifier,
    showPrompt: Boolean = false,
    maxLines: Int = Int.MAX_VALUE
) {
    val c = LocalAdbColors.current
    val annotated = remember(text) { colorizeConsole(text, c) }
    val scroll = rememberScrollState()
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(c.consoleBg)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
    ) {
        SelectionContainer {
            Column(
                Modifier
                    .verticalScroll(scroll)
                    .padding(10.dp)
            ) {
                if (text.isBlank()) {
                    Text(
                        if (showPrompt) "Digite um comando e toque em Executar." else "Sem saída.",
                        fontFamily = c.mono,
                        fontSize = 12.sp,
                        color = c.consoleDim
                    )
                } else {
                    Text(
                        annotated,
                        fontFamily = c.mono,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }
}

/** Pinta linhas do console por heurística de conteúdo. */
fun colorizeConsole(text: String, c: AdbColors): AnnotatedString {
    val builder = StringBuilder(text.length + 32)
    return buildAnnotatedString {
        text.lines().forEachIndexed { index, line ->
            val trimmed = line.trimStart()
            val style = when {
                trimmed.startsWith("✗") || trimmed.startsWith("FAIL") ||
                    trimmed.contains("[stderr]") ||
                    trimmed.contains("Error", ignoreCase = true) &&
                    (trimmed.contains("Exception") || trimmed.startsWith("E/")) ->
                    SpanStyle(color = c.consoleError)
                trimmed.startsWith("⚠") || trimmed.startsWith("WARNING") ||
                    trimmed.startsWith("W/") || trimmed.startsWith("$ ") ->
                    SpanStyle(color = c.consoleWarning)
                trimmed.startsWith("✓") || trimmed.startsWith("Success") ||
                    trimmed.startsWith("OKAY") || trimmed.startsWith("I/") ->
                    SpanStyle(color = c.consolePrompt)
                trimmed.startsWith("adb ") || trimmed.startsWith("▸") ->
                    SpanStyle(color = c.consolePrompt, fontWeight = FontWeight.Bold)
                else -> SpanStyle(color = c.consoleText)
            }
            withStyle(style) { append(line) }
            if (index < text.lines().size - 1) append('\n')
            builder.append(line)
        }
    }
}

/**
 * Barra de seleção de alvo (chips roláveis horizontalmente).
 * Mostra só os alvos possíveis e marca visualmente o escolhido.
 */
@Composable
fun TargetSelector(
    targets: List<DeviceTarget>,
    selected: DeviceTarget?,
    onSelect: (DeviceTarget) -> Unit,
    modifier: Modifier = Modifier,
    statusOf: (DeviceTarget) -> DeviceStatus = { DeviceStatus.UNKNOWN },
    emptyText: String = "Nenhum aparelho conectado"
) {
    if (targets.isEmpty()) {
        Text(
            emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
        return
    }
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        targets.forEach { target ->
            val isSelected = selected?.key() == target.key()
            val status = statusOf(target)
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(target) },
                leadingIcon = {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor(status))
                    )
                },
                label = {
                    val icon = when (target) {
                        is DeviceTarget.Usb -> "🔌"
                        is DeviceTarget.Net -> "📶"
                        is DeviceTarget.Fastboot -> "⚡"
                    }
                    Text("$icon ${shortTargetLabel(target)}", fontSize = 12.sp)
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

fun shortTargetLabel(target: DeviceTarget): String = when (target) {
    is DeviceTarget.Usb -> target.info.label()
    is DeviceTarget.Net -> target.info.title()
    is DeviceTarget.Fastboot -> target.info.label()
}

/**
 * Diálogo de confirmação para operações destrutivas.
 * Sempre mostra **qual aparelho** será afetado.
 */
@Composable
fun ConfirmDangerDialog(
    title: String,
    targetLabel: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    extraConfirmWord: String? = null,
    confirmEnabled: Boolean = true
) {
    val c = LocalAdbColors.current
    var typed by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf("")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = c.warning) },
        title = { Text(title) },
        text = {
            Column {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Aparelho: ", style = MaterialTheme.typography.labelMedium)
                        Text(
                            targetLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (extraConfirmWord != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Para confirmar, digite \"$extraConfirmWord\":",
                        style = MaterialTheme.typography.bodySmall
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled &&
                    (extraConfirmWord == null || typed.trim() == extraConfirmWord),
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.error,
                    contentColor = Color.White
                )
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

/** Diálogo simples de informação/erro com texto selecionável. */
@Composable
fun OutputDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    tone: NoticeTone = NoticeTone.INFO
) {
    val c = LocalAdbColors.current
    val color = when (tone) {
        NoticeTone.ERROR -> c.error
        NoticeTone.WARNING -> c.warning
        NoticeTone.INFO -> c.info
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (tone == NoticeTone.ERROR) Icons.Filled.Warning else Icons.Filled.Info,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            ConsoleView(
                text = text.ifBlank { "(sem saída)" },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}

/** Linha rótulo/valor compacta. */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    mono: Boolean = false
) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp)
        )
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Barra de progresso com texto e porcentagem. */
@Composable
fun ProgressBlock(
    label: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    detail: String = "",
    indeterminate: Boolean = false,
    tone: Color = MaterialTheme.colorScheme.primary
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            if (!indeterminate) {
                Text(
                    "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = tone
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        if (indeterminate) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = tone
            )
        } else {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = tone
            )
        }
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Formata tamanhos de arquivo. */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

/** Formata velocidade (bytes/s). */
fun formatSpeed(bytesPerSec: Double): String = when {
    bytesPerSec < 1024 -> String.format("%.0f B/s", bytesPerSec)
    bytesPerSec < 1024 * 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024)
    else -> String.format("%.1f MB/s", bytesPerSec / (1024 * 1024))
}

/** Texto vazio com dica. */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Botão de ação compacto e consistente. */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
    icon: (@Composable () -> Unit)? = null
) {
    val c = LocalAdbColors.current
    val colors = when {
        danger -> ButtonDefaults.buttonColors(
            containerColor = c.error,
            contentColor = Color.White
        )
        primary -> ButtonDefaults.buttonColors()
        else -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    }
    if (primary || danger) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            colors = colors,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 14.dp, vertical = 8.dp
            )
        ) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 14.dp, vertical = 8.dp
            )
        ) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Divisor com espaçamento. */
@Composable
fun VSpace(space: Int = 10) {
    Spacer(Modifier.height(space.dp))
}

@Composable
fun HSpace(space: Int = 8) {
    Spacer(Modifier.width(space.dp))
}

/** Linha de chips de opções (ex.: filtros de logcat). */
@Composable
fun ChipRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

/**
 * Copia [text] para a área de transferência.
 *
 * Usa a ClipboardManager do próprio Android em vez de `LocalClipboard` do
 * Compose: aquela API só existe a partir do Compose UI 1.7 (BOM 2024.09+) e
 * este projeto usa a BOM 2024.06.00, onde ela não compila.
 */
fun copyToClipboard(context: Context, label: String, text: String) {
    try {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return
        manager.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    } catch (_: Exception) {
        // Área de transferência indisponível: não é motivo para quebrar a tela.
    }
}
