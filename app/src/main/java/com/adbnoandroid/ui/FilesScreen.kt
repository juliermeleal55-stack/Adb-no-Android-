package com.adbnoandroid

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Estado de uma transferência em andamento. */
data class TransferState(
    val label: String,
    val done: Long,
    val total: Long?,
    val startedAt: Long,
    val finished: Boolean = false,
    val error: String? = null,
    val speed: Double = 0.0
)

/**
 * Aba Arquivos — explorador do aparelho conectado (o que o `adb shell ls`,
 * `adb push` e `adb pull` fazem no PC, com barra de progresso e velocidade).
 *
 * - Navega pelas pastas do dispositivo (LIST do protocolo sync).
 * - **Enviar** arquivos do celular controlador (seletor do sistema).
 * - **Baixar** arquivos para a pasta de Downloads do app.
 * - Criar pasta, renomear/mover, apagar (com confirmação).
 * - Transferências grandes com progresso, velocidade e tamanho.
 */
@Composable
fun FilesScreen(state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAdbColors.current

    var path by remember { mutableStateOf("/sdcard") }
    var pathDraft by remember { mutableStateOf("/sdcard") }
    val entries = remember { mutableStateListOf<RemoteEntry>() }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var transfer by remember { mutableStateOf<TransferState?>(null) }
    var confirmDelete by remember { mutableStateOf<RemoteEntry?>(null) }
    var mkdirDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    val target = state.selectedTarget
    val mode = state.selectedMode()
    val canBrowse = target != null && (mode == DeviceMode.ADB)

    fun load(dir: String) {
        val t = state.selectedTarget ?: return
        loading = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(AdbClient.listDir(t, dir))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            loading = false
            result.onSuccess {
                entries.clear()
                entries.addAll(
                    it.filter { e -> e.name != "." && e.name != ".." }
                        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                )
                path = dir
                pathDraft = dir
            }.onFailure {
                error = it.message ?: "Falha ao listar a pasta."
            }
        }
    }

    LaunchedEffect(target?.key(), mode) {
        if (canBrowse) load(path)
    }

    // Seletor de arquivos para upload.
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val t = state.selectedTarget ?: return@rememberLauncherForActivityResult
        scope.launch {
            uris.forEach { uri ->
                val name = queryName(context, uri) ?: "arquivo"
                val size = querySize(context, uri)
                transfer = TransferState(
                    label = "Enviando $name", done = 0, total = size,
                    startedAt = System.currentTimeMillis()
                )
                val result = withContext(Dispatchers.IO) {
                    try {
                        val conn = AdbClient.connect(t)
                        try {
                            val remote = if (path.endsWith("/")) "$path$name" else "$path/$name"
                            val input = context.contentResolver.openInputStream(uri)
                                ?: throw java.io.IOException("não foi possível abrir o arquivo")
                            var lastBytes = 0L
                            var lastTime = System.currentTimeMillis()
                            AdbFiles.pushStream(conn, input, size, remote) { done, total ->
                                val now = System.currentTimeMillis()
                                val speed = if (now > lastTime) {
                                    ((done - lastBytes) * 1000.0) / (now - lastTime)
                                } else 0.0
                                if (now - lastTime > 250 || done == total) {
                                    lastBytes = done
                                    lastTime = now
                                }
                                transfer = TransferState(
                                    label = "Enviando $name", done = done, total = total,
                                    startedAt = System.currentTimeMillis(), speed = speed
                                )
                            }
                            Result.success(remote)
                        } finally {
                            conn.close()
                        }
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
                result.onSuccess {
                    transfer = transfer?.copy(finished = true, done = size ?: 0, total = size)
                    state.logOk("push $name", t, it)
                }.onFailure {
                    transfer = transfer?.copy(finished = true, error = it.message)
                    state.logFail("push $name", t, it.message ?: "erro")
                }
            }
            load(path)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // -------------------------------------------------- alvo
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                TargetSelector(
                    targets = state.allTargets(includeFastboot = false),
                    selected = target,
                    onSelect = { state.select(it) },
                    statusOf = { state.statusOf(it) },
                    emptyText = "Conecte um aparelho para explorar arquivos"
                )
            }
        }

        if (!canBrowse) {
            NoticeBox(
                when {
                    target == null -> "Conecte um aparelho por USB ou Wi‑Fi para navegar " +
                        "nos arquivos dele."
                    mode == DeviceMode.FASTBOOT -> "O aparelho está em fastboot: transferência de " +
                        "arquivos não existe nesse modo."
                    target.let { state.needsAuthorization(it) } ->
                        "Autorize o app no aparelho (toque em Permitir) para usar arquivos."
                    else -> "Neste modo (${mode.label}) o protocolo de arquivos (sync) não está " +
                        "disponível. Use o modo normal (ADB) do aparelho."
                },
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }

        // -------------------------------------------------- barra de caminho
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val parent = if (path == "/" || path.isEmpty()) "/" else {
                        val trimmed = path.trimEnd('/')
                        val idx = trimmed.lastIndexOf('/')
                        if (idx <= 0) "/" else trimmed.substring(0, idx)
                    }
                    if (canBrowse) load(parent)
                },
                enabled = canBrowse && path != "/"
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Subir")
            }
            OutlinedTextField(
                value = pathDraft,
                onValueChange = { pathDraft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp
                ),
                placeholder = { Text("/sdcard", fontSize = 12.sp) }
            )
            IconButton(
                onClick = { if (canBrowse) load(pathDraft.ifBlank { "/" }) },
                enabled = canBrowse
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Abrir")
            }
        }

        // Atalhos
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "/sdcard" to "📁 sdcard",
                "/sdcard/Download" to "⬇ Download",
                "/sdcard/DCIM" to "📷 DCIM",
                "/sdcard/Android/data" to "📦 data",
                "/data/local/tmp" to "🧪 tmp",
                "/" to "🗂 raiz"
            ).forEach { (p, label) ->
                TextButton(onClick = { if (canBrowse) load(p) }) {
                    Text(label, fontSize = 11.sp)
                }
            }
        }

        // -------------------------------------------------- ações
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { pickFile.launch(arrayOf("*/*")) },
                enabled = canBrowse,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors()
            ) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Enviar arquivo", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = { mkdirDialog = true },
                enabled = canBrowse,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Nova pasta", fontSize = 12.sp)
            }
        }

        // -------------------------------------------------- transferência
        transfer?.let { t ->
            SectionCard(modifier = Modifier.padding(horizontal = 12.dp)) {
                val total = t.total
                val fraction = if (total != null && total > 0) {
                    (t.done.toFloat() / total).coerceIn(0f, 1f)
                } else 0f
                ProgressBlock(
                    label = t.label,
                    fraction = fraction,
                    indeterminate = !t.finished && (total == null || total <= 0),
                    tone = when {
                        t.error != null -> c.error
                        t.finished -> c.success
                        else -> MaterialTheme.colorScheme.primary
                    },
                    detail = buildString {
                        append(formatBytes(t.done))
                        if (total != null && total > 0) append(" de " + formatBytes(total))
                        if (!t.finished && t.speed > 0) append(" · " + formatSpeed(t.speed))
                        if (t.error != null) append(" · ✗ " + t.error)
                        else if (t.finished) append(" · ✓ concluído")
                    }
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // -------------------------------------------------- lista
        if (error != null) {
            NoticeBox(
                error!!,
                modifier = Modifier.padding(horizontal = 12.dp),
                tone = NoticeTone.ERROR
            )
        }
        if (loading) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            items(entries, key = { it.name + it.mtime }) { entry ->
                FileRow(
                    entry = entry,
                    onOpen = {
                        if (entry.isDirectory) {
                            load(joinPath(path, entry.name))
                        } else {
                            // Baixa o arquivo tocado.
                            val t = state.selectedTarget ?: return@FileRow
                            scope.launch {
                                transfer = TransferState(
                                    label = "Baixando ${entry.name}", done = 0,
                                    total = entry.size, startedAt = System.currentTimeMillis()
                                )
                                val result = withContext(Dispatchers.IO) {
                                    try {
                                        val dest = File(
                                            FileSaver.saveDir(context),
                                            entry.name
                                        )
                                        var lastBytes = 0L
                                        var lastTime = System.currentTimeMillis()
                                        AdbClient.pull(
                                            t, joinPath(path, entry.name), dest
                                        ) { done, total ->
                                            val now = System.currentTimeMillis()
                                            val speed = if (now > lastTime) {
                                                ((done - lastBytes) * 1000.0) / (now - lastTime)
                                            } else 0.0
                                            if (now - lastTime > 250 || done == total) {
                                                lastBytes = done
                                                lastTime = now
                                            }
                                            transfer = TransferState(
                                                label = "Baixando ${entry.name}",
                                                done = done, total = total,
                                                startedAt = System.currentTimeMillis(),
                                                speed = speed
                                            )
                                        }
                                        Result.success(dest)
                                    } catch (e: Exception) {
                                        Result.failure(e)
                                    }
                                }
                                result.onSuccess { f ->
                                    transfer = transfer?.copy(finished = true)
                                    state.logOk("pull ${entry.name}", t, f.absolutePath)
                                    FileSaver.shareFile(context, f)
                                }.onFailure {
                                    transfer = transfer?.copy(
                                        finished = true, error = it.message
                                    )
                                    state.logFail("pull ${entry.name}", t, it.message ?: "erro")
                                }
                            }
                        }
                    },
                    onDownload = {
                        if (entry.isDirectory) {
                            val t = state.selectedTarget ?: return@FileRow
                            scope.launch {
                                transfer = TransferState(
                                    label = "Baixando pasta ${entry.name}", done = 0,
                                    total = null, startedAt = System.currentTimeMillis()
                                )
                                val result = withContext(Dispatchers.IO) {
                                    try {
                                        val dest = File(FileSaver.saveDir(context), entry.name)
                                        AdbClient.pullDir(
                                            t, joinPath(path, entry.name), dest
                                        ) { _, _ -> }
                                        Result.success(dest)
                                    } catch (e: Exception) {
                                        Result.failure(e)
                                    }
                                }
                                result.onSuccess {
                                    transfer = transfer?.copy(finished = true)
                                    state.logOk("pull dir ${entry.name}", t, it.absolutePath)
                                }.onFailure {
                                    transfer = transfer?.copy(
                                        finished = true, error = it.message
                                    )
                                    state.logFail("pull dir ${entry.name}", t, it.message ?: "erro")
                                }
                            }
                        }
                    },
                    onDelete = { confirmDelete = entry }
                )
            }
            if (!loading && entries.isEmpty() && canBrowse && error == null) {
                item { EmptyHint("Pasta vazia ou sem permissão de leitura.") }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    confirmDelete?.let { entry ->
        ConfirmDangerDialog(
            title = "Apagar no aparelho?",
            targetLabel = target?.label() ?: "—",
            message = "O item \"${entry.name}\" será apagado de " +
                joinPath(path, entry.name) + ". Esta ação não pode ser desfeita.",
            confirmText = "Apagar",
            onConfirm = {
                val t = state.selectedTarget
                confirmDelete = null
                if (t != null) {
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            AdbClient.remove(t, joinPath(path, entry.name))
                        }
                        if (res.failed || (res.exitCode ?: 0) != 0) {
                            error = "Não foi possível apagar: " + res.output
                        } else {
                            state.logOk("rm ${entry.name}", t)
                            load(path)
                        }
                    }
                }
            },
            onDismiss = { confirmDelete = null }
        )
    }

    if (mkdirDialog) {
        AlertDialog(
            onDismissRequest = { mkdirDialog = false },
            title = { Text("Nova pasta") },
            text = {
                Column {
                    Text("Em: $path", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        placeholder = { Text("nome-da-pasta") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val t = state.selectedTarget
                        val name = newFolderName.trim()
                        mkdirDialog = false
                        newFolderName = ""
                        if (t != null && name.isNotEmpty()) {
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    try {
                                        AdbClient.mkdir(t, joinPath(path, name))
                                        null
                                    } catch (e: Exception) {
                                        e.message ?: "erro"
                                    }
                                }
                                if (res == null) {
                                    state.logOk("mkdir $name", t)
                                    load(path)
                                } else {
                                    error = "Não foi possível criar a pasta: $res"
                                }
                            }
                        }
                    }
                ) { Text("Criar") }
            },
            dismissButton = {
                TextButton(onClick = { mkdirDialog = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun FileRow(
    entry: RemoteEntry,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    val c = LocalAdbColors.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpen() }
                .padding(vertical = 9.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when {
                    entry.isDirectory -> "📁"
                    entry.name.endsWith(".apk", true) -> "📦"
                    entry.name.endsWith(".zip", true) -> "🗜"
                    entry.name.endsWith(".img", true) -> "💽"
                    entry.name.endsWith(".txt", true) || entry.name.endsWith(".log", true) -> "📄"
                    else -> "📄"
                },
                fontSize = 18.sp
            )
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
                        if (!entry.isDirectory) {
                            append(" · ")
                            append(formatBytes(entry.size))
                        }
                        if (entry.uid != 0 || entry.gid != 0) {
                            append(" · ${entry.uid}:${entry.gid}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
            IconButton(onClick = onDownload) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Baixar",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Apagar",
                    tint = c.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

fun joinPath(base: String, name: String): String {
    if (name.startsWith("/")) return name
    return if (base.endsWith("/")) "$base$name" else "$base/$name"
}

private fun queryName(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Exception) {
        uri.lastPathSegment
    }
}

private fun querySize(context: android.content.Context, uri: Uri): Long? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                cursor.getLong(idx)
            } else null
        }
    } catch (_: Exception) {
        null
    }
}
