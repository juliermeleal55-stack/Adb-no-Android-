package com.adbnoandroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adbnoandroid.AdbClient
import com.adbnoandroid.AdbUsb
import com.adbnoandroid.AppState
import com.adbnoandroid.ShellStreamCallbacks
import com.adbnoandroid.DeviceSnapshot
import com.adbnoandroid.DeviceStatus
import com.adbnoandroid.DeviceTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A tela única do app.
 *
 * Três blocos, de cima para baixo, na ordem em que a pessoa usa:
 * 1. **Aparelho** — o que está no cabo (ou o aviso de que não há nada);
 * 2. **Comando** — um campo e o botão Executar, com a saída embaixo;
 * 3. **Atalhos** — as ações comuns, cada uma pedindo confirmação quando é
 *    destrutiva.
 *
 * Nada de abas: o aparelho fica visível o tempo todo, e a tela rola quando o
 * teclado abre.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: AppState, darkTheme: Boolean, onToggleTheme: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Histórico da sessão (some ao fechar o app — é um console, não um diário).
    val lines = remember { mutableStateListOf<ConsoleLine>() }
    val listState = rememberLazyListState()
    var command by remember { mutableStateOf("") }
    var runningMessage by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Confirm?> (null) }

    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * Publica uma linha no console.
     *
     * Os callbacks do shell chegam numa thread de I/O; mexer na
     * lista observável fora da thread principal é corrida de dados. Aqui a
     * escrita é sempre repassada para a main thread.
     */
    fun addLine(text: String, kind: LineKind) {
        mainHandler.post {
            if (lines.size > 400) {
                while (lines.size > 400) lines.removeAt(0)
            }
            lines.add(ConsoleLine(text, kind))
            scope.launch {
                if (lines.isNotEmpty()) {
                    listState.animateScrollToItem(lines.size - 1)
                }
            }
        }
    }

    /** Executa um comando e mostra o que o aparelho respondeu, sem resumir. */
    fun runCommand(text: String, title: String = text) {
        val target = state.selectedTarget ?: run {
            addLine("Nenhum aparelho conectado.", LineKind.ERRO)
            return
        }
        if (runningMessage != null) {
            addLine("O aparelho está ocupado com: $runningMessage", LineKind.ERRO)
            return
        }
        command = text
        runningMessage = title
        addLine("$ $text", LineKind.COMANDO)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AdbClient.shellStream(
                    target = target,
                    command = text,
                    timeoutMs = 180_000,
                    callbacks = ShellStreamCallbacks(
                        onStdout = { chunk -> addLine(chunk, LineKind.SAIDA) },
                        onStderr = { chunk -> addLine(chunk, LineKind.ERRO) }
                    )
                )
            }
            // O shell já entregou a saída em blocos; aqui só fechamos com o código.
            val code = result.exitCode
            when {
                result.failed -> addLine(
                    result.stderr.ifBlank { "O comando falhou." },
                    LineKind.ERRO
                )
                code != null && code != 0 -> addLine("[saiu com código $code]", LineKind.ERRO)
                result.output.isBlank() && result.stderr.isBlank() -> addLine("(sem saída)", LineKind.DIM)
                else -> addLine("(concluído)", LineKind.DIM)
            }
            runningMessage = null
        }
    }

    /** Instala um APK escolhido pelo seletor de arquivos. */
    val pickApk = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = state.selectedTarget ?: return@rememberLauncherForActivityResult
        val name = queryDisplayName(context, uri) ?: "arquivo.apk"
        runningMessage = "Instalando $name"
        addLine("$ instalando $name", LineKind.COMANDO)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val file = copyToCache(context, uri, name)
                if (file == null) {
                    null
                } else {
                    AdbClient.install(target, file) { step, frac ->
                        // Só as mensagens de etapa; o percentual encheria a tela.
                        if (step.isNotBlank()) {
                            addLine("  " + step.trim() + " (" + (frac * 100).toInt() + "%)", LineKind.DIM)
                        }
                    }
                }
            }
            if (result == null) {
                addLine("Não consegui ler o arquivo escolhido.", LineKind.ERRO)
            } else if (result.ok) {
                addLine(result.output.ifBlank { "Instalado." }, LineKind.SAIDA)
            } else {
                addLine(
                    (result.output.ifBlank { "" } + "\n" + result.stderr).trim()
                        .ifBlank { "O aparelho recusou a instalação." },
                    LineKind.ERRO
                )
            }
            runningMessage = null
        }
    }

    /** Ações rápidas: cada uma é um comando real; as destrutivas confirmam. */
    fun quick(label: String, command: String) = runCommand(command, label)

    fun quickReboot(label: String, mode: String) {
        val target = state.selectedTarget ?: return
        runningMessage = label
        addLine("$ $label", LineKind.COMANDO)
        scope.launch {
            val res = withContext(Dispatchers.IO) { AdbClient.reboot(target, mode) }
            if (res.ok || res.output.isNotBlank()) {
                addLine(res.output.ifBlank { "Comando enviado." }, LineKind.SAIDA)
            } else {
                addLine(res.stderr.ifBlank { "O aparelho não reiniciou." }, LineKind.ERRO)
            }
            runningMessage = null
        }
    }

    val snapshot = state.selectedSnapshot()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "ADB",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            snapshot?.model?.ifBlank { snapshot.target.label() }
                                ?: "platform-tools no celular",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    StatusChip(
                        state.selectedTarget?.let { state.statusOf(it) } ?: DeviceStatus.UNKNOWN
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onToggleTheme) {
                        Text(if (darkTheme) "☀" else "☾", fontSize = 18.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Spacer(Modifier.height(2.dp)) }

            // --------------------------------------------------- 1. aparelho
            item {
                DeviceBlock(state) { state.refreshAll() }
            }

            // --------------------------------------------------- 2. comando
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("digite um comando…") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    val text = command.trim()
                                    if (text.isNotEmpty()) runCommand(text)
                                }
                            ),
                            trailingIcon = {
                                FilledTonalIconButton(
                                    onClick = {
                                        val text = command.trim()
                                        if (text.isNotEmpty()) runCommand(text)
                                    },
                                    enabled = runningMessage == null
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = "Executar")
                                }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                runningMessage ?: if (snapshot?.mode?.supportsShell == true) {
                                    "pronto"
                                } else {
                                    "conecte um aparelho para executar"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (runningMessage != null) {
                                CircularProgressIndicator(
                                    Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            if (lines.isNotEmpty()) {
                                TextButton(onClick = { lines.clear() }) {
                                    Text("Limpar", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // saída do console
            if (lines.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = LocalAdbColors.current.consoleBg
                        )
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 520.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            lines.forEach { line ->
                                Text(
                                    line.text,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = when (line.kind) {
                                        LineKind.COMANDO ->
                                            LocalAdbColors.current.consolePrompt
                                        LineKind.ERRO ->
                                            LocalAdbColors.current.consoleError
                                        LineKind.DIM ->
                                            LocalAdbColors.current.consoleDim
                                        LineKind.SAIDA ->
                                            LocalAdbColors.current.consoleText
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // --------------------------------------------------- 3. atalhos
            if (snapshot != null) {
                item {
                    Text(
                        "Atalhos",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                item {
                    ShortcutGrid(
                        enabled = runningMessage == null,
                        snapshot = snapshot,
                        onShell = { label, cmd -> quick(label, cmd) },
                        onPower = { label, cmd -> runCommand(cmd, label) },
                        onReboot = { label, mode -> quickReboot(label, mode) },
                        onInstall = { pickApk.launch(arrayOf("application/vnd.android.package-archive")) },
                        onConfirm = { pending = it }
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    pending?.let { action ->
        ConfirmDangerDialog(
            title = action.title,
            targetLabel = snapshot?.target?.label() ?: "—",
            message = action.message,
            confirmText = action.confirmText,
            onConfirm = {
                pending = null
                action.action()
            },
            onDismiss = { pending = null }
        )
    }
}

// --------------------------------------------------------------- modelos

private enum class LineKind { COMANDO, SAIDA, ERRO, DIM }

private data class ConsoleLine(val text: String, val kind: LineKind)

/** Uma ação destrutiva esperando o "sim" do usuário. */
private data class Confirm(
    val title: String,
    val message: String,
    val confirmText: String,
    val action: () -> Unit
)

// --------------------------------------------------------------- blocos

@Composable
private fun DeviceBlock(state: AppState, onRefresh: () -> Unit) {
    val snapshot = state.selectedSnapshot()
    val status = state.selectedTarget?.let { state.statusOf(it) } ?: DeviceStatus.UNKNOWN
    val usb = state.usbDevices
    val fastboot = state.fastbootDevices

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            if (snapshot != null && snapshot.online) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📱", fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            snapshot.title(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            listOfNotNull(
                                snapshot.manufacturer.takeIf { it.isNotBlank() },
                                ("Android " + snapshot.android)
                                    .takeIf { snapshot.android.isNotBlank() },
                                snapshot.mode.label
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                VerificationRow(snapshot)
            } else {
                // Sem aparelho: a única instrução útil, sem parágrafos.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🔌", fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (usb.isEmpty() && fastboot.isEmpty()) {
                                "Nenhum aparelho no cabo"
                            } else {
                                "Escolha um aparelho"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            statusHint(status, usb.isEmpty(), fastboot.isEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalIconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Procurar")
                    }
                }
            }

            // Lista só aparece quando há mais de um aparelho no cabo.
            val total = usb.size + fastboot.size
            if (total > 1) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "No cabo",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                usb.forEach { info ->
                    DevicePickerRow(
                        label = info.label(),
                        detail = info.manufacturer.ifBlank { info.product },
                        selected = state.selectedTarget?.key() == DeviceTarget.Usb(info).key(),
                        status = state.statusOf(DeviceTarget.Usb(info)),
                        onClick = { state.select(DeviceTarget.Usb(info)); state.probe(DeviceTarget.Usb(info)) }
                    )
                }
                fastboot.forEach { info ->
                    DevicePickerRow(
                        label = info.label(),
                        detail = "fastboot",
                        selected = state.selectedTarget?.key() == DeviceTarget.Fastboot(info).key(),
                        status = DeviceStatus.FASTBOOT,
                        onClick = {
                            state.select(DeviceTarget.Fastboot(info))
                            state.probe(DeviceTarget.Fastboot(info))
                        }
                    )
                }
            }

            // Pedido de permissão USB ainda não concedida.
            val pendingPermission = usb.firstOrNull { !it.hasPermission }
            if (pendingPermission != null) {
                Spacer(Modifier.height(10.dp))
                val ctx = LocalContext.current
                Button(
                    // A permissão é pedida ao UsbDevice do sistema, encontrado
                    // pelo nome — UsbDeviceInfo é só o que guardamos na lista.
                    onClick = {
                        val device = AdbUsb.deviceByName(ctx, pendingPermission.deviceName)
                        if (device != null) {
                            AdbUsb.requestPermission(ctx, device)
                        } else {
                            onRefresh()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Permitir acesso ao aparelho")
                }
            }
        }
    }
}

@Composable
private fun VerificationRow(snapshot: DeviceSnapshot) {
    val c = LocalAdbColors.current
    // Só o que importa saber antes de digitar um comando.
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MiniTag("shell", snapshot.mode.supportsShell, c)
        MiniTag("arquivos", snapshot.mode.supportsFiles, c)
        MiniTag("instalar", snapshot.mode.supportsFiles, c)
        if (snapshot.mode.supportsFastboot) MiniTag("flash", true, c)
    }
}

@Composable
private fun MiniTag(label: String, on: Boolean, c: AdbColors) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (on) {
            c.success.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (on) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    Modifier.size(12.dp),
                    tint = c.success
                )
                Spacer(Modifier.width(3.dp))
            } else {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(3.dp))
            }
            Text(
                label,
                fontSize = 11.sp,
                color = if (on) c.success else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DevicePickerRow(
    label: String,
    detail: String,
    selected: Boolean,
    status: DeviceStatus,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(statusColor(status), CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "selecionado",
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** Atalhos em grade de duas colunas — os mesmos comandos do dia a dia. */
@Composable
private fun ShortcutGrid(
    enabled: Boolean,
    snapshot: DeviceSnapshot,
    onShell: (String, String) -> Unit,
    onPower: (String, String) -> Unit,
    onReboot: (String, String) -> Unit,
    onInstall: () -> Unit,
    onConfirm: (Confirm) -> Unit
) {
    val canShell = snapshot.mode.supportsShell
    val canFiles = snapshot.mode.supportsFiles

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickTile(
                "Instalar APK",
                "escolher arquivo",
                Modifier.weight(1f),
                enabled && canFiles,
                primary = true,
                onClick = onInstall
            )
            QuickTile(
                "Espaço livre",
                "df -h /data",
                Modifier.weight(1f),
                enabled && canShell,
                onClick = { onShell("Espaço livre", "df -h /data") }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickTile(
                "Bateria",
                "dumpsys battery",
                Modifier.weight(1f),
                enabled && canShell,
                onClick = { onShell("Bateria", "dumpsys battery") }
            )
            QuickTile(
                "Tela",
                "captura em /sdcard",
                Modifier.weight(1f),
                enabled && canShell,
                onClick = {
                    onShell(
                        "Capturar tela",
                        // \$ escapa o $ para o Kotlin nao tentar interpolar.
                        "screencap -p /sdcard/tela_\$(date +%s).png && echo salvo em /sdcard"
                    )
                }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickTile(
                "Reiniciar",
                "pede confirmação",
                Modifier.weight(1f),
                enabled && canShell,
                onClick = {
                    onConfirm(
                        Confirm(
                            title = "Reiniciar o aparelho?",
                            message = "O aparelho vai reiniciar agora.",
                            confirmText = "Reiniciar"
                        ) { onReboot("Reiniciar", "") }
                    )
                }
            )
            QuickTile(
                "Recovery",
                "pede confirmação",
                Modifier.weight(1f),
                enabled && canShell,
                onClick = {
                    onConfirm(
                        Confirm(
                            title = "Reiniciar no recovery?",
                            message = "O aparelho vai reiniciar no modo recovery.",
                            confirmText = "Reiniciar"
                        ) { onReboot("Recovery", "recovery") }
                    )
                }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickTile(
                "Root",
                "adb root",
                Modifier.weight(1f),
                enabled && canShell,
                onClick = { onPower("adb root", "root") }
            )
            QuickTile(
                "Remount",
                "adb remount",
                Modifier.weight(1f),
                enabled && canShell,
                onClick = { onPower("adb remount", "remount") }
            )
        }
    }
}

@Composable
private fun QuickTile(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 64.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (primary) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    if (primary) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Selo compacto do estado (online, sem autorização, offline…). */
@Composable
private fun StatusChip(status: DeviceStatus) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = statusColor(status).copy(alpha = 0.14f)
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(statusColor(status), CircleShape)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                statusLabel(status),
                fontSize = 11.sp,
                color = statusColor(status),
                maxLines = 1
            )
        }
    }
}

/** A frase de instrução muda conforme o que o sistema está pedindo. */
private fun statusHint(
    status: DeviceStatus,
    noUsb: Boolean,
    noFastboot: Boolean
): String = when (status) {
    DeviceStatus.UNAUTHORIZED, DeviceStatus.NEEDS_AUTH ->
        "Confirme “Permitir depuração USB” na tela do aparelho."
    DeviceStatus.OFFLINE -> "O aparelho respondeu, mas o adbd não está atendendo."
    DeviceStatus.ERROR -> "Falha ao abrir a conexão. Reconecte o cabo e procure de novo."
    DeviceStatus.CHECKING -> "Verificando…"
    else -> if (noUsb && noFastboot) {
        "Conecte o cabo OTG e ative a depuração USB no outro aparelho."
    } else {
        "Toque em procurar para atualizar a conexão."
    }
}
