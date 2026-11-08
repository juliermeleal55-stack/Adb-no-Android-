package com.adbnoandroid

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Implementação do protocolo ADB no lado HOST/CLIENT — a mesma conversa de
 * pacotes que o `adb` do PC (platform-tools) faz com o `adbd` do aparelho.
 *
 * O protocolo é o mesmo independentemente do transporte:
 *   - **USB (cabo/OTG)**: endpoints bulk do interface ADB (class 0xFF, sub 0x42)
 *   - **TCP (Wi‑Fi)**: socket na porta 5555 ou na porta de depuração sem fio
 *
 * Fluxo: CNXN (banner) -> [AUTH (token -> assinatura RSA -> chave pública)]
 *       -> OPEN (serviço) -> OKAY -> WRTE/OKAY … -> CLSE/CLSE
 *
 * Cada pacote: 24 bytes little-endian
 * (comando, arg0, arg1, data_length, data_checksum, magic) + dados.
 * `magic` = comando XOR 0xFFFFFFFF; checksum = soma dos bytes.
 */
object Adb {
    const val VERSION = 0x01000000
    const val MAX_DATA = 4096
    const val DEFAULT_PORT = 5555

    // IDs de comando (little-endian do nome ASCII)
    const val CNXN = 0x4E584E43.toInt() // "CNXN"
    const val AUTH = 0x48545541.toInt() // "AUTH"
    const val OPEN = 0x454E4F50.toInt() // "OPEN"
    const val OKAY = 0x59414B4F.toInt() // "OKAY"
    const val CLSE = 0x45534C43.toInt() // "CLSE"
    const val WRTE = 0x45545257.toInt() // "WRTE"
    const val FAIL = 0x4C494146.toInt() // "FAIL"

    // arg0 do AUTH
    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val EXIT_MARKER = "__ADBNOANDROID_EXIT__"

    // Interface USB do ADB (a mesma que o adb do PC procura)
    const val USB_CLASS = 0xFF
    const val USB_SUBCLASS = 0x42
    const val USB_PROTOCOL = 0x01

    /** Soma de todos os bytes (o checksum do protocolo ADB). */
    fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum += (b.toInt() and 0xFF)
        return sum
    }

    fun nameOf(command: Int): String = when (command) {
        CNXN -> "CNXN"
        AUTH -> "AUTH"
        OPEN -> "OPEN"
        OKAY -> "OKAY"
        CLSE -> "CLSE"
        WRTE -> "WRTE"
        FAIL -> "FAIL"
        else -> String(
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).array()
        )
    }
}

/** Um pacote do protocolo ADB já decodificado. */
data class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val data: ByteArray
) {
    override fun equals(other: Any?) =
        other is AdbPacket &&
            command == other.command && arg0 == other.arg0 &&
            arg1 == other.arg1 && data.contentEquals(other.data)

    override fun hashCode(): Int {
        var h = command
        h = 31 * h + arg0
        h = 31 * h + arg1
        h = 31 * h + data.contentHashCode()
        return h
    }

    val name: String get() = Adb.nameOf(command)
}

/** Erros de ADB com mensagens amigáveis para a UI. */
open class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

class AdbConnectException(message: String, cause: Throwable? = null) : AdbException(message, cause)

class AdbAuthException(message: String) : AdbException(message)

class AdbServiceException(message: String) : AdbException(message)

/**
 * Transporte de bytes entre o app e o adbd (USB bulk ou TCP).
 * Implementações: [TcpTransport] e [UsbTransport].
 */
interface AdbTransport {
    fun write(data: ByteArray)

    /** Lê exatamente n bytes (lança exceção em fim de conexão/erro/timeout). */
    fun readFull(n: Int): ByteArray

    fun setTimeout(ms: Int)

    fun close()
}

/** Transporte TCP: conexão direta no adbd do aparelho (Wi‑Fi). */
class TcpTransport(
    val host: String,
    val port: Int,
    private val connectTimeoutMs: Int = 5000
) : AdbTransport {
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var input: DataInputStream? = null

    init {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: Exception) {
            s.close()
            throw AdbConnectException(
                "Não foi possível conectar em $host:$port. " +
                    "Confira se o aparelho está na mesma rede e com o ADB sem fio ativo.",
                e
            )
        }
        s.soTimeout = 30_000
        socket = s
        out = DataOutputStream(s.getOutputStream())
        input = DataInputStream(s.getInputStream())
    }

    override fun write(data: ByteArray) {
        val o = out ?: throw AdbException("Conexão não aberta.")
        o.write(data)
        o.flush()
    }

    override fun readFull(n: Int): ByteArray {
        val i = input ?: throw AdbException("Conexão não aberta.")
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = i.read(buf, off, n - off)
            if (r == -1) throw AdbConnectException("O aparelho fechou a conexão.")
            off += r
        }
        return buf
    }

    override fun setTimeout(ms: Int) {
        try {
            socket?.soTimeout = ms
        } catch (_: Exception) {
        }
    }

    override fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }
}

/**
 * Conexão lógica com o adbd (banner + auth + serviços) sobre qualquer
 * [AdbTransport] (USB ou TCP).
 *
 * Uso: `connect()` -> `openService("shell:…")` -> `readUntilClose(id)` -> fecha.
 * Uma conexão nova por comando é o mais simples e confiável (a chave RSA
 * fica autorizada no aparelho depois da primeira aprovação).
 */
class AdbConnection(
    private val transport: AdbTransport,
    private val auth: AdbAuth? = null
) {
    /** Banner do aparelho após o handshake (ex.: "device:…:produto:…"). */
    var banner: String? = null
        private set

    @Volatile
    private var connected = false

    /**
     * Troca o banner (CNXN) e autentica se o aparelho pedir. Devolve o banner.
     * **Idempotente**: conexões USB já chegam conectadas (AdbUsb.open), e
     * chamar de novo seria enviar um segundo CNXN ao adbd.
     */
    fun connect(): String {
        if (connected) return banner ?: ""
        val result = connectInner()
        connected = true
        return result
    }

    private fun connectInner(): String {
        // Banner do host (o adbd só exige o prefixo "host:").
        send(Adb.CNXN, Adb.VERSION, Adb.MAX_DATA, "host::adb_no_android\u0000".toByteArray(Charsets.UTF_8))
        var packet = readPacket()
        if (packet.command == Adb.CNXN) return finish(stripNul(packet))

        if (packet.command != Adb.AUTH) {
            close()
            throw AdbServiceException(
                "Resposta inesperada do aparelho (${packet.name}). " +
                    "Se você usou a porta de PAREAMENTO (código de 6 dígitos), use a porta de " +
                    "conexão em vez dela (5555 ou a mostrada em \"Depuração sem fio\")."
            )
        }

        // -------- Autenticação (chave RSA, igual ao adb do PC) --------
        if (auth == null) {
            close()
            throw AdbAuthException("O aparelho exige autenticação e o app não tem uma chave ADB.")
        }
        while (true) {
            if (packet.arg0 != Adb.AUTH_TOKEN) {
                close()
                throw AdbServiceException("Resposta de autenticação inesperada (arg0=${packet.arg0}).")
            }
            // Assina o token do aparelho com nossa chave privada.
            val signature = auth.sign(packet.data)
            send(Adb.AUTH, Adb.AUTH_SIGNATURE, 0, signature)
            packet = readPacket()
            if (packet.command == Adb.CNXN) return finish(stripNul(packet))
            if (packet.command != Adb.AUTH) {
                close()
                throw AdbServiceException("Falha na autenticação (${packet.name}).")
            }
            // O aparelho rejeitou a chave: envia a chave pública e espera o
            // usuário tocar "Permitir" no diálogo do aparelho.
            send(Adb.AUTH, Adb.AUTH_RSAPUBLICKEY, 0, (auth.publicKeyPem() + "\u0000").toByteArray(Charsets.UTF_8))
            transport.setTimeout(120_000)
            packet = readPacket()
            transport.setTimeout(30_000)
            if (packet.command == Adb.CNXN) return finish(stripNul(packet))
            close()
            throw AdbAuthException(
                "Autenticação não aceita pelo aparelho. Toque em \"Permitir\" no diálogo " +
                    "que apareceu nele e tente de novo."
            )
        }
    }

    /** Abre um serviço (ex.: "shell:comando"). Retorna o id remoto da conexão. */
    fun openService(destination: String): Int {
        send(Adb.OPEN, LOCAL_ID, 0, (destination + "\u0000").toByteArray(Charsets.UTF_8))
        val packet = readPacket()
        if (packet.command == Adb.CLSE) {
            throw AdbServiceException(
                "O aparelho não oferece o serviço \"$destination\". " +
                    "Para sideload, ele precisa estar em RECOVERY com \"Apply update from ADB\"."
            )
        }
        if (packet.command != Adb.OKAY) {
            throw AdbServiceException("Resposta inesperada ao abrir o serviço (${packet.name}).")
        }
        return packet.arg0
    }

    /** Lê WRTE (com ack) até o CLSE e devolve a saída completa do serviço. */
    fun readUntilClose(remoteId: Int): String {
        val sb = StringBuilder()
        while (true) {
            val packet = readPacket()
            when (packet.command) {
                Adb.WRTE -> {
                    send(Adb.OKAY, LOCAL_ID, remoteId) // ack do WRTE
                    sb.append(packet.data.toString(Charsets.UTF_8))
                }
                Adb.CLSE -> {
                    closeService(remoteId)
                    return sb.toString()
                }
                else -> throw AdbServiceException("Pacote inesperado durante a leitura (${packet.name}).")
            }
        }
    }

    /** Fecha o serviço (CLSE) sem fechar o transporte. */
    fun closeService(remoteId: Int) {
        send(Adb.CLSE, LOCAL_ID, remoteId)
    }

    /**
     * Envia [data] num serviço já aberto (ex.: "sync:", "sideload:") em blocos
     * de 4096 bytes. Cada bloco vai num pacote WRTE e espera o ack OKAY do
     * aparelho — exatamente o que o `adb` do PC faz no file-sync protocol.
     */
    fun writeServiceData(remoteId: Int, data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = minOf(Adb.MAX_DATA, data.size - off)
            send(Adb.WRTE, LOCAL_ID, remoteId, data.copyOfRange(off, off + n))
            val packet = readPacket()
            if (packet.command != Adb.OKAY || packet.arg0 != LOCAL_ID || packet.arg1 != remoteId) {
                throw AdbServiceException(
                    "Resposta inesperada ao enviar dados (${packet.name})."
                )
            }
            off += n
        }
    }

    /**
     * Lê o próximo bloco de dados do serviço (ackeando o WRTE).
     * Devolve **null** quando o aparelho fecha o serviço (CLSE).
     */
    fun readServicePayload(remoteId: Int): ByteArray? {
        val packet = readPacket()
        return when (packet.command) {
            Adb.WRTE -> {
                if (packet.arg0 != remoteId || packet.arg1 != LOCAL_ID) {
                    throw AdbServiceException(
                        "WRTE de serviço inesperado (arg0=${packet.arg0}, arg1=${packet.arg1})."
                    )
                }
                send(Adb.OKAY, LOCAL_ID, remoteId) // ack do WRTE
                packet.data
            }
            Adb.CLSE -> {
                if (packet.arg0 != remoteId) {
                    throw AdbServiceException("CLSE de serviço inesperado (arg0=${packet.arg0}).")
                }
                closeService(remoteId)
                null
            }
            else -> throw AdbServiceException(
                "Pacote inesperado na leitura do serviço (${packet.name})."
            )
        }
    }

    // ---------------- I/O cru (protocolo file-sync: sideload) ----------------

    fun writeRaw(data: ByteArray) {
        transport.write(data)
    }

    fun readRaw(n: Int): ByteArray = transport.readFull(n)

    /**
     * Lê um frame file-sync. `OKAY`/`FAIL` vêm sozinhos (4 bytes, sem payload);
     * os demais comandos (DATA, DONE, BEGIN…) têm payload terminado em NUL.
     */
    fun readSyncFrame(): String {
        val header = transport.readFull(4)
        val cmd = String(header, Charsets.US_ASCII)
        if (cmd == "OKAY" || cmd == "FAIL") return cmd
        val payload = StringBuilder()
        while (true) {
            val b = transport.readFull(1)[0]
            if (b == 0.toByte()) break
            payload.append(b.toInt().toChar())
            if (payload.length > 512) {
                throw AdbServiceException("Frame file-sync grande demais (comando $cmd).")
            }
        }
        return cmd + payload.toString()
    }

    /** Timeout de leitura (ms) para esperar a resposta do aparelho. */
    fun setTimeout(ms: Int) {
        transport.setTimeout(ms)
    }

    fun close() {
        transport.close()
    }

    // ---------------- pacotes ----------------

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(data.size)
        header.putInt(Adb.checksum(data))
        header.putInt(command xor -1)
        transport.write(header.array())
        if (data.isNotEmpty()) transport.write(data)
    }

    private fun readPacket(): AdbPacket {
        val header = transport.readFull(24)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val dataLength = bb.int
        val dataChecksum = bb.int
        val magic = bb.int
        if (magic != (command xor -1)) {
            throw AdbServiceException("Pacote inválido recebido (magic não confere).")
        }
        if (dataLength < 0 || dataLength > Adb.MAX_DATA) {
            throw AdbServiceException("Pacote com tamanho inválido: $dataLength")
        }
        val data = if (dataLength > 0) transport.readFull(dataLength) else ByteArray(0)
        if (command == Adb.FAIL) {
            val detail = if (data.isNotEmpty()) String(data, Charsets.UTF_8).trim() else ""
            throw AdbServiceException(
                "O aparelho respondeu FAIL ao comando ${Adb.nameOf(arg0)}" +
                    if (detail.isNotEmpty()) ": $detail" else "."
            )
        }
        if (dataLength > 0 && Adb.checksum(data) != dataChecksum) {
            throw AdbServiceException("Checksum inválido no pacote ${Adb.nameOf(command)}.")
        }
        return AdbPacket(command, arg0, arg1, data)
    }

    private fun stripNul(packet: AdbPacket): String =
        packet.data.toString(Charsets.UTF_8).removeSuffix("\u0000")

    private fun finish(bannerStr: String): String {
        banner = bannerStr
        return bannerStr
    }

    companion object {
        /** Id local usado pelo host para a conexão de serviço. */
        const val LOCAL_ID = 1
    }
}
