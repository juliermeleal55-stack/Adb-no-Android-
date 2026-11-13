package com.adbnoandroid

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * File-sync protocol do ADB — a base do `adb push`, `adb pull` e `adb ls`.
 *
 * Roda dentro de um serviço `sync:` já aberto. Cada requisição é
 * `4 bytes ASCII + tamanho (uint32 LE) + payload` e as respostas usam o mesmo
 * enquadramento (exceto `STAT`, que é a única resposta sem campo de tamanho).
 *
 * Layouts confirmados no AOSP (file_sync_protocol.h):
 * ```
 * sync_stat_v1  : id, mode, size, mtime                     (16 bytes — sem length)
 * sync_dent_v1  : id, mode, size, mtime, namelen            + nome
 * sync_dent_v2  : id, error, dev, ino, mode, nlink, uid, gid,
 *                 size(64), atime(64), mtime(64), ctime(64), namelen  + nome
 * sync_data     : id, size                                  + dados
 * sync_status   : id, msglen                                + mensagem
 * ```
 * O modo do `SEND` é `strtoul(base 0)` no adbd, então mandamos o st_mode em
 * **decimal** (0100644 → "33188").
 */
object SyncCommands {
    const val STAT_V1 = "STAT"
    const val STAT_V2 = "STA2"
    const val LSTAT_V2 = "LST2"
    const val LIST_V1 = "LIST"
    const val LIST_V2 = "LIS2"
    const val DENT_V1 = "DENT"
    /** Resposta da listagem v2 (o "DNT2" do file_sync_protocol.h). */
    const val DENT_V2 = "DNT2"
    const val SEND_V1 = "SEND"
    const val RECV_V1 = "RECV"
    const val DATA = "DATA"
    const val DONE = "DONE"
    const val OKAY = "OKAY"
    const val FAIL = "FAIL"
    const val QUIT = "QUIT"

    /** Modo usado no push (arquivo 0644). */
    const val MODE_FILE = 33188 // 0100644

    /** Modo usado ao criar diretório no dispositivo (0755). */
    const val MODE_DIR = 16877 // 040755

    /** Moldura do protocolo sync: id (4) + length (4). */
    const val SyncCommands.FRAME_HEADER = 8

    /** `sync_stat_v1` (packed): id, mode, size, mtime. */
    const val SyncCommands.STAT_V1_SIZE = 16

    /** `sync_stat_v2` (packed): 72 bytes. */
    const val SyncCommands.STAT_V2_SIZE = 72

    /** Tamanho do cabeçalho `sync_dent_v1` (packed): 5 × uint32. */
    const val SyncCommands.DENT_V1_SIZE = 20

    /** Tamanho do cabeçalho `sync_dent_v2` (packed): 76 bytes. */
    const val SyncCommands.DENT_V2_SIZE = 76
}

/** Falha ao interpretar o fluxo do serviço sync. */
class AdbSyncException(message: String) : AdbException(message)

/** Uma entrada de diretório vinda do dispositivo. */
data class RemoteEntry(
    val name: String,
    val mode: Int,
    val size: Long,
    val mtime: Long,
    val uid: Int = 0,
    val gid: Int = 0
) {
    val isDirectory: Boolean get() = (mode and 0xF000) == 0x4000
    val isSymlink: Boolean get() = (mode and 0xF000) == 0xA000
    val isFile: Boolean get() = (mode and 0xF000) == 0x8000

    fun permissionString(): String {
        val sb = StringBuilder(10)
        sb.append(if (isDirectory) 'd' else if (isSymlink) 'l' else '-')
        val bits = arrayOf(
            0x100, 0x80, 0x40, // dono
            0x20, 0x10, 0x08,  // grupo
            0x04, 0x02, 0x01   // outros
        )
        val chars = charArrayOf('r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x')
        for (i in bits.indices) {
            sb.append(if (mode and bits[i] != 0) chars[i] else '-')
        }
        return sb.toString()
    }
}

/** Resultado de um STAT. */
data class RemoteStat(
    val mode: Int,
    val size: Long,
    val mtime: Long
) {
    val exists: Boolean get() = mode != 0
    val isDirectory: Boolean get() = (mode and 0xF000) == 0x4000
    val isFile: Boolean get() = (mode and 0xF000) == 0x8000
}

/** Frames que o aparelho pode devolver no canal sync. */
sealed class SyncFrame {
    data class Stat(val stat: RemoteStat) : SyncFrame()
    data class Dent(val entry: RemoteEntry) : SyncFrame()
    data class Data(val bytes: ByteArray) : SyncFrame()
    object Done : SyncFrame()
    object Okay : SyncFrame()
    data class Fail(val message: String) : SyncFrame()
    data class Other(val command: String, val bytes: ByteArray) : SyncFrame()
}

/**
 * Canal sync sobre uma conexão ADB. Não é thread-safe: use uma conexão por
 * transferência (é o que [AdbClient] faz).
 */
class SyncChannel(
    private val connection: AdbConnection,
    private val remoteId: Int
) {
    private var buf = ByteArray(64 * 1024 + 8192)
    private var len = 0

    // -------------------------------------------------------------- envio

    /**
     * Envia uma requisição do file-sync.
     *
     * **Não** esperamos OKAY: o serviço sync do adbd responde no protocolo
     * (OKAY/FAIL/DONE) e não confirma cada WRTE — esperar por ACK aqui trava o
     * push inteiro. Quem lê a resposta é sempre `readFrame()`.
     */
    fun request(command: String, arg: String) {
        val payload = arg.toByteArray(Charsets.UTF_8)
        val head = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        head.put(command.toByteArray(Charsets.US_ASCII))
        head.putInt(payload.size)
        head.put(payload)
        connection.writeServiceData(remoteId, head.array(), waitForAck = false)
    }

    fun request(command: String, arg: Int) {
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        head.put(command.toByteArray(Charsets.US_ASCII))
        head.putInt(arg)
        connection.writeServiceData(remoteId, head.array(), waitForAck = false)
    }

    /**
     * Envia um bloco de dados (`DATA`): cabeçalho de 8 bytes + bytes.
     *
     * O bloco é fatiado de acordo com o payload NEGOCIADO da conexão — um WRTE
     * maior que `maxPayload` seria rejeitado pelo adbd. Cada WRTE equivale a um
     * ACK, por isso mantemos os pedaços pequenos (SYNC_DATA_MAX).
     */
    fun sendData(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        val maxChunk = connection.maxPayload - 8
        if (maxChunk <= 0) {
            throw AdbServiceException(
                "O aparelho informou um payload inválido (${connection.maxPayload} bytes)."
            )
        }
        val perPacket = minOf(maxChunk, Adb.SYNC_DATA_MAX)
        var off = 0
        while (off < chunk.size) {
            val n = minOf(perPacket, chunk.size - off)
            val head = ByteBuffer.allocate(8 + n).order(ByteOrder.LITTLE_ENDIAN)
            head.put(SyncCommands.DATA.toByteArray(Charsets.US_ASCII))
            head.putInt(n)
            head.put(chunk, off, n)
            connection.writeServiceData(remoteId, head.array(), waitForAck = false)
            off += n
        }
    }

    // ------------------------------------------------------------- recepção

    private fun ensureCapacity(extra: Int) {
        if (len + extra <= buf.size) return
        val newSize = maxOf(buf.size * 2, len + extra)
        val nb = ByteArray(newSize)
        System.arraycopy(buf, 0, nb, 0, len)
        buf = nb
    }

    /**
     * Lê mais um bloco do serviço sync para o buffer.
     *
     * Bloqueia até chegar pelo menos um byte. Pode receber um WRTE vazio (o
     * adbd usa isso para separar o cabeçalho do nome do arquivo), então
     * insistimos até vir dado de verdade — devolver cedo faria
     * `while (len < n) fill()` girar para sempre.
     */
    private fun fill() {
        while (true) {
            val payload = connection.readServicePayload(remoteId)
                ?: throw AdbServiceException("O aparelho encerrou a sessão de arquivos.")
            if (payload.isEmpty()) continue
            ensureCapacity(payload.size)
            System.arraycopy(payload, 0, buf, len, payload.size)
            len += payload.size
            return
        }
    }

    private fun peekCommand(): String {
        while (len < 4) fill()
        return String(buf, 0, 4, Charsets.US_ASCII)
    }

    private fun consume(n: Int) {
        if (n >= len) {
            len = 0
            return
        }
        System.arraycopy(buf, n, buf, 0, len - n)
        len -= n
    }

    /** Lê o próximo frame do canal (bloqueante). */
    /**
     * Lê o próximo frame do serviço sync.
     *
     * Formato (file_sync_protocol.h): `id` (4 bytes) + `length` (4 bytes LE) +
     * `length` bytes de dados. Detalhe que pega todo mundo: nas respostas
     * `DENT`/`DNT2` o `length` cobre **apenas o cabeçalho da struct** (20 bytes
     * no v1, 76 no v2) e o NOME do arquivo vem depois, como uma escrita
     * separada de `namelen` bytes. Tratar `length` como "cabeçalho + nome"
     * desalinha o fluxo e a listagem de diretórios sai vazia ou corrompida.
     */
    fun readFrame(): SyncFrame {
        val command = peekCommand()

        // STAT/LSTAT: o adbd escreve a struct direto, sem moldura id+length.
        if (command == SyncCommands.STAT_V1 || command == SyncCommands.STAT_V2) {
            return readStat(command == SyncCommands.STAT_V2)
        }

        // DENT/DNT2: struct inteira + nome, também sem moldura.
        if (command == SyncCommands.DENT_V1 || command == SyncCommands.DENT_V2) {
            return readDent(command == SyncCommands.DENT_V2)
        }

        // Demais frames: id (4) + length (4) + length bytes.
        while (len < SyncCommands.FRAME_HEADER) fill()
        val argLen = ByteBuffer.wrap(buf, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (argLen < 0 || argLen > Adb.SYNC_DATA_MAX + 4096) {
            throw AdbServiceException("Pacote sync inválido (tamanho $argLen).")
        }
        while (len < SyncCommands.FRAME_HEADER + argLen) fill()
        val payload = buf.copyOfRange(SyncCommands.FRAME_HEADER, SyncCommands.FRAME_HEADER + argLen)
        consume(SyncCommands.FRAME_HEADER + argLen)
        return when (command) {
            SyncCommands.DATA -> SyncFrame.Data(payload)
            SyncCommands.DONE -> SyncFrame.Done
            SyncCommands.OKAY -> SyncFrame.Okay
            SyncCommands.FAIL -> {
                val text = String(payload, Charsets.UTF_8).trim().trimEnd('\u0000')
                SyncFrame.Fail(
                    if (text.isEmpty() || text.all { it.code < 32 }) {
                        "O aparelho recusou a operação (sem detalhes)."
                    } else {
                        text
                    }
                )
            }
            else -> SyncFrame.Other(command, payload)
        }
    }

    /** `sync_stat_v1` (16 bytes) ou `sync_stat_v2` (72 bytes), sem moldura. */
    private fun readStat(v2: Boolean): SyncFrame.Stat {
        val statSize = if (v2) SyncCommands.STAT_V2_SIZE else SyncCommands.STAT_V1_SIZE
        while (len < statSize) fill()
        val bb = ByteBuffer.wrap(buf, 0, statSize).order(ByteOrder.LITTLE_ENDIAN)
        val mode: Int
        val size: Long
        val mtime: Long
        if (v2) {
            bb.int // id
            bb.int // error
            bb.long // dev
            bb.long // ino
            mode = bb.int
            bb.int // nlink
            bb.int // uid
            bb.int // gid
            size = bb.long
            bb.long // atime
            mtime = bb.long
            bb.long // ctime
        } else {
            bb.int // id
            mode = bb.int
            size = bb.int.toLong() and 0xFFFFFFFFL
            mtime = bb.int.toLong() and 0xFFFFFFFFL
        }
        consume(statSize)
        return SyncFrame.Stat(RemoteStat(mode, size, mtime))
    }

    /**
     * Struct `sync_dent_v1` (20 B) / `sync_dent_v2` (76 B) seguida do nome.
     *
     * São DUAS escritas do adbd (`WriteFdExactly(&msg, sizeof(msg))` e depois
     * `WriteFdExactly(d_name, d_name_length)`), sem tamanho na frente do nome —
     * por isso lemos `namelen` da própria struct.
     */
    private fun readDent(v2: Boolean): SyncFrame {
        val headerSize = if (v2) SyncCommands.DENT_V2_SIZE else SyncCommands.DENT_V1_SIZE
        while (len < headerSize) fill()
        // A listagem termina com um DONE do tamanho da MESMA struct (o adbd faz
        // memset(&msg, 0, sizeof(msg)) e escreve sizeof(msg) bytes) — não com
        // a moldura de 8 bytes dos demais frames.
        if (String(buf, 0, 4, Charsets.US_ASCII) == SyncCommands.DONE) {
            consume(headerSize)
            return SyncFrame.Done
        }
        val bb = ByteBuffer.wrap(buf, 0, headerSize).order(ByteOrder.LITTLE_ENDIAN)
        val namelen: Int
        val entry: RemoteEntry
        if (v2) {
            bb.int // id
            bb.int // error
            bb.long // dev
            bb.long // ino
            val mode = bb.int
            bb.int // nlink
            val uid = bb.int
            val gid = bb.int
            val size = bb.long
            bb.long // atime
            val mtime = bb.long
            bb.long // ctime
            namelen = bb.int
            entry = RemoteEntry("", mode, size, mtime, uid, gid)
        } else {
            bb.int // id
            val mode = bb.int
            val size = bb.int.toLong() and 0xFFFFFFFFL
            val mtime = bb.int.toLong() and 0xFFFFFFFFL
            namelen = bb.int
            entry = RemoteEntry("", mode, size, mtime)
        }
        consume(headerSize)
        return SyncFrame.Dent(entry.copy(name = readName(namelen)))
    }

    /** Lê [namelen] bytes do nome do arquivo que vem após um frame DENT/DNT2. */
    private fun readName(namelen: Int): String {
        if (namelen <= 0) return ""
        if (namelen > 4096) {
            throw AdbSyncException("Nome de arquivo absurdo ($namelen bytes).")
        }
        while (len < namelen) fill()
        val name = String(buf, 0, namelen, Charsets.UTF_8)
        consume(namelen)
        return name
    }

    /** Envia QUIT e fecha o serviço. */
    fun quit() {
        try {
            request(SyncCommands.QUIT, "")
        } catch (_: Exception) {
        }
        try {
            connection.closeService(remoteId)
        } catch (_: Exception) {
        }
    }
}

/**
 * Operações de arquivo de alto nível: ls, stat, push e pull, com progresso.
 * Cada operação abre uma conexão nova (handshake rápido) — simples, sem estado
 * compartilhado e resistente a quedas entre uma operação e outra.
 */
object AdbFiles {

    /** Executa [block] com um canal sync aberto. */
    private fun <T> withSync(connection: AdbConnection, block: (SyncChannel) -> T): T {
        connection.connect()
        connection.setTimeout(60_000)
        val remoteId = connection.openService("sync:")
        val channel = SyncChannel(connection, remoteId)
        try {
            return block(channel)
        } finally {
            try {
                channel.quit()
            } catch (_: Exception) {
            }
            connection.close()
        }
    }

    /** Lista um diretório do aparelho (LIST v1, com fallback LIS2). */
    fun list(connection: AdbConnection, path: String): List<RemoteEntry> {
        return withSync(connection) { ch ->
            // Alguns adbd preferem LIST com barra final; mandamos o caminho como veio.
            ch.request(SyncCommands.LIST_V1, path)
            val out = ArrayList<RemoteEntry>()
            while (true) {
                when (val frame = ch.readFrame()) {
                    is SyncFrame.Dent -> out.add(frame.entry)
                    is SyncFrame.Done -> return@withSync out
                    is SyncFrame.Fail -> throw AdbServiceException(
                        "O aparelho recusou listar \"$path\": ${frame.message}"
                    )
                    else -> {
                        // OKAY/DATA inesperados: ignora e continua.
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            out
        }
    }

    /**
     * Lista diretório com nome completo (para o explorer). Tenta `LIS2` (nomes
     * completos, 64-bit) e cai para `LIST` em aparelhos antigos.
     */
    fun listEntries(connection: AdbConnection, path: String): List<RemoteEntry> {
        return try {
            withSync(connection) { ch ->
                ch.request(SyncCommands.LIST_V2, path)
                val out = ArrayList<RemoteEntry>()
                var gotAny = false
                while (true) {
                    when (val frame = ch.readFrame()) {
                        is SyncFrame.Dent -> {
                            gotAny = true
                            out.add(frame.entry)
                        }
                        is SyncFrame.Done -> return@withSync out
                        is SyncFrame.Fail -> throw AdbServiceException(frame.message)
                        else -> {
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                if (!gotAny) emptyList() else out
            }
        } catch (e: AdbException) {
            // Aparelho sem LIS2 (ou diretório inexistente): tenta o LIST clássico.
            list(connection, path)
        }
    }

    /** stat remoto (tamanho, modo, mtime). */
    fun stat(connection: AdbConnection, path: String): RemoteStat {
        return withSync(connection) { ch ->
            ch.request(SyncCommands.STAT_V1, path)
            when (val frame = ch.readFrame()) {
                is SyncFrame.Stat -> frame.stat
                is SyncFrame.Fail -> throw AdbServiceException(
                    "Não foi possível consultar \"$path\": ${frame.message}"
                )
                else -> throw AdbServiceException("Resposta inesperada ao consultar \"$path\".")
            }
        }
    }

    /**
     * `adb pull`: baixa [remotePath] para [dest].
     * [onProgress] recebe (bytes recebidos, total quando conhecido).
     */
    fun pull(
        connection: AdbConnection,
        remotePath: String,
        dest: File,
        onProgress: (Long, Long?) -> Unit = { _, _ -> }
    ) {
        withSync(connection) { ch ->
            connection.setTimeout(180_000)
            // STAT dá o tamanho e valida o caminho antes de abrir o arquivo local.
            var total: Long? = null
            try {
                ch.request(SyncCommands.STAT_V1, remotePath)
                when (val frame = ch.readFrame()) {
                    is SyncFrame.Stat -> {
                        if (!frame.stat.exists) {
                            throw AdbServiceException("Arquivo não encontrado: $remotePath")
                        }
                        if (frame.stat.isDirectory) {
                            throw AdbServiceException(
                                "\"$remotePath\" é uma pasta. Selecione um arquivo ou use " +
                                    "o modo de pasta no gerenciador."
                            )
                        }
                        total = frame.stat.size
                    }
                    is SyncFrame.Fail -> throw AdbServiceException(
                        "Não foi possível ler \"$remotePath\": ${frame.message}"
                    )
                    else -> {
                    }
                }
            } catch (e: AdbServiceException) {
                throw e
            } catch (_: Exception) {
                // Aparelho muito antigo sem STAT: segue sem o tamanho.
            }

            dest.parentFile?.mkdirs()
            var received = 0L
            val out: OutputStream = dest.outputStream().buffered(128 * 1024)
            try {
                ch.request(SyncCommands.RECV_V1, remotePath)
                var failure: String? = null
                loop@ while (true) {
                    when (val frame = ch.readFrame()) {
                        is SyncFrame.Data -> {
                            out.write(frame.bytes)
                            received += frame.bytes.size
                            onProgress(received, total)
                        }
                        is SyncFrame.Done -> break@loop
                        is SyncFrame.Fail -> {
                            failure = frame.message
                            break@loop
                        }
                        else -> {
                        }
                    }
                }
                out.flush()
                if (failure != null) {
                    throw AdbServiceException("Falha ao baixar: $failure")
                }
                onProgress(received, total ?: received)
            } finally {
                try {
                    out.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Pull de pasta: baixa [remoteDir] inteira para [localDir].
     *
     * O protocolo sync não tem "pull recursivo", então usamos a mesma ideia do
     * `adb pull` de diretório: o `tar` do aparelho empacota a pasta num arquivo
     * temporário, baixamos esse arquivo e extraímos localmente.
     *
     * Importante: cada passo abre a SUA própria conexão — depois de um pull a
     * conexão de serviço é encerrada, então não reaproveitamos a mesma aqui.
     */
    fun pullDirectory(
        connection: AdbConnection,
        remoteDir: String,
        localDir: File,
        onProgress: (String, Long) -> Unit = { _, _ -> }
    ) {
        val clean = remoteDir.trimEnd('/')
        if (clean.isEmpty()) throw AdbServiceException("Caminho inválido: $remoteDir")
        val parent = clean.substringBeforeLast('/', "")
        val name = clean.substringAfterLast('/')
        if (name.isEmpty()) throw AdbServiceException("Caminho inválido: $remoteDir")
        val cdPath = if (parent.isEmpty()) "/" else parent
        val tarPath = "/data/local/tmp/adbna_pull_${System.currentTimeMillis()}.tar"
        val tmp = File(localDir.parentFile ?: localDir, ".${localDir.name}.pull.tar")

        // 1) empacota no aparelho
        val shell = AdbShell.exec(
            connection,
            "cd " + shellQuote(cdPath) + " 2>/dev/null && tar -cf " +
                shellQuote(tarPath) + " " + shellQuote(name),
            timeoutMs = 900_000
        )
        val errText = shell.combined().lowercase()
        if (shell.failed || (shell.exitCode ?: 0) != 0 ||
            errText.contains("not found") || errText.contains("inaccessible")
        ) {
            throw AdbServiceException(
                "O aparelho não conseguiu empacotar a pasta (${shell.combined().ifBlank {
                    "tar indisponível neste build"
                }}). Baixe os arquivos separadamente."
            )
        }

        // 2) baixa o tar e 3) extrai localmente
        try {
            val stat = stat(connection, tarPath)
            val total = stat.size
            pull(connection, tarPath, tmp) { done, _ ->
                onProgress(name, if (total > 0) (done * 100 / total) else 0)
            }
            extractTar(tmp, localDir)
            onProgress(name, 100)
        } finally {
            try {
                tmp.delete()
            } catch (_: Exception) {
            }
            // 4) limpa o temporário no aparelho (conexão nova: a do pull fechou).
            try {
                AdbShell.exec(connection, "rm -f " + shellQuote(tarPath), timeoutMs = 15_000)
            } catch (_: Exception) {
            }
        }
    }

    /** Escapa um argumento para o shell do aparelho. */
    private fun shellQuote(text: String): String {
        val escaped = text.replace("'", "'\\''")
        return "'" + escaped + "'"
    }

    /** Extração mínima de tar (ustar/POSIX) — arquivos e diretórios. */
    private fun extractTar(tar: File, target: File) {
        target.mkdirs()
        val targetAbs = target.canonicalFile
        tar.inputStream().buffered().use { ins ->
            val header = ByteArray(512)
            while (true) {
                if (!readFully(ins, header)) break
                if (header.all { it == 0.toByte() }) break
                val name = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
                val sizeField = String(header, 124, 12, Charsets.US_ASCII)
                    .trimEnd('\u0000', ' ').trim()
                val size = sizeField.toLongOrNull(8) ?: 0L
                val typeFlag = header[156].toInt().toChar()
                val prefix = String(header, 345, 155, Charsets.UTF_8).trimEnd('\u0000')
                val full = if (prefix.isNotEmpty()) "$prefix/$name" else name
                // Segurança: nunca escrever fora da pasta de destino.
                val outFile = File(target, full).canonicalFile
                if (!outFile.path.startsWith(targetAbs.path)) {
                    if (size > 0) skipFully(ins, padded(size))
                    continue
                }
                if (typeFlag == '5' || full.endsWith("/")) {
                    outFile.mkdirs()
                } else if (typeFlag == '0' || typeFlag == '\u0000' || typeFlag == '7') {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().buffered().use { os ->
                        copyExactly(ins, os, size)
                    }
                } else {
                    skipFully(ins, size)
                }
                skipFully(ins, paddingFor(size))
            }
        }
    }

    private fun padded(size: Long): Long = size + paddingFor(size)

    private fun paddingFor(size: Long): Long {
        val rem = (size % 512).toInt()
        return if (rem == 0) 0 else (512 - rem).toLong()
    }

    private fun readFully(ins: java.io.InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val r = ins.read(buf, off, buf.size - off)
            if (r == -1) return off > 0
            off += r
        }
        return true
    }

    private fun copyExactly(ins: java.io.InputStream, os: OutputStream, size: Long) {
        var left = size
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val r = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r == -1) throw java.io.IOException("Tar truncado.")
            os.write(buf, 0, r)
            left -= r
        }
    }

    private fun skipFully(ins: java.io.InputStream, size: Long) {
        var left = size
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r == -1) return
            left -= r
        }
    }

    /**
     * `adb push`: envia [local] para [remotePath].
     * [onProgress] recebe (bytes enviados, total).
     */
    fun push(
        connection: AdbConnection,
        local: File,
        remotePath: String,
        mode: Int = SyncCommands.MODE_FILE,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        withSync(connection) { ch ->
            connection.setTimeout(180_000)
            val size = local.length()
            // SEND v1: "caminho,modo-decimal"
            ch.request(SyncCommands.SEND_V1, "$remotePath,$mode")
            when (val frame = ch.readFrame()) {
                is SyncFrame.Okay -> {
                }
                is SyncFrame.Fail -> throw AdbServiceException(
                    "O aparelho recusou o envio para \"$remotePath\": ${frame.message} " +
                        "(pasta inexistente ou sem permissão?)"
                )
                else -> throw AdbServiceException("Resposta inesperada ao iniciar o envio.")
            }

            var sent = 0L
            val buffer = ByteArray(Adb.SYNC_DATA_MAX)
            local.inputStream().buffered(128 * 1024).use { ins ->
                while (true) {
                    val n = ins.read(buffer, 0, buffer.size)
                    if (n == -1) break
                    if (n > 0) {
                        ch.sendData(buffer.copyOf(n))
                        sent += n
                        onProgress(sent, size)
                    }
                }
            }
            // DONE com o mtime (segundos) no campo de tamanho.
            ch.request(SyncCommands.DONE, (System.currentTimeMillis() / 1000).toInt())
            when (val frame = ch.readFrame()) {
                is SyncFrame.Okay -> {
                }
                is SyncFrame.Fail -> throw AdbServiceException(
                    "O aparelho falhou ao gravar \"$remotePath\": ${frame.message}"
                )
                else -> throw AdbServiceException("Resposta inesperada ao finalizar o envio.")
            }
            onProgress(size, size)
        }
    }

    /**
     * Envia um fluxo qualquer (ex.: saída de outro app) para um arquivo no
     * aparelho, útil para receber do seletor de arquivos sem copiar duas vezes.
     */
    fun pushStream(
        connection: AdbConnection,
        input: java.io.InputStream,
        size: Long?,
        remotePath: String,
        onProgress: (Long, Long?) -> Unit = { _, _ -> }
    ) {
        withSync(connection) { ch ->
            connection.setTimeout(180_000)
            ch.request(SyncCommands.SEND_V1, "$remotePath,${SyncCommands.MODE_FILE}")
            when (val frame = ch.readFrame()) {
                is SyncFrame.Okay -> {
                }
                is SyncFrame.Fail -> throw AdbServiceException(
                    "O aparelho recusou o envio: ${frame.message}"
                )
                else -> throw AdbServiceException("Resposta inesperada ao iniciar o envio.")
            }
            var sent = 0L
            val buffer = ByteArray(Adb.SYNC_DATA_MAX)
            input.use { ins ->
                while (true) {
                    val n = ins.read(buffer, 0, buffer.size)
                    if (n == -1) break
                    if (n > 0) {
                        ch.sendData(buffer.copyOf(n))
                        sent += n
                        onProgress(sent, size)
                    }
                }
            }
            ch.request(SyncCommands.DONE, (System.currentTimeMillis() / 1000).toInt())
            when (val frame = ch.readFrame()) {
                is SyncFrame.Okay -> {
                }
                is SyncFrame.Fail -> throw AdbServiceException(
                    "O aparelho falhou ao gravar: ${frame.message}"
                )
                else -> throw AdbServiceException("Resposta inesperada ao finalizar o envio.")
            }
            onProgress(sent, size ?: sent)
        }
    }
}
