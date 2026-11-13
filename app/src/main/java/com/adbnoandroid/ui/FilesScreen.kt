package com.adbnoandroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adbnoandroid.AdbClient
import com.adbnoandroid.AppState
import com.adbnoandroid.FileSaver
import com.adbnoandroid.RemoteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gerenciador de arquivos + coleta de logs.
 *
 * Duas metades na mesma tela, porque as duas mexem com arquivos do aparelho:
 * 1. **Navegar** — entra e sai de diretórios, recebe (pull) um arquivo e envia
 *    (push) do armazenamento do celular.
 * 2. **Coletar** — puxa logs prontos do aparelho (logcat, kernel/dmesg,
 *    bugreport) e deixa em Downloads, para anexar ou compartilhar.
 *
 * Tudo é lido do aparelho pelo protocolo sync; nada é inventado.
 */
@Composable
fun FilesScreen(state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val target = state.selectedTarget
    val snapshot = state.selectedSnapshot()
    val canFiles = snapshot?.mode?.supportsFiles == true

    var path by remember { mutableStateOf("/sdcard") }
    val entries = remember { mutableStateListOf<RemoteEntry>() }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var logs by remember { mutableStateOf("") }
    var logsTitle by remember { mutableStateOf("") }

    fun notify(text: String, error: Boolean = false) {
        message = text
        messageIsError = error
    }

    fun reload() {
        if (target == null) return
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    AdbClient.listDir(target, path)
                } catch (e: Exception) {
                    null
                }
            }
            if (result == null) {
                notify("Não consegui listar $path. O aparelho está em modo ADB?", true)
            } else {
                entries.clear()
                entries.addAll(result.sortedWith(
                    compareByDescending<RemoteEntry> { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                ))
                message = null
            }
            loading = false
        }
    }

    // Carrega ao abrir e sempre que troca de aparelho.
    LaunchedEffect(target?.key(), path) {
        if (target != null && canFiles) reload()
    }

    // Enviar arquivo do celular → aparelho
    val pickUpload = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        val name = queryDisplayName(context, uri) ?: "arquivo"
        busy = "Enviando $name"
        notify("Enviando $name para $path…")
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val local = copyToCache(context, uri, name)
                if (local == null) {
                    false
                } else {
                    try {
                        AdbClient.push(target, local, "$path/$name")
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
            }
            busy = null
            if (ok) {
                notify("$name enviado para $path")
                reload()
            } else {
                notify("Falha ao enviar $name. O caminho existe e é gravável?", true)
            }
        }
    }

    /** Recebe um arquivo do aparelho para Downloads. */
    fun download(entry: RemoteEntry) {
        if (target == null) return
        busy = "Recebendo ${entry.name}"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val dir = FileSaver.saveDir(context)
                    val dest = File(dir, FileSaver.cleanName(entry.name))
                    AdbClient.pull(target, joinRemote(path, entry.name), dest)
                    // No Android 10+ também publica em Downloads público.
                    FileSaver.publishToPublicDownloads(context, dest)
                    dest.name
                } catch (e: Exception) {
                    null
                }
            }
            busy = null
            if (result != null) {
                notify("Recebido: $result (em Downloads do app)")
            } else {
                notify("Falha ao receber ${entry.name}.", true)
            }
        }
    }

    /** Coleta um log do aparelho e mostra o começo na tela. */
    fun collect(title: String, baseName: String, command: String, ext: String = "txt") {
        if (target == null) return
        busy = "Coletando $title"
        logsTitle = title
        logs = ""
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val res = AdbClient.shell(target, command, timeoutMs = 300_000)
                    val body = res.combined()
                    if (body.isBlank()) {
                        "O aparelho não devolveu nada para: $command"
                    } else {
                        // Guarda também em arquivo, para anexar/compartilhar.
                        FileSaver.saveText(context, baseName, body, ext)
                        body
                    }
                } catch (e: Exception) {
                    "Falha ao coletar: ${e.message}"
                }
            }
            logs = text
            busy = null
        }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        if (target == null || !canFiles) {
            item {
                InfoCard(
                    title = "Sem acesso a arquivos",
                    text = if (target == null) {
                        "Conecte um aparelho por cabo OTG na tela CMD."
                    } else {
                        "O aparelho está em modo ${snapshot?.mode?.label ?: "desconhecido"}. " +
                            "Arquivos e logs só funcionam em modo ADB normal."
                    }
                )
            }
        } else {
            item { PathBar(path, loading) { path = it } }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { pickUpload.launch(arrayOf("*/*")) },
                        enabled = busy == null,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Enviar", fontSize = 13.sp)
                    }
                    FilledTonalButton(
                        onClick = { reload() },
                        enabled = busy == null && !loading,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Atualizar", fontSize = 13.sp)
                    }
                }
            }

            message?.let { text ->
                item {
                    InfoCard(
                        title = if (messageIsError) "Não deu certo" else "Feito",
                        text = text,
                        error = messageIsError
                    )
                }
            }
            busy?.let { what ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(what, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ---------------------------------------------------- listagem
            if (entries.isEmpty() && !loading) {
                item {
                    InfoCard(
                        title = "Pasta vazia",
                        text = "Nada em $path (ou sem permissão de leitura)."
                    )
                }
            }
            items(entries, key = { it.name }) { entry ->
                EntryRow(
                    entry = entry,
                    onOpen = {
                        if (entry.isDirectory) {
                            path = joinRemote(path, entry.name)
                        } else {
                            download(entry)
                        }
                    }
                )
            }

            // ---------------------------------------------------- logs
            item { SectionTitle("Coletar logs do aparelho") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LogButton("Logcat (últimas 500 linhas)", busy) {
                        collect(
                            "logcat",
                            "logcat",
                            "logcat -d -t 500"
                        )
                    }
                    LogButton("Kernel / dmesg", busy) {
                        collect("kernel", "kernel", "dmesg")
                    }
                    LogButton("Bugreport resumido", busy) {
                        collect(
                            "bugreport",
                            "bugreport",
                            "dumpsys -l && echo && getprop"
                        )
                    }
                    LogButton("Propriedades do sistema", busy) {
                        collect("propriedades", "propriedades", "getprop")
                    }
                    LogButton("Pacotes instalados", busy) {
                        collect("pacotes", "pacotes", "pm list packages -f")
                    }
                }
            }

            if (logs.isNotBlank()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = LocalAdbColors.current.consoleBg
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    logsTitle.ifBlank { "log" },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = LocalAdbColors.current.consoleDim,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { logs = "" }) {
                                    Text("Fechar", fontSize = 11.sp)
                                }
                            }
                            Text(
                                logs.take(4000) + if (logs.length > 4000) {
                                    "\n… (o arquivo completo está em Downloads)"
                                } else "",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = LocalAdbColors.current.consoleText
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---------------------------------------------------------------- peças

/**
 * Barra de caminho editável.
 *
 * Editável porque se a pasta atual não existir (ou não for legível) o usuário
 * precisa conseguir digitar outro caminho — só o botão de subir não resolve.
 */
@Composable
private fun PathBar(path: String, loading: Boolean, onNavigate: (String) -> Unit) {
    var draft by remember(path) { mutableStateOf(path) }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                placeholder = { Text("/sdcard", fontSize = 12.sp) },
                leadingIcon = {
                    IconButton(
                        onClick = { onNavigate(parentOf(path)) },
                        enabled = path != "/"
                    ) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Subir")
                    }
                },
                trailingIcon = {
                    when {
                        loading -> CircularProgressIndicator(
                            Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        else -> TextButton(
                            onClick = {
                                val clean = draft.trim().ifBlank { "/sdcard" }
                                onNavigate(clean)
                            }
                        ) { Text("ir", fontSize = 12.sp) }
                    }
                }
            )
        }
    }
}

@Composable
private fun EntryRow(entry: RemoteEntry, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (entry.isDirectory) "📁" else "📄", fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(entry.permissionString())
                        append(" · ")
                        append(if (entry.isDirectory) "pasta" else formatSize(entry.size))
                        append(" · ")
                        append(formatDate(entry.mtime))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.isDirectory) {
                Text("›", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "receber",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun LogButton(label: String, busy: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = busy == null,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📋", fontSize = 14.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text("coletar", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun InfoCard(title: String, text: String, error: Boolean = false) {
    val c = LocalAdbColors.current
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (error) {
                c.error.copy(alpha = 0.10f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (error) c.error else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------- util

private fun joinRemote(base: String, name: String): String =
    (if (base.endsWith("/")) base else "$base/") + name

private fun parentOf(path: String): String {
    val clean = path.trimEnd('/')
    if (clean.isEmpty() || clean == "/") return "/"
    val cut = clean.substringBeforeLast('/', "missing")
    if (cut == "missing") return "/"
    return cut.ifBlank { "/" }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private fun formatDate(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "—"
    return try {
        SimpleDateFormat("dd/MM/yy HH:mm", Locale.US).format(Date(epochSeconds * 1000))
    } catch (_: Exception) {
        "—"
    }
}
