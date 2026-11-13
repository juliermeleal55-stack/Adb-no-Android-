package com.adbnoandroid

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
 * Aba Fastboot — ferramentas de bootloader (equivalente ao `fastboot` do PC,
 * via USB OTG).
 *
 * Regras de honestidade técnica:
 * - **Nada aparece habilitado sem o aparelho em modo fastboot.**
 * - Cada botão envia o comando real; a resposta é a do bootloader (INFO/FAIL).
 * - Gravar/ apagar partições pede confirmação com o nome do aparelho e da
 *   partição, e partições críticas exigem digitar o nome para confirmar.
 */
@Composable
fun FastbootScreen(state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAdbColors.current

    var vars by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loadingVars by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<String?>(null) }
    var outputTitle by remember { mutableStateOf("") }
    var outputTone by remember { mutableStateOf(NoticeTone.INFO) }
    var partition by remember { mutableStateOf("boot") }
    var imageFile by remember { mutableStateOf<File?>(null) }
    var progress by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var confirmFlash by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    var currentSlot by remember { mutableStateOf("") }

    val fastbootTarget = state.selectedTarget as? DeviceTarget.Fastboot
    val info = fastbootTarget?.info
        ?: state.fastbootDevices.firstOrNull()?.also {
            LaunchedEffect(it.deviceName) { state.select(DeviceTarget.Fastboot(it)) }
        }
    val connected = info != null

    fun run(
        title: String,
        block: suspend (FastbootDeviceInfo) -> FastbootResult
    ) {
        val dev = info ?: return
        scope.launch {
            progress = title to 0f
            val result = withContext(Dispatchers.IO) {
                try {
                    block(dev)
                } catch (e: Exception) {
                    FastbootResult(title, "", false, e.message ?: "erro")
                }
            }
            progress = null
            outputTitle = title
            output = buildString {
                if (result.output.isNotBlank()) append(result.output).append("\n\n")
                if (result.ok) append("✓ OKAY")
                else append("✗ ").append(result.error ?: "FAIL")
            }
            outputTone = if (result.ok) NoticeTone.INFO else NoticeTone.ERROR
            if (result.ok) state.logOk("fastboot $title", fastbootTarget)
            else state.logFail("fastboot $title", fastbootTarget, result.error ?: "")
        }
    }

    fun fetchVars() {
        val dev = info ?: return
        loadingVars = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    AdbClient.fastbootSession(dev).use { session ->
                        val all = session.getVarAll()
                        val slot = session.getVar("current-slot").orEmpty()
                        all to slot
                    }
                } catch (e: Exception) {
                    null
                }
            }
            loadingVars = false
            if (result != null) {
                vars = result.first
                currentSlot = result.second
            } else {
                outputTitle = "getvar all"
                output = "Não foi possível conectar no bootloader. Confira o cabo e se o " +
                    "aparelho continua no modo fastboot."
                outputTone = NoticeTone.ERROR
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = queryDisplayNameFastboot(context, uri) ?: "imagem.img"
        val cached = copyToCache(context, uri, name)
        if (cached == null) {
            outputTitle = "Erro"
            output = "Não foi possível ler a imagem escolhida."
            outputTone = NoticeTone.ERROR
            return@rememberLauncherForActivityResult
        }
        imageFile = cached
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        // ------------------------------------------------ aparelhos
        item {
            SectionCard(
                title = "fastboot devices",
                subtitle = "Aparelhos no bootloader detectados por USB",
                trailing = {
                    TextButton(onClick = { state.refreshFastboot() }) {
                        Text("Atualizar", fontSize = 12.sp)
                    }
                }
            ) {
                if (state.fastbootDevices.isEmpty()) {
                    NoticeBox(
                        "Nenhum aparelho em modo fastboot.\n\n" +
                            "Para entrar: reinicie no bootloader (Opções do desenvolvedor → " +
                            "Reiniciar no bootloader, ou `adb reboot bootloader` na aba Ferramentas). " +
                            "O aparelho mostra uma tela com o modo fastboot e aparece aqui.",
                        tone = NoticeTone.INFO
                    )
                } else {
                    state.fastbootDevices.forEach { dev ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusDot(DeviceStatus.FASTBOOT)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(dev.label(), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    String.format("%04x:%04x", dev.vendorId, dev.productId) +
                                        " · " + dev.deviceName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (!dev.hasPermission) {
                                Button(
                                    onClick = { state.requestFastbootPermission(dev) },
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text("Permitir", fontSize = 12.sp) }
                            } else {
                                FilterChip(
                                    selected = state.selectedTarget?.key() ==
                                        DeviceTarget.Fastboot(dev).key(),
                                    onClick = { state.select(DeviceTarget.Fastboot(dev)) },
                                    label = { Text("Selecionar", fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (!connected) {
            item {
                NoticeBox(
                    "As ferramentas abaixo ficam disponíveis quando um aparelho entra em " +
                        "modo fastboot. Este app não mostra como disponível nada que o " +
                        "aparelho não possa executar.",
                    tone = NoticeTone.WARNING
                )
            }
        }

        // ------------------------------------------------ informações
        item {
            SectionCard(
                title = "getvar / informações do bootloader",
                subtitle = "Lê as variáveis reais do bootloader (uma a uma)"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { fetchVars() },
                        enabled = connected && !loadingVars,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (loadingVars) {
                            CircularProgressIndicator(
                                Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(6.dp))
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("Ler todas (getvar all)", fontSize = 12.sp)
                    }
                }
                if (vars.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    val keys = listOf(
                        "product", "variant", "version-bootloader", "version-baseband",
                        "serialno", "secure", "unlocked", "current-slot",
                        "slot-count", "battery-voltage", "max-download-size",
                        "hw-revision", "is-userspace"
                    )
                    keys.forEach { key ->
                        vars[key]?.let { value -> InfoRow(key, value, mono = true) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${vars.size} variáveis lidas. Abra \"Todas as variáveis\" para ver o resto.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = {
                        outputTitle = "getvar all"
                        output = vars.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                        outputTone = NoticeTone.INFO
                    }) { Text("Todas as variáveis", fontSize = 12.sp) }
                }
            }
        }

        // ------------------------------------------------ gravar
        item {
            SectionCard(
                title = "Gravar imagem (fastboot flash)",
                subtitle = "download + flash: a imagem é enviada e só então gravada na partição"
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = partition,
                        onValueChange = { partition = it.trim() },
                        modifier = Modifier.width(150.dp),
                        label = { Text("Partição", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { pickImage.launch(arrayOf("*/*")) },
                        enabled = connected,
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(if (imageFile == null) "Escolher .img" else "Trocar .img", fontSize = 12.sp) }
                }
                if (currentSlot.isNotBlank() && currentSlot != "a" && currentSlot != "b") {
                    Text(
                        "Slot atual: $currentSlot",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                imageFile?.let { f ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${f.name} · ${formatBytes(f.length())}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    val remote = partition.trim()
                    if (Fastboot.DANGEROUS_PARTITIONS.contains(remote)) {
                        Spacer(Modifier.height(6.dp))
                        NoticeBox(
                            "A partição \"$remote\" é crítica. Uma imagem errada pode " +
                                "inutilizar o aparelho (brick). Confirme que o arquivo é " +
                                "exatamente o da sua versão/modelo.",
                            tone = NoticeTone.WARNING
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { confirmFlash = true },
                    enabled = connected && imageFile != null && partition.isNotBlank() &&
                        progress == null,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = c.warning,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Gravar na partição", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        progress?.let { current ->
            item {
                SectionCard {
                    ProgressBlock(
                        label = current.first,
                        fraction = current.second,
                        indeterminate = current.second <= 0f,
                        detail = "Não desconecte o cabo."
                    )
                }
            }
        }

        // ------------------------------------------------ ações
        item {
            SectionCard(
                title = "Ações do bootloader",
                subtitle = "Cada botão envia o comando real; a resposta é do aparelho"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ActionButton("reboot", {
                            run("reboot") { dev ->
                                AdbClient.fastbootSession(dev).use { session -> session.reboot() }
                            }
                        }, enabled = connected && progress == null, primary = true)
                        ActionButton("reboot-bootloader", {
                            run("reboot-bootloader") { dev ->
                                AdbClient.fastbootSession(dev).use { session -> session.reboot("bootloader") }
                            }
                        }, enabled = connected && progress == null)
                        ActionButton("reboot-recovery", {
                            run("reboot-recovery") { dev ->
                                AdbClient.fastbootSession(dev).use { session -> session.reboot("recovery") }
                            }
                        }, enabled = connected && progress == null)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ActionButton("boot (imagem)", {
                            val f = imageFile
                            if (f == null) {
                                outputTitle = "Boot"
                                output = "Escolha uma imagem (boot.img) primeiro."
                                outputTone = NoticeTone.WARNING
                                return@ActionButton
                            }
                            run("boot") { dev ->
                                AdbClient.fastbootBoot(dev, f) { p ->
                                    progress = p.message to p.fraction
                                }
                            }
                        }, enabled = connected && imageFile != null && progress == null)
                        ActionButton("erase", {
                            confirmErase = true
                        }, enabled = connected && progress == null, danger = true)
                        ActionButton("continue", {
                            run("continue") { dev ->
                                AdbClient.fastbootSession(dev).use { it.command("continue") }
                            }
                        }, enabled = connected && progress == null)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        "Comandos como flashall/update dependem de arquivos e do suporte do " +
                            "bootloader; use o terminal ADB para `fastboot` avançado via " +
                            "script no aparelho quando o bootloader oferecer o comando.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }

    output?.let { text ->
        OutputDialog(
            title = outputTitle,
            text = text,
            onDismiss = { output = null },
            tone = outputTone
        )
    }

    if (confirmFlash) {
        val dev = info
        val part = partition.trim()
        ConfirmDangerDialog(
            title = "Gravar imagem na partição?",
            targetLabel = dev?.label() ?: "—",
            message = "A imagem \"${imageFile?.name ?: ""}\" será gravada em " +
                "\"$part\". Uma imagem incompatível pode impedir o aparelho de iniciar. " +
                "Só continue se o arquivo é o correto para este modelo/versão.",
            confirmText = "Gravar",
            onConfirm = {
                confirmFlash = false
                val f = imageFile
                if (dev != null && f != null && part.isNotEmpty()) {
                    run("flash $part") { d ->
                        AdbClient.fastbootFlash(d, part, f) { p ->
                            progress = p.message to p.fraction
                        }
                    }
                }
            },
            onDismiss = { confirmFlash = false },
            extraConfirmWord = if (Fastboot.DANGEROUS_PARTITIONS.contains(part)) part else null
        )
    }

    if (confirmErase) {
        val dev = info
        val part = partition.trim()
        val blocked = Fastboot.NEVER_ERASE.contains(part.lowercase())
        ConfirmDangerDialog(
            title = if (blocked) "Partição protegida" else "Apagar partição?",
            targetLabel = dev?.label() ?: "—",
            message = if (blocked) {
                "\"$part\" não pode ser apagada por este app. Apagar uma dessas " +
                    "partições (bootloader, modem, efs, super…) deixa o aparelho " +
                    "definitivamente inutilizável, sem recuperação pelo fastboot."
            } else {
                "A partição \"$part\" será apagada (0xFF). " +
                    "Isso é destrutivo e não pode ser desfeito."
            },
            confirmText = "Apagar",
            onConfirm = {
                confirmErase = false
                // A palavra de confirmação passa pelo diálogo, mas NEVER_ERASE
                // é bloqueante por decisão do app: digitar o nome não autoriza.
                if (dev != null && part.isNotEmpty() && !blocked) {
                    run("erase $part") { d ->
                        AdbClient.fastbootSession(d).use { it.erase(part) }
                    }
                }
            },
            onDismiss = { confirmErase = false },
            extraConfirmWord = if (blocked) null else if (
                Fastboot.DANGEROUS_PARTITIONS.contains(part)
            ) part else null,
            confirmEnabled = !blocked
        )
    }
}

private fun queryDisplayNameFastboot(context: android.content.Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
} catch (_: Exception) {
    uri.lastPathSegment
}
