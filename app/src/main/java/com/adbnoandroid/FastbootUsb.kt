package com.adbnoandroid

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.File

/**
 * Acesso a aparelhos em **modo fastboot** (bootloader) por USB OTG.
 *
 * O bootloader expõe uma interface vendor-specific (class 0xFF, subclass 0x42,
 * protocol 0x03) com dois endpoints bulk — mesma ideia da interface do ADB.
 * O app fala o protocolo fastboot diretamente (comandos ASCII), então
 * `fastboot devices`, `getvar`, `flash`, `erase`, `boot`, `reboot` funcionam
 * sem PC.
 *
 * Nem todo bootloader/hardware suporta tudo: quando o aparelho responde FAIL
 * ou não tem a interface, a UI mostra a mensagem real do bootloader — nada é
 * "dado como disponível" sem confirmação do aparelho.
 */
object FastbootUsb {

    fun usbManager(context: Context): UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Índice da interface fastboot (0xFF/0x42/0x03) ou null. */
    fun findFastbootInterface(device: UsbDevice): Int? {
        for (c in 0 until device.configurationCount) {
            val config = try {
                device.getConfiguration(c)
            } catch (_: Exception) {
                null
            } ?: continue
            for (i in 0 until config.interfaceCount) {
                val itf = config.getInterface(i)
                if (itf.interfaceClass == Fastboot.FB_CLASS &&
                    itf.interfaceSubclass == Fastboot.FB_SUBCLASS &&
                    itf.interfaceProtocol == Fastboot.FB_PROTOCOL
                ) {
                    return i
                }
            }
        }
        return null
    }

    /** Alguns aparelhos expõem o fastbootd (userspace) com outra combinação. */
    fun findFastbootdInterface(device: UsbDevice): Int? {
        for (c in 0 until device.configurationCount) {
            val config = try {
                device.getConfiguration(c)
            } catch (_: Exception) {
                null
            } ?: continue
            for (i in 0 until config.interfaceCount) {
                val itf = config.getInterface(i)
                if (itf.interfaceClass == Fastboot.FB_CLASS &&
                    itf.interfaceProtocol != 0x01 // não é ADB
                ) {
                    val hasBulk = (0 until itf.endpointCount).any {
                        itf.getEndpoint(it).type == UsbConstants.USB_ENDPOINT_XFER_BULK
                    }
                    if (hasBulk) return i
                }
            }
        }
        return null
    }

    fun isFastbootDevice(device: UsbDevice): Boolean =
        findFastbootInterface(device) != null || isFastbootd(device)

    private fun isFastbootd(device: UsbDevice): Boolean {
        // fastbootd aparece com uma interface 0xFF/0x42/0x03 também, mas em
        // alguns aparelhos com o próprio protocolo ADB desligado. A checagem
        // por interface é suficiente: se não for ADB e tiver bulk, tentamos.
        for (c in 0 until device.configurationCount) {
            val config = try {
                device.getConfiguration(c)
            } catch (_: Exception) {
                null
            } ?: continue
            for (i in 0 until config.interfaceCount) {
                val itf = config.getInterface(i)
                if (itf.interfaceClass == Fastboot.FB_CLASS &&
                    itf.interfaceSubclass == Fastboot.FB_SUBCLASS &&
                    itf.interfaceProtocol != Adb.USB_PROTOCOL
                ) {
                    return true
                }
            }
        }
        return false
    }

    /** Lista os aparelhos em modo fastboot visíveis no USB. */
    fun listDevices(context: Context): List<FastbootDeviceInfo> {
        val usb = usbManager(context)
        val devices = try {
            usb.deviceList.values.toList()
        } catch (_: Exception) {
            emptyList()
        }
        val out = ArrayList<FastbootDeviceInfo>()
        for (device in devices) {
            val idx = findFastbootInterface(device) ?: findFastbootdInterface(device) ?: continue
            // Se for a interface ADB (protocol 0x01), não é fastboot.
            val config = device.getConfiguration(0)
            val itf = config.getInterface(idx)
            if (itf.interfaceProtocol == Adb.USB_PROTOCOL) continue
            val serial = try {
                device.serialNumber ?: ""
            } catch (_: Exception) {
                ""
            }
            val product = try {
                device.productName ?: ""
            } catch (_: Exception) {
                ""
            }
            out.add(
                FastbootDeviceInfo(
                    deviceName = device.deviceName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    serial = serial,
                    product = product,
                    hasPermission = usb.hasPermission(device),
                    interfaceIndex = idx
                )
            )
        }
        return out.sortedBy { it.deviceName }
    }

    fun requestPermission(context: Context, device: UsbDevice) {
        val usb = usbManager(context)
        val flags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(
            context,
            device.deviceId + 1000,
            Intent(UsbReceiver.ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        usb.requestPermission(device, pi)
    }

    fun deviceByName(context: Context, deviceName: String): UsbDevice? =
        try {
            usbManager(context).deviceList[deviceName]
        } catch (_: Exception) {
            null
        }

    /** Abre o transporte fastboot (claim + endpoints). */
    fun open(context: Context, device: UsbDevice, interfaceIndex: Int? = null): FastbootTransport {
        val manager = usbManager(context)
        if (!manager.hasPermission(device)) {
            throw AdbConnectException(
                "Sem permissão USB para o aparelho em modo fastboot. Autorize o acesso USB."
            )
        }
        val conn: UsbDeviceConnection = try {
            manager.openDevice(device)
        } catch (_: Exception) {
            null
        } ?: throw AdbConnectException("Falha ao abrir o dispositivo USB em modo fastboot.")

        var claimedItf: UsbInterface? = null
        try {
            val idx = interfaceIndex
                ?: findFastbootInterface(device)
                ?: findFastbootdInterface(device)
                ?: throw AdbServiceException(
                    "Este aparelho USB não está oferecendo a interface fastboot. " +
                        "Reinicie no bootloader (adb reboot bootloader) e reconecte."
                )
            val itf = device.getConfiguration(0).getInterface(idx)
            if (!conn.claimInterface(itf, true)) {
                throw AdbConnectException(
                    "Não foi possível assumir a interface fastboot (pode estar em uso)."
                )
            }
            claimedItf = itf
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
            }
            val inEp = epIn ?: throw AdbServiceException("Endpoint fastboot IN ausente.")
            val outEp = epOut ?: throw AdbServiceException("Endpoint fastboot OUT ausente.")
            try {
                Thread.sleep(40)
            } catch (_: InterruptedException) {
            }
            return FastbootTransport(conn, inEp, outEp)
        } catch (e: Exception) {
            if (claimedItf != null) {
                try {
                    conn.releaseInterface(claimedItf)
                } catch (_: Exception) {
                }
            }
            try {
                conn.close()
            } catch (_: Exception) {
            }
            throw e
        }
    }
}

/**
 * Transporte fastboot sobre USB bulk. Síncrono: envia um comando de até 4 KB e
 * lê respostas de até 256 bytes.
 *
 * Nota sobre leitura: `bulkTransfer` devolve o pacote inteiro do endpoint
 * (não espera encher o buffer), então lemos com o buffer máximo e usamos o
 * tamanho devolvido — exatamente o que o driver do PC faz.
 */
class FastbootTransport(
    private val conn: UsbDeviceConnection,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    initialTimeoutMs: Int = 30_000
) {
    @Volatile
    private var closed = false

    @Volatile
    private var timeoutMs: Int = initialTimeoutMs

    /** Envia um comando ASCII (sem terminador, como manda a especificação). */
    fun send(command: String) {
        if (closed) throw AdbConnectException("Conexão fastboot encerrada.")
        val bytes = command.toByteArray(Charsets.US_ASCII)
        if (bytes.size > 4096) throw AdbServiceException("Comando fastboot grande demais.")
        var off = 0
        while (off < bytes.size) {
            val n = minOf(4096, bytes.size - off)
            val sent = try {
                conn.bulkTransfer(epOut, bytes, off, n, timeoutMs)
            } catch (e: Exception) {
                throw AdbConnectException("Falha USB ao enviar comando fastboot.", e)
            }
            if (sent < 0) throw AdbConnectException(
                "Falha ao enviar comando fastboot (o aparelho pode ter saído do bootloader)."
            )
            off += sent
        }
    }

    /** Lê uma resposta (um pacote) do bootloader. */
    fun readResponse(): String {
        if (closed) throw AdbConnectException("Conexão fastboot encerrada.")
        val buf = ByteArray(256)
        val n = try {
            conn.bulkTransfer(epIn, buf, 0, buf.size, timeoutMs)
        } catch (e: Exception) {
            throw AdbConnectException("Falha USB ao ler resposta do fastboot.", e)
        }
        if (n < 0) {
            throw if (timeoutMs > 0) {
                AdbTimeoutException(
                    "O bootloader não respondeu (timeout). Confira a tela do aparelho."
                )
            } else {
                AdbConnectException("Falha ao ler a resposta do bootloader.")
            }
        }
        return String(buf, 0, n, Charsets.US_ASCII).trimEnd('\u0000')
    }

    /**
     * Envia [data] (a fase de dados do `download`).
     * Divide em blocos pequenos para não estourar o buffer do controlador.
     */
    fun sendData(data: File, total: Long, onProgress: (Long) -> Unit = {}) {
        var sent = 0L
        val buffer = ByteArray(16384)
        data.inputStream().buffered().use { ins ->
            while (true) {
                val n = ins.read(buffer, 0, buffer.size)
                if (n == -1) break
                if (n <= 0) continue
                writeChunk(buffer, n)
                sent += n
                onProgress(sent)
            }
        }
        if (sent != total) {
            throw AdbServiceException(
                "O arquivo mudou de tamanho durante o envio ($sent de $total bytes)."
            )
        }
    }

    /** Envia um bloco de dados cru. */
    fun writeChunk(data: ByteArray, length: Int) {
        var off = 0
        while (off < length) {
            val n = minOf(16384, length - off)
            val sent = try {
                conn.bulkTransfer(epOut, data, off, n, timeoutMs)
            } catch (e: Exception) {
                throw AdbConnectException("Falha USB durante o envio de dados.", e)
            }
            if (sent <= 0) {
                throw AdbConnectException(
                    "O bootloader parou de receber dados (cabo removido?)."
                )
            }
            off += sent
        }
    }

    /** Timeout (ms) usado nas próximas transferências. 0 = sem timeout. */
    fun setTimeout(ms: Int) {
        timeoutMs = ms
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            conn.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Sessão de alto nível do fastboot: conversa com o bootloader e transforma
 * respostas em resultados prontos para a UI.
 */
class FastbootSession(private val transport: FastbootTransport) : java.io.Closeable {

    /**
     * Envia um comando e junta INFO/TEXT até OKAY/FAIL.
     * Devolve ([ok], saída completa).
     */
    fun command(cmd: String): FastbootResult {
        transport.send(cmd)
        val sb = StringBuilder()
        while (true) {
            val response = transport.readResponse()
            when {
                Fastboot.isOkay(response) -> {
                    val extra = Fastboot.messageOf(response)
                    if (extra.isNotEmpty()) sb.append(extra).append('\n')
                    return FastbootResult(cmd, sb.toString().trimEnd(), true)
                }
                Fastboot.isFail(response) -> {
                    val msg = Fastboot.messageOf(response)
                    return FastbootResult(
                        cmd,
                        sb.toString().trimEnd(),
                        false,
                        msg.ifBlank { "FAIL" }
                    )
                }
                response.startsWith("INFO") -> {
                    sb.append(Fastboot.messageOf(response)).append('\n')
                }
                response.startsWith("TEXT") -> {
                    sb.append(Fastboot.messageOf(response)).append('\n')
                }
                response.startsWith("DATA") -> {
                    // Não esperado sem download.
                    return FastbootResult(cmd, sb.toString().trimEnd(), false, "DATA inesperado")
                }
                else -> sb.append(response).append('\n')
            }
        }
    }

    /** `getvar:<nome>` — lê uma variável do bootloader. */
    fun getVar(name: String): String? {
        val res = command("getvar:$name")
        return if (res.ok) {
            // Alguns bootloaders devolvem o valor antes do OKAY (INFO) e outros
            // no próprio OKAY. A primeira linha não vazia serve para os dois.
            res.output.lines().firstOrNull { it.isNotBlank() }?.trim()
        } else null
    }

    /** `getvar:all` — todas as variáveis, já em mapa. */
    fun getVarAll(): Map<String, String> {
        val res = command("getvar:all")
        return Fastboot.parseGetvarAll(res.output)
    }

    /**
     * Fase de `download`: manda o tamanho, espera `DATA`, envia os bytes e
     * espera OKAY. Devolve o resultado (com as mensagens do bootloader).
     */
    fun download(
        file: File,
        onProgress: (Long) -> Unit = {}
    ): FastbootResult {
        val size = file.length()
        if (size <= 0) {
            return FastbootResult("download", "", false, "Arquivo vazio: ${file.name}")
        }
        val sz = Fastboot.downloadCommand(size)
        transport.send(sz)
        val sb = StringBuilder()
        // Lê até DATA/FAIL (INFO pode vir antes).
        while (true) {
            val response = transport.readResponse()
            when {
                response.startsWith("DATA") -> {
                    // O bootloader confirma o tamanho que vai aceitar. Se ele
                    // anunciar algo diferente do nosso arquivo, abortamos: enviar
                    // o tamanho errado corromperia a imagem.
                    val announced = Fastboot.parseDataSize(response)
                    if (announced != null && announced != size) {
                        return FastbootResult(
                            sz, sb.toString().trimEnd(), false,
                            "O bootloader espera $announced bytes, mas o arquivo tem $size. " +
                                "Envio cancelado para não corromper a imagem."
                        )
                    }
                    transport.sendData(file, size) { sent -> onProgress(sent) }
                    // Espera OKAY final.
                    while (true) {
                        val done = transport.readResponse()
                        when {
                            Fastboot.isOkay(done) -> return FastbootResult(
                                sz, sb.toString().trimEnd(), true
                            )
                            Fastboot.isFail(done) -> return FastbootResult(
                                sz, sb.toString().trimEnd(), false,
                                Fastboot.messageOf(done).ifBlank { "FAIL no download" }
                            )
                            done.startsWith("INFO") -> sb.append(Fastboot.messageOf(done)).append('\n')
                            done.startsWith("TEXT") -> sb.append(Fastboot.messageOf(done)).append('\n')
                            else -> sb.append(done).append('\n')
                        }
                    }
                }
                Fastboot.isFail(response) -> return FastbootResult(
                    sz, sb.toString().trimEnd(), false,
                    Fastboot.messageOf(response).ifBlank { "FAIL no download" }
                )
                Fastboot.isOkay(response) -> return FastbootResult(
                    sz, sb.toString().trimEnd(), false,
                    "O bootloader aceitou o download mas não abriu a fase de dados."
                )
                response.startsWith("INFO") -> sb.append(Fastboot.messageOf(response)).append('\n')
                response.startsWith("TEXT") -> sb.append(Fastboot.messageOf(response)).append('\n')
                else -> sb.append(response).append('\n')
            }
        }
    }

    /** `flash:<partição>` depois de um [download]. */
    fun flash(partition: String): FastbootResult = command("flash:$partition")

    /** `erase:<partição>`. */
    fun erase(partition: String): FastbootResult = command("erase:$partition")

    /** `boot` — inicia a imagem baixada sem gravar. */
    fun bootDownloaded(): FastbootResult = command("boot")

    /** `reboot`, `reboot-bootloader`, `reboot-recovery` (quando suportado). */
    fun reboot(target: String = ""): FastbootResult =
        command(if (target.isBlank()) "reboot" else "reboot-$target")

    /** `getvar:current-slot` (A/B). */
    fun currentSlot(): String? = getVar("current-slot")

    /** Lista de slots A/B quando existem. */
    fun slotSuffix(): String {
        val slot = currentSlot() ?: return ""
        return if (slot.isBlank()) "" else "_$slot"
    }

    override fun close() = transport.close()
}
