package com.adbnoandroid

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Implementação do protocolo ADB no lado HOST/CLIENT — a mesma conversa de
 * pacotes que o `adb` do PC faz com o `adbd` do aparelho remoto (por TCP):
 *
 *   CNXN (banner) -> [AUTH (token -> assinatura RSA -> chave pública)]
 *   OPEN (serviço, ex.: "shell:comando") -> OKAY
 *   WRTE (dados) + OKAY (ack) ... CLSE / CLSE
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

    // arg0 do AUTH
    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val EXIT_MARKER = "__ADBNOANDROID_EXIT__"

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
 * Conexão TCP com o `adbd` de um aparelho remoto (porta 5555 por padrão,
 * ou a porta mostrada em "Depuração sem fio" no Android 11+).
 *
 * Uso típico: `connect()` -> `openShell(command)` -> fecha.
 * Uma conexão nova por comando é o mais simples e confiável (a chave RSA
 * fica autorizada no aparelho depois da primeira aprovação).
 */
class AdbConnection(
    val host: String,
    val port: Int,
    private val auth: AdbAuth? = null,
    private val connectTimeoutMs: Int = 5000
) {
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var input: DataInputStream? = null

    /** Abre o TCP, troca o banner (CNXN) e autentica se o aparelho pedir. */
    fun connect(): String {
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

        // Banner do host (o adbd só exige o prefixo "host:").
        send(Adb.CNXN, Adb.VERSION, Adb.MAX_DATA, "host::adb_no_android\0".toByteArray(Charsets.UTF_8))
        var packet = readPacket()
        if (packet.command == Adb.CNXN) return stripNul(packet)

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
            if (packet.command == Adb.CNXN) return stripNul(packet)
            if (packet.command != Adb.AUTH) {
                close()
                throw AdbServiceException("Falha na autenticação (${packet.name}).")
            }
            // O aparelho rejeitou a chave: envia a chave pública e espera o
            // usuário tocar "Permitir" no diálogo do aparelho.
            send(Adb.AUTH, Adb.AUTH_RSAPUBLICKEY, 0, (auth.publicKeyPem() + "\0").toByteArray(Charsets.UTF_8))
            s.soTimeout = 120_000
            packet = readPacket()
            s.soTimeout = 30_000
            if (packet.command == Adb.CNXN) return stripNul(packet)
            close()
            throw AdbAuthException(
                "Autenticação não aceita pelo aparelho. Toque em \"Permitir\" no diálogo " +
                    "que apareceu nele e tente de novo."
            )
        }
    }

    /** Abre um serviço (ex.: "shell:comando"). Retorna o id remoto da conexão. */
    fun openService(destination: String): Int {
        val packet = openServiceRaw(destination)
        if (packet.command == Adb.CLSE) {
            throw AdbServiceException("O aparelho não oferece o serviço \"$destination\".")
        }
        if (packet.command != Adb.OKAY) {
            throw AdbServiceException("Resposta inesperada ao abrir o serviço (${packet.name}).")
        }
        return packet.arg0
    }

    private fun openServiceRaw(destination: String): AdbPacket {
        send(Adb.OPEN, LOCAL_ID, 0, (destination + "\0").toByteArray(Charsets.UTF_8))
        val packet = readPacket()
        if (packet.arg1 != LOCAL_ID && packet.arg1 != 0) {
            throw AdbServiceException("OPEN endereçado a outro id (${packet.arg1}).")
        }
        return packet
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
                    send(Adb.CLSE, LOCAL_ID, remoteId)
                    return sb.toString()
                }
                else -> throw AdbServiceException("Pacote inesperado durante a leitura (${packet.name}).")
            }
        }
    }

    /** Timeout de leitura (ms) para esperar a resposta do aparelho. */
    fun setTimeout(ms: Int) {
        try {
            socket?.soTimeout = ms
        } catch (_: Exception) {
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    // ---------------- transporte ----------------

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val o = out ?: throw AdbException("Conexão não aberta.")
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(data.size)
        header.putInt(Adb.checksum(data))
        header.putInt(command xor -1)
        o.write(header.array())
        if (data.isNotEmpty()) o.write(data)
        o.flush()
    }

    private fun readPacket(): AdbPacket {
        val i = input ?: throw AdbException("Conexão não aberta.")
        val header = readFull(24)
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
        val data = if (dataLength > 0) readFull(dataLength) else ByteArray(0)
        if (dataLength > 0 && Adb.checksum(data) != dataChecksum) {
            throw AdbServiceException("Checksum inválido no pacote ${Adb.nameOf(command)}.")
        }
        return AdbPacket(command, arg0, arg1, data)
    }

    private fun readFull(n: Int): ByteArray {
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

    private fun stripNul(packet: AdbPacket): String =
        packet.data.toString(Charsets.UTF_8).removeSuffix("\u0000")

    companion object {
        /** Id local usado pelo host para a conexão de serviço. */
        const val LOCAL_ID = 1
    }
}