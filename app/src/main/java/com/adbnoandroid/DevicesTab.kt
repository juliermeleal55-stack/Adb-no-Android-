package com.adbnoandroid

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

/**
 * Aba Dispositivos: a "adb devices" do app — lista ao vivo os aparelhos
 * conectados por USB (cabo/OTG) e os endereços de rede salvos.
 */
@Composable
fun DevicesTab(
    devices: List<AdbDeviceInfo>,
    usbDevices: List<AdbUsb.UsbDeviceInfo>,
    usbStatus: Map<String, DeviceStatus>,
    usbModels: Map<String, String>,
    onAdd: (String) -> Boolean,
    onRemove: (AdbDeviceInfo) -> Unit,
    onTest: (AdbDeviceInfo) -> Unit,
    onOpenTerminal: (AdbDeviceInfo) -> Unit,
    onTestUsb: (AdbUsb.UsbDeviceInfo) -> Unit,
    onPermissionUsb: (AdbUsb.UsbDeviceInfo) -> Unit,
    onOpenUsbTerminal: (AdbUsb.UsbDeviceInfo) -> Unit
) {
    var address by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        // ================= USB (cabo/OTG) =================
        Text(
            "Via USB (cabo / OTG)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))

        if (usbDevices.isEmpty()) {
            Text(
                "Nenhum aparelho ADB detectado por USB. Conecte um cabo USB ao " +
                    "aparelho de destino (via adaptador OTG) com a depuração USB ativada.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            usbDevices.forEach { info ->
                UsbRow(
                    info = info,
                    status = usbStatus[info.deviceName] ?: DeviceStatus.UNKNOWN,
                    model = usbModels[info.deviceName].orEmpty(),
                    onPermission = { onPermissionUsb(info) },
                    onTest = { onTestUsb(info) },
                    onOpenTerminal = { onOpenUsbTerminal(info) }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // ================= Rede (Wi-Fi) =================
        Text(
            "Via rede (Wi‑Fi)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it
                    addError = ""
                },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("IP:porta — ex.: 192.168.1.50:5555", fontSize = 13.sp)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (!onAdd(address)) {
                            addError =
                                "Endereço inválido. Use IP ou IP:porta (ex.: 192.168.1.50:5555)."
                        }
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TerminalGreen,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                    cursorColor = TerminalGreen
                ),
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (onAdd(address)) {
                        address = ""
                        addError = ""
                    } else {
                        addError =
                            "Endereço inválido. Use IP ou IP:porta (ex.: 192.168.1.50:5555)."
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = TerminalGreen,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Adicionar", fontWeight = FontWeight.SemiBold)
            }
        }
        if (addError.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(addError, fontSize = 11.sp, color = TerminalRed)
        }
        Spacer(Modifier.height(10.dp))

        // ---------------- lista de rede ----------------
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(devices, key = { it.address() }) { device ->
                NetworkRow(
                    device = device,
                    onTest = { onTest(device) },
                    onOpenTerminal = { onOpenTerminal(device) },
                    onRemove = { onRemove(device) }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
            }
            if (devices.isEmpty()) {
                item {
                    Text(
                        "Nenhum endereço de rede salvo.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }

        // ---------------- Como usar ----------------
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "Como conectar outro aparelho",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "USB (cabo/OTG) — igual ao PC com platform-tools:\n" +
                        "1. Conecte um cabo USB ao aparelho de destino (use adaptador OTG " +
                        "se precisar); ele precisa ter Depuração USB ativada.\n" +
                        "2. Quando o diálogo \"Permitir acesso USB\" aparecer neste " +
                        "aparelho, toque em PERMITIR.\n" +
                        "3. O aparelho aparece nesta lista (como no adb devices) — " +
                        "toque em ▶ para o terminal.\n\n" +
                        "Wi‑Fi:\n" +
                        "1. Mesma rede Wi‑Fi nos dois aparelhos.\n" +
                        "2. No destino: \"Depuração sem fio\" (Android 11+; use o " +
                        "IP:porta mostrados) ou \"adb tcpip 5555\" (mais antigos).\n" +
                        "3. Adicione o IP aqui.\n\n" +
                        "Na primeira conexão (USB ou Wi‑Fi), o aparelho de destino mostra " +
                        "\"Permitir depuração?\" — toque em PERMITIR uma vez.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun UsbRow(
    info: AdbUsb.UsbDeviceInfo,
    status: DeviceStatus,
    model: String,
    onPermission: () -> Unit,
    onTest: () -> Unit,
    onOpenTerminal: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(9.dp)
                .background(statusColor(status), CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                info.label(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val extra = buildString {
                append(info.deviceName)
                if (model.isNotBlank()) append("  •  ").append(model)
            }
            Text(
                extra,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (!info.hasPermission) "aguardando permissão USB" else statusText(status),
                fontSize = 10.sp,
                color = statusColor(status)
            )
        }
        if (!info.hasPermission) {
            TextButton(onClick = onPermission) {
                Text("Permitir USB", fontSize = 12.sp)
            }
        }
        IconButton(onClick = onTest) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Testar conexão",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        IconButton(onClick = onOpenTerminal, enabled = info.hasPermission) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = "Abrir terminal",
                tint = if (info.hasPermission) TerminalGreen
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
private fun NetworkRow(
    device: AdbDeviceInfo,
    onTest: () -> Unit,
    onOpenTerminal: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(9.dp)
                .background(statusColor(device.status), CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                device.label(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val extra = buildString {
                append(device.address())
                if (device.model.isNotBlank()) append("  •  ").append(device.model)
            }
            Text(
                extra,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                statusText(device.status),
                fontSize = 10.sp,
                color = statusColor(device.status)
            )
        }
        IconButton(onClick = onTest) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Testar conexão",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        IconButton(onClick = onOpenTerminal) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = "Abrir terminal",
                tint = TerminalGreen
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Remover",
                tint = TerminalRed.copy(alpha = 0.8f)
            )
        }
    }
}

private fun statusText(status: DeviceStatus): String = when (status) {
    DeviceStatus.ONLINE -> "device (conectado)"
    DeviceStatus.CHECKING -> "verificando…"
    DeviceStatus.OFFLINE -> "offline — confira o cabo/rede"
    DeviceStatus.NEEDS_AUTH -> "aguardando permissão no aparelho"
    DeviceStatus.ERROR -> "erro — veja a permissão USB"
    DeviceStatus.UNKNOWN -> "desconhecido"
}
