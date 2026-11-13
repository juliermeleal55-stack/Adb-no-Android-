package com.adbnoandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Aba Dispositivos — o "adb devices" do app, em forma de dashboard:
 *
 * - **Dashboard** do aparelho selecionado (modelo, fabricante, Android, serial,
 *   modo, protocolo, tamanho de pacote negociado);
 * - **USB (OTG)**: lista ao vivo, botão de permissão, reconectar;
 * - **Wi‑Fi**: adicionar IP/porta, testar, renomear, remover;
 * - **Fastboot**: aparelhos no bootloader;
 * - Dicas de como habilitar a depuração.
 */
@Composable
fun DevicesScreen(state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var newAddress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<AdbDeviceInfo?>(null) }

    fun notify(text: String, error: Boolean = false) {
        message = text
        messageIsError = error
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        // ------------------------------------------------ dashboard
        item {
            val snapshot = state.selectedSnapshot()
            if (snapshot != null && snapshot.online) {
                DashboardCard(state, snapshot)
            } else {
                WelcomeCard(state)
            }
        }

        message?.let { text ->
            item {
                NoticeBox(
                    text,
                    tone = if (messageIsError) NoticeTone.ERROR else NoticeTone.INFO
                )
            }
        }

        // ------------------------------------------------ USB
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "🔌 Cabo USB (OTG)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { state.refreshUsb() }) {
                    Text("Reconectar", fontSize = 12.sp)
                }
            }
        }
        if (state.usbDevices.isEmpty()) {
            item {
                val hasUsbHost = context.packageManager.hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_USB_HOST
                )
                NoticeBox(
                    if (hasUsbHost) {
                        "Nenhum aparelho USB detectado.\n\n" +
                            "1. Ligue a **Depuração USB** no aparelho de destino " +
                            "(Opções do desenvolvedor).\n" +
                            "2. Conecte com cabo OTG. O destino deve aparecer aqui em segundos.\n\n" +
                            "Dica: se o celular usar o mesmo conector, use um adaptador OTG " +
                            "e um cabo USB-C/USB-C de dados (cabo só de carga não funciona)."
                    } else {
                        "Este aparelho não suporta USB Host (OTG). Use a conexão por Wi‑Fi."
                    },
                    tone = NoticeTone.INFO
                )
            }
        }
        items(state.usbDevices, key = { "usb-" + it.deviceName }) { info ->
            UsbDeviceCard(
                state = state,
                info = info,
                onPermission = {
                    state.requestUsbPermission(info)
                    notify("Autorize o acesso USB no diálogo do sistema.")
                },
                onProbe = { state.probe(DeviceTarget.Usb(info)) },
                onSelect = { state.select(DeviceTarget.Usb(info)) },
                onRebootBootloader = {
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            AdbClient.reboot(DeviceTarget.Usb(info), "bootloader")
                        }
                        notify(
                            if (res.failed) "Falha: ${res.output}" else
                                "Comando enviado: o aparelho vai reiniciar no bootloader.",
                            res.failed
                        )
                    }
                }
            )
        }

        // ------------------------------------------------ Fastboot
        if (state.fastbootDevices.isNotEmpty()) {
            item {
                Text(
                    "⚡ Bootloader / Fastboot",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            items(state.fastbootDevices, key = { "fb-" + it.deviceName }) { info ->
                FastbootDeviceCard(
                    state = state,
                    info = info,
                    onSelect = { state.select(DeviceTarget.Fastboot(info)) }
                )
            }
        }

        // ------------------------------------------------ Wi-Fi
        item {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "📶 Wi‑Fi (ADB sem fio)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { state.probeAll() }) {
                    Text("Testar todos", fontSize = 12.sp)
                }
            }
        }
        item {
            SectionCard(
                title = "Adicionar aparelho por IP",
                subtitle = "Android 11+: Depuração sem fio → use o IP:porta mostrado. " +
                    "Aparelhos mais antigos: adb tcpip 5555 e o IP do Wi‑Fi."
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newAddress,
                        onValueChange = { newAddress = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("192.168.0.10:5555", fontSize = 13.sp) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val (ok, msg) = state.addNetworkDevice(newAddress)
                            notify(msg, !ok)
                            if (ok) newAddress = ""
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Adicionar")
                    }
                }
            }
        }
        if (state.networkDevices.isEmpty()) {
            item {
                EmptyHint("Nenhum aparelho por Wi‑Fi na lista.")
            }
        }
        items(state.networkDevices, key = { "net-" + it.address() }) { device ->
            NetworkDeviceCard(
                state = state,
                device = device,
                onProbe = { state.probe(DeviceTarget.Net(device)) },
                onSelect = { state.select(DeviceTarget.Net(device)) },
                onRemove = { confirmRemove = device }
            )
        }

        // ------------------------------------------------ dicas
        item {
            SectionCard(title = "Como preparar o aparelho de destino") {
                Text(
                    "1. Ative **Opções do desenvolvedor** (toque 7x em Número da compilação).\n" +
                        "2. Ligue **Depuração USB** (cabo) e/ou **Depuração sem fio**.\n" +
                        "3. Na primeira conexão, o destino mostra \"Permitir depuração USB?\" — " +
                        "toque em **Permitir** (marque \"Sempre permitir\").\n" +
                        "4. Recovery: use \"Apply update from ADB\" para sideload.\n" +
                        "5. Bootloader: o aparelho mostra o modo fastboot e aparece na seção ⚡.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp
                )
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }

    confirmRemove?.let { device ->
        ConfirmDangerDialog(
            title = "Remover aparelho da lista?",
            targetLabel = device.label(),
            message = "Você poderá adicionar de novo a qualquer momento. " +
                "Nada é alterado no aparelho.",
            confirmText = "Remover",
            onConfirm = {
                state.removeNetworkDevice(device)
                confirmRemove = null
            },
            onDismiss = { confirmRemove = null }
        )
    }
}

// ---------------------------------------------------------------- dashboard

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardCard(state: AppState, snapshot: DeviceSnapshot) {
    val c = LocalAdbColors.current
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(modeColor(snapshot.mode).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when (snapshot.mode) {
                        DeviceMode.ADB -> "📱"
                        DeviceMode.RECOVERY -> "🛠"
                        DeviceMode.SIDELOAD -> "📦"
                        DeviceMode.FASTBOOT -> "⚡"
                        else -> "❓"
                    },
                    fontSize = 20.sp
                )
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
                    snapshot.subtitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusPill(
                text = snapshot.mode.label,
                color = modeColor(snapshot.mode),
                filled = true
            )
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(8.dp))
        InfoRow("Fabricante", snapshot.manufacturer)
        InfoRow("Modelo", snapshot.model)
        InfoRow("Android", if (snapshot.android.isBlank()) "" else "Android ${snapshot.android} (API ${snapshot.sdk})")
        InfoRow("Número de série", snapshot.serial, mono = true)
        InfoRow("Build", snapshot.build, mono = true)
        InfoRow(
            "Protocolo",
            buildString {
                append(snapshot.protocol)
                if (snapshot.maxPayload > 0) {
                    append(" · pacote de ")
                    append(formatBytes(snapshot.maxPayload.toLong()))
                }
            }
        )
        if (snapshot.features.isNotEmpty()) {
            InfoRow("Features", snapshot.features.take(6).joinToString(", "))
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (snapshot.mode.supportsShell) {
                StatusPill("shell", c.success)
                StatusPill("push/pull", c.success)
                StatusPill("install", c.success)
                StatusPill("logcat", c.success)
            }
            if (snapshot.mode.supportsSideload) StatusPill("sideload", c.accent)
            if (snapshot.mode.supportsFastboot) {
                StatusPill("flash", c.info)
                StatusPill("erase", c.info)
            }
            if (!snapshot.mode.supportsFiles && snapshot.mode != DeviceMode.FASTBOOT) {
                StatusPill("sem arquivos neste modo", c.warning)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                text = "Atualizar",
                onClick = { state.refreshSnapshot(snapshot.target) },
                icon = {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        Modifier.size(16.dp)
                    )
                }
            )
            ActionButton(
                text = "Recovery",
                onClick = { state.rebootSelected("recovery") }
            )
            ActionButton(
                text = "Bootloader",
                onClick = { state.rebootSelected("bootloader") }
            )
        }
    }
}

@Composable
private fun WelcomeCard(state: AppState) {
    val c = LocalAdbColors.current
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text("📟", fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Nenhum aparelho selecionado", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Conecte por USB OTG ou adicione um IP abaixo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { state.refreshAll() },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Procurar aparelhos")
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "O app fala o protocolo ADB de verdade com o aparelho (o mesmo do " +
                "platform-tools), então você pode rodar comandos, instalar APKs, " +
                "transferir arquivos, coletar logs e gravar imagens fastboot.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (c.isDark) Spacer(Modifier.height(0.dp))
    }
}

// ---------------------------------------------------------------- cartões

@Composable
private fun UsbDeviceCard(
    state: AppState,
    info: AdbUsb.UsbDeviceInfo,
    onPermission: () -> Unit,
    onProbe: () -> Unit,
    onSelect: () -> Unit,
    onRebootBootloader: () -> Unit
) {
    val status = state.statusOf(DeviceTarget.Usb(info))
    val snapshot = state.snapshotOf(DeviceTarget.Usb(info))
    val selected = state.selectedTarget?.key() == DeviceTarget.Usb(info).key()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        snapshot?.model?.ifBlank { info.label() } ?: info.label(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        buildString {
                            append(statusLabel(status))
                            append(" · ")
                            append(info.shortId())
                            if (info.manufacturer.isNotBlank()) append(" · " + info.manufacturer)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (status == DeviceStatus.CHECKING) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    StatusPill(statusLabel(status), statusColor(status))
                }
            }

            if (!info.hasPermission) {
                Spacer(Modifier.height(10.dp))
                NoticeBox(
                    "O app ainda não tem permissão para usar este aparelho USB. " +
                        "Autorize no diálogo do Android."
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onPermission,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Permitir USB")
                }
            } else if (state.needsAuthorization(DeviceTarget.Usb(info))) {
                Spacer(Modifier.height(10.dp))
                NoticeBox(
                    "O aparelho precisa autorizar este app.\n" +
                        "Desbloqueie o aparelho e toque em **Permitir** no diálogo " +
                        "\"Permitir depuração USB?\" (a chave RSA do app aparece lá). " +
                        "Depois toque em Verificar.",
                    tone = NoticeTone.WARNING
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onProbe,
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Verificar de novo") }
                    // LocalContext.current so pode ser lido no corpo composable;
                    // dentro do onClick (lambda comum) o Compose ja saiu do escopo.
                    val permissionContext = androidx.compose.ui.platform.LocalContext.current
                    OutlinedButton(
                        onClick = {
                            val d = AdbUsb.deviceByName(
                                permissionContext,
                                info.deviceName
                            )
                            if (d != null) {
                                AdbUsb.requestPermission(permissionContext, d)
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Reautorizar") }
                }
            } else {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status == DeviceStatus.ONLINE || status == DeviceStatus.RECOVERY) {
                        Button(
                            onClick = onSelect,
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Usar este aparelho") }
                    } else {
                        OutlinedButton(
                            onClick = onProbe,
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Verificar") }
                    }
                    OutlinedButton(
                        onClick = onRebootBootloader,
                        shape = RoundedCornerShape(12.dp),
                        enabled = status == DeviceStatus.ONLINE
                    ) { Text("⚡ Bootloader") }
                }
            }
        }
    }
}

@Composable
private fun FastbootDeviceCard(
    state: AppState,
    info: FastbootDeviceInfo,
    onSelect: () -> Unit
) {
    val c = LocalAdbColors.current
    val selected = state.selectedTarget?.key() == DeviceTarget.Fastboot(info).key()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) c.info.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(DeviceStatus.FASTBOOT)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(info.label(), style = MaterialTheme.typography.titleSmall)
                Text(
                    "bootloader · " + info.product.ifBlank { info.deviceName },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!info.hasPermission) {
                OutlinedButton(onClick = { state.requestFastbootPermission(info) }) {
                    Text("Permitir")
                }
            } else {
                Button(onClick = onSelect, shape = RoundedCornerShape(12.dp)) {
                    Text("Abrir fastboot")
                }
            }
        }
    }
}

@Composable
private fun NetworkDeviceCard(
    state: AppState,
    device: AdbDeviceInfo,
    onProbe: () -> Unit,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    val target = DeviceTarget.Net(device)
    val status = state.statusOf(target)
    val snapshot = state.snapshotOf(target)
    val selected = state.selectedTarget?.key() == target.key()
    var editing by remember { mutableStateOf(false) }
    var nameDraft by remember(device.name) { mutableStateOf(device.name) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        device.name.ifBlank { snapshot?.model?.ifBlank { device.address() } ?: device.address() },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        device.address() +
                            (snapshot?.model?.takeIf { it.isNotBlank() && device.name.isNotBlank() }
                                ?.let { " · $it" } ?: "") +
                            " · " + statusLabel(status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (status == DeviceStatus.CHECKING) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            if (device.lastError.isNotBlank() && status != DeviceStatus.ONLINE) {
                Spacer(Modifier.height(6.dp))
                Text(
                    device.lastError,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalAdbColors.current.error
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onProbe, shape = RoundedCornerShape(12.dp)) {
                    Text("Testar", fontSize = 12.sp)
                }
                if (status == DeviceStatus.ONLINE) {
                    Button(onClick = onSelect, shape = RoundedCornerShape(12.dp)) {
                        Text("Usar", fontSize = 12.sp)
                    }
                }
                TextButton(onClick = { editing = !editing }) {
                    Text("Renomear", fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remover",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (editing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("Apelido (ex.: Moto G do João)", fontSize = 12.sp) },
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = {
                            state.renameNetworkDevice(device, nameDraft.trim())
                            editing = false
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Salvar", fontSize = 12.sp) }
                }
            }
        }
    }
}

