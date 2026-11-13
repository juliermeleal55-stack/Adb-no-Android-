package com.adbnoandroid

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Salva e compartilha arquivos gerados pelo app (logs, downloads, bugreports).
 *
 * - Padrão: pasta de downloads **do app** (Android/data/…/files/Downloads),
 *   que não precisa de permissão em nenhuma versão.
 * - No Android 10+ também é possível publicar na pasta **Download pública**
 *   (via MediaStore), de onde qualquer gerenciador de arquivos abre o arquivo.
 * - O compartilhamento usa FileProvider (WhatsApp, e-mail, Drive…).
 */
object FileSaver {

    private const val PROVIDER_AUTHORITY = "com.adbnoandroid.fileprovider"

    /** Pasta de downloads do app (criada se preciso). */
    fun saveDir(context: Context): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Salva [text] em [saveDir] com o nome `baseName_<timestamp>.<ext>` e
     * devolve o arquivo.
     */
    fun saveText(
        context: Context,
        baseName: String,
        text: String,
        ext: String = "txt"
    ): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(saveDir(context), "${cleanName(baseName)}_$stamp.$ext")
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    /** Nome de arquivo seguro (sem barras/caracteres estranhos). */
    fun cleanName(pathOrName: String): String {
        val base = pathOrName.substringAfterLast('/').ifBlank { "arquivo" }
        val safe = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return safe.ifBlank { "arquivo" }
    }

    fun mimeTypeOf(file: File): String {
        val ext = file.extension.lowercase(Locale.US)
        return when (ext) {
            "txt", "log" -> "text/plain"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            else -> "*/*"
        }
    }

    /**
     * Publica [file] na pasta **Download** pública (Android 10+) para o usuário
     * achar facilmente. Devolve a Uri pública, ou null quando não suportado.
     */
    fun publishToPublicDownloads(context: Context, file: File): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeTypeOf(file))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { ins -> ins.copyTo(out) }
            } ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            null
        }
    }

    /** Abre o diálogo "Compartilhar" com o arquivo. */
    fun shareFile(context: Context, file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(context, PROVIDER_AUTHORITY, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeTypeOf(file)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Compartilhar ${file.name}")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Abre o arquivo com o app padrão do sistema. */
    fun openFile(context: Context, file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(context, PROVIDER_AUTHORITY, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeTypeOf(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
