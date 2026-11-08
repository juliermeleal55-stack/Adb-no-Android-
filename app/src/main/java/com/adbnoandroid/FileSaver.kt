package com.adbnoandroid

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Salva e compartilha arquivos gerados pelo app (logs, downloads).
 *
 * Os arquivos vão para a pasta de downloads **do app**
 * (/storage/emulated/0/Android/data/com.adbnoandroid/files/Downloads) —
 * sem pedir permissões de armazenamento (scoped storage do Android 11+).
 * O usuário acha o arquivo em qualquer gerenciador de arquivos.
 */
object FileSaver {

    private const val PROVIDER_AUTHORITY = "com.adbnoandroid.fileprovider"

    /** Pasta de downloads do app (criada se preciso). */
    fun saveDir(context: Context): File {
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Salva [text] em [saveDir] com o nome [baseName]_<timestamp>.<ext>.
     * Devolve o arquivo salvo.
     */
    fun saveText(context: Context, baseName: String, text: String, ext: String = "txt"): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(saveDir(context), "${baseName}_$stamp.$ext")
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    /** Nome de arquivo limpo (sem barras), mantendo a extensão. */
    fun cleanName(pathOrName: String): String {
        val base = pathOrName.substringAfterLast('/')
        val safe = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return safe.ifBlank { "arquivo" }
    }

    private fun mimeTypeOf(file: File): String {
        val ext = file.extension.lowercase(Locale.US)
        return when (ext) {
            "txt", "log" -> "text/plain"
            "zip", "apk" -> "application/zip"
            "png", "jpg", "jpeg" -> "image/*"
            else -> "*/*"
        }
    }

    /**
     * Abre o diálogo "Compartilhar" com o arquivo (WhatsApp, e-mail…).
     * Devolve false se o sistema não oferecer nenhum app.
     */
    fun shareFile(context: Context, file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(context, PROVIDER_AUTHORITY, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeTypeOf(file)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "Compartilhar ${file.name}").addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
