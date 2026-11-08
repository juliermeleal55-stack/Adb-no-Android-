package com.adbnoandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Aba do Terminal: escolha o alvo, digite (ou toque num comando pronto) e veja a saída. */
@Composable
fun TerminalTab(
    selected: Target,
    onSelect: (Target) -> Unit,
    devices: List<AdbDeviceInfo>,
    usbDevices: List<AdbUsb.UsbDeviceInfo>,
    usbStatus: Map<String, DeviceStatus>,
    onSelectUsb: (AdbUsb.UsbDeviceInfo) -> Unit,
    onAddDevice: () -> Unit,
    input: String,
    onInput: (String) -> Unit,
    inputFocus: FocusRequester,
    category: String,
    onCategory: (String) -> Unit,
    isRunning: Boolean,
    onRun: (String) -> Unit,
    history: List<ConsoleEntry>,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    rootDenied: Boolean
) {
    val consoleScroll = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        // ---------------- Alvos ----------------
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selected is Target.Local,
                onClick = { onSelect(Target.Local) },
                label = { Text("📱 Este aparelho", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = TerminalGreen.copy(alpha = 0.2f),
                    selectedLabelColor = TerminalGreen
                )
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
                                    .background(statusColor(usbStatus[info.deviceName] ?: DeviceStatus.UNKNOWN), CircleShape)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(info.label(), fontSize = 12.sp)
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TerminalGreen.copy(alpha = 0.2f),
                        selectedLabelColor = TerminalGreen
                    )
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
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TerminalGreen.copy(alpha = 0.2f),
                        selectedLabelColor = TerminalGreen
                    )
                )
            }
            FilterChip(
                selected = false,
                onClick = onAddDevice,
                label = { Text("+ Adicionar", fontSize = 12.sp) }
            )
        }

        // ---------------- Campo de comando ----------------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(inputFocus),
                placeholder = {
                    Text("Comando… (ex.: getprop ro.product.model)", fontSize = 13.sp)
                },
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(onSend = { onRun(input) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = TerminalGreen,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                    cursorColor = TerminalGreen
                ),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onRun(input) },
                enabled = !isRunning && input.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TerminalGreen,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (isRunning) "…" else "Executar", fontWeight = FontWeight.SemiBold)
            }
        }

        Text(
            "Alvo: ${selected.label()}. Digite igual ao PC (\"adb shell …\" é aceito). " +
                "USB = cabo/OTG, como o adb do PC; local = com ROOT. Use com cuidado.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Spacer(Modifier.height(6.dp))

        // ---------------- Aviso de root (só no alvo local) ----------------
        if (rootDenied) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(TerminalRed.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = TerminalAmber,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Nenhum root detectado neste aparelho — os comandos locais rodam como " +
                        "usuário normal. Toque em “SEM ROOT” lá em cima para tentar de novo.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        // ---------------- Categorias ----------------
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Commands.categories.forEach { c ->
                FilterChip(
                    selected = category == c,
                    onClick = { onCategory(c) },
                    label = { Text(c, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TerminalGreen.copy(alpha = 0.2f),
                        selectedLabelColor = TerminalGreen
                    )
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        Text(
            "Comandos prontos",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Spacer(Modifier.height(4.dp))

        // ---------------- Lista de comandos prontos ----------------
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f),
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) {
            items(
                Commands.all.filter { category == "Todos" || it.category == category },
                key = { it.name + it.command }
            ) { preset ->
                CommandRow(
                    preset = preset,
                    onRun = { onRun(preset.command) },
                    onUse = {
                        onInput(preset.command)
                        inputFocus.requestFocus()
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
            }
        }

        // ---------------- Console (saída) ----------------
        Column(
            Modifier
                .fillMaxWidth()
                .weight(0.55f)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SAÍDA",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear) {
                    Text("Limpar", fontSize = 12.sp)
                }
                TextButton(onClick = onCopy, enabled = history.isNotEmpty()) {
                    Text("Copiar tudo", fontSize = 12.sp)
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ConsoleBg)
            ) {
                if (history.isEmpty()) {
                    Text(
                        "A saída dos comandos aparece aqui.\nExecute um comando para começar.",
                        modifier = Modifier.padding(14.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(consoleScroll)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        history.forEach { entry ->
                            ConsoleEntryView(entry)
                        }
                    }
                    LaunchedEffect(history.size, history.lastOrNull()?.result) {
                        delay(80)
                        consoleScroll.maxValue.takeIf { it > 0 }?.let {
                            consoleScroll.jumpTo(it)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandRow(
    preset: CommandPreset,
    onRun: () -> Unit,
    onUse: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    preset.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!preset.ready) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "ajuste antes de rodar",
                        fontSize = 10.sp,
                        color = TerminalAmber
                    )
                }
            }
            Text(
                preset.command,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (preset.ready) {
            IconButton(onClick = onRun) {
                Icon(
                    Icons.Filled.PlayCircle,
                    contentDescription = "Rodar ${preset.name}",
                    tint = TerminalGreen
                )
            }
        }
        IconButton(onClick = onUse) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = "Colocar no campo",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ConsoleEntryView(entry: ConsoleEntry) {
    Column {
        Text(
            "▸ [${entry.target.label()}] ${entry.command}",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = TerminalGreen
        )
        Spacer(Modifier.height(4.dp))
        val r = entry.result
        if (r == null) {
            Text(
                "executando…",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            return
        }
        if (r.output.isNotBlank()) {
            Text(
                r.output,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = true
            )
            Spacer(Modifier.height(4.dp))
        }
        val status: String
        val statusColor: Color
        val isRemote = entry.target is Target.Remote
        when {
            r.timedOut -> {
                status = "⏱ Tempo esgotado (90 s) — o comando foi cancelado."
                statusColor = TerminalAmber
            }
            r.failed -> {
                status = "✗ Falha na execução"
                statusColor = TerminalRed
            }
            else -> {
                status = buildString {
                    when {
                        isRemote -> append("✓ Concluído")
                        r.usedRoot -> append("✓ Concluído (root)")
                        else -> append("⚠ Concluído sem root")
                    }
                    val ec = r.exitCode
                    if (ec != null && ec != 0) append("  •  exit code: $ec")
                }
                statusColor = when {
                    isRemote -> TerminalGreen
                    r.usedRoot -> TerminalGreen
                    else -> TerminalAmber
                }
            }
        }
        Text(
            status,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = statusColor
        )
    }
}

@Composable
private fun RootBadge(state: RootState, onClick: () -> Unit) {
    val (label, color) = when (state) {
        RootState.CHECKING -> "VERIFICANDO…" to TerminalAmber
        RootState.GRANTED -> "ROOT ✓" to TerminalGreen
        RootState.DENIED -> "SEM ROOT" to TerminalRed
    }
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
        contentDescription = "Verificar root"
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
fun statusColor(status: DeviceStatus): Color = when (status) {
    DeviceStatus.ONLINE -> TerminalGreen
    DeviceStatus.CHECKING -> TerminalAmber
    DeviceStatus.OFFLINE -> TerminalRed
    DeviceStatus.NEEDS_AUTH -> TerminalAmber
    DeviceStatus.ERROR -> TerminalRed
    DeviceStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
}
