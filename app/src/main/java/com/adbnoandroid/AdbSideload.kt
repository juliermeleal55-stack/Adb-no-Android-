package com.adbnoandroid

import android.hardware.usb.UsbDevice
import java.io.File

/**
 * Sideload — o `adb sideload <zip>` do PC: envia um arquivo (ex.: update.zip
 * de custom ROM / GApps) para o recovery do aparelho de destino, que faz o
 * flash sozinho.
 *
 * O serviço "sideload:" fala o **file-sync protocol** do ADB:
 *   BEGIN:<modo><tamanho em hex>\0  -> OKAY
 *   [dados em blocos de 4096]       -> DATA:<offset em hex>
 *   DONE:<checksum em hex>\0        -> OKAY
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
        connection.setTimeout(60_000)
        val remoteId = connection.openService("sideload:")

        val size = file.length()
        connection.writeRaw(
            ("BEGIN:100644${String.format("%08x", size)}\0").toByteArray(Charsets.US_ASCII)
        )
        expectFrame(connection, "OKAY", "iniciar o sideload")

        var offset = 0L
        var checksum = 0
        val buffer = ByteArray(CHUNK)
        file.inputStream().buffered().use { ins ->
            while (true) {
                val n = ins.read(buffer, 0, CHUNK)
                if (n == -1) break
                if (n > 0) {
                    connection.writeRaw(buffer.copyOf(n))
                    for (i in 0 until n) {
                        checksum = (checksum + (buffer[i].toInt() and 0xFF)) and 0xFFFFFFFF
                    }
                }
                // O recovery responde DATA:<novo offset> a cada bloco
                val frame = connection.readSyncFrame()
                if (!frame.startsWith("DATA:")) {
                    throw AdbServiceException(
                        "Resposta inesperada do recovery durante o sideload: $frame"
                    )
                }
                offset = frame.substring(5).toLong(16)
                onProgress(offset, size)
            }
        }

        connection.writeRaw(
            ("DONE:${String.format("%08x", checksum)}\0").toByteArray(Charsets.US_ASCII)
        )
        expectFrame(connection, "OKAY", "finalizar o sideload")
        onProgress(size, size)
        connection.closeService(remoteId)
    }

    private fun expectFrame(connection: AdbConnection, expected: String, oQue: String) {
        val frame = connection.readSyncFrame()
        if (frame != expected) {
            throw AdbServiceException("Falha ao $oQue (o recovery respondeu: $frame).")
        }
    }
}
