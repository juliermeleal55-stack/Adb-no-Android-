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

/**
 * ADB por USB (cabo OTG): o app usa a USB Host API do Android para falar
 * direto com o `adbd` do aparelho conectado — exatamente o que o `adb` do
 * PC (platform-tools) faz via libusb.
 *
 * O aparelho de destino aparece como interface USB vendor-specific:
 * class 0xFF, subclass 0x42 ("ADB"), protocol 0x01, com dois endpoints bulk.
 */
object AdbUsb {

    /** Um dispositivo USB com interface ADB (a tela "adb devices" por cabo). */
    data class UsbDeviceInfo(
        val deviceName: String, // ex.: /dev/bus/usb/001/005
        val serial: String,
        val manufacturer: String,
        val product: String,
        val hasPermission: Boolean
    ) {
        fun label(): String = when {
            serial.isNotBlank() -> "USB: $serial"
            product.isNotBlank() -> "USB: $product"
            else -> "USB: $deviceName"
        }
    }

    /** Índice da interface ADB no dispositivo, ou null se não for um ADB. */
    fun findAdbInterface(device: UsbDevice): Int? {
        val config = device.configurationList.firstOrNull() ?: return null
        for (i in 0 until config.interfaceCount) {
            val itf: UsbInterface = config.getInterface(i)
            if (itf.interfaceClass == Adb.USB_CLASS &&
                itf.interfaceSubclass == Adb.USB_SUBCLASS &&
                itf.interfaceProtocol == Adb.USB_PROTOCOL
            ) {
                return i
            }
        }
        return null
    }

    /** Lista os dispositivos USB com interface ADB (o "adb devices" por cabo). */
    fun listAdbDevices(context: Context): List<UsbDeviceInfo> {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val result = ArrayList<UsbDeviceInfo>()
        for (device in usb.deviceList.values) {
            if (findAdbInterface(device) == null) continue
            val serial = device.serialNumber ?: ""
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
                    serial = serial,
                    manufacturer = manufacturer,
                    product = product,
                    hasPermission = usb.hasPermission(device)
                )
            )
        }
        return result
    }

    fun usbManager(context: Context): UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun deviceByName(context: Context, deviceName: String): UsbDevice? =
        usbManager(context).deviceList[deviceName]

    fun hasPermission(context: Context, device: UsbDevice): Boolean =
        usbManager(context).hasPermission(device)

    /**
     * Pede permissão de acesso ao dispositivo USB (diálogo do sistema).
     * O resultado chega em [UsbReceiver.ACTION_USB_PERMISSION] e a UI
     * re-lista os dispositivos (o estado de permissão é relido do UsbManager).
     */
    fun requestPermission(context: Context, device: UsbDevice) {
        val usb = usbManager(context)
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(UsbReceiver.ACTION_USB_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        usb.requestPermission(device, pi)
    }

    /**
     * Abre o dispositivo, claima a interface ADB, abre os endpoints bulk e
     * faz o handshake (banner + auth). Devolve a conexão pronta para abrir
     * serviços (shell, sideload…). O `banner` fica em [AdbConnection.banner].
     */
    fun open(context: Context, device: UsbDevice, auth: AdbAuth?): AdbConnection {
        val manager = usbManager(context)
        val conn: UsbDeviceConnection = try {
            manager.openDevice(device)
        } catch (e: Exception) {
            throw AdbConnectException(
                "Falha ao abrir o dispositivo USB ${device.deviceName}. " +
                    "Confira o cabo OTG e se a permissão USB foi concedida.",
                e
            )
        }
        try {
            val itfIndex = findAdbInterface(device)
                ?: throw AdbServiceException("Dispositivo USB ${device.deviceName} sem interface ADB.")
            val itf = device.configurationList.firstOrNull()?.getInterface(itfIndex)
                ?: throw AdbServiceException("Interface ADB indisponível.")
            try {
                conn.claimInterface(itf, true)
            } catch (e: Exception) {
                throw AdbConnectException(
                    "Não foi possível assumir a interface ADB (o dispositivo pode estar em uso).",
                    e
                )
            }
            var epOut: UsbEndpoint? = null
            var epIn: UsbEndpoint? = null
            for (i in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(i)
                if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
            }
            val o = epOut ?: throw AdbServiceException("Endpoint USB OUT não encontrado.")
            val i = epIn ?: throw AdbServiceException("Endpoint USB IN não encontrado.")
            // Alguns host controllers precisam de uma pequena pausa após o claim.
            Thread.sleep(60)
            val transport = UsbTransport(conn, o, i)
            val connection = AdbConnection(transport, auth)
            connection.connect() // pode lançar; fecha o transporte em caso de erro
            return connection
        } catch (e: Exception) {
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
 * O Android limita alguns bulkTransfer a buffers pequenos em certos
 * controladores — por isso tudo é enviado/lido em blocos de 4096 bytes.
 */
class UsbTransport(
    private val conn: UsbDeviceConnection,
    private val epOut: UsbEndpoint,
    private val epIn: UsbEndpoint,
    private var timeoutMs: Int = 30_000
) : AdbTransport {

    override fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val chunk = minOf(CHUNK, data.size - off)
            val n = conn.bulkTransfer(epOut, data, off, chunk, timeoutMs)
            if (n < 0) throw AdbConnectException("Falha USB ao enviar dados (código $n).")
            if (n == 0) throw AdbConnectException("Timeout USB ao enviar dados.")
            off += n
        }
    }

    override fun readFull(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val want = minOf(CHUNK, n - off)
            val tmp = ByteArray(want)
            val r = conn.bulkTransfer(epIn, tmp, 0, want, timeoutMs)
            if (r < 0) throw AdbConnectException("Falha USB ao ler dados (código $r).")
            if (r == 0) throw AdbConnectException("Timeout USB ao ler do aparelho.")
            tmp.copyInto(buf, off, 0, r)
            off += r
        }
        return buf
    }

    override fun setTimeout(ms: Int) {
        timeoutMs = ms
    }

    override fun close() {
        try {
            conn.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val CHUNK = 4096
    }
}
