package com.adbnoandroid

import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Aba Logs — o fluxo de coleta de logs igual ao do PC:
 *
 *  - **Logcat ao vivo**: stream do `logcat` do aparelho de destino (USB,
 *    Wi‑Fi ou este aparelho) com botões de parar, salvar e compartilhar.
 *  - **adb pull**: baixa um arquivo do aparelho (ex.: o log que você gerou).
 *  - **adb push**: envia um arquivo para o aparelho.
 */
@Composable
fun LogcatTab(
    selected: Target,
    onSelect: (Target) -> Unit,
    devices: List<AdbDeviceInfo>,
    usbDevices: List<AdbUsb.UsbDeviceInfo>,
    usbStatus: Map<String, DeviceStatus>,
    onSelectUsb: (AdbUsb.UsbDeviceInfo) -> Unit,
    rootGranted: Boolean,
    // estado do logcat (vive na tela para sobreviver à troca de abas)
    logText: String,
    isStreaming: Boolean,
    logStatus: String?,
    onStartStream: (String) -> Unit,
    onStopStream: () -> Unit,
    onClearLog: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var filter by remember { mutableStateOf("Tudo") }
    var extraFilter by remember { mutableStateOf("") }

    var lastSavedLog by remember { mutableStateOf<File?>(null) }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    var pullPath by remember { mutableStateOf("") }
    var pullBusy by remember { mutableStateOf(false) }
    var pullResult by remember { mutableStateOf<File?>(null) }
    var pullError by remember { mutableStateOf<String?>(null) }

    var pushFile by remember { mutableStateOf<File?>(null) }
    var pushPath by remember { mutableStateOf("") }
    var pushBusy by remember { mutableStateOf(false) }
    var pushProgress by remember { mutableStateOf(0f) }
    var pushError by remember { mutableStateOf<String?>(null) }

    fun logcatArgs(): String = buildString {
        append("logcat -b all")
        if (filter == "Erros") append(" *:E")
        val extra = extraFilter.trim()
        if (extra.isNotEmpty()) append(" ").append(extra)
    }

    fun saveLog(): Boolean {
        if (logText.isBlank()) return false
        val f = FileSaver.saveText(context, "logcat", logText, "txt")
        lastSavedLog = f
        savedMsg = "Log salvo em ${f.absolutePath}"
        return true
    }

    fun shareLog() {
        val f = lastSavedLog ?: run {
            if (!saveLog()) return
            lastSavedLog
        } ?: return
        FileSaver.shareFile(context, f)
    }

    fun doPull() {
        val path = pullPath.trim()
        if (path.isEmpty() || pullBusy) return
        pullBusy = true
        pullResult = null
        pullError = null
        scope.launch {
            try {
                val dest = File(FileSaver.saveDir(context), "pulled_${FileSaver.cleanName(path)}")
                when (val t = selected) {
                    is Target.Local -> {
                        val bytes = withContext(Dispatchers.IO) { RootShell.readBytesLocal(path) }
                        dest.writeBytes(bytes)
                    }
                    is Target.Remote -> withContext(Dispatchers.IO) {
                        AdbFiles.pullTcp(t.device.host, t.device.port, path, dest) { _, _ -> }
                    }
                    is Target.Usb -> {
                        val d = AdbUsb.deviceByName(context, t.info.deviceName)
                            ?: throw AdbException("Dispositivo USB desconectado.")
                        withContext(Dispatchers.IO) {
                            AdbFiles.pullUsb(d, path, dest) { _, _ -> }
                        }
                    }
                }
                pullResult = dest
            } catch (e: Exception) {
                pullError = e.message ?: "Falha ao baixar o arquivo."
            } finally {
                pullBusy = false
            }
        }
    }

    fun queryName(context: Context, uri: android.net.Uri): String {
        var name = "arquivo"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        } catch (_: Exception) {
        }
        return name
    }

    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val name = withContext(Dispatchers.IO) { queryName(context, uri) }
                val clean = FileSaver.cleanName(name)
                val tmp = File(context.cacheDir, "push_$clean")
                context.contentResolver.openInputStream(uri)?.use { ins ->
                    tmp.outputStream().use { os -> ins.copyTo(os) }
                } ?: throw AdbException("Não foi possível ler o arquivo escolhido.")
                pushFile = tmp
                if (pushPath.isBlank()) {
                    pushPath = "/sdcard/Download/$clean"
                }
                pushError = null
            } catch (e: Exception) {
                pushError = e.message ?: "Falha ao escolher o arquivo."
            }
        }
    }

    fun doPush() {
        val f = pushFile ?: return
        val path = pushPath.trim()
        if (path.isEmpty() || pushBusy) return
        pushBusy = true
        pushProgress = 0f
        pushError = null
        scope.launch {
            try {
                when (val t = selected) {
                    is Target.Local ->
                        throw AdbException(
                            "O arquivo já está neste aparelho — escolha um alvo USB ou Wi‑Fi."
                        )
                    is Target.Remote -> withContext(Dispatchers.IO) {
                        AdbFiles.pushTcp(t.device.host, t.device.port, f, path) { sent, total ->
                            if (total > 0) pushProgress = (sent.toFloat() / total).coerceIn(0f, 1f)
                        }
                    }
                    is Target.Usb -> {
                        val d = AdbUsb.deviceByName(context, t.info.deviceName)
                            ?: throw AdbException("Dispositivo USB desconectado.")
                        withContext(Dispatchers.IO) {
                            AdbFiles.pushUsb(d, f, path) { sent, total ->
                                if (total > 0) pushProgress = (sent.toFloat() / total).coerceIn(0f, 1f)
                            }
                        }
                    }
                }
                pushProgress = 1f
            } catch (e: Exception) {
                pushError = e.message ?: "Falha ao enviar o arquivo."
            } finally {
                pushBusy = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp)
    ) {
        // ================= Alvo =================
        Text(
            "Alvo (mesmo do Terminal)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selected is Target.Local,
                onClick = { onSelect(Target.Local) },
                label = { Text("📱 Este aparelho", fontSize = 12.sp) },
                colors = chipColors()
            )
            usbDevices.forEach { info ->
                FilterChip(
                    selected = selected is Target.Usb &&
                        (selected as Target.Usb).info.deviceName == info.deviceName,
                    onClick = { onSelectUsb(info) },
                    label = {
                        Row {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .background(
                                        statusColor(usbStatus[info.deviceName] ?: DeviceStatus.UNKNOWN),
                                        CircleShape
                                    )
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(info.label(), fontSize = 12.sp)
                        }
                    },
                    colors = chipColors()
                )
            }
            devices.forEach { device ->
                FilterChip(
                    selected = selected is Target.Remote &&
                        (selected as Target.Remote).device.address() == device.address(),
                    onClick = { onSelect(Target.Remote(device)) },
                    label = {
                        Row {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .background(statusColor(device.status), CircleShape)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(device.label(), fontSize = 12.sp)
                        }
                    },
                    colors = chipColors()
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // ================= Logcat ao vivo =================
        Text(
            "Logcat ao vivo (igual ao adb logcat do PC)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = filter == "Tudo",
                onClick = { filter = "Tudo" },
                label = { Text("Tudo", fontSize = 12.sp) },
                colors = chipColors()
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = filter == "Erros",
                onClick = { filter = "Erros" },
                label = { Text("Só erros", fontSize = 12.sp) },
                colors = chipColors()
            )
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = extraFilter,
                onValueChange = { extraFilter = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("filtro extra (ex.: Wifi:V)", fontSize = 12.sp)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = TerminalGreen,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                    cursorColor = TerminalGreen
                ),
                shape = RoundedCornerShape(10.dp)
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isStreaming) {
                Button(
                    onClick = { onStartStream(logcatArgs()) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TerminalGreen,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("▶ Iniciar logcat", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            } else {
                Button(
                    onClick = onStopStream,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TerminalRed,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("⏹ Parar", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClearLog, enabled = logText.isNotEmpty()) {
                Text("Limpar tela", fontSize = 12.sp)
            }
            TextButton(onClick = { saveLog() }, enabled = logText.isNotEmpty()) {
                Text("💾 Salvar", fontSize = 12.sp)
            }
            TextButton(onClick = { shareLog() }, enabled = logText.isNotEmpty()) {
                Text("📤 Compartilhar", fontSize = 12.sp)
            }
        }
        if (logStatus != null) {
            Text(
                logStatus.orEmpty(),
                fontSize = 12.sp,
                color = if (isStreaming) TerminalGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
        savedMsg?.let {
            Text(
                it,
                fontSize = 11.sp,
                color = TerminalAmber,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
        if (selected is Target.Local && !rootGranted) {
            Text(
                "⚠ Sem root neste aparelho: o logcat local só mostra os logs deste app. " +
                    "Para logs completos, use outro aparelho por USB/Wi‑Fi.",
                fontSize = 11.sp,
                color = TerminalAmber,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(6.dp))

        LogcatOutput(text = logText, streaming = isStreaming)
        Spacer(Modifier.height(12.dp))

        // ================= Arquivos (pull/push) =================
        Text(
            "Arquivos — o adb pull / adb push do PC",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))

        if (selected is Target.Local) {
            Text(
                "O alvo é este aparelho: os arquivos já estão aqui (abra num " +
                    "gerenciador de arquivos). Para pull/push de OUTRO aparelho, " +
                    "escolha o alvo USB ou Wi‑Fi acima.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        } else {
            OutlinedTextField(
                value = pullPath,
                onValueChange = {
                    pullPath = it
                    pullError = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "Baixar (adb pull): caminho no aparelho — ex.: /sdcard/Download/log.txt",
                        fontSize = 12.sp
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = TerminalGreen,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                    cursorColor = TerminalGreen
                ),
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { doPull() },
                    enabled = !pullBusy && pullPath.isNotBlank(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (pullBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = TerminalGreen
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("⬇ Baixar", fontSize = 13.sp)
                }
                pullResult?.let { f ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${f.name} (${formatSize(f.length())})",
                        fontSize = 12.sp,
                        color = TerminalGreen,
                        maxLines = 1
                    )
                    TextButton(onClick = { FileSaver.shareFile(context, f) }) {
                        Text("📤", fontSize = 13.sp)
                    }
                }
            }
            pullError?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = TerminalRed,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { openDoc.launch(arrayOf("*/*")) },
                    enabled = !pushBusy,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("📁 Escolher arquivo", fontSize = 13.sp)
                }
                pushFile?.let { f ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${f.name} (${formatSize(f.length())})",
                        fontSize = 12.sp,
                        color = TerminalGreen,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = pushPath,
                onValueChange = {
                    pushPath = it
                    pushError = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "Enviar (adb push): destino no aparelho — ex.: /sdcard/Download/app.apk",
                        fontSize = 12.sp
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = TerminalGreen,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                    cursorColor = TerminalGreen
                ),
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { doPush() },
                    enabled = !pushBusy && pushFile != null && pushPath.isNotBlank(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("⬆ Enviar", fontSize = 13.sp)
                }
                Spacer(Modifier.width(12.dp))
                LinearProgressIndicator(
                    progress = { pushProgress },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = TerminalGreen,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )
            }
            pushError?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = TerminalRed,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = TerminalGreen.copy(alpha = 0.2f),
    selectedLabelColor = TerminalGreen
)

/** Caixa de saída do logcat (monoespaçada, rola sozinha até o fim). */
@Composable
private fun LogcatOutput(text: String, streaming: Boolean) {
    val scroll = rememberScrollState()
    LaunchedEffect(text.length) {
        if (scroll.maxValue > 0) {
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp, max = 420.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ConsoleBg)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(10.dp)
        ) {
            Row(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (streaming) TerminalGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                            CircleShape
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (streaming) "LOGCAT • recebendo…" else "LOGCAT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (streaming) TerminalGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Spacer(Modifier.height(6.dp))
            if (text.isEmpty()) {
                Text(
                    "O log aparece aqui em tempo real.\nToque em “▶ Iniciar logcat” e reproduza o problema.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            } else {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TerminalText,
                    style = TextStyle(lineHeight = 14.sp),
                    softWrap = true
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024f / 1024f)
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024f)
    else -> "$bytes B"
}
