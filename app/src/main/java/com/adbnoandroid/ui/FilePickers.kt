package com.adbnoandroid.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.adbnoandroid.FileSaver
import java.io.File

/**
 * Nome de exibição do arquivo escolhido no seletor do sistema.
 *
 * Cai para o último segmento do URI quando o provedor não responde ao query —
 * sem isso a instalação falharia só porque o nome bonito não veio.
 */
fun queryDisplayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
} catch (_: Exception) {
    uri.lastPathSegment
}

/**
 * Copia o conteúdo do URI para o cache do app com o nome original.
 *
 * O protocolo de instalação precisa de um [File] real para medir o tamanho e
 * mandar por streaming; `openInputStream` do provedor não dá tamanho confiável.
 */
fun copyToCache(context: Context, uri: Uri, name: String): File? {
    return try {
        val dir = File(context.cacheDir, "picked")
        dir.mkdirs()
        val safeName = FileSaver.cleanName(name)
        val file = File(dir, safeName)
        val stream = context.contentResolver.openInputStream(uri)
        if (stream == null) {
            null
        } else {
            stream.use { ins ->
                file.outputStream().use { outs -> ins.copyTo(outs, 128 * 1024) }
            }
            file
        }
    } catch (_: Exception) {
        null
    }
}
