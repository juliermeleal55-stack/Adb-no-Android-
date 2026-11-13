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

/**
 * Acesso ao USB Host (cabo OTG).
 *
 * O aparelho de destino com depuração USB ligada apresenta uma interface
 * vendor-specific: class 0xFF, subclass 0x42 ("ADB"), protocol 0x01, com dois
 * endpoints bulk (IN/OUT). É exatamente o que o `adb` do PC procura via
 * libusb — aqui usamos a UsbManager/UsbDeviceConnection do Android.
 */
object AdbUsb {

    /** Um dispositivo USB com interface ADB (a lista do "adb devices" por cabo). */
    data class UsbDeviceInfo(
        val deviceName: String,
        val vendorId: Int,
        val productId: Int,
        val serial: String,
        val manufacturer: String,
        val product: String,
        val hasPermission: Boolean,
        /** 0 quando ainda não sabemos; preenchido após o handshake. */
        val maxPayload: Int = 0
    ) {
        fun label(): String = when {
            serial.isNotBlank() -> serial
            product.isNotBlank() -> product
            else -> "USB ${String.format("%04x:%04x", vendorId, productId)}"
        }

        fun shortId(): String = String.format("%04x:%04x", vendorId, productId)
    }

    /** Índice da interface ADB no dispositivo, ou null se não for um ADB. */
    fun findAdbInterface(device: UsbDevice): Int? {
        for (c in 0 until device.configurationCount) {
            val config = try {
                device.getConfiguration(c)
            } catch (_: Exception) {
                null
            } ?: continue
            for (i in 0 until config.interfaceCount) {
                val itf: UsbInterface = config.getInterface(i)
                if (isAdbInterface(itf)) return i
            }
        }
        return null
    }

    private fun isAdbInterface(itf: UsbInterface): Boolean =
        itf.interfaceClass == Adb.USB_CLASS &&
            itf.interfaceSubclass == Adb.USB_SUBCLASS &&
            itf.interfaceProtocol == Adb.USB_PROTOCOL

    fun isAdbDevice(device: UsbDevice): Boolean = findAdbInterface(device) != null

    /** Lista os dispositivos USB com interface ADB. */
    fun listAdbDevices(context: Context): List<UsbDeviceInfo> {
        val usb = usbManager(context)
        val result = ArrayList<UsbDeviceInfo>()
        val devices = try {
            usb.deviceList.values.toList()
        } catch (_: Exception) {
            emptyList()
        }
        for (device in devices) {
            if (findAdbInterface(device) == null) continue
            val serial = try {
                device.serialNumber ?: ""
            } catch (_: Exception) {
                ""
            }
            val manufacturer = try {
                device.manufacturerName ?: ""
            } catch (_: Exception) {
                ""
            }
            val product = try {
                device.productName ?: ""
            } catch (_: Exception) {
                ""
            }
            result.add(
                UsbDeviceInfo(
                    deviceName = device.deviceName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    serial = serial,
                    manufacturer = manufacturer,
                    product = product,
                    hasPermission = usb.hasPermission(device)
                )
            )
        }
        return result.sortedBy { it.deviceName }
    }

    fun usbManager(context: Context): UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun deviceByName(context: Context, deviceName: String): UsbDevice? =
        try {
            usbManager(context).deviceList[deviceName]
        } catch (_: Exception) {
            null
        }

    fun hasPermission(context: Context, device: UsbDevice): Boolean =
        try {
            usbManager(context).hasPermission(device)
        } catch (_: Exception) {
            false
        }

    /**
     * Pede permissão de acesso ao dispositivo USB (diálogo do sistema).
     * O resultado chega em [UsbReceiver.ACTION_USB_PERMISSION].
     */
    fun requestPermission(context: Context, device: UsbDevice) {
        val usb = usbManager(context)
        val flags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(
            context,
            device.deviceId + 1,
            Intent(UsbReceiver.ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        usb.requestPermission(device, pi)
    }

    /**
     * Abre o dispositivo, claima a interface ADB, abre os endpoints bulk e faz
     * o handshake (banner + auth). A conexão devolvida já está conectada
     * ([AdbConnection.banner] preenchido).
     */
    fun open(context: Context, device: UsbDevice, auth: AdbAuth?): AdbConnection {
        val manager = usbManager(context)
        if (!manager.hasPermission(device)) {
            throw AdbConnectException(
                "Sem permissão USB para ${device.deviceName}. Toque em \"Permitir USB\" " +
                    "e aceite o diálogo do sistema."
            )
        }
        val conn: UsbDeviceConnection = try {
            manager.openDevice(device)
        } catch (e: Exception) {
            null
        } ?: throw AdbConnectException(
            "Falha ao abrir o dispositivo USB ${device.deviceName}. " +
                "Confira o cabo OTG e se a permissão USB foi concedida."
        )

        var claimed: UsbInterface? = null
        try {
            val itfIndex = findAdbInterface(device)
                ?: throw AdbServiceException("Dispositivo USB sem interface ADB.")
            val config = device.getConfiguration(0)
            val itf = config.getInterface(itfIndex)
            val ok = try {
                conn.claimInterface(itf, true)
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                throw AdbConnectException(
                    "Não foi possível assumir a interface ADB (o dispositivo pode estar " +
                        "em uso por outro app ou pelo sistema)."
                )
            }
            claimed = itf

            var epOut: UsbEndpoint? = null
            var epIn: UsbEndpoint? = null
            for (i in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) epOut = ep
                if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) epIn = ep
            }
            val out = epOut ?: throw AdbServiceException("Endpoint USB OUT não encontrado.")
            val inp = epIn ?: throw AdbServiceException("Endpoint USB IN não encontrado.")

            // Alguns host controllers precisam de um instante após o claim.
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
            }

            val transport = UsbTransport(
                conn = conn,
                epOut = out,
                epIn = inp,
                outPacketSize = out.maxPacketSize,
                inPacketSize = inp.maxPacketSize
            )
            val connection = AdbConnection(transport, auth)
            connection.connect() // handshake (CNXN + auth) já aqui, com erro claro
            return connection
        } catch (e: Exception) {
            if (claimed != null) {
                try {
                    conn.releaseInterface(claimed)
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
 * Transporte USB: endpoints bulk da interface ADB.
 *
 * Regras que o AOSP segue (usb_linux.cpp) e que aplicamos aqui:
 * - escritas maiores que 16 KB são divididas (o kernel aloca um buffer
 *   contíguo por transferência e falha com ENOMEM em blocos grandes);
 * - quando o total escrito é múltiplo do tamanho máximo de pacote do endpoint,
 *   envia-se um pacote de comprimento zero para delimitar o fim do dado.
 *
 * A leitura usa blocos do tamanho máximo de pacote (o que o endpoint devolveu
 * na descrição) e trata timeout como [AdbTimeoutException].
 */
class UsbTransport(
    private val conn: UsbDeviceConnection,
    private val epOut: UsbEndpoint,
    private val epIn: UsbEndpoint,
    private val outPacketSize: Int,
    private val inPacketSize: Int,
    private var timeoutMs: Int = 30_000
) : AdbTransport {

    /**
     * Limite por transferência bulk.
     *
     * O kernel precisa alocar um buffer contíguo por transferência, por isso o
     * AOSP limita a 16 KB (usb_linux.cpp). Isso NÃO tem relação com o tamanho
     * do pacote USB: mandar 64 bytes por vez (como era feito antes) gerava
     * centenas de transferências por pacote ADB e deixava push/instalação
     * lentíssimos.
     */
    private val maxTransfer: Int = 16 * 1024

    private val writeChunk: Int = if (maxTransfer < outPacketSize) {
        outPacketSize
    } else {
        // Alinha ao tamanho de pacote para o kernel não fragmentar sozinho.
        val ps = if (outPacketSize > 0) outPacketSize else 512
        (maxTransfer / ps) * ps
    }

    private val readChunk: Int = if (maxTransfer < inPacketSize) {
        inPacketSize
    } else {
        val ps = if (inPacketSize > 0) inPacketSize else 512
        (maxTransfer / ps) * ps
    }

    @Volatile
    private var closed = false

    override fun write(data: ByteArray) {
        if (closed) throw AdbConnectException("Conexão USB encerrada.")
        var off = 0
        while (off < data.size) {
            val n = minOf(writeChunk, data.size - off)
            val sent = try {
                conn.bulkTransfer(epOut, data, off, n, timeoutMs)
            } catch (e: Exception) {
                throw AdbConnectException("Falha de comunicação USB (escrita).", e)
            }
            if (sent < 0) {
                throw AdbConnectException(
                    "Falha USB ao enviar dados. O cabo pode ter sido desconectado."
                )
            }
            if (sent == 0) throw AdbConnectException("Timeout USB ao enviar dados.")
            off += sent
        }
        if (outPacketSize > 0 && data.size % outPacketSize == 0) {
            // ZLP: delimita o fim de uma transferência múltipla do pacote.
            try {
                conn.bulkTransfer(epOut, ByteArray(0), 0, 0, timeoutMs)
            } catch (_: Exception) {
            }
        }
    }

    override fun readFull(n: Int): ByteArray {
        if (closed) throw AdbConnectException("Conexão USB encerrada.")
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val want = minOf(readChunk, n - off)
            val tmp = ByteArray(want)
            val r = try {
                conn.bulkTransfer(epIn, tmp, 0, want, timeoutMs)
            } catch (e: Exception) {
                throw AdbConnectException("Falha de comunicação USB (leitura).", e)
            }
            if (r < 0) {
                throw if (timeoutMs > 0) {
                    AdbTimeoutException(
                        "O aparelho não respondeu via USB em ${timeoutMs / 1000}s " +
                            "(timeout). Confira o cabo e se o aparelho continua autorizado."
                    )
                } else {
                    AdbConnectException("Falha USB ao ler dados. O cabo pode ter sido removido.")
                }
            }
            if (r == 0) {
                throw AdbTimeoutException("Timeout USB ao ler do aparelho.")
            }
            tmp.copyInto(buf, off, 0, r)
            off += r
        }
        return buf
    }

    override fun setTimeout(ms: Int) {
        timeoutMs = ms
    }

    override fun describe(): String = "usb"

    override fun close() {
        if (closed) return
        closed = true
        try {
            conn.close()
        } catch (_: Exception) {
        }
    }
}
