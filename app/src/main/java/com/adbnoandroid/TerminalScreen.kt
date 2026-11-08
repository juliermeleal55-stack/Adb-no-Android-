package com.adbnoandroid

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalClipboard
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

/** Para onde o comando vai: este aparelho (root) ou um dispositivo remoto. */
sealed class Target {
    object Local : Target()
    data class Remote(val device: AdbDeviceInfo) : Target()

    fun label(): String = when (this) {
        is Local -> "Este aparelho"
        is Remote -> device.label()
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

@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var input by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Todos") }
    var selected by remember { mutableStateOf<Target>(Target.Local) }
    val history = remember { mutableStateListOf<ConsoleEntry>() }
    val devices = remember { mutableStateListOf<AdbDeviceInfo>() }
    var rootState by remember { mutableStateOf(RootState.CHECKING) }
    var isRunning by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val inputFocus = remember { FocusRequester() }
    val clipboard = LocalClipboard.current

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
                if (target is Target.Local) {
                    RootShell.execute(cmd)
                } else {
                    AdbClient.shell(target.device.host, target.device.port, cmd)
                }
            }
            val idx = history.indexOfFirst { it.id == entry.id }
            if (idx >= 0) history[idx] = entry.copy(result = res)
            isRunning = false
            syncRootBadge()
        }
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
        clipboard.setText(AnnotatedString(sb.toString().trimEnd()))
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
                    RootBadge(state = rootState, onClick = recheckRoot)
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
            }
            if (tab == 0) {
                TerminalTab(
                    selected = selected,
                    onSelect = { selected = it },
                    devices = devices,
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
            } else {
                DevicesTab(
                    devices = devices,
                    onAdd = { addDevice(it) },
                    onRemove = { removeDevice(it) },
                    onTest = { probe(it) },
                    onOpenTerminal = { device ->
                        selected = Target.Remote(device)
                        tab = 0
                    }
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
