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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.foundation.text.selection.SelectionContainer

/** Uma linha do console do terminal. */
data class ConsoleLine(
    val id: Long,
    val kind: LineKind,
    val text: String,
    val targetLabel: String = "",
    val exitCode: Int? = null
)

enum class LineKind { COMMAND, OUTPUT, ERROR, INFO, SUCCESS }

/**
 * Aba Terminal — o shell do platform-tools dentro do app.
 *
 * - Escolhe o aparelho (chips) e digita comandos como no PC (`adb shell` é
 *   aceito e removido).
 * - **Autocompletar** com os comandos mais usados e preset por categoria.
 * - **Histórico** de comandos (↑/↓ e lista), **execução em sequência**
 *   (uma linha por vez ou separados por `;`).
 * - Saída em tempo real, com cores por tipo (erro/aviso/sucesso).
 * - Limpar, copiar tudo e exportar .txt.
 */
@Composable
fun TerminalScreen(state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAdbColors.current

    val lines = remember { mutableStateListOf<ConsoleLine>() }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var currentCommand by remember { mutableStateOf("") }
    var streamBuffer by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Dispositivo") }
    var historyIndex by remember { mutableStateOf(-1) }
    var showSuggestions by remember { mutableStateOf(false) }
    var sequence by remember { mutableStateOf("") }
    var showSequence by remember { mutableStateOf(false) }

    val target = state.selectedTarget
    val mode = state.selectedMode()
    val usable = target != null && !state.needsAuthorization(target)

    val commandHistory = remember { mutableStateListOf<String>() }
    val focusRequester = remember { FocusRequester() }

    fun addLine(kind: LineKind, text: String, exit: Int? = null) {
        lines.add(
            ConsoleLine(
                id = System.nanoTime(),
                kind = kind,
                text = text,
                targetLabel = target?.label() ?: "—",
                exitCode = exit
            )
        )
        // Limita o buffer para não estourar a memória em logcat/dumpsys.
        while (lines.size > 500) lines.removeAt(0)
    }

    fun scrollToEnd() {
        // O LazyColumn já acompanha; nada a fazer além de anexar.
    }

    fun execute(command: String) {
        val t = state.selectedTarget
        if (t == null) {
            addLine(LineKind.ERROR, "Nenhum aparelho selecionado.")
            return
        }
        if (command.isBlank()) return
        val normalized = normalizeCommand(command)
        if (normalized.isBlank()) return
        commandHistory.add(0, normalized)
        if (commandHistory.size > 100) commandHistory.removeAt(commandHistory.size - 1)
        historyIndex = -1
        addLine(LineKind.COMMAND, "$ $normalized")
        running = true
        currentCommand = normalized
        var buffer = StringBuilder()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    AdbClient.shellStream(
                        t, normalized, 180_000,
                        ShellStreamCallbacks(
                            onStdout = { chunk -> buffer.append(chunk) },
                            onStderr = { chunk -> buffer.append(chunk) },
                            onExit = { }
                        )
                    )
                } catch (e: Exception) {
                    ShellResult(e.message ?: "erro", null, failed = true)
                }
            }
            val text = buffer.toString().trimEnd()
            if (text.isNotBlank()) addLine(LineKind.OUTPUT, text)
            if (result.failed) {
                addLine(LineKind.ERROR, result.output.trim().ifBlank { "Falha na execução." })
            } else if (result.exitCode != null && result.exitCode != 0) {
                addLine(LineKind.ERROR, "exit code ${result.exitCode}")
            } else if (text.isBlank()) {
                addLine(LineKind.SUCCESS, "✓ concluído (sem saída)")
            } else {
                addLine(LineKind.SUCCESS, "✓ concluído")
            }
            running = false
            currentCommand = ""
            state.logOk("shell: " + normalized.take(60), t, text.take(300))
        }
    }

    fun runSequence() {
        val commands = sequence.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (commands.isEmpty()) return
        scope.launch {
            commands.forEach { cmd ->
                execute(cmd)
                // execute() usa o mesmo scope e bloqueia em runBlocking? Não:
                // aqui esperamos terminar pelo flag running.
                while (running) {
                    kotlinx.coroutines.delay(120)
                }
            }
        }
    }

    // Rola para o fim quando chega linha nova.
    val scrollState = rememberScrollState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // ------------------------------------------------- alvo + ações
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                TargetSelector(
                    targets = state.allTargets(includeFastboot = false),
                    selected = target,
                    onSelect = { state.select(it) },
                    statusOf = { state.statusOf(it) },
                    emptyText = "Conecte um aparelho para usar o terminal"
                )
            }
            IconButton(onClick = {
                lines.clear()
            }) {
                Icon(
                    Icons.Filled.Clear,
                    contentDescription = "Limpar terminal",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = {
                val text = lines.joinToString("\n") { line ->
                    when (line.kind) {
                        LineKind.COMMAND -> line.text
                        LineKind.OUTPUT -> line.text
                        LineKind.ERROR -> "[erro] " + line.text
                        LineKind.SUCCESS -> line.text
                        LineKind.INFO -> line.text
                    }
                }
                copyToClipboard(context, "Terminal ADB", text)
            }) { Text("Copiar", fontSize = 12.sp) }
        }

        if (mode == DeviceMode.RECOVERY || mode == DeviceMode.SIDELOAD) {
            NoticeBox(
                "O aparelho está em modo ${mode.label}: o shell pode ter poucos comandos " +
                    "disponíveis e transferência de arquivos normalmente não funciona.",
                modifier = Modifier.padding(horizontal = 12.dp),
                tone = NoticeTone.WARNING
            )
        }
        if (target != null && state.needsAuthorization(target)) {
            NoticeBox(
                "O aparelho ainda não autorizou o app. Toque em \"Permitir\" no diálogo " +
                    "do aparelho (aba Dispositivos → Verificar).",
                modifier = Modifier.padding(horizontal = 12.dp),
                tone = NoticeTone.WARNING
            )
        }

        // ------------------------------------------------- console
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = c.consoleBg,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(10.dp)
                ) {
                    if (lines.isEmpty()) {
                        Text(
                            "Terminal pronto.\n\n" +
                                "Digite um comando (ex.: getprop ro.product.model) ou toque " +
                                "num comando pronto abaixo.\n" +
                                "Também aceita o formato do PC: adb shell <comando>.",
                            fontFamily = c.mono,
                            fontSize = 12.sp,
                            color = c.consoleDim,
                            lineHeight = 18.sp
                        )
                    }
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Column {
                            lines.forEach { line ->
                                ConsoleLineView(line)
                            }
                            if (running) {
                                Text(
                                    "executando…",
                                    fontFamily = c.mono,
                                    fontSize = 12.sp,
                                    color = c.consoleWarning
                                )
                            }
                        }
                    }
                }
            }
        }

        if (running) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
            )
        }

        // ------------------------------------------------- comandos prontos
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Commands.categories.filter { it != "Todos" }.forEach { cat ->
                AssistChip(
                    onClick = { category = cat },
                    label = { Text(cat, fontSize = 11.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (cat == category) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        labelColor = if (cat == category) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 92.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Commands.all.filter { it.category == category }.forEach { preset ->
                AssistChip(
                    onClick = {
                        if (preset.ready) {
                            execute(preset.command)
                        } else {
                            input = preset.command
                            focusRequester.requestFocus()
                        }
                    },
                    label = {
                        Text(
                            (if (preset.ready) "▶ " else "✏️ ") + preset.name,
                            fontSize = 11.sp
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        labelColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }

        // ------------------------------------------------- entrada
        if (showSequence) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                OutlinedTextField(
                    value = sequence,
                    onValueChange = { sequence = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 90.dp),
                    placeholder = {
                        Text("Um comando por linha (executa em sequência)", fontSize = 12.sp)
                    },
                    textStyle = TextStyle(
                        fontFamily = c.mono,
                        fontSize = 12.sp
                    )
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { runSequence() },
                        enabled = !running,
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Executar sequência") }
                    TextButton(onClick = { showSequence = false }) { Text("Fechar") }
                }
            }
        }

        // Autocomplete: filtra os comandos prontos pelo que foi digitado.
        val suggestions = remember(input) {
            if (input.isBlank()) emptyList()
            else Commands.all.filter {
                !it.ready && it.command.contains(input, ignoreCase = true) ||
                    it.command.startsWith(input, ignoreCase = true)
            }.take(4)
        }
        if (suggestions.isNotEmpty() && showSuggestions) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                suggestions.forEach { s ->
                    AssistChip(
                        onClick = {
                            input = s.command
                            showSuggestions = false
                        },
                        label = {
                            Text(
                                s.command.take(34),
                                fontSize = 11.sp,
                                fontFamily = c.mono
                            )
                        }
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    showSuggestions = true
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = { Text("Comando (ex.: adb shell pm list packages)", fontSize = 12.sp) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = c.mono, fontSize = 13.sp),
                leadingIcon = {
                    Text(
                        "❯",
                        fontFamily = c.mono,
                        color = c.consolePrompt,
                        fontWeight = FontWeight.Bold
                    )
                },
                trailingIcon = {
                    if (commandHistory.isNotEmpty()) {
                        TextButton(onClick = {
                            val next = if (historyIndex + 1 < commandHistory.size) historyIndex + 1 else 0
                            historyIndex = next
                            input = commandHistory[next]
                        }) { Text("↑", fontSize = 14.sp) }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send,
                    keyboardType = KeyboardType.Ascii
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        execute(input)
                        input = ""
                        showSuggestions = false
                    }
                )
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    execute(input)
                    input = ""
                    showSuggestions = false
                },
                enabled = !running && usable && input.isNotBlank(),
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (!running && usable && input.isNotBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        RoundedCornerShape(14.dp)
                    )
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = "Executar",
                    tint = if (!running && usable && input.isNotBlank()) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = { showSequence = !showSequence }) {
                Text(if (showSequence) "Ocultar sequência" else "▶▶ Executar sequência", fontSize = 12.sp)
            }
            TextButton(onClick = {
                val text = lines.joinToString("\n") { it.text }
                if (text.isNotBlank()) {
                    val f = FileSaver.saveText(context, "terminal", text)
                    state.logOk("Exportar terminal", target, f.name)
                }
            }) { Text("Exportar .txt", fontSize = 12.sp) }
            Spacer(Modifier.weight(1f))
            Text(
                "histórico: ${commandHistory.size}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConsoleLineView(line: ConsoleLine) {
    val c = LocalAdbColors.current
    val color = when (line.kind) {
        LineKind.COMMAND -> c.consolePrompt
        LineKind.ERROR -> c.consoleError
        LineKind.SUCCESS -> c.consolePrompt
        LineKind.INFO -> c.consoleDim
        LineKind.OUTPUT -> c.consoleText
    }
    Text(
        line.text,
        fontFamily = c.mono,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = color,
        fontWeight = if (line.kind == LineKind.COMMAND) FontWeight.Bold else FontWeight.Normal
    )
}

/** Remove o prefixo "adb" / "adb shell" e normaliza espaços. */
fun normalizeCommand(raw: String): String {
    var cmd = raw.trim()
    if (cmd.startsWith("adb ", ignoreCase = true)) {
        cmd = cmd.substring(4).trim()
        if (cmd.startsWith("shell ", ignoreCase = true)) {
            cmd = cmd.substring(6).trim()
        }
    } else if (cmd.equals("adb", ignoreCase = true)) {
        cmd = ""
    }
    return cmd
}
