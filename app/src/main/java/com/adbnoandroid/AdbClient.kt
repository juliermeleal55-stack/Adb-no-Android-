package com.adbnoandroid

import android.content.Context
import android.hardware.usb.UsbDevice
import java.io.File

/** Uma sessão de logcat ao vivo (stream) em execução. */
interface LogSession {
    /** Para o stream. O [LogcatCallbacks.onDone] ainda é chamado em seguida. */
    fun stop()
}

/** Callbacks de uma sessão de logcat. [onDone] recebe null se parou limpo, ou a mensagem do erro. */
class LogcatCallbacks(
    val onChunk: (String) -> Unit,
    val onDone: (String?) -> Unit
)

/** Status de um dispositivo na lista. */
enum class DeviceStatus {
    UNKNOWN,
    CHECKING,
    ONLINE,
    OFFLINE,
    NEEDS_AUTH,
    ERROR
}

/** Um dispositivo remoto (outro aparelho) alcançável por ADB sem fio. */
data class AdbDeviceInfo(
    val host: String,
    val port: Int = Adb.DEFAULT_PORT,
    val name: String = "",
    val status: DeviceStatus = DeviceStatus.UNKNOWN,
    val banner: String = "",
    val model: String = ""
) {
    fun address(): String = "$host:$port"

    /** Nome exibido: o apelido, ou o endereço se não houver apelido. */
    fun label(): String = name.ifBlank { address() }
}

/**
 * Camada de alto nível do ADB — a "platform-tools" dentro do app:
 * conecta em outros aparelhos por **USB (cabo/OTG)** ou **Wi‑Fi** e roda
 * comandos no shell deles, igual ao `adb` de um PC.
 */
object AdbClient {

    private var auth: AdbAuth? = null
    private var appContext: Context? = null

    /** Cria/carrega a chave RSA do app. Chamar no onCreate da Activity. */
    fun init(context: Context) {
        if (auth == null) {
            appContext = context.applicationContext
            auth = AdbAuth(File(context.filesDir, "adb/adbkey.pem"))
        }
    }

    // ===================== Wi-Fi (TCP) =====================

    /** Conecta pela rede e lê o banner do aparelho. */
    fun probe(host: String, port: Int, connectTimeoutMs: Int = 5000): String {
        val connection = AdbConnection(TcpTransport(host, port, connectTimeoutMs), auth)
        try {
            return connection.connect()
        } finally {
            connection.close()
        }
    }

    /** Roda um comando no shell do aparelho remoto (Wi‑Fi). */
    fun shell(host: String, port: Int, command: String, timeoutMs: Long = 180_000): ShellResult {
        val connection = AdbConnection(TcpTransport(host, port), auth)
        return withConnection(connection, command, timeoutMs)
    }

    // ===================== USB (cabo/OTG) =====================

    /** Conecta pelo cabo e lê o banner do aparelho. */
    fun probeUsb(device: UsbDevice, connectTimeoutMs: Int = 5000): String {
        val context = appContext
            ?: throw AdbException("App não inicializado.")
        val connection = AdbUsb.open(context, device, auth)
        try {
            return connection.banner
                ?: throw AdbException("Conexão USB sem banner.")
        } finally {
            connection.close()
        }
    }

    /** Roda um comando no shell do aparelho conectado por USB. */
    fun shellUsb(device: UsbDevice, command: String, timeoutMs: Long = 180_000): ShellResult {
        val context = appContext
            ?: return ShellResult("App não inicializado.", null, false, true, false)
        val connection = try {
            AdbUsb.open(context, device, auth)
        } catch (e: AdbException) {
            return ShellResult(e.message ?: "Falha na conexão USB.", null, false, true, false)
        }
        return withConnection(connection, command, timeoutMs)
    }

    // ===================== comum =====================

    /** Extrai o modelo do banner (4º campo: produto). */
    fun parseModel(banner: String): String {
        val parts = banner.split(":")
        return if (parts.size >= 4) parts[3].trim() else ""
    }

    // ===================== logcat ao vivo (stream) =====================

    /**
     * Logcat ao vivo no aparelho remoto via Wi‑Fi — igual ao `adb logcat` do PC.
     * Chama [LogcatCallbacks.onChunk] a cada bloco recebido, em uma thread de
     * fundo, até [LogSession.stop] (ou o aparelho fechar a sessão).
     *
     * @param logcatArgs argumentos do logcat (ex.: "-b all" ou "-b all *:E").
     */
    fun logcatTcp(host: String, port: Int, logcatArgs: String, callbacks: LogcatCallbacks): LogSession {
        val session = AdbLogSession(
            openConnection = { AdbConnection(TcpTransport(host, port), auth) },
            service = "shell:logcat $logcatArgs",
            callbacks = callbacks
        )
        session.start()
        return session
    }

    /** Logcat ao vivo no aparelho conectado por USB (cabo/OTG). */
    fun logcatUsb(device: UsbDevice, logcatArgs: String, callbacks: LogcatCallbacks): LogSession {
        val context = appContext
            ?: throw AdbException("App não inicializado.")
        val session = AdbLogSession(
            openConnection = { AdbUsb.open(context, device, auth) },
            service = "shell:logcat $logcatArgs",
            callbacks = callbacks
        )
        session.start()
        return session
    }

    internal fun appContextOrNull(): Context? = appContext

    internal fun authOrNull(): AdbAuth? = auth

    /**
     * Sessão de logcat sobre um transporte ADB (USB ou TCP). A leitura fica
     * SEM timeout (um logcat silencioso não pode matar o stream); parar a
     * sessão fecha o transporte e a thread termina sozinha.
     */
    private class AdbLogSession(
        private val openConnection: () -> AdbConnection,
        private val service: String,
        private val callbacks: LogcatCallbacks
    ) : LogSession {
        @Volatile private var stopped = false
        @Volatile private var connection: AdbConnection? = null

        fun start() {
            val thread = Thread {
                var conn: AdbConnection? = null
                @Volatile var done = false
                fun finish(msg: String?) {
                    if (!done) {
                        done = true
                        callbacks.onDone(msg)
                    }
                }
                try {
                    conn = openConnection()
                    connection = conn
                    conn.connect()
                    // 0 = sem timeout: o stream fica aberto até o usuário parar.
                    conn.setTimeout(0)
                    val remoteId = conn.openService(service)
                    while (!stopped) {
                        val payload = conn.readServicePayload(remoteId) ?: break // CLSE = fim
                        if (payload.isNotEmpty()) {
                            callbacks.onChunk(payload.toString(Charsets.UTF_8))
                        }
                    }
                    finish(null)
                } catch (e: Exception) {
                    finish(if (stopped) null else (e.message ?: e.javaClass.simpleName))
                } finally {
                    try {
                        conn?.close()
                    } catch (_: Exception) {
                    }
                    finish(null)
                }
            }
            thread.isDaemon = true
            thread.start()
        }

        override fun stop() {
            stopped = true
            try {
                connection?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun withConnection(
        connection: AdbConnection,
        command: String,
        timeoutMs: Long
    ): ShellResult {
        try {
            connection.connect()
            connection.setTimeout(timeoutMs.toInt())
            val remoteId = connection.openService(
                "shell:$command; echo ${Adb.EXIT_MARKER}:\$?"
            )
            val raw = connection.readUntilClose(remoteId)
            val (clean, code) = findExitMarker(raw) ?: (raw.trimEnd() to null)
            return ShellResult(clean, code, false, false, false)
        } catch (e: AdbException) {
            return ShellResult(e.message ?: "Falha no ADB.", null, false, true, false)
        } catch (e: Exception) {
            return ShellResult(
                "Erro ao executar: ${e.message ?: e.javaClass.simpleName}",
                null, false, true, false
            )
        } finally {
            connection.close()
        }
    }
}
