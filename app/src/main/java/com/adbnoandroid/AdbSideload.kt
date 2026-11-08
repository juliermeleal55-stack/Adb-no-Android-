package com.adbnoandroid

import android.hardware.usb.UsbDevice
import java.io.File

/**
 * Sideload — o `adb sideload <zip>` do PC: envia um arquivo (ex.: update.zip
 * de custom ROM / GApps) para o recovery do aparelho de destino, que faz o
 * flash sozinho.
 *
 * O serviço "sideload:" fala o **file-sync protocol** do ADB (protocolo
 * legado, o mesmo que o platform-tools usa). Tudo trafega em pacotes WRTE
 * (cada um ackeado com OKAY pelo aparelho):
 *
 *   BEGIN:100644<8 hex do tamanho>\0  -> OKAY
 *   [blocos crus de 4096 bytes]       -> DATA:<offset em hex>\0
 *   DONE:<checksum em hex>\0          -> OKAY
 *
 * O aparelho de destino precisa estar em RECOVERY com a opção
 * "Apply update from ADB" selecionada.
 */
object AdbSideload {

    private const val CHUNK = 4096

    /** Sideload via USB. [onProgress] recebe (bytes enviados, total). */
    fun sideloadUsb(
        device: UsbDevice,
        file: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val context = AdbClient.appContextOrNull()
            ?: throw AdbException("App não inicializado.")
        val connection = AdbUsb.open(context, device, AdbClient.authOrNull())
        try {
            doSideload(connection, file, onProgress)
        } finally {
            connection.close()
        }
    }

    /** Sideload via Wi‑Fi. */
    fun sideloadTcp(
        host: String,
        port: Int,
        file: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val connection = AdbConnection(TcpTransport(host, port), AdbClient.authOrNull())
        try {
            doSideload(connection, file, onProgress)
        } finally {
            connection.close()
        }
    }

    private fun doSideload(
        connection: AdbConnection,
        file: File,
        onProgress: (Long, Long) -> Unit
    ) {
        connection.connect()
        connection.setTimeout(120_000)
        val remoteId = connection.openService("sideload:")
        val reader = SyncTextReader(connection, remoteId)
        try {
            val size = file.length()
            connection.writeServiceData(
                remoteId,
                "BEGIN:100644${String.format("%08x", size)}\0".toByteArray(Charsets.US_ASCII)
            )
            expectTextFrame(reader, "OKAY", "iniciar o sideload")

            var offset = 0L
            var checksum = 0
            val buffer = ByteArray(CHUNK)
            file.inputStream().buffered().use { ins ->
                while (true) {
                    val n = ins.read(buffer, 0, CHUNK)
                    if (n == -1) break
                    if (n > 0) {
                        connection.writeServiceData(remoteId, buffer.copyOf(n))
                        for (i in 0 until n) {
                            checksum = (checksum + (buffer[i].toInt() and 0xFF)) and 0xFFFFFFFF
                        }
                        // O recovery responde DATA:<novo offset> a cada bloco
                        val frame = reader.readFrame()
                        if (!frame.startsWith("DATA:")) {
                            throw AdbServiceException(
                                "Resposta inesperada do recovery durante o sideload: $frame"
                            )
                        }
                        offset = frame.substring(5).trim().toLong(16)
                        onProgress(offset, size)
                    }
                }
            }

            connection.writeServiceData(
                remoteId,
                "DONE:${String.format("%08x", checksum)}\0".toByteArray(Charsets.US_ASCII)
            )
            expectTextFrame(reader, "OKAY", "finalizar o sideload")
            onProgress(size, size)
        } finally {
            // O recovery pode reiniciar em seguida — enviamos o CLSE sem
            // esperar a resposta.
            try {
                connection.closeService(remoteId)
            } catch (_: Exception) {
            }
        }
    }

    private fun expectTextFrame(reader: SyncTextReader, expected: String, oQue: String) {
        val frame = reader.readFrame()
        if (frame != expected) {
            throw AdbServiceException("Falha ao $oQue (o recovery respondeu: $frame).")
        }
    }
}
