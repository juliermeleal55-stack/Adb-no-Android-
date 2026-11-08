package com.adbnoandroid

import android.hardware.usb.UsbDevice
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Arquivos ADB — o `adb pull` e o `adb push` do PC, pelo serviço "sync:".
 *
 * O file-sync protocol roda DENTRO dos pacotes WRTE do serviço (cada WRTE é
 * ackeado com OKAY pelo aparelho — igual ao platform-tools):
 *
 *  - **Pull (RECV)**:  `RECV<len><caminho>`  -> [blocos `DATA<len><dados>`]
 *                      -> `DONE<0>`  (o `STAT` antes dá o tamanho para a barra)
 *  - **Push (SEND)**:  `SEND<len><caminho,modo>` -> OKAY
 *                      -> blocos `DATA<len><dados>` -> `DONE<mtime>` -> OKAY
 *
 * Os comandos vêm como 4 bytes ASCII + tamanho (LE, 4 bytes) + payload — o
 * mesmo formato que o adb.py e o adbkit (clientes maduros de ADB) implementam.
 */
object AdbFiles {

    private const val CHUNK = 4096
    private const val MODE_FILE_644 = 33188 // = 0100644 em octal (arquivo 644)

    // ===================== USB (cabo/OTG) =====================

    /** Baixa [remotePath] do aparelho USB para [dest]. onProgress(recebido, total?). */
    fun pullUsb(
        device: UsbDevice,
        remotePath: String,
        dest: File,
        onProgress: (Long, Long?) -> Unit
    ) {
        val context = AdbClient.appContextOrNull()
            ?: throw AdbException("App não inicializado.")
        val connection = AdbUsb.open(context, device, AdbClient.authOrNull())
        try {
            pull(connection, remotePath, dest, onProgress)
        } finally {
            connection.close()
        }
    }

    /** Envia [local] para [remotePath] no aparelho USB. onProgress(enviado, total). */
    fun pushUsb(
        device: UsbDevice,
        local: File,
        remotePath: String,
        onProgress: (Long, Long) -> Unit
    ) {
        val context = AdbClient.appContextOrNull()
            ?: throw AdbException("App não inicializado.")
        val connection = AdbUsb.open(context, device, AdbClient.authOrNull())
        try {
            push(connection, local, remotePath, onProgress)
        } finally {
            connection.close()
        }
    }

    // ===================== Wi‑Fi =====================

    fun pullTcp(
        host: String,
        port: Int,
        remotePath: String,
        dest: File,
        onProgress: (Long, Long?) -> Unit
    ) {
        val connection = AdbConnection(TcpTransport(host, port), AdbClient.authOrNull())
        try {
            pull(connection, remotePath, dest, onProgress)
        } finally {
            connection.close()
        }
    }

    fun pushTcp(
        host: String,
        port: Int,
        local: File,
        remotePath: String,
        onProgress: (Long, Long) -> Unit
    ) {
        val connection = AdbConnection(TcpTransport(host, port), AdbClient.authOrNull())
        try {
            push(connection, local, remotePath, onProgress)
        } finally {
            connection.close()
        }
    }

    // ===================== pull =====================

    private fun pull(
        connection: AdbConnection,
        remotePath: String,
        dest: File,
        onProgress: (Long, Long?) -> Unit
    ) {
        connection.connect()
        connection.setTimeout(120_000)
        val remoteId = connection.openService("sync:")
        val reader = SyncReader(connection, remoteId)
        try {
            // STAT primeiro: valida o caminho e dá o tamanho p/ a barra de progresso.
            var total: Long? = null
            try {
                reader.sendCommand("STAT", remotePath.toByteArray(Charsets.UTF_8))
                when (val reply = reader.readPacket()) {
                    is SyncReply.Stats -> {
                        if (reply.mode == 0L) {
                            throw AdbServiceException("Arquivo não encontrado: $remotePath")
                        }
                        total = reply.size
                    }
                    is SyncReply.Fail ->
                        throw AdbServiceException("Não deu para ler $remotePath (${reply.message}).")
                    is SyncReply.Other ->
                        throw AdbServiceException("Resposta inesperada no STAT: ${reply.command}.")
                    else ->
                        throw AdbServiceException("Resposta inesperada no STAT (${reply.command}).")
                }
            } catch (e: AdbServiceException) {
                throw e
            } catch (_: Exception) {
                // Aparelho antigo sem STAT — segue sem o tamanho total.
            }

            reader.sendCommand("RECV", remotePath.toByteArray(Charsets.UTF_8))
            var received = 0L
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out ->
                while (true) {
                    when (val reply = reader.readPacket()) {
                        is SyncReply.Data -> {
                            out.write(reply.data)
                            received += reply.data.size
                            onProgress(received, total)
                        }
                        is SyncReply.Done -> break
                        is SyncReply.Fail ->
                            throw AdbServiceException("Falha ao baixar: ${reply.message}")
                        is SyncReply.Okay -> continue
                        is SyncReply.Stats ->
                            throw AdbServiceException("Resposta inesperada no download (STAT).")
                        is SyncReply.Other ->
                            throw AdbServiceException(
                                "Resposta inesperada no download: ${reply.command}."
                            )
                    }
                }
            }
            onProgress(received, total)
        } finally {
            try {
                connection.closeService(remoteId)
            } catch (_: Exception) {
            }
        }
    }

    // ===================== push =====================

    private fun push(
        connection: AdbConnection,
        local: File,
        remotePath: String,
        onProgress: (Long, Long) -> Unit
    ) {
        connection.connect()
        connection.setTimeout(120_000)
        val remoteId = connection.openService("sync:")
        val reader = SyncReader(connection, remoteId)
        try {
            val size = local.length()
            val arg = "$remotePath,$MODE_FILE_644"
            reader.sendCommand("SEND", arg.toByteArray(Charsets.UTF_8))
            when (val reply = reader.readPacket()) {
                is SyncReply.Okay -> {}
                is SyncReply.Fail ->
                    throw AdbServiceException("O aparelho recusou o envio: ${reply.message}")
                else ->
                    throw AdbServiceException("Resposta inesperada ao iniciar o envio: ${reply.command}.")
            }

            var offset = 0L
            val buffer = ByteArray(CHUNK)
            local.inputStream().buffered().use { ins ->
                while (true) {
                    val n = ins.read(buffer, 0, CHUNK)
                    if (n == -1) break
                    if (n > 0) {
                        reader.sendData(buffer.copyOf(n))
                        offset += n
                        onProgress(offset, size)
                    }
                }
            }
            // DONE não tem payload: o campo de 4 bytes carrega o mtime.
            reader.sendCommand("DONE", (System.currentTimeMillis() / 1000).toInt())
            when (val reply = reader.readPacket()) {
                is SyncReply.Okay -> {}
                is SyncReply.Fail ->
                    throw AdbServiceException("O aparelho falhou ao receber: ${reply.message}")
                else ->
                    throw AdbServiceException("Resposta inesperada ao finalizar o envio: ${reply.command}.")
            }
            onProgress(size, size)
        } finally {
            try {
                connection.closeService(remoteId)
            } catch (_: Exception) {
            }
        }
    }

}

/** Resposta file-sync (protocolo novo: 4 bytes ASCII + tamanho + payload). */
sealed class SyncReply {
    abstract val command: String
    class Okay : SyncReply() { override val command = "OKAY" }
    class Fail(val message: String) : SyncReply() { override val command = "FAIL" }
    class Data(val data: ByteArray) : SyncReply() { override val command = "DATA" }
    class Done : SyncReply() { override val command = "DONE" }
    class Stats(val mode: Long, val size: Long, val mtime: Long) : SyncReply() { override val command = "STAT" }
    class Other(val command: String) : SyncReply()
}

/**
 * Lê/escreve o file-sync protocol (protocolo novo) num serviço "sync:" já
 * aberto. Os pacotes podem chegar espalhados por vários WRTE — por isso há
 * um buffer que só devolve um pacote file-sync completo por vez.
 */
internal class SyncReader(
    private val connection: AdbConnection,
    private val remoteId: Int
) {
    private val buf = ArrayList<Byte>()

    fun sendCommand(command: String, arg: ByteArray) {
        val head = command.toByteArray(Charsets.US_ASCII)
        val len = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(arg.size).array()
        val out = ByteArray(head.size + 4 + arg.size)
        System.arraycopy(head, 0, out, 0, head.size)
        System.arraycopy(len, 0, out, head.size, 4)
        if (arg.isNotEmpty()) System.arraycopy(arg, 0, out, head.size + 4, arg.size)
        connection.writeServiceData(remoteId, out)
    }

    fun sendCommand(command: String, argValue: Int) {
        val head = command.toByteArray(Charsets.US_ASCII)
        val len = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(argValue).array()
        val out = ByteArray(head.size + 4)
        System.arraycopy(head, 0, out, 0, head.size)
        System.arraycopy(len, 0, out, head.size, 4)
        connection.writeServiceData(remoteId, out)
    }

    /** Envia um bloco de dados (comando DATA + tamanho + bytes). */
    fun sendData(chunk: ByteArray) {
        val head = "DATA".toByteArray(Charsets.US_ASCII)
        val len = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunk.size).array()
        val out = ByteArray(head.size + 4 + chunk.size)
        System.arraycopy(head, 0, out, 0, head.size)
        System.arraycopy(len, 0, out, head.size, 4)
        if (chunk.isNotEmpty()) System.arraycopy(chunk, 0, out, head.size + 4, chunk.size)
        connection.writeServiceData(remoteId, out)
    }

    /** Lê o próximo pacote file-sync completo (aguardando WRTEs se preciso). */
    fun readPacket(): SyncReply {
        while (true) {
            if (buf.size >= 4) {
                val command = String(
                    ByteArray(4) { buf[it].toByte() }, Charsets.US_ASCII
                )
                when (command) {
                    // STAT é o único pacote SEM campo de tamanho:
                    // "STAT" + 12 bytes (mode, size, mtime — LE).
                    "STAT" -> {
                        if (buf.size >= 16) {
                            val payload = ByteArray(12) { buf[4 + it].toByte() }
                            buf.subList(0, 16).clear()
                            return parse(command, payload)
                        }
                    }
                    else -> {
                        if (buf.size >= 8) {
                            val argLen = ((buf[4].toInt() and 0xFF)) or
                                ((buf[5].toInt() and 0xFF) shl 8) or
                                ((buf[6].toInt() and 0xFF) shl 16) or
                                ((buf[7].toInt() and 0xFF) shl 24)
                            if (argLen < 0 || argLen > Adb.MAX_DATA + 64) {
                                throw AdbServiceException("Pacote file-sync com tamanho inválido: $argLen")
                            }
                            if (buf.size >= 8 + argLen) {
                                val payload = ByteArray(argLen) { buf[8 + it].toByte() }
                                buf.subList(0, 8 + argLen).clear()
                                return parse(command, payload)
                            }
                        }
                    }
                }
            }
            val next = connection.readServicePayload(remoteId)
                ?: throw AdbServiceException("O serviço sync foi fechado pelo aparelho.")
            for (b in next) buf.add(b)
        }
    }

    private fun parse(command: String, payload: ByteArray): SyncReply = when (command) {
        "OKAY" -> SyncReply.Okay()
        "FAIL" -> SyncReply.Fail(String(payload, Charsets.UTF_8).trim())
        "DATA" -> SyncReply.Data(payload)
        "DONE" -> SyncReply.Done()
        "STAT" -> {
            if (payload.size >= 12) {
                SyncReply.Stats(
                    mode = (payload.readIntLe(0)).toLong(),
                    size = (payload.readIntLe(4)).toLong(),
                    mtime = (payload.readIntLe(8)).toLong()
                )
            } else {
                SyncReply.Other("STAT")
            }
        }
        else -> SyncReply.Other(command)
    }

    private fun ByteArray.readIntLe(off: Int): Int =
        ((this[off].toInt() and 0xFF)) or
            ((this[off + 1].toInt() and 0xFF) shl 8) or
            ((this[off + 2].toInt() and 0xFF) shl 16) or
            ((this[off + 3].toInt() and 0xFF) shl 24)
}

/**
 * Lê o file-sync protocol **legado** (frames de texto terminados em NUL —
 * "OKAY" tem só 4 bytes) num serviço "sideload:". Usado pelo sideload, que é
 * o único formato aceito pelos recoverys (o mesmo que o `adb sideload` do PC).
 */
internal class SyncTextReader(
    private val connection: AdbConnection,
    private val remoteId: Int
) {
    private val buf = ArrayList<Byte>()

    /** Lê o próximo frame: "OKAY", "FAIL:msg", "DATA:hex", "DONE:hex"… */
    fun readFrame(): String {
        while (true) {
            // OKAY: 4 bytes sem NUL.
            if (buf.size >= 4 &&
                buf[0] == 'O'.code.toByte() && buf[1] == 'K'.code.toByte() &&
                buf[2] == 'A'.code.toByte() && buf[3] == 'Y'.code.toByte()
            ) {
                buf.subList(0, 4).clear()
                return "OKAY"
            }
            // Demais frames: bytes até o NUL.
            val nulIdx = buf.indexOf(0.toByte())
            if (nulIdx >= 0) {
                val frame = String(
                    ByteArray(nulIdx) { buf[it].toByte() }, Charsets.US_ASCII
                )
                buf.subList(0, nulIdx + 1).clear()
                return frame
            }
            val next = connection.readServicePayload(remoteId)
                ?: throw AdbServiceException("O serviço foi fechado pelo aparelho.")
            for (b in next) buf.add(b)
        }
    }
}
