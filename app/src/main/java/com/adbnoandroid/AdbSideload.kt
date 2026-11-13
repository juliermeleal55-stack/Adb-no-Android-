package com.adbnoandroid

import java.io.File
import java.io.RandomAccessFile

/**
 * `adb sideload <zip>` — envia um pacote (ROM custom, GApps, OTA) para o
 * **recovery** do aparelho de destino, que instala sozinho.
 *
 * Implementação idêntica à do platform-tools (client/commandline.cpp,
 * `adb_sideload_install`):
 *
 * 1. Abre o serviço `sideload-host:<tamanho>:<bloco>` (recovery moderno, desde
 *    o KitKat). Se o aparelho só tiver o modo antigo, abre `sideload:<size>`.
 * 2. **sideload-host** é um protocolo de blocos dirigido pelo aparelho: o
 *    recovery pede `"00000023"` (8 dígitos decimais = número do bloco), nós
 *    respondemos com os bytes daquele bloco. No fim ele manda `DONEDONE` ou
 *    `FAILFAIL`. É por isso que a instalação continua mesmo se o cabo oscilar:
 *    cada bloco é reenviável.
 * 3. **sideload legado** (<= KitKat): os bytes crus do zip vão no canal e no
 *    fim o aparelho responde `OKAY`/`FAIL`.
 *
 * O aparelho precisa estar em recovery com "Apply update from ADB" escolhido.
 */
object AdbSideload {

    /** Tamanho do bloco que o platform-tools anuncia (CHUNK_SIZE = 64 KB). */
    const val BLOCK_SIZE = 64 * 1024

    private const val EXIT_SUCCESS = "DONEDONE"
    private const val EXIT_FAILURE = "FAILFAIL"

    /** Progresso do sideload. */
    data class Progress(
        val sent: Long,
        val total: Long,
        /** Fração 0..1 estimada do trabalho (o recovery lê alguns blocos 2x). */
        val fraction: Float,
        val message: String
    )

    /**
     * Envia [file] para o recovery via [connection].
     *
     * @param onProgress chamado a cada bloco servido (pode ser chamado de
     *   thread de fundo; a UI deve postar no main thread).
     */
    fun sideload(
        connection: AdbConnection,
        file: File,
        onProgress: (Progress) -> Unit = {}
    ) {
        if (!file.exists()) throw AdbServiceException("Arquivo não encontrado: ${file.name}")
        val size = file.length()
        if (size <= 0) throw AdbServiceException("O arquivo ${file.name} está vazio.")

        connection.connect()
        connection.setTimeout(120_000)

        // Tenta o protocolo moderno primeiro.
        val hostService = "sideload-host:$size:$BLOCK_SIZE"
        val hostId = try {
            connection.openService(hostService)
        } catch (e: AdbServiceException) {
            null
        }

        if (hostId != null) {
            sideloadHost(connection, hostId, file, size, onProgress)
            return
        }

        // Fallback: sideload legado (aparelhos muito antigos, <= KitKat).
        val legacyId = try {
            connection.openService("sideload:$size")
        } catch (e: AdbServiceException) {
            throw AdbServiceException(
                "O aparelho não está em modo sideload. No recovery, escolha " +
                    "\"Apply update from ADB\" (ou \"Sideload\") e tente novamente.",
                e
            )
        }
        sideloadLegacy(connection, legacyId, file, size, onProgress)
    }

    // ------------------------------------------------------- sideload-host

    private fun sideloadHost(
        connection: AdbConnection,
        remoteId: Int,
        file: File,
        size: Long,
        onProgress: (Progress) -> Unit
    ) {
        val raf = RandomAccessFile(file, "r")
        try {
            var transferred = 0L
            var lastReported = -1
            connection.setTimeout(180_000)
            val command = ByteArray(8)
            while (true) {
                // O aparelho manda 8 bytes: número do bloco ou DONEDONE/FAILFAIL.
                readExactly(connection, remoteId, command)
                val text = String(command, Charsets.US_ASCII)
                if (text == EXIT_SUCCESS || text == EXIT_FAILURE) {
                    if (text == EXIT_FAILURE) {
                        throw AdbServiceException(
                            "O recovery reportou FALHA na instalação (FAILFAIL). " +
                                "Confira a tela do aparelho — o pacote pode ser " +
                                "incompatível, estar corrompido ou ser de outra versão."
                        )
                    }
                    onProgress(
                        Progress(size, size, 1f, "Sideload concluído — o recovery está instalando.")
                    )
                    return
                }
                // O minadbd manda o número do bloco em DECIMAL com 8 dígitos
                // (ex.: "00000023"). Tentar hex primeiro corromperia o envio
                // silenciosamente, então só aceitamos decimal.
                val block = text.trim().toLongOrNull()
                    ?: throw AdbServiceException(
                        "Resposta inesperada do recovery no sideload: \"$text\". " +
                            "O envio foi interrompido para não corromper a instalação."
                    )
                val offset = block * BLOCK_SIZE
                if (offset < 0 || offset >= size) {
                    throw AdbServiceException(
                        "O recovery pediu um bloco fora do arquivo ($block). " +
                            "O envio foi interrompido para evitar corrupção."
                    )
                }
                val toSend = minOf(BLOCK_SIZE.toLong(), size - offset).toInt()
                val chunk = ByteArray(toSend)
                raf.seek(offset)
                raf.readFully(chunk)
                // Sem esperar ACK: o minadbd só responde ao consumir o bloco inteiro.
                connection.writeServiceData(remoteId, chunk, waitForAck = false)
                transferred += toSend
                // O recovery costuma ler o pacote ~2x (verificação + instalação).
                val fraction = (transferred.toDouble() / (size * 2.13)).coerceIn(0.0, 1.0)
                val percent = (fraction * 100).toInt()
                if (percent != lastReported) {
                    lastReported = percent
                    onProgress(
                        Progress(
                            transferred, size, fraction.toFloat(),
                            "Enviando blocos… ~$percent%"
                        )
                    )
                }
            }
        } finally {
            try {
                raf.close()
            } catch (_: Exception) {
            }
            try {
                connection.closeService(remoteId)
            } catch (_: Exception) {
            }
        }
    }

    private fun readExactly(connection: AdbConnection, remoteId: Int, target: ByteArray) {
        var off = 0
        while (off < target.size) {
            val payload = connection.readServicePayload(remoteId)
            if (payload == null) {
                throw AdbServiceException(
                    "O recovery encerrou a conexão durante o sideload. " +
                        "Confira o cabo e a tela do aparelho."
                )
            }
            if (payload.isEmpty()) continue
            val n = minOf(payload.size, target.size - off)
            System.arraycopy(payload, 0, target, off, n)
            if (n < payload.size) {
                throw AdbServiceException(
                    "O recovery enviou dados inesperados durante o sideload."
                )
            }
            off += n
        }
    }

    // ------------------------------------------------------ sideload legado

    private fun sideloadLegacy(
        connection: AdbConnection,
        remoteId: Int,
        file: File,
        size: Long,
        onProgress: (Progress) -> Unit
    ) {
        var sent = 0L
        val buffer = ByteArray(BLOCK_SIZE)
        file.inputStream().buffered().use { ins ->
            while (true) {
                val n = ins.read(buffer, 0, buffer.size)
                if (n == -1) break
                if (n > 0) {
                    connection.writeServiceData(remoteId, buffer.copyOf(n), waitForAck = false)
                    sent += n
                    onProgress(
                        Progress(
                            sent, size, (sent.toDouble() / size).toFloat().coerceIn(0f, 1f),
                            "Enviando… ${(sent * 100 / size)}%"
                        )
                    )
                }
            }
        }
        // O aparelho responde OKAY ou FAIL no fim.
        connection.setTimeout(180_000)
        val reply = StringBuilder()
        while (reply.length < 64) {
            val payload = connection.readServicePayload(remoteId) ?: break
            reply.append(payload.toString(Charsets.UTF_8))
            if (reply.contains("OKAY")) break
            if (reply.contains("FAIL")) break
        }
        val text = reply.toString()
        when {
            text.contains("OKAY") -> onProgress(
                Progress(size, size, 1f, "Sideload concluído — o recovery está instalando.")
            )
            text.contains("FAIL") -> throw AdbServiceException(
                "O recovery recusou o pacote: ${text.trim()}"
            )
            else -> throw AdbServiceException(
                "O recovery não confirmou o fim do sideload (resposta: \"${text.trim()}\")."
            )
        }
    }
}
