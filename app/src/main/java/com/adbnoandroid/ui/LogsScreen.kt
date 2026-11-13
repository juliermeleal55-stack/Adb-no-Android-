package com.adbnoandroid

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.text.SpanStyle

private const val MAX_LOG_CHARS = 400_000

/**
 * Aba Logs — central de diagnóstico:
 *
 * - **Logcat ao vivo** com filtro por prioridade (V/D/I/W/E/F), por tag
 *   (`Tag:V`) e por processo (`--pid`), com parar, limpar, salvar e compartilhar.
 * - **Coletas** (dumpsys, dmesg/kmsg, propostas de boot, partições, hardware).
 * - **Bugreport** com progresso e download do ZIP.
 * - Exportação em TXT.
 */
@Composable
fun LogsScreen(state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAdbColors.current

    var logText by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<StreamSession?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var level by remember { mutableStateOf("Tudo") }
    var tagFilter by remember { mutableStateOf("") }
    var processFilter by remember { mutableStateOf("") }
    var usePidFilter by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var collected by remember { mutableStateOf<String?>(null) }
    var collectedTitle by remember { mutableStateOf("") }
    var bugreportProgress by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var autoScroll by remember { mutableStateOf(true) }

    val target = state.selectedTarget
    val mode = state.selectedMode()
    val canStream = target != null && (mode == DeviceMode.ADB || mode == DeviceMode.RECOVERY)
    val scroll = rememberScrollState()

    fun stopStream() {
        session?.stop()
        session = null
        streaming = false
        status = "Logcat parado."
    }

    fun startStream() {
        val t = state.selectedTarget ?: return
        if (streaming) return
        logText = ""
        status = "Conectando ao logcat do aparelho…"
        streaming = true

        val args = buildString {
            append("-b all")
            if (level == "Tudo") append(" *:V")
            else append(" *:")
                .append(
                    when (level) {
                        "Verbose" -> "V"
                        "Debug" -> "D"
                        "Info" -> "I"
                        "Avisos" -> "W"
                        "Erros" -> "E"
                        "Fatal" -> "F"
                        else -> "V"
                    }
                )
            val tag = tagFilter.trim()
            if (tag.isNotEmpty()) append(" $tag:V")
            val pid = processFilter.trim()
            if (usePidFilter && pid.isNotEmpty()) append(" --pid $pid")
        }
        status = "Logcat: logcat $args"

        val callbacks = StreamCallbacks(
            onChunk = { chunk ->
                val next = logText + chunk
                logText = if (next.length > MAX_LOG_CHARS) {
                    next.substring(next.length - MAX_LOG_CHARS)
                } else next
            },
            onError = { err ->
                status = "Erro: $err"
                streaming = false
            },
            onDone = { err ->
                streaming = false
                session = null
                if (err != null) status = "Logcat encerrado: $err"
                else if (status?.startsWith("Parando") != true) status = "Logcat encerrado."
            }
        )
        // thread de fundo: logcat() já dispara uma thread própria
        session = AdbClient.logcat(t, args, callbacks)
    }

    LaunchedEffect(logText.length) {
        if (autoScroll && streaming) {
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Para o stream ao sair da tela para não deixar conexão pendurada.
            session?.stop()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        TargetSelector(
            targets = state.allTargets(includeFastboot = false),
            selected = target,
            onSelect = { state.select(it) },
            statusOf = { state.statusOf(it) },
            emptyText = "Conecte um aparelho para coletar logs"
        )
        Spacer(Modifier.height(8.dp))

        // ------------------------------------------------ filtros
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("Tudo", "Debug", "Info", "Avisos", "Erros", "Fatal").forEach { option ->
                FilterChip(
                    selected = level == option,
                    onClick = { level = option },
                    label = { Text(option, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = tagFilter,
                onValueChange = { tagFilter = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Tag (ex.: Wifi)", fontSize = 11.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
            )
            OutlinedTextField(
                value = processFilter,
                onValueChange = { processFilter = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("PID ou pacote", fontSize = 11.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
            )
        }

        // ------------------------------------------------ controles
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!streaming) {
                Button(
                    onClick = { startStream() },
                    enabled = canStream,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Iniciar logcat", fontSize = 12.sp)
                }
            } else {
                Button(
                    onClick = { stopStream() },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = c.error,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Filled.Clear, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Parar", fontSize = 12.sp)
                }
            }
            TextButton(onClick = { logText = "" }) {
                Text("Limpar", fontSize = 12.sp)
            }
            TextButton(onClick = {
                if (logText.isNotBlank()) {
                    val f = FileSaver.saveText(context, "logcat", logText)
                    status = "Log salvo em ${f.absolutePath}"
                    state.logOk("Salvar logcat", target, f.name)
                    FileSaver.shareFile(context, f)
                }
            }) { Text("Salvar", fontSize = 12.sp) }
            Spacer(Modifier.weight(1f))
            Text(
                "${formatBytes(logText.length.toLong())}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (status != null) {
            Text(
                status!!,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }

        // ------------------------------------------------ console do logcat
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = c.consoleBg,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (logText.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            !canStream -> "Escolha um aparelho em modo ADB para ver o logcat."
                            streaming -> "Aguardando as primeiras linhas…"
                            else -> "Toque em \"Iniciar logcat\" para ver o log ao vivo."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.consoleDim
                    )
                }
            } else {
                SelectionContainer {
                    Text(
                        buildAnnotatedString {
                            logText.lines().forEachIndexed { i, line ->
                                val color = when {
                                    line.contains(" E/") || line.contains(" F/") -> c.consoleError
                                    line.contains(" W/") -> c.consoleWarning
                                    line.contains(" I/") -> c.consolePrompt
                                    else -> c.consoleText
                                }
                                withStyle(
                                    androidx.compose.ui.text.SpanStyle(color = color)
                                ) { append(line) }
                                if (i < logText.lines().size - 1) append('\n')
                            }
                        },
                        fontFamily = c.mono,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(8.dp)
                    )
                }
            }
        }

        // ------------------------------------------------ coletas
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (busy != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(busy!!, fontSize = 11.sp)
                }
            } else {
                listOf(
                    "dumpsys" to "Dumpsys",
                    "dmesg" to "dmesg (kernel)",
                    "boot" to "Info de boot",
                    "partitions" to "Partições",
                    "bugreport" to "Bugreport (ZIP)"
                ).forEach { (key, label) ->
                    TextButton(
                        onClick = {
                            val t = state.selectedTarget ?: return@TextButton
                            if (key == "bugreport") {
                                scope.launch {
                                    bugreportProgress = "Iniciando…" to 0f
                                    val result = withContext(Dispatchers.IO) {
                                        try {
                                            val dest = File(
                                                FileSaver.saveDir(context),
                                                "bugreport.zip"
                                            )
                                            AdbClient.bugreport(t, dest.parentFile!!) { msg, p ->
                                                bugreportProgress = msg to p
                                            }
                                            Result.success(dest)
                                        } catch (e: Exception) {
                                            Result.failure(e)
                                        }
                                    }
                                    bugreportProgress = null
                                    result.onSuccess {
                                        status = "Bugreport salvo em ${it.absolutePath}"
                                        state.logOk("bugreport", t, it.name)
                                        FileSaver.shareFile(context, it)
                                    }.onFailure {
                                        status = "Bugreport falhou: ${it.message}"
                                        state.logFail("bugreport", t, it.message ?: "erro")
                                    }
                                }
                                return@TextButton
                            }
                            busy = label
                            scope.launch {
                                val cmd = when (key) {
                                    "dumpsys" -> "dumpsys | head -4000"
                                    "dmesg" -> "dmesg | tail -n 800"
                                    "boot" -> "getprop | grep -E 'boot|init.svc' | head -80; " +
                                        "echo; uptime; echo; cat /proc/cmdline"
                                    "partitions" -> "cat /proc/partitions; echo; df -h"
                                    else -> "echo ok"
                                }
                                val res = withContext(Dispatchers.IO) {
                                    AdbClient.shell(t, cmd, 240_000)
                                }
                                busy = null
                                collectedTitle = label
                                collected = res.combined().ifBlank { "(sem saída)" }
                                if (res.failed) status = "Falha: ${res.output}"
                            }
                        }
                    ) {
                        Text(label, fontSize = 11.sp)
                    }
                }
            }
        }
        if (bugreportProgress != null) {
            ProgressBlock(
                label = bugreportProgress!!.first,
                fraction = bugreportProgress!!.second,
                detail = "Bugreports grandes demoram alguns minutos."
            )
        }
        Spacer(Modifier.height(6.dp))
    }

    collected?.let { text ->
        OutputDialog(
            title = collectedTitle,
            text = text,
            onDismiss = { collected = null },
            tone = NoticeTone.INFO
        )
    }
}
