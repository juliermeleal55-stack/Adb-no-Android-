package com.adbnoandroid

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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

/** Estado da verificação de root local. */
enum class RootState { CHECKING, GRANTED, DENIED }

/** Para onde o comando vai: este aparelho (root), USB (cabo) ou rede (Wi‑Fi). */
sealed class Target {
    object Local : Target()
    data class Remote(val device: AdbDeviceInfo) : Target()
    class Usb(val info: AdbUsb.UsbDeviceInfo, val usb: UsbDevice) : Target()

    fun label(): String = when (this) {
        is Local -> "Este aparelho"
        is Remote -> device.label()
        is Usb -> info.label()
    }
}

/** Uma execução na saída (console). */
data class ConsoleEntry(
    val id: Long,
    val command: String,
    val target: Target,
    /** null = ainda executando. */
    val result: ShellResult?
)

/** Tamanho máximo do buffer de logcat na tela (caracteres). */
private const val LOG_MAX_CHARS = 250_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var input by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Todos") }
    var selected by remember { mutableStateOf<Target>(Target.Local) }
    val history = remember { mutableStateListOf<ConsoleEntry>() }
    val devices = remember { mutableStateListOf<AdbDeviceInfo>() }
    val usbDevices = remember { mutableStateListOf<AdbUsb.UsbDeviceInfo>() }
    val usbStatus = remember { mutableStateMapOf<String, DeviceStatus>() }
    val usbModels = remember { mutableStateMapOf<String, String>() }
    var rootState by remember { mutableStateOf(RootState.CHECKING) }
    var isRunning by remember { mutableStateOf(false) }

    // ---------------- Estado do logcat (aba Logs) ----------------
    // Vive aqui (e não na aba) para o stream continuar na troca de abas.
    var logText by remember { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    var logStatus by remember { mutableStateOf<String?>(null) }
    var logSession by remember { mutableStateOf<LogSession?>(null) }

    val scope = rememberCoroutineScope()
    val inputFocus = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current

    // Carrega a lista de dispositivos remotos
    LaunchedEffect(Unit) {
        val loaded = DevicesStore.load(context)
        devices.clear()
        devices.addAll(loaded)
    }

    // Persiste quando a lista muda (endereço/apelido)
    val deviceSignature = devices.joinToString(",") { "${it.address()}/${it.name}" }
    var savedSignature by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(deviceSignature) {
        if (deviceSignature != savedSignature) {
            savedSignature = deviceSignature
            DevicesStore.save(context, devices.toList())
        }
    }

    // Verifica o root local ao abrir
    LaunchedEffect(Unit) {
        rootState = RootState.CHECKING
        val ok = withContext(Dispatchers.IO) { RootShell.checkRoot() }
        rootState = if (ok) RootState.GRANTED else RootState.DENIED
    }

    // ---------------- Dispositivos USB (plugue/quente) ----------------
    fun refreshUsb() {
        val list = AdbUsb.listAdbDevices(context)
        val oldNames = usbDevices.map { it.deviceName }.toSet()
        val newNames = list.map { it.deviceName }.toSet()
        usbDevices.clear()
        usbDevices.addAll(list)
        if (newNames != oldNames) {
            // Dispositivos mudaram (plugue/remoção): zera status/modelo
            usbStatus.clear()
            usbModels.clear()
        }
        // Se o alvo USB foi desconectado, volta para o alvo local
        if (selected is Target.Usb &&
            list.none { it.deviceName == (selected as Target.Usb).info.deviceName }
        ) {
            selected = Target.Local
        }
    }

    LaunchedEffect(Unit) {
        // O collect dispara imediatamente (valor atual) e a cada plugue/permisso
        UsbEvents.version.collect { refreshUsb() }
    }

    fun probeUsb(info: AdbUsb.UsbDeviceInfo) {
        val device = AdbUsb.deviceByName(context, info.deviceName) ?: return
        if (!AdbUsb.hasPermission(context, device)) {
            usbStatus[info.deviceName] = DeviceStatus.ERROR
            return
        }
        usbStatus[info.deviceName] = DeviceStatus.CHECKING
        scope.launch {
            val (status, model) = withContext(Dispatchers.IO) {
                try {
                    val banner = AdbClient.probeUsb(device)
                    DeviceStatus.ONLINE to AdbClient.parseModel(banner)
                } catch (e: AdbAuthException) {
                    DeviceStatus.NEEDS_AUTH to ""
                } catch (e: AdbConnectException) {
                    DeviceStatus.OFFLINE to ""
                } catch (e: Exception) {
                    DeviceStatus.ERROR to ""
                }
            }
            usbStatus[info.deviceName] = status
            usbModels[info.deviceName] = model
        }
    }

    fun requestUsbPermission(info: AdbUsb.UsbDeviceInfo) {
        val device = AdbUsb.deviceByName(context, info.deviceName) ?: return
        AdbUsb.requestPermission(context, device)
    }

    fun recheckRoot() {
        rootState = RootState.CHECKING
        scope.launch {
            val ok = withContext(Dispatchers.IO) { RootShell.checkRoot() }
            rootState = if (ok) RootState.GRANTED else RootState.DENIED
        }
    }

    fun syncRootBadge() {
        val av = RootShell.rootAvailable
        if (av == true) {
            rootState = RootState.GRANTED
        } else if (av == false && rootState == RootState.GRANTED) {
            rootState = RootState.DENIED
        }
    }

    fun replaceDevice(device: AdbDeviceInfo) {
        val idx = devices.indexOfFirst { it.address() == device.address() }
        if (idx >= 0) devices[idx] = device
    }

    fun probe(device: AdbDeviceInfo) {
        replaceDevice(device.copy(status = DeviceStatus.CHECKING))
        scope.launch {
            val (status, banner, model) = withContext(Dispatchers.IO) {
                try {
                    val b = AdbClient.probe(device.host, device.port, 4000)
                    Triple(DeviceStatus.ONLINE, b, AdbClient.parseModel(b))
                } catch (e: AdbAuthException) {
                    Triple(DeviceStatus.NEEDS_AUTH, "", "")
                } catch (e: AdbConnectException) {
                    Triple(DeviceStatus.OFFLINE, "", "")
                } catch (e: Exception) {
                    Triple(DeviceStatus.ERROR, "", "")
                }
            }
            replaceDevice(device.copy(status = status, banner = banner, model = model))
        }
    }

    fun addDevice(address: String): Boolean {
        val parsed = parseAddress(address) ?: return false
        if (devices.any { it.address() == parsed.first }) return true
        devices.add(AdbDeviceInfo(parsed.first, parsed.second))
        probe(devices.last())
        return true
    }

    fun removeDevice(device: AdbDeviceInfo) {
        val idx = devices.indexOfFirst { it.address() == device.address() }
        if (idx >= 0) devices.removeAt(idx)
        if (selected is Target.Remote &&
            (selected as Target.Remote).device.address() == device.address()
        ) {
            selected = Target.Local
        }
    }

    fun runCommand(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || isRunning) return
        // "Igual ao PC": se o usuário digitou `adb shell ...`, remove o prefixo.
        val lower = trimmed.lowercase()
        val cmd = if (lower.startsWith("adb shell ")) {
            trimmed.substring("adb shell ".length).trim()
        } else {
            trimmed
        }
        if (cmd.isEmpty()) return

        val target = selected
        val entry = ConsoleEntry(System.currentTimeMillis(), cmd, target, null)
        history.add(entry)
        isRunning = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                when (target) {
                    is Target.Local -> RootShell.execute(cmd)
                    is Target.Remote ->
                        AdbClient.shell(target.device.host, target.device.port, cmd)
                    is Target.Usb -> {
                        val d = AdbUsb.deviceByName(context, target.info.deviceName)
                        if (d == null) {
                            ShellResult("Dispositivo USB desconectado.", null, false, true, false)
                        } else {
                            AdbClient.shellUsb(d, cmd)
                        }
                    }
                }
            }
            val idx = history.indexOfFirst { it.id == entry.id }
            if (idx >= 0) history[idx] = entry.copy(result = res)
            isRunning = false
            syncRootBadge()
        }
    }

    // ---------------- Logcat ao vivo (aba Logs) ----------------

    fun appendLog(chunk: String) {
        val next = logText + chunk
        logText = if (next.length > LOG_MAX_CHARS) {
            next.substring(next.length - LOG_MAX_CHARS)
        } else {
            next
        }
    }

    fun startLogcat(args: String) {
        if (isStreaming) return
        logText = ""
        logStatus = "Conectando e iniciando o logcat…"
        val callbacks = LogcatCallbacks(
            onChunk = { chunk -> scope.launch { appendLog(chunk) } },
            onDone = { err ->
                scope.launch {
                    isStreaming = false
                    logSession = null
                    logStatus = err ?: "Logcat encerrado."
                }
            }
        )
        val session: LogSession = when (val t = selected) {
            is Target.Local ->
                RootShell.startLogcatLocal("logcat $args", callbacks)
            is Target.Remote ->
                AdbClient.logcatTcp(t.device.host, t.device.port, args, callbacks)
            is Target.Usb -> {
                val d = AdbUsb.deviceByName(context, t.info.deviceName)
                if (d == null) {
                    logStatus = "Dispositivo USB desconectado."
                    return
                }
                AdbClient.logcatUsb(d, args, callbacks)
            }
        }
        logSession = session
        isStreaming = true
    }

    fun stopLogcat() {
        logSession?.stop()
        logStatus = "Parando…"
    }

    fun copyAll() {
        val sb = StringBuilder()
        history.forEach { e ->
            sb.append("[").append(e.target.label()).append("] ").append(e.command).append("\n")
            val r = e.result
            if (r == null) {
                sb.append("(executando…)\n")
            } else {
                sb.append(r.output).append("\n")
            }
            sb.append("\n")
        }
        copyToClipboard(context, sb.toString().trimEnd())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ADB no Android", fontWeight = FontWeight.Bold)
                        Text(
                            "Comandos em outros aparelhos + local com root",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                },
                actions = {
                    RootBadge(state = rootState, onClick = { recheckRoot() })
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Terminal") })
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("Dispositivos") }
                )
                Tab(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    text = { Text("Logs") }
                )
                Tab(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    text = { Text("Sideload") }
                )
            }
            when (tab) {
                0 -> TerminalTab(
                    selected = selected,
                    onSelect = { selected = it },
                    devices = devices,
                    usbDevices = usbDevices,
                    usbStatus = usbStatus,
                    onSelectUsb = { info ->
                        val d = AdbUsb.deviceByName(context, info.deviceName)
                            ?: return@TerminalTab
                        selected = Target.Usb(info, d)
                    },
                    onAddDevice = { tab = 1 },
                    input = input,
                    onInput = { input = it },
                    inputFocus = inputFocus,
                    category = category,
                    onCategory = { category = it },
                    isRunning = isRunning,
                    onRun = { runCommand(it) },
                    history = history,
                    onClear = { history.clear() },
                    onCopy = { copyAll() },
                    rootDenied = selected is Target.Local && rootState == RootState.DENIED
                )
                1 -> DevicesTab(
                    devices = devices,
                    usbDevices = usbDevices,
                    usbStatus = usbStatus,
                    usbModels = usbModels,
                    onAdd = { addDevice(it) },
                    onRemove = { removeDevice(it) },
                    onTest = { probe(it) },
                    onOpenTerminal = { device ->
                        selected = Target.Remote(device)
                        tab = 0
                    },
                    onTestUsb = { probeUsb(it) },
                    onPermissionUsb = { requestUsbPermission(it) },
                    onOpenUsbTerminal = { info ->
                        val d = AdbUsb.deviceByName(context, info.deviceName) ?: return@DevicesTab
                        selected = Target.Usb(info, d)
                        tab = 0
                    }
                )
                2 -> LogcatTab(
                    selected = selected,
                    onSelect = { selected = it },
                    devices = devices,
                    usbDevices = usbDevices,
                    usbStatus = usbStatus,
                    onSelectUsb = { info ->
                        val d = AdbUsb.deviceByName(context, info.deviceName)
                            ?: return@LogcatTab
                        selected = Target.Usb(info, d)
                    },
                    rootGranted = rootState == RootState.GRANTED,
                    logText = logText,
                    isStreaming = isStreaming,
                    logStatus = logStatus,
                    onStartStream = { args -> startLogcat(args) },
                    onStopStream = { stopLogcat() },
                    onClearLog = { logText = "" }
                )
                else -> SideloadTab(
                    usbDevices = usbDevices,
                    usbStatus = usbStatus,
                    networkDevices = devices
                )
            }
        }
    }
}

/** Interpreta "192.168.1.50" (porta 5555) ou "192.168.1.50:37245". */
fun parseAddress(raw: String): Pair<String, Int>? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    val i = t.lastIndexOf(':')
    return if (i >= 0) {
        val host = t.substring(0, i).trim()
        val port = t.substring(i + 1).trim().toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) null else host to port
    } else {
        if (t.any { it.isLetterOrDigit() }) t to Adb.DEFAULT_PORT else null
    }
}
