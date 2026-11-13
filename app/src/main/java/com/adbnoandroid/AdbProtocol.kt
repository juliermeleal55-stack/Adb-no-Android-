package com.adbnoandroid

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocolo ADB no lado HOST/CLIENT — exatamente a conversa de pacotes que o
 * `adb` do PC (platform-tools) faz com o `adbd` do aparelho.
 *
 * O protocolo é o mesmo independentemente do transporte:
 *  - **USB (cabo/OTG)**: endpoints bulk da interface ADB (classe 0xFF, sub 0x42)
 *  - **TCP (Wi‑Fi)**: socket na porta 5555 (ou na porta da "Depuração sem fio")
 *
 * Fluxo: CNXN (banner + capacidades) -> [AUTH (token -> assinatura RSA -> chave pública)]
 *        -> OPEN (serviço) -> OKAY -> WRTE/OKAY … -> CLSE/CLSE
 *
 * Cada pacote tem 24 bytes little-endian:
 * (comando, arg0, arg1, data_length, data_checksum, magic) + dados.
 * `magic` = comando XOR 0xFFFFFFFF; checksum = soma dos bytes.
 *
 * Versões: 0x01000000 (original) e 0x01000001 (a partir do Android 9 o
 * checksum é ignorado). Anunciamos a v2 e negociamos o tamanho máximo de
 * payload (até 256 KB) — é o que permite transferências rápidas de verdade.
 *
 * **Ack de escrita**: de propósito NÃO anunciamos a feature `delayed_ack` no
 * nosso banner. Com ela desligada o adbd usa o fluxo clássico (ele para de
 * enviar até receber nosso OKAY a cada WRTE), que é simples e não trava; com
 * ela ligada o adbd libera até 32 MB "sem ack" e um ack vazio (sem o contador
 * de bytes) pode deixar a janela zerada para sempre em pulls grandes.
 */
object Adb {
    /** Versão original do protocolo (Android <= 8). */
    const val VERSION_V1 = 0x01000000

    /** Versão com checksum ignorado (Android 9+). */
    const val VERSION_V2 = 0x01000001

    /** Versão usada no CNXN. */
    const val VERSION = VERSION_V2

    /** Payload máximo do protocolo original (4 KB). */
    const val MAX_PAYLOAD_V1 = 4 * 1024

    /** Payload máximo anunciado por nós (o adbd moderno usa até 1 MB). */
    const val MAX_PAYLOAD_V2 = 256 * 1024

    const val DEFAULT_PORT = 5555

    /** Tamanho de dados máximo do protocolo file-sync (SYNC_DATA_MAX). */
    const val SYNC_DATA_MAX = 64 * 1024

    // IDs de comando (little-endian dos 4 caracteres ASCII)
    const val CNXN = 0x4E584E43       // "CNXN"
    const val AUTH = 0x48545541       // "AUTH"
    const val OPEN = 0x4E45504F       // "OPEN"
    const val OKAY = 0x59414B4F       // "OKAY"
    const val CLSE = 0x45534C43       // "CLSE"
    const val WRTE = 0x45545257       // "WRTE"
    const val FAIL = 0x4C494146       // "FAIL"
    const val STLS = 0x534C5453       // "STLS" (TLS — não usado)

    // arg0 do AUTH
    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    /** Marcador de exit code usado quando o shell é v1 (aparelhos antigos). */
    const val EXIT_MARKER = "__ADBNOANDROID_EXIT__"

    // Interface USB do ADB (a mesma que o adb do PC procura)
    const val USB_CLASS = 0xFF
    const val USB_SUBCLASS = 0x42
    const val USB_PROTOCOL = 0x01

    /** Features que anunciamos ao aparelho (curtas e conservadoras). */
    val HOST_FEATURES = listOf("cmd", "shell_v2")

    /** Soma de todos os bytes (checksum do protocolo). */
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
        STLS -> "STLS"
        else -> String(
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).array()
        )
    }
}

/** Um pacote do protocolo ADB decodificado. */
data class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val data: ByteArray
) {
    val name: String get() = Adb.nameOf(command)

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
}

/** Erro base do ADB/Fastboot, com mensagem pronta para a interface. */
open class AdbException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Falha de transporte: cabo removido, sem permissão USB, rede fora… */
class AdbConnectException(message: String, cause: Throwable? = null) : AdbException(message, cause)

/** O aparelho pediu autorização e o usuário ainda não tocou em "Permitir". */
class AdbAuthException(message: String) : AdbException(message)

/** O aparelho respondeu FAIL/CLSE, ou o serviço não existe naquele modo. */
class AdbServiceException(message: String) : AdbException(message)

/** Timeout de leitura/escrita. */
class AdbTimeoutException(message: String) : AdbException(message)

/**
 * Transporte de bytes entre o app e o adbd (USB bulk ou TCP).
 * O transporte entrega bytes crus: o enquadramento é do [AdbConnection].
 */
interface AdbTransport {
    fun write(data: ByteArray)

    /** Lê exatamente n bytes (lança exceção em fim de conexão/erro/timeout). */
    fun readFull(n: Int): ByteArray

    /** Timeout de leitura em ms (0 = sem timeout, usado em streams). */
    fun setTimeout(ms: Int)

    /** Identificação curta do transporte (para logs/UI). */
    fun describe(): String

    fun close()
}

/** Transporte TCP: conexão direta no adbd do aparelho (Wi‑Fi). */
class TcpTransport(
    val host: String,
    val port: Int,
    private val connectTimeoutMs: Int = 6000
) : AdbTransport {
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var input: DataInputStream? = null

    init {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: Exception) {
            try {
                s.close()
            } catch (_: Exception) {
            }
            throw AdbConnectException(
                "Não foi possível conectar em $host:$port. Confira se o aparelho está " +
                    "na mesma rede, com a Depuração sem fio ativa e a porta correta.",
                e
            )
        }
        s.soTimeout = 30_000
        socket = s
        out = DataOutputStream(s.getOutputStream())
        input = DataInputStream(s.getInputStream())
    }

    override fun write(data: ByteArray) {
        val o = out ?: throw AdbConnectException("Conexão encerrada.")
        try {
            o.write(data)
            o.flush()
        } catch (e: Exception) {
            throw AdbConnectException("A conexão de rede caiu durante o envio.", e)
        }
    }

    override fun readFull(n: Int): ByteArray {
        val i = input ?: throw AdbConnectException("Conexão encerrada.")
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = try {
                i.read(buf, off, n - off)
            } catch (e: java.net.SocketTimeoutException) {
                throw AdbTimeoutException("O aparelho não respondeu a tempo (rede).")
            } catch (e: Exception) {
                throw AdbConnectException("A conexão de rede caiu.", e)
            }
            if (r == -1) throw AdbConnectException("O aparelho encerrou a conexão.")
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

    override fun describe(): String = "tcp://$host:$port"

    override fun close() {
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            out?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        out = null
    }
}

/**
 * Conexão lógica com o adbd (banner + auth + serviços) sobre qualquer
 * [AdbTransport].
 *
 * Uso típico: `connect()` -> `openService("shell:...")` -> leitura -> `close()`.
 * A conexão pode hospedar vários serviços em sequência (ex.: STAT e depois
 * RECV), mas **não** serviços simultâneos — cada operação usa uma conexão.
 */
class AdbConnection(
    private val transport: AdbTransport,
    private val auth: AdbAuth? = null
) {
    /** Banner do aparelho depois do handshake (ex.: "device::ro.product.model=…"). */
    var banner: String? = null
        private set

    /** Versão negociada do protocolo. */
    var protocolVersion: Int = Adb.VERSION_V1
        private set

    /** Payload máximo negociado (bytes de dados por WRTE). */
    var maxPayload: Int = Adb.MAX_PAYLOAD_V1
        private set

    /** Features anunciadas PELO aparelho (ex.: shell_v2, cmd, stat_v2…). */
    var deviceFeatures: List<String> = emptyList()
        private set

    /** true quando o aparelho aceita o shell v2 (stdout/stderr/exit separados). */
    val supportsShellV2: Boolean get() = deviceFeatures.contains("shell_v2")

    /** Número de série informado no banner (pode vir vazio em USB). */
    var bannerSerial: String = ""
        private set

    @Volatile
    private var connected = false

    @Volatile
    private var closed = false

    /**
     * Lock do aparelho (ver `AdbClient.connectHeld`). Quando não é nulo, só é
     * liberado ao fechar a conexão — é isso que impede duas operações de
     * usarem a mesma interface USB ao mesmo tempo.
     */
    @Volatile
    internal var heldLock: java.util.concurrent.Semaphore? = null

    private var nextLocalId = 1

    /**
     * id local do nosso lado para cada serviço aberto: remoteId -> localId.
     *
     * O protocolo ADB é simétrico: cada lado tem o seu id para o mesmo serviço
     * e o par (local, remoto) viaja em todo pacote. Usar `arg0 = 0` faz o adbd
     * **descartar** o pacote silenciosamente (ele exige arg0 != 0), então
     * precisamos guardar o nosso id para mandar WRTE/CLSE corretamente.
     */
    private val localIds = HashMap<Int, Int>()

    @Synchronized
    private fun localIdOf(remoteId: Int): Int = localIds[remoteId] ?: 0

    @Synchronized
    private fun forgetService(remoteId: Int) {
        localIds.remove(remoteId)
    }

    @Synchronized
    private fun rememberService(remoteId: Int, localId: Int) {
        localIds[remoteId] = localId
    }

    @Synchronized
    private fun allocLocalId(): Int {
        var id = nextLocalId
        nextLocalId += 1
        if (nextLocalId > 0x7FFFFFF0) nextLocalId = 1
        // 0 é inválido como id local de serviço.
        if (id == 0) {
            id = 1
            nextLocalId = 2
        }
        return id
    }

    /** true depois que o handshake (CNXN + auth) terminou com sucesso. */
    val isConnected: Boolean get() = connected

    /**
     * Troca o banner (CNXN) e autentica se o aparelho pedir. Devolve o banner.
     *
     * **Idempotente**: chamar de novo não reenvia o CNXN. Isso é obrigatório
     * porque [AdbUsb.open] já devolve a conexão conectada e o restante do app
     * chama `connect()` por segurança antes de abrir serviços.
     */
    fun connect(): String {
        if (connected) return banner ?: ""
        if (closed) throw AdbConnectException("Conexão já encerrada.")
        val result = handshake()
        connected = true
        return result
    }

    private fun hostBanner(): ByteArray =
        ("host::features=" + Adb.HOST_FEATURES.joinToString(",") + "\u0000")
            .toByteArray(Charsets.UTF_8)

    private fun handshake(): String {
        send(Adb.CNXN, Adb.VERSION, Adb.MAX_PAYLOAD_V2, hostBanner())
        var packet = readPacket()
        if (packet.command == Adb.CNXN) return adoptBanner(packet)

        if (packet.command == Adb.STLS) {
            close()
            throw AdbServiceException(
                "O aparelho exigiu uma conexão TLS que este app não negocia. " +
                    "Desligue a opção de depuração sem fio com TLS e tente de novo."
            )
        }

        if (packet.command != Adb.AUTH) {
            close()
            throw AdbServiceException(
                "Resposta inesperada do aparelho (${packet.name}). " +
                    "Se você usou a porta de PAREAMENTO (código de 6 dígitos), use a porta " +
                    "de conexão (5555 ou a mostrada em \"Depuração sem fio\")."
            )
        }

        if (auth == null) {
            close()
            throw AdbAuthException("O aparelho exige autenticação e o app não tem chave ADB.")
        }

        var sentPublicKey = false
        var attempts = 0
        while (true) {
            attempts++
            if (packet.command != Adb.AUTH || packet.arg0 != Adb.AUTH_TOKEN) {
                close()
                throw AdbServiceException("Resposta de autenticação inesperada (${packet.name}).")
            }
            if (attempts > 4) {
                close()
                throw AdbAuthException(
                    "O aparelho recusou a chave do app. Revogue as autorizações de " +
                        "depuração USB no aparelho (Opções do desenvolvedor → Revogar autorizações) " +
                        "e tente de novo."
                )
            }

            // 1ª tentativa: assinatura do token (chave já autorizada no aparelho).
            send(Adb.AUTH, Adb.AUTH_SIGNATURE, 0, auth.sign(packet.data))
            packet = readPacket()
            if (packet.command == Adb.CNXN) return adoptBanner(packet)
            if (packet.command != Adb.AUTH) {
                close()
                throw AdbServiceException("Falha na autenticação (${packet.name}).")
            }

            // Chave desconhecida: envia a pública e espera o "Permitir" no aparelho.
            if (!sentPublicKey) {
                sentPublicKey = true
                send(
                    Adb.AUTH, Adb.AUTH_RSAPUBLICKEY, 0,
                    (auth.publicKeyAndroid() + "\u0000").toByteArray(Charsets.UTF_8)
                )
                transport.setTimeout(ADB_AUTH_TIMEOUT_MS)
                try {
                    packet = readPacket()
                } finally {
                    transport.setTimeout(30_000)
                }
                if (packet.command == Adb.CNXN) return adoptBanner(packet)
            }

            close()
            throw AdbAuthException(
                "Autorização pendente: toque em \"Permitir\" no diálogo que apareceu no " +
                    "aparelho (e marque \"Sempre permitir\"). Depois tente de novo."
            )
        }
    }

    private fun adoptBanner(packet: AdbPacket): String {
        val raw = stripNul(packet.data)
        banner = raw
        protocolVersion = packet.arg0
        maxPayload = when {
            packet.arg1 <= 0 -> Adb.MAX_PAYLOAD_V1
            else -> minOf(packet.arg1, Adb.MAX_PAYLOAD_V2)
        }.coerceAtLeast(Adb.MAX_PAYLOAD_V1)
        val bannerInfo = parseBanner(raw)
        bannerSerial = bannerInfo.serial
        deviceFeatures = bannerInfo.features
        return raw
    }

    /**
     * Abre um serviço (ex.: "shell:comando"). Devolve o id remoto da conexão.
     * Serviços que o aparelho não conhece fazem o adbd responder CLSE — nesse
     * caso a mensagem explica o motivo provável.
     */
    fun openService(destination: String): Int {
        val localId = allocLocalId()
        send(Adb.OPEN, localId, 0, (destination + "\u0000").toByteArray(Charsets.UTF_8))
        while (true) {
            val packet = readPacket()
            when (packet.command) {
                Adb.OKAY -> {
                    if (packet.arg1 == localId) {
                        rememberService(packet.arg0, localId)
                        return packet.arg0
                    }
                    // OKAY de outro serviço: ignora (não deve acontecer).
                }
                Adb.CLSE -> {
                    throw AdbServiceException(serviceClosedMessage(destination))
                }
                Adb.FAIL -> {
                    throw AdbServiceException(
                        "O aparelho recusou o serviço \"${destination}\": " +
                            stripNul(packet.data)
                    )
                }
                else -> {
                    // WRTE antes do OKAY não é esperado; ignora o pacote.
                }
            }
        }
    }

    private fun serviceClosedMessage(destination: String): String = when {
        destination.startsWith("sideload") ->
            "O aparelho não está em sideload. No recovery, escolha " +
                "\"Apply update from ADB\" (ou \"Sideload\") e tente de novo."
        destination.startsWith("rescue") ->
            "O modo rescue não está ativo neste aparelho/modo."
        destination.startsWith("sync") ->
            "O aparelho não oferece transferência de arquivos neste modo (recovery/fastboot " +
                "não tem o serviço sync)."
        destination.startsWith("shell") ->
            "O shell não está disponível agora. Confira se a depuração USB está ligada e " +
                "autorizada no aparelho."
        destination.startsWith("reboot") ->
            "O aparelho recusou reiniciar (pode estar bloqueado por política ou sem permissão)."
        destination.startsWith("root") || destination.startsWith("unroot") ->
            "Este build não aceita adb root (só builds tipo userdebug/eng)."
        destination.startsWith("remount") ->
            "Remount não permitido: o aparelho precisa estar com root (adb root) e o sistema " +
                "em modo gravável (verity/AVB pode bloquear)."
        destination.startsWith("tcpip") ->
            "O aparelho recusou mudar para TCP/IP (talvez seja um build de produção)."
        else ->
            "O aparelho não oferece o serviço \"$destination\" neste modo de conexão."
    }

    /**
     * Lê WRTE (com ack) até o CLSE e devolve a saída completa do serviço.
     * Bom para comandos curtos; para streams longos use [readServicePayload].
     */
    fun readUntilClose(remoteId: Int): String {
        val sb = StringBuilder()
        while (true) {
            val payload = readServicePayload(remoteId) ?: break
            if (payload.isNotEmpty()) sb.append(payload.toString(Charsets.UTF_8))
        }
        return sb.toString()
    }

    /**
     * Fecha o serviço (CLSE) sem fechar o transporte.
     *
     * Convenção do AOSP (`send_close`/`remote_socket_shutdown`):
     * `arg0` = NOSSO id local, `arg1` = id do par. O adbd só processa CLSE
     * com `arg1 != 0`, então mandar o id do aparelho em arg0 dava a impressão
     * de funcionar mas deixava o serviço vivo do lado dele.
     */
    fun closeService(remoteId: Int) {
        if (remoteId == 0 || closed) return
        val localId = localIdOf(remoteId)
        forgetService(remoteId)
        // arg0 == 0 só é aceito para "OPEN falhou"; para fechar normalmente
        // usamos o nosso id (quando conhecido).
        try {
            send(Adb.CLSE, localId, remoteId)
        } catch (_: Exception) {
        }
    }

    /**
     * Envia [data] num serviço já aberto (sync, sideload, shell stdin…) em
     * blocos que respeitam o payload negociado. Cada bloco vai num WRTE e
     * espera o OKAY do aparelho (fluxo clássico, como o adb do PC).
     *
     * IMPORTANTE: o adbd ignora WRTE com arg0 == 0 (`if (arg0 != 0 && arg1 != 0)`),
     * então mandamos o NOSSO id local em arg0 e o id do aparelho em arg1.
     */
    fun writeServiceData(remoteId: Int, data: ByteArray) {
        writeServiceData(remoteId, data, waitForAck = true)
    }

    /**
     * Variante em que o chamador decide se espera o OKAY.
     *
     * Sideload e streams longos **não podem** esperar: o minadbd só responde
     * depois de consumir o bloco anterior, então esperar por ACK a cada
     * escrita trava os dois lados. O `adb` oficial escreve direto e só
     * bloqueia na próxima leitura (o transporte USB já faz o controle de
     * fluxo). Serviços que respondem por pacote (sync) continuam usando a
     * versão com ACK.
     */
    fun writeServiceData(remoteId: Int, data: ByteArray, waitForAck: Boolean) {
        val localId = localIdOf(remoteId)
        if (localId == 0) {
            throw AdbConnectException(
                "Serviço não está mais aberto nesta conexão (não sei o id local)."
            )
        }
        // Um WRTE por pacote, no tamanho máximo negociado: o adbd só devolve
        // OKAY quando o serviço consome os bytes, então mandar "meio pacote"
        // deixa o fluxo de acks imprevisível.
        var off = 0
        while (off < data.size) {
            val n = minOf(maxPayload, data.size - off)
            send(Adb.WRTE, localId, remoteId, data.copyOfRange(off, off + n))
            if (waitForAck) awaitAck(remoteId)
            off += n
        }
    }

    /**
     * Espera o OKAY do aparelho para o último WRTE.
     *
     * O adbd manda um OKAY sempre que **consegue gravar** os bytes no serviço
     * (`local_socket_flush_incoming`), o que pode gerar 0, 1 ou vários acks por
     * pacote. Por isso o laço ignora OKAYs/CLSEs que não sejam do nosso serviço
     * e só considera fechamento de verdade o CLSE deste remoteId.
     */
    private fun awaitAck(remoteId: Int) {
        while (true) {
            val packet = readPacket()
            when (packet.command) {
                // O adbd manda OKAY quando grava os bytes no serviço. Pode vir
                // mais de um por WRTE: aceitamos o primeiro e seguimos.
                Adb.OKAY -> return
                Adb.CLSE -> {
                    // CLSE de outro serviço não é assunto nosso; o deste é falha.
                    if (packet.arg1 != 0 && packet.arg1 != remoteId) continue
                    forgetService(remoteId)
                    throw AdbConnectException(
                        "O aparelho encerrou o serviço durante o envio de dados."
                    )
                }
                Adb.FAIL -> throw AdbServiceException(
                    "O aparelho recusou os dados: " + stripNul(packet.data)
                )
                else -> {
                }
            }
        }
    }

    /**
     * Lê o próximo bloco de dados do serviço (ackeando o WRTE).
     * Devolve **null** quando o aparelho fecha o serviço (CLSE).
     *
     * Importante: um WRTE pode trazer MENOS de um frame do serviço (o adbd
     * envia até `maxPayload` bytes por pacote, mas o serviço pode ter juntado
     * mais). Quem chama deve tratar dados parciais — os leitores deste app
     * (shell v2, sync, sideload) têm buffer próprio justamente por isso.
     */
    fun readServicePayload(remoteId: Int): ByteArray? {
        while (true) {
            val packet = readPacket()
            when (packet.command) {
                Adb.WRTE -> {
                    // Ack clássico (sem payload): mantém o adbd no fluxo v1,
                    // que nunca "trava" esperando contadores de janela.
                    // arg0 = nosso id local, arg1 = id do aparelho (o adbd
                    // descarta OKAY com arg0 == 0 quando o par é remote).
                    send(Adb.OKAY, localIdOf(remoteId), remoteId)
                    return packet.data
                }
                Adb.CLSE -> {
                    // O aparelho fechou: não devolvemos CLSE (o AOSP também não
                    // responde a CLSE), só esquecemos o serviço.
                    forgetService(remoteId)
                    return null
                }
                Adb.OKAY -> {
                    // OKAY espontâneo/delayed_ack: ignora.
                }
                Adb.FAIL -> throw AdbServiceException(
                    "O aparelho respondeu FAIL: " + stripNul(packet.data)
                )
                else -> {
                }
            }
        }
    }

    /** Timeout de leitura (ms) para esperar a resposta do aparelho. */
    fun setTimeout(ms: Int) {
        transport.setTimeout(ms)
    }

    fun describe(): String = transport.describe()

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        try {
            transport.close()
        } finally {
            // release() só acontece uma vez: o guarda `closed` acima já impede
            // fechar duas vezes, e o synchronized cobre close() concorrente.
            heldLock?.release()
            heldLock = null
        }
    }

    // ---------------- pacotes ----------------

    /**
     * Envia um pacote. O payload pode ter até [maxPayload] bytes — o transporte
     * quebra em escritas menores respeitando o tamanho de pacote USB.
     */
    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        if (closed) throw AdbConnectException("Conexão já encerrada.")
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(data.size)
        header.putInt(if (protocolVersion >= Adb.VERSION_V2) 0 else Adb.checksum(data))
        header.putInt(command xor -1)
        transport.write(header.array())
        if (data.isNotEmpty()) transport.write(data)
    }

    private fun readPacket(): AdbPacket {
        if (closed) throw AdbConnectException("Conexão já encerrada.")
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
        if (dataLength < 0 || dataLength > Adb.MAX_PAYLOAD_V2 + 64) {
            throw AdbServiceException("Pacote com tamanho inválido: $dataLength")
        }
        val data = if (dataLength > 0) transport.readFull(dataLength) else ByteArray(0)
        // Na v2 o checksum é 0 e não deve ser validado (adbd antigo ainda manda
        // checksum; nesse caso a versão negociada é v1 e validamos).
        if (protocolVersion < Adb.VERSION_V2 &&
            dataLength > 0 && Adb.checksum(data) != dataChecksum
        ) {
            throw AdbServiceException("Checksum inválido no pacote ${Adb.nameOf(command)}.")
        }
        // FAIL é uma resposta legítima do protocolo (serviço recusado, sync
        // com erro etc.). Quem chamou decide o que fazer — lançar aqui impedia
        // os tratamentos específicos (ex.: push com pasta inexistente).
        // Só o handshake trata FAIL diretamente (cnxn/auth).
        return AdbPacket(command, arg0, arg1, data)
    }

    private fun stripNul(packet: AdbPacket): String =
        packet.data.toString(Charsets.UTF_8).trimEnd('\u0000')

    companion object {
        const val ADB_AUTH_TIMEOUT_MS = 120_000

        /**
         * Interpreta o banner do CNXN: `"device::ro.product.model=…;features=…"`.
         * O primeiro campo é o estado (device/recovery/sideload…), o segundo o
         * serial (quase sempre vazio) e o resto propriedades separadas por `;`.
         */
        fun parseBanner(banner: String): BannerInfo {
            val parts = banner.split(":")
            val state = parts.getOrNull(0)?.trim().orEmpty()
            val serial = parts.getOrNull(1)?.trim().orEmpty()
            val propsText = parts.drop(2).joinToString(":")
            val props = LinkedHashMap<String, String>()
            var features: List<String> = emptyList()
            propsText.split(";").forEach { item ->
                val t = item.trim()
                if (t.isEmpty()) return@forEach
                val eq = t.indexOf('=')
                if (eq <= 0) return@forEach
                val key = t.substring(0, eq).trim()
                val value = t.substring(eq + 1).trim()
                if (key == "features") {
                    features = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    props[key] = value
                }
            }
            return BannerInfo(state, serial, props, features)
        }
    }
}

/** Campos do banner CNXN do aparelho. */
data class BannerInfo(
    val state: String,
    val serial: String,
    val props: Map<String, String>,
    val features: List<String>
) {
    val model: String get() = props["ro.product.model"].orEmpty()

    val product: String get() = props["ro.product.name"].orEmpty()
}
