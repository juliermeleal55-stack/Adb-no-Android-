package com.adbnoandroid

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Execução de comandos no shell do aparelho de destino — o
 * `adb shell <comando>` do platform-tools, com duas implementações:
 *
 * - **shell v2** (`shell,v2,raw:<cmd>`): usada quando o adbd anuncia a feature
 *   `shell_v2` (Android 7+). O aparelho devolve pacotes com ID (stdin/stdout/
 *   stderr/exit) — a saída de erro vem separada e o **exit code é real**, então
 *   nunca precisamos "sujar" a saída do usuário com marcadores.
 * - **shell v1** (`shell:<cmd>`): aparelhos antigos, um único fluxo misto;
 *   o exit code é obtido com `; echo marcador:$?` e removido da saída.
 *
 * O modo v2 também permite **streaming**: cada bloco de stdout/stderr chega
 * num callback em tempo real (logcat, dumpsys, instalação por streaming…).
 */

/** Resultado de um comando de shell. */
data class ShellResult(
    val output: String,
    val exitCode: Int?,
    /** Texto que veio no canal de erro (só shell v2 preenche). */
    val stderr: String = "",
    /** true = a execução em si falhou (transporte/autorização). */
    val failed: Boolean = false,
    /** true = estourou o timeout configurado. */
    val timedOut: Boolean = false,
    val durationMs: Long = 0,
    /** Protocolo usado ("shell_v2" ou "shell"). */
    val protocol: String = ""
) {
    val ok: Boolean get() = !failed && !timedOut && (exitCode == null || exitCode == 0)

    /** Saída combinada para exibição (stdout + stderr, se houver). */
    fun combined(): String = when {
        stderr.isBlank() -> output
        output.isBlank() -> stderr
        else -> output.trimEnd() + "\n" + stderr
    }
}

/**
 * Fonte de dados para o **stdin** de um processo (usado pelo
 * `cmd package install -S <tamanho>` — instalação por streaming).
 */
class StdSource(
    /** Tamanho total (bytes) que será enviado. */
    val size: Long,
    /** Próximo bloco de dados; null quando acabou. */
    val reader: () -> ByteArray?,
    /** Progresso em bytes enviados. */
    val onProgress: ((Long) -> Unit)? = null
)

/** Callbacks do modo streaming. */
class ShellStreamCallbacks(
    val onStdout: (String) -> Unit,
    val onStderr: (String) -> Unit = {},
    val onExit: (Int?) -> Unit = {},
    /** Falha de transporte/protocolo que encerrou o stream (cabo, timeout…). */
    val onError: (String) -> Unit = {}
)

/** Aviso de encerramento da sessão (timeout, queda, erro de protocolo). */
/**
 * O stream do shell terminou de forma anormal (timeout, queda do cabo, serviço
 * fechado). É uma **exceção de verdade** (`Throwable`) porque atravessa
 * `catch (e: Exception)` em toda a camada de cima — antes era uma classe
 * comum e nenhum `catch` conseguia pegá-la.
 */
class StreamClosed(
    override val message: String?,
    val timedOut: Boolean = false
) : AdbException(message ?: "O stream do shell foi encerrado.")

object AdbShell {

    private const val MAX_CAPTURE = 8 * 1024 * 1024

    /**
     * Roda um comando curto e devolve tudo (stdout, stderr, exit code).
     *
     * @param command sem o prefixo "adb shell" (ex.: "getprop ro.product.model").
     * @param timeoutMs tempo máximo.
     */
    fun exec(
        connection: AdbConnection,
        command: String,
        timeoutMs: Int = 120_000,
        stdin: StdSource? = null
    ): ShellResult {
        val collected = StringBuilder()
        val collectedErr = StringBuilder()
        var exit: Int? = null
        val started = System.currentTimeMillis()

        val close = try {
            run(
                connection = connection,
                command = command,
                timeoutMs = timeoutMs,
                stdin = stdin,
                callbacks = ShellStreamCallbacks(
                    onStdout = { if (collected.length < MAX_CAPTURE) collected.append(it) },
                    onStderr = { if (collectedErr.length < MAX_CAPTURE) collectedErr.append(it) },
                    onExit = { exit = it }
                )
            )
            null
        } catch (e: StreamClosed) {
            e
        } catch (e: AdbException) {
            StreamClosed(e.message)
        }

        var output = collected.toString().trimEnd()
        var stderr = collectedErr.toString().trimEnd()
        if (close != null && close.message != null) {
            if (output.isBlank()) {
                output = close.message ?: ""
            } else {
                stderr = listOf(stderr, close.message).filter { it.isNotBlank() }
                    .joinToString("\n")
            }
        }
        return ShellResult(
            output = output,
            exitCode = exit,
            stderr = stderr,
            failed = close?.message != null,
            timedOut = close?.timedOut == true,
            durationMs = System.currentTimeMillis() - started,
            protocol = if (connection.supportsShellV2) "shell_v2" else "shell"
        )
    }

    /**
     * Executa um comando entregando a saída em blocos ao vivo. Bloqueante —
     * chame de uma thread/Dispatchers.IO. Termina normalmente quando o processo
     * acaba; lança [StreamClosed] em timeout/queda para o chamador diferenciar
     * "acabou" de "caiu".
     */
    fun run(
        connection: AdbConnection,
        command: String,
        timeoutMs: Int,
        stdin: StdSource? = null,
        callbacks: ShellStreamCallbacks
    ) {
        if (connection.supportsShellV2) {
            runV2(connection, command, timeoutMs, stdin, callbacks)
        } else {
            // Shell v1 não tem canal de stdin: um comando que dependa de dados
            // nossos (install -S, tar) ficaria esperando para sempre. Falhar
            // claro é melhor que travar a tela.
            if (stdin != null) {
                throw StreamClosed(
                    "Este aparelho não suporta envio de dados pelo shell (shell v1). " +
                        "A operação precisa de um Android 7+ com shell_v2."
                )
            }
            runV1(connection, command, timeoutMs, callbacks)
        }
    }

    // ---------------------------------------------------------------- shell v2

    private fun runV2(
        connection: AdbConnection,
        command: String,
        timeoutMs: Int,
        stdin: StdSource?,
        callbacks: ShellStreamCallbacks
    ) {
        val remoteId = try {
            connection.openService("shell,v2,raw:$command")
        } catch (e: AdbException) {
            throw StreamClosed(e.message)
        }

        // Buffer de bytes do shell protocol: os pacotes de 5 bytes de header +
        // dados podem chegar partidos entre WRTEs.
        val buf = ByteArrayOutputStream()
        var exitCode: Int? = null
        var stdinState = StdinState.IDLE
        var finished = false

        fun handleFrames() {
            val bytes = buf.toByteArray()
            var off = 0
            while (off + ShellProtocol.HEADER_SIZE <= bytes.size) {
                val id = bytes[off].toInt() and 0xFF
                val len = ByteBuffer.wrap(bytes, off + 1, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int
                if (len < 0 || off + ShellProtocol.HEADER_SIZE + len > bytes.size) break
                val start = off + ShellProtocol.HEADER_SIZE
                val end = start + len
                when (id) {
                    ShellProtocol.STDOUT -> if (len > 0) {
                        callbacks.onStdout(String(bytes, start, len, Charsets.UTF_8))
                    }
                    ShellProtocol.STDERR -> if (len > 0) {
                        callbacks.onStderr(String(bytes, start, len, Charsets.UTF_8))
                    }
                    ShellProtocol.EXIT -> {
                        exitCode = parseExit(bytes, start, len)
                        finished = true
                    }
                    ShellProtocol.STDIN -> {
                        // O daemon não usa este id para pedir dados (quem envia
                        // STDIN é o host), mas se algum build pedir, respondemos
                        // com o que tivermos — e fechamos em seguida.
                        if (stdinState == StdinState.IDLE) {
                            stdinState = StdinState.SENDING
                            sendStdin(connection, remoteId, stdin)
                            stdinState = StdinState.CLOSED
                        }
                    }
                    ShellProtocol.CLOSE_STDIN -> {
                        stdinState = StdinState.CLOSED
                    }
                    else -> {
                        // kIdWindowSizeChange / inválidos: ignora.
                    }
                }
                off = end
            }
            if (off > 0) {
                buf.reset()
                if (off < bytes.size) buf.write(bytes, off, bytes.size - off)
            }
        }

        try {
            // Se o comando não for receber dados nossos, fechamos o canal de
            // stdin logo no início: é o que o `adb shell` faz e evita processos
            // parados para sempre esperando entrada (ex.: `cat`, `pm install`).
            if (stdin == null) {
                sendId(connection, remoteId, ShellProtocol.CLOSE_STDIN, ByteArray(0))
            }
            while (true) {
                connection.setTimeout(if (finished) 3000 else timeoutMs)
                val payload = connection.readServicePayload(remoteId) ?: break // CLSE
                if (payload.isNotEmpty()) {
                    buf.write(payload, 0, payload.size)
                    handleFrames()
                }
                if (exitCode != null) {
                    // Depois do EXIT o adbd fecha a sessão; continuamos até CLSE.
                    finished = true
                }
            }
            callbacks.onExit(exitCode)
        } catch (e: AdbTimeoutException) {
            callbacks.onExit(exitCode)
            // Se o comando já tinha terminado (EXIT recebido), um timeout só
            // significa que o adbd não mandou o CLSE — não é falha do comando.
            if (exitCode == null) {
                throw StreamClosed(e.message ?: "Timeout no shell.", timedOut = true)
            }
        } catch (e: AdbConnectException) {
            callbacks.onExit(exitCode)
            throw StreamClosed(e.message ?: "Conexão perdida.")
        } catch (e: AdbException) {
            callbacks.onExit(exitCode)
            throw StreamClosed(e.message ?: "Falha no shell.")
        } finally {
            try {
                connection.closeService(remoteId)
            } catch (_: Exception) {
            }
        }
    }

    private enum class StdinState { IDLE, SENDING, CLOSED }

    private fun sendStdin(connection: AdbConnection, remoteId: Int, src: StdSource?) {
        if (src == null) {
            sendId(connection, remoteId, ShellProtocol.CLOSE_STDIN, ByteArray(0))
            return
        }
        var sent = 0L
        try {
            while (true) {
                val chunk = src.reader() ?: break
                if (chunk.isEmpty()) continue
                sendId(connection, remoteId, ShellProtocol.STDIN, chunk)
                sent += chunk.size
                src.onProgress?.invoke(sent)
            }
        } finally {
            sendId(connection, remoteId, ShellProtocol.CLOSE_STDIN, ByteArray(0))
        }
    }

    private fun parseExit(bytes: ByteArray, start: Int, len: Int): Int? = when {
        len <= 0 -> 0
        len == 1 -> (bytes[start].toInt() and 0xFF).coerceAtMost(127)
        else -> ByteBuffer.wrap(bytes, start, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    /**
     * Envia um frame do shell protocol (1 byte de id + 4 bytes de tamanho + dados).
     *
     * Aqui **esperamos o OKAY** de propósito: não anunciamos `delayed_ack`, então
     * o adbd bloqueia o serviço até confirmar cada WRTE (`sockets.cpp` →
     * "acks not deferred, blocking"). É exatamente esse ack que dá o controle de
     * fluxo do stdin.
     */
    private fun sendId(connection: AdbConnection, remoteId: Int, id: Int, data: ByteArray) {
        val head = ByteBuffer.allocate(ShellProtocol.HEADER_SIZE + data.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        head.put(id.toByte())
        head.putInt(data.size)
        if (data.isNotEmpty()) head.put(data)
        connection.writeServiceData(remoteId, head.array())
    }

    // ---------------------------------------------------------------- shell v1

    private fun runV1(
        connection: AdbConnection,
        command: String,
        timeoutMs: Int,
        callbacks: ShellStreamCallbacks
    ) {
        val marker = Adb.EXIT_MARKER
        val remoteId = try {
            connection.openService("shell:$command; echo \"$marker:\$?\"")
        } catch (e: AdbException) {
            throw StreamClosed(e.message)
        }
        val markerRegex = Regex("(?m)^" + Regex.escape(marker) + ":(-?\\d+)\\s*$")
        // Mantemos no máximo ~2 KB para detectar o marcador sem acumular tudo.
        val tail = StringBuilder()
        var exitCode: Int? = null

        try {
            connection.setTimeout(timeoutMs)
            while (true) {
                val payload = connection.readServicePayload(remoteId) ?: break
                if (payload.isEmpty()) continue
                tail.append(payload.toString(Charsets.UTF_8))
                val m = markerRegex.find(tail)
                if (m != null) {
                    val before = tail.substring(0, m.range.first)
                    if (before.isNotEmpty()) callbacks.onStdout(before)
                    exitCode = m.groupValues[1].toIntOrNull()
                    tail.setLength(0)
                    continue
                }
                // Emite o que já é seguro (deixa 64 chars de folga para o marcador).
                if (tail.length > 4096) {
                    val emitCount = tail.length - 128
                    callbacks.onStdout(tail.substring(0, emitCount))
                    tail.delete(0, emitCount)
                }
            }
            if (tail.isNotEmpty()) callbacks.onStdout(tail.toString())
            callbacks.onExit(exitCode)
        } catch (e: AdbTimeoutException) {
            callbacks.onExit(exitCode)
            throw StreamClosed(e.message ?: "Timeout.", timedOut = true)
        } catch (e: AdbConnectException) {
            callbacks.onExit(exitCode)
            throw StreamClosed(e.message ?: "Conexão perdida.")
        } catch (e: AdbException) {
            callbacks.onExit(exitCode)
            throw StreamClosed(e.message ?: "Falha no shell.")
        } finally {
            try {
                connection.closeService(remoteId)
            } catch (_: Exception) {
            }
        }
    }
}

/** IDs do shell protocol v2 (AOSP shell_protocol.h). */
object ShellProtocol {
    const val STDIN = 0
    const val STDOUT = 1
    const val STDERR = 2
    const val EXIT = 3
    const val CLOSE_STDIN = 4
    const val WINDOW_SIZE = 5

    /** 1 byte de ID + 4 bytes de tamanho. */
    const val HEADER_SIZE = 5
}
