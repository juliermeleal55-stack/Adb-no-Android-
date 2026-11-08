package com.adbnoandroid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Para onde vai o sideload. */
sealed class SideloadTarget {
    data class UsbT(val info: AdbUsb.UsbDeviceInfo) : SideloadTarget()
    data class NetT(val device: AdbDeviceInfo) : SideloadTarget()

    fun label(): String = when (this) {
        is UsbT -> info.label()
        is NetT -> device.label()
    }
}

/**
 * Aba Sideload — o `adb sideload <zip>` do PC: envia um update.zip para o
 * recovery do aparelho de destino (USB ou Wi‑Fi) com barra de progresso.
 */
@Composable
fun SideloadTab(
    usbDevices: List<AdbUsb.UsbDeviceInfo>,
    usbStatus: Map<String, DeviceStatus>,
    networkDevices: List<AdbDeviceInfo>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<SideloadTarget?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf("") }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val f = File(context.cacheDir, "sideload_${System.currentTimeMillis()}.zip")
            context.contentResolver.openInputStream(uri)?.use { ins ->
                f.outputStream().use { os -> ins.copyTo(os) }
            } ?: throw RuntimeException("não foi possível ler o arquivo escolhido")
            file = f
            message = ""
        } catch (e: Exception) {
            message = "Erro ao abrir o arquivo: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        Text(
            "Sideload (adb sideload)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Envia um arquivo .zip para o RECOVERY do aparelho de destino — " +
                "a mesma função do \"adb sideload\" do PC (flashing de custom ROM, GApps…).",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(12.dp))

        // ---------------- 1. dispositivo ----------------
        Text("1. Dispositivo de destino", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            usbDevices.forEach { info ->
                FilterChip(
                    selected = target is SideloadTarget.UsbT &&
                        (target as SideloadTarget.UsbT).info.deviceName == info.deviceName,
                    enabled = info.hasPermission,
                    onClick = { target = SideloadTarget.UsbT(info) },
                    label = {
                        Text(
                            info.label() +
                                if (info.hasPermission) "" else " (sem permissão)",
                            fontSize = 12.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TerminalGreen.copy(alpha = 0.2f),
                        selectedLabelColor = TerminalGreen
                    )
                )
            }
            networkDevices.forEach { device ->
                FilterChip(
                    selected = target is SideloadTarget.NetT &&
                        (target as SideloadTarget.NetT).device.address() == device.address(),
                    onClick = { target = SideloadTarget.NetT(device) },
                    label = { Text(device.label(), fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TerminalGreen.copy(alpha = 0.2f),
                        selectedLabelColor = TerminalGreen
                    )
                )
            }
            if (usbDevices.isEmpty() && networkDevices.isEmpty()) {
                Text(
                    "Nenhum dispositivo disponível. Conecte um cabo USB ou adicione um IP " +
                        "na aba Dispositivos.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------------- 2. arquivo ----------------
        Text("2. Arquivo (.zip)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(
                onClick = { pickFile.launch(arrayOf("*/*")) },
                enabled = !running,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TerminalGreen,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(if (file == null) "Escolher arquivo" else "Trocar arquivo")
            }
            file?.let { f ->
                Spacer(Modifier.width(10.dp))
                Text(
                    "${f.name} — ${String.format("%.1f", f.length() / 1048576.0)} MB",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------------- 3. executar ----------------
        if (message.isNotEmpty()) {
            Text(
                message,
                fontSize = 12.sp,
                color = if (message.startsWith("✗")) TerminalRed else TerminalGreen
            )
            Spacer(Modifier.height(8.dp))
        }

        if (running) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Enviando… ${(progress * 100).toInt()}% — não desconecte o cabo nem o Wi‑Fi!",
                fontSize = 12.sp,
                color = TerminalAmber
            )
        } else {
            Button(
                onClick = {
                    val t = target
                    val f = file
                    if (t == null || f == null || running) return@Button
                    running = true
                    progress = 0f
                    message = ""
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                when (t) {
                                    is SideloadTarget.UsbT -> {
                                        val d = AdbUsb.deviceByName(context, t.info.deviceName)
                                            ?: throw AdbConnectException("Dispositivo USB desconectado.")
                                        AdbSideload.sideloadUsb(d, f) { sent, total ->
                                            scope.launch {
                                                if (total > 0) progress = sent.toFloat() / total
                                            }
                                        }
                                    }
                                    is SideloadTarget.NetT -> {
                                        AdbSideload.sideloadTcp(
                                            t.device.host, t.device.port, f
                                        ) { sent, total ->
                                            scope.launch {
                                                if (total > 0) progress = sent.toFloat() / total
                                            }
                                        }
                                    }
                                }
                            }
                            message =
                                "✔ Sideload concluído — o aparelho deve estar instalando e reiniciando."
                        } catch (e: Exception) {
                            message = "✗ ${e.message ?: "Falha no sideload."}"
                        } finally {
                            running = false
                        }
                    }
                },
                enabled = target != null && file != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TerminalGreen,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Iniciar sideload", fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---------------- aviso ----------------
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = TerminalAmber.copy(alpha = 0.1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "Antes de iniciar",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "• O aparelho de destino precisa estar em RECOVERY com a opção " +
                        "\"Apply update from ADB\" (ou \"Sideload\") selecionada.\n" +
                        "• Use apenas arquivos .zip confiáveis: um flash errado pode " +
                        "deixar o aparelho instável.\n" +
                        "• O envio pode levar alguns minutos (arquivos grandes).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    lineHeight = 18.sp
                )
            }
        }
    }
}
