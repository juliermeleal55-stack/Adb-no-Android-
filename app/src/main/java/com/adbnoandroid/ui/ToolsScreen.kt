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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Aba Ferramentas — todas as ações do platform-tools organizadas por tarefa:
 *
 * - **Instalar APK** (streaming, com flags e progresso)
 * - **Apps** (listar, desinstalar, limpar dados, forçar parada)
 * - **Sistema** (reboot/recovery/bootloader/sideload, root, remount, tcpip)
 * - **Sideload de ROM** (com progresso e validação)
 * - **Diagnóstico** (bugreport, dumpsys, props, partições)
 *
 * Toda ação destrutiva pede confirmação mostrando o aparelho afetado.
 */
@Composable
fun ToolsScreen(state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAdbColors.current

    val target = state.selectedTarget
    val mode = state.selectedMode()
    val adbOk = target != null && (mode == DeviceMode.ADB || mode == DeviceMode.RECOVERY)

    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var progressLabel by remember { mutableStateOf("") }
    var output by remember { mutableStateOf<String?>(null) }
    var outputTitle by remember { mutableStateOf("Resultado") }
    var outputTone by remember { mutableStateOf(NoticeTone.INFO) }

    var installFlags by remember { mutableStateOf(InstallFlags()) }
    var confirmAction by remember { mutableStateOf<DestructiveAction?>(null) }
    var packageName by remember { mutableStateOf("") }
    var tcpipPort by remember { mutableStateOf("5555") }
    var backupPackage by remember { mutableStateOf("") }
    var sideloadFile by remember { mutableStateOf<File?>(null) }
    var sideloadProgress by remember { mutableStateOf(0f) }
    var sideloadMessage by remember { mutableStateOf("") }
    var sideloadRunning by remember { mutableStateOf(false) }

    fun show(title: String, text: String, tone: NoticeTone = NoticeTone.INFO) {
        outputTitle = title
        output = text
        outputTone = tone
    }

    fun runAction(
        label: String,
        block: suspend () -> Pair<Boolean, String>
    ) {
        if (busy != null) return
        busy = label
        progress = 0f
        progressLabel = label
        scope.launch {
            val (ok, text) = withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: Exception) {
                    false to (e.message ?: e.javaClass.simpleName)
                }
            }
            busy = null
            show(
                if (ok) "Sucesso" else "Falhou",
                text,
                if (ok) NoticeTone.INFO else NoticeTone.ERROR
            )
            if (ok) state.logOk(label, target, text.take(200))
            else state.logFail(label, target, text.take(200))
        }
    }

    // Seletor de APK.
    val pickApk = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val t = state.selectedTarget ?: return@rememberLauncherForActivityResult
        val name = queryDisplayName(context, uri) ?: "app.apk"
        val cached = copyToCache(context, uri, name) ?: run {
            show("Erro", "Não foi possível ler o APK escolhido.", NoticeTone.ERROR)
            return@rememberLauncherForActivityResult
        }
        if (!cached.name.lowercase().endsWith(".apk") && !cached.name.lowercase().endsWith(".apks")) {
            show(
                "Arquivo inválido",
                "Escolha um arquivo .apk (ou .apks). O arquivo selecionado: ${cached.name}",
                NoticeTone.ERROR
            )
            return@rememberLauncherForActivityResult
        }
        busy = "Instalando ${cached.name}"
        progress = 0f
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AdbClient.install(
                    t, cached,
                    reinstall = installFlags.reinstall,
                    grantPermissions = installFlags.grantPermissions,
                    downgrade = installFlags.downgrade
                ) { msg, p ->
                    progressLabel = msg
                    progress = p
                }
            }
            busy = null
            val success = !result.failed && result.output.contains("Success", true)
            show(
                if (success) "APK instalado" else "Falha na instalação",
                buildString {
                    append(result.combined().ifBlank { "(sem saída do aparelho)" })
                    if (result.exitCode != null) append("\n\nexit code: ${result.exitCode}")
                },
                if (success) NoticeTone.INFO else NoticeTone.ERROR
            )
            if (success) state.logOk("install ${cached.name}", t)
            else state.logFail("install ${cached.name}", t, result.combined().take(200))
            cached.delete()
        }
    }

    val pickZip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = queryDisplayName(context, uri) ?: "update.zip"
        val cached = copyToCache(context, uri, name)
        if (cached == null) {
            sideloadMessage = "Não foi possível ler o arquivo escolhido."
            return@rememberLauncherForActivityResult
        }
        if (!cached.name.lowercase().endsWith(".zip")) {
            sideloadMessage = "O sideload aceita apenas arquivos .zip (ROM/OTA)."
            return@rememberLauncherForActivityResult
        }
        sideloadFile = cached
        sideloadMessage = ""
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            TargetSelector(
                targets = state.allTargets(includeFastboot = false),
                selected = target,
                onSelect = { state.select(it) },
                statusOf = { state.statusOf(it) },
                emptyText = "Conecte um aparelho para usar as ferramentas"
            )
        }

        if (!adbOk) {
            item {
                NoticeBox(
                    when {
                        target == null -> "Nenhum aparelho selecionado."
                        state.needsAuthorization(target) ->
                            "Autorize o app no aparelho (toque em Permitir) para usar as ferramentas."
                        else -> "O aparelho está no modo ${mode.label}. " +
                            "Instalação, arquivos e shell precisam do modo ADB ou Recovery."
                    }
                )
            }
        }

        if (busy != null) {
            item {
                SectionCard {
                    ProgressBlock(
                        label = busy!!,
                        fraction = progress,
                        indeterminate = progress <= 0f,
                        detail = progressLabel
                    )
                }
            }
        }

        // ---------------------------------------------------- instalar APK
        item {
            SectionCard(
                title = "📦 Instalar APK",
                subtitle = "O arquivo é enviado por streaming, sem copiar no armazenamento do " +
                    "aparelho. Funciona também com APKs grandes."
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = installFlags.reinstall,
                        onCheckedChange = {
                            installFlags = installFlags.copy(reinstall = it)
                        }
                    )
                    Text("Reinstalar (-r)", fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Checkbox(
                        checked = installFlags.grantPermissions,
                        onCheckedChange = {
                            installFlags = installFlags.copy(grantPermissions = it)
                        }
                    )
                    Text("Conceder permissões (-g)", fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = installFlags.downgrade,
                        onCheckedChange = {
                            installFlags = installFlags.copy(downgrade = it)
                        }
                    )
                    Text("Permitir downgrade (-d)", fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                    enabled = adbOk && busy == null && mode == DeviceMode.ADB,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Escolher APK e instalar")
                }
            }
        }

        // ---------------------------------------------------- apps
        item {
            SectionCard(
                title = "🧩 Apps instalados",
                subtitle = "Listar, desinstalar, limpar dados e forçar parada"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            runAction("pm list packages") {
                                val res = AdbClient.listPackages(target!!)
                                res.ok to res.combined()
                            }
                        },
                        enabled = adbOk && busy == null,
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Listar apps", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            runAction("pm list packages -3") {
                                val res = AdbClient.listPackages(target!!, onlyThirdParty = true)
                                res.ok to res.combined()
                            }
                        },
                        enabled = adbOk && busy == null,
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Só os instalados", fontSize = 12.sp) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("nome.do.pacote", fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp
                    )
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            val pkg = packageName.trim()
                            if (pkg.isEmpty()) return@OutlinedButton
                            // Captura o aparelho AGORA: se ele for desconectado
                            // enquanto o diálogo está aberto, o !! estouraria.
                            val device = target ?: return@OutlinedButton
                            confirmAction = DestructiveAction(
                                title = "Desinstalar app?",
                                message = "O pacote \"$pkg\" será removido do aparelho. " +
                                    "Os dados do app também são apagados.",
                                confirmText = "Desinstalar",
                                action = {
                                    runAction("uninstall $pkg") {
                                        val res = AdbClient.uninstall(device, pkg)
                                        res.ok to res.combined()
                                    }
                                }
                            )
                        },
                        enabled = adbOk && busy == null && packageName.isNotBlank(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Desinstalar", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            val pkg = packageName.trim()
                            runAction("pm clear $pkg") {
                                val res = AdbClient.shell(target!!, "pm clear $pkg")
                                res.ok to res.combined()
                            }
                        },
                        enabled = adbOk && busy == null && packageName.isNotBlank(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Limpar dados", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            val pkg = packageName.trim()
                            runAction("force-stop $pkg") {
                                val res = AdbClient.shell(target!!, "am force-stop $pkg")
                                res.ok to res.combined()
                            }
                        },
                        enabled = adbOk && busy == null && packageName.isNotBlank(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Parar app", fontSize = 12.sp) }
                }
            }
        }

        // ---------------------------------------------------- sistema
        item {
            SectionCard(
                title = "⚙️ Sistema e energia",
                subtitle = "Comandos do adbd executados de verdade — o resultado é o do aparelho"
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionButton("Reiniciar", {
                        // Captura o aparelho agora: se ele cair, o dialogo nao usa !!
                        val device = target ?: return@ActionButton
                        confirmAction = DestructiveAction(
                            title = "Reiniciar o aparelho?",
                            message = "O aparelho vai reiniciar agora. Salve o que estiver fazendo nele.",
                            confirmText = "Reiniciar",
                            action = {
                                runAction("reboot") {
                                    val res = AdbClient.reboot(device)
                                    true to (res.combined().ifBlank { "Comando enviado." })
                                }
                            }
                        )
                    }, enabled = adbOk && busy == null, danger = true)
                    ActionButton("Recovery", {
                        // Captura o aparelho agora: se ele cair, o dialogo nao usa !!
                        val device = target ?: return@ActionButton
                        confirmAction = DestructiveAction(
                            title = "Reiniciar no recovery?",
                            message = "O aparelho vai reiniciar no modo recovery.",
                            confirmText = "Reiniciar",
                            action = {
                                runAction("reboot recovery") {
                                    val res = AdbClient.reboot(device, "recovery")
                                    true to res.combined().ifBlank { "Comando enviado." }
                                }
                            }
                        )
                    }, enabled = adbOk && busy == null)
                    ActionButton("Bootloader", {
                        // Captura o aparelho agora: se ele cair, o dialogo nao usa !!
                        val device = target ?: return@ActionButton
                        confirmAction = DestructiveAction(
                            title = "Reiniciar no bootloader?",
                            message = "O aparelho vai reiniciar no bootloader (fastboot).",
                            confirmText = "Reiniciar",
                            action = {
                                runAction("reboot bootloader") {
                                    val res = AdbClient.reboot(device, "bootloader")
                                    true to res.combined().ifBlank { "Comando enviado." }
                                }
                            }
                        )
                    }, enabled = adbOk && busy == null)
                    ActionButton("Modo sideload", {
                        runAction("reboot sideload") {
                            val res = AdbClient.rebootSideload(device)
                            true to res.combined().ifBlank { "Comando enviado." }
                        }
                    }, enabled = adbOk && busy == null)
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionButton("adb root", {
                        runAction("adb root") {
                            val res = AdbClient.root(target!!)
                            val text = res.output.ifBlank { "(sem saída)" }
                            val ok = text.contains("restarting", true) ||
                                text.contains("already running as root", true)
                            ok to text
                        }
                    }, enabled = adbOk && busy == null)
                    ActionButton("adb unroot", {
                        runAction("adb unroot") {
                            val res = AdbClient.unroot(target!!)
                            true to res.output.ifBlank { "(sem saída)" }
                        }
                    }, enabled = adbOk && busy == null)
                    ActionButton("adb remount", {
                        runAction("adb remount") {
                            val res = AdbClient.remount(target!!)
                            val ok = res.output.contains("succeeded", true)
                            ok to res.output.ifBlank { "(sem saída)" }
                        }
                    }, enabled = adbOk && busy == null)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = tcpipPort,
                        onValueChange = { tcpipPort = it.filter { ch -> ch.isDigit() }.take(5) },
                        modifier = Modifier.width(96.dp),
                        label = { Text("Porta", fontSize = 11.sp) },
                        singleLine = true
                    )
                    Spacer(Modifier.width(8.dp))
                    ActionButton("adb tcpip", {
                        val port = tcpipPort.toIntOrNull() ?: 5555
                        runAction("adb tcpip $port") {
                            val res = AdbClient.tcpip(target!!, port)
                            val ok = res.output.contains("restarting", true)
                            val ip = if (ok) AdbClient.wifiIp(target) else null
                            ok to buildString {
                                append(res.output.ifBlank { "(sem saída)" })
                                if (ip != null) {
                                    append("\n\nAgora conecte por Wi‑Fi usando:\n")
                                    append("$ip:$port")
                                    append("\n\n(O IP pode ser adicionado na aba Dispositivos.)")
                                }
                            }
                        }
                    }, enabled = adbOk && busy == null)
                    Spacer(Modifier.width(8.dp))
                    ActionButton("Ver IP Wi‑Fi", {
                        runAction("ip do aparelho") {
                            val ip = AdbClient.wifiIp(target!!)
                            (ip != null) to (ip ?: "Não foi possível ler o IP (Wi‑Fi desligado?).")
                        }
                    }, enabled = adbOk && busy == null)
                    ActionButton("Reconectar", {
                        runAction("reconectar") {
                            val res = AdbClient.reconnect(target!!)
                            res.ok to res.output.ifBlank { "(sem saída)" }
                        }
                    }, enabled = adbOk && busy == null)
                }
            }
        }

        // ---------------------------------------------------- backup
        item {
            SectionCard(
                title = "💾 Backup de app",
                subtitle = "Usa o Backup Manager do aparelho. O \"adb backup\" clássico foi " +
                    "desativado no Android 12+ — a saída mostra o que o aparelho respondeu"
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = backupPackage,
                        onValueChange = { backupPackage = it.trim() },
                        modifier = Modifier.weight(1f),
                        label = { Text("Pacote (ex.: com.whatsapp)", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    ActionButton("Fazer backup", {
                        runAction("backup de $backupPackage") {
                            val res = AdbClient.backupApp(target!!, backupPackage)
                            res.ok to res.combined().ifBlank { "(sem saída)" }
                        }
                    }, enabled = adbOk && busy == null && backupPackage.isNotBlank())
                }
            }
        }

        // ---------------------------------------------------- sideload
        item {
            SectionCard(
                title = "📦 Sideload de ROM / OTA",
                subtitle = "Envia o .zip para o recovery, que instala sozinho (igual ao " +
                    "\"adb sideload\" do PC)"
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { pickZip.launch(arrayOf("application/zip", "*/*")) },
                        enabled = !sideloadRunning,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (sideloadFile == null) "Escolher .zip" else "Trocar .zip")
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            sideloadFile?.let { f ->
                                "${f.name} · ${formatBytes(f.length())}"
                            } ?: "Nenhum arquivo escolhido",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                        if (sideloadMessage.isNotBlank()) {
                            Text(
                                sideloadMessage,
                                style = MaterialTheme.typography.labelSmall,
                                color = c.error
                            )
                        }
                    }
                }
                if (sideloadRunning) {
                    Spacer(Modifier.height(10.dp))
                    ProgressBlock(
                        label = "Enviando o pacote…",
                        fraction = sideloadProgress,
                        detail = "Não desconecte o cabo nem o Wi‑Fi."
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val t = state.selectedTarget
                        val f = sideloadFile
                        if (t == null || f == null) return@Button
                        sideloadRunning = true
                        sideloadProgress = 0f
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    AdbClient.sideload(t, f) { p ->
                                        sideloadProgress = p.fraction
                                    }
                                    null
                                } catch (e: Exception) {
                                    e.message ?: "falha no sideload"
                                }
                            }
                            sideloadRunning = false
                            if (result == null) {
                                show(
                                    "Sideload concluído",
                                    "O pacote foi enviado e o recovery está instalando. " +
                                        "Acompanhe a tela do aparelho — ele reinicia no fim.",
                                    NoticeTone.INFO
                                )
                                state.logOk("sideload ${f.name}", t)
                            } else {
                                show("Sideload falhou", result, NoticeTone.ERROR)
                                state.logFail("sideload ${f.name}", t, result)
                            }
                        }
                    },
                    enabled = sideloadFile != null && !sideloadRunning &&
                        (mode == DeviceMode.SIDELOAD || mode == DeviceMode.RECOVERY),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Iniciar sideload")
                }
                if (mode != DeviceMode.SIDELOAD && mode != DeviceMode.RECOVERY) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Antes: no recovery do aparelho escolha \"Apply update from ADB\". " +
                            "O modo atual é ${mode.label}.",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.warning
                    )
                }
            }
        }

        // ---------------------------------------------------- diagnóstico
        item {
            SectionCard(
                title = "🩺 Diagnóstico rápido",
                subtitle = "Saída direta do aparelho, sem resumos"
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionButton("Propriedades", {
                        runAction("getprop") {
                            val res = AdbClient.shell(target!!, "getprop")
                            res.ok to res.combined()
                        }
                    }, enabled = adbOk && busy == null)
                    ActionButton("Partições", {
                        runAction("partições") {
                            val res = AdbClient.shell(
                                target!!,
                                "cat /proc/partitions; echo; df -h"
                            )
                            res.ok to res.combined()
                        }
                    }, enabled = adbOk && busy == null)
                    ActionButton("Memória", {
                        runAction("memória") {
                            val res = AdbClient.shell(target!!, "free -m; echo; cat /proc/meminfo | head -20")
                            res.ok to res.combined()
                        }
                    }, enabled = adbOk && busy == null)
                    ActionButton("Kernel/boot", {
                        runAction("kernel") {
                            val res = AdbClient.shell(
                                target!!,
                                "uname -a; echo; getprop | grep -E 'boot|kernel' | head -40"
                            )
                            res.ok to res.combined()
                        }
                    }, enabled = adbOk && busy == null)
                    ActionButton("Hardware", {
                        runAction("hardware") {
                            val res = AdbClient.shell(
                                target!!,
                                "cat /proc/cpuinfo | head -30; echo; getprop | grep -E " +
                                    "'ro.hardware|ro.board|ro.soc|ro.product.cpu' "
                            )
                            res.ok to res.combined()
                        }
                    }, enabled = adbOk && busy == null)
                }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }
    }

    // ------------------------------------------------------- diálogos
    output?.let { text ->
        OutputDialog(
            title = outputTitle,
            text = text,
            onDismiss = { output = null },
            tone = outputTone
        )
    }

    confirmAction?.let { action ->
        ConfirmDangerDialog(
            title = action.title,
            targetLabel = target?.label() ?: "—",
            message = action.message,
            confirmText = action.confirmText,
            onConfirm = {
                confirmAction = null
                action.action()
            },
            onDismiss = { confirmAction = null }
        )
    }
}

data class InstallFlags(
    val reinstall: Boolean = true,
    val grantPermissions: Boolean = false,
    val downgrade: Boolean = false
)

data class DestructiveAction(
    val title: String,
    val message: String,
    val confirmText: String,
    val action: () -> Unit
)

fun queryDisplayName(context: android.content.Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
} catch (_: Exception) {
    uri.lastPathSegment
}

/** Copia o conteúdo do URI para o cache do app com o nome original. */
fun copyToCache(context: android.content.Context, uri: Uri, name: String): File? = try {
    val dir = File(context.cacheDir, "picked")
    dir.mkdirs()
    val safeName = FileSaver.cleanName(name)
    val file = File(dir, safeName)
    context.contentResolver.openInputStream(uri)?.use { ins ->
        file.outputStream().use { outs -> ins.copyTo(outs, 128 * 1024) }
    } ?: return null
    file
} catch (_: Exception) {
    null
}
