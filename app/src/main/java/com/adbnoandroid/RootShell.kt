package com.adbnoandroid

import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** Resultado de uma execução de comando. */
data class ShellResult(
    val output: String,
    val exitCode: Int?,
    val usedRoot: Boolean,
    /** true = não deu para executar de jeito nenhum (ou o su falhou e não houve fallback). */
    val failed: Boolean,
    val timedOut: Boolean
)

/**
 * Motor de execução de comandos "no próprio aparelho".
 *
 * - **Com root:** abre o `su` (shell interativo) e "digita" o comando no stdin.
 *   Funciona em Magisk, KernelSU e SuperSU, e evita qualquer problema de
 *   aspas — o comando roda exatamente como se você tivesse digitado num
 *   `adb shell` de PC.
 * - **Sem root:** cai para o shell local do aparelho (`sh -c`). Muitos
 *   comandos continuam funcionando assim (getprop, pm list packages, df…).
 */
object RootShell {

    private const val TIMEOUT_SECONDS = 180L

    @Volatile
    var rootAvailable: Boolean? = null

    /**
     * Verifica se o dispositivo tem root.
     *
     * Se o gerenciador de root (Magisk/SuperSU) mostrar o diálogo de
     * permissão, será neste momento — a chamada espera até o usuário
     * responder (ou o tempo esgotar).
     */
    fun checkRoot(): Boolean {
        val r = run("id", viaRoot = true)
        val ok = r != null && !r.failed && !r.timedOut && r.usedRoot &&
            r.output.contains("uid=0")
        rootAvailable = ok
        return ok
    }

    /**
     * Executa um comando.
     *
     * @param command o comando, como você digitaria no `adb shell` de um PC.
     * @param preferRoot se houver root disponível, executa com ele.
     */
    fun execute(command: String, preferRoot: Boolean = true): ShellResult {
        val wantRoot = preferRoot && (rootAvailable ?: checkRoot())
        if (wantRoot) {
            val r = run(command, viaRoot = true)
            if (r != null && !r.failed) return r
            // O su não executou (permissão negada ou binário ausente):
            // trata como "sem root" e tenta pelo shell normal para o
            // comando ainda funcionar quando possível.
            rootAvailable = false
        }
        val sh = run(command, viaRoot = false)
        return sh ?: ShellResult(
            output = "Erro: não foi possível iniciar o shell para executar o comando.",
            exitCode = null,
            usedRoot = false,
            failed = true,
            timedOut = false
        )
    }

    /**
     * Logcat ao vivo **no próprio aparelho** (alvo local) — igual ao
     * `adb logcat` do PC, mas sem outro aparelho.
     *
     * - Com root: roda dentro de uma sessão `su` (o logcat fica em background
     *   na sessão e é matado limpo ao parar — sem processo órfão).
     * - Sem root: roda via `sh` — o Android limita a mostrar só os logs deste
     *   app (limitação do sistema, sem solução sem root).
     */
    fun startLogcatLocal(command: String, callbacks: LogcatCallbacks): LogSession {
        return if (rootAvailable == true) SuLogSession(command, callbacks)
        else ShLogSession(command, callbacks)
    }

    /**
     * Lê um arquivo deste aparelho em modo binário (para o "baixar arquivo"
     * com alvo local). Com root, usa `cat` via `su`; sem root, só funciona
     * em caminhos que o próprio app pode acessar.
     */
    fun readBytesLocal(path: String): ByteArray {
        if (rootAvailable == true) {
            val process = try {
                ProcessBuilder("su").start()
            } catch (e: IOException) {
                throw AdbException("Não foi possível iniciar o su: ${e.message}")
            }
            try {
                process.outputStream.use { os ->
                    os.write("cat \"$path\"\nexit\n".toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                val bytes = process.inputStream.readBytes()
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
                return bytes
            } catch (e: Exception) {
                throw AdbException("Falha ao ler $path: ${e.message}")
            } finally {
                try {
                    process.destroy()
                } catch (_: Exception) {
                }
            }
        }
        val file = File(path)
        if (!file.canRead()) {
            throw AdbException(
                "Sem root, o app não consegue ler $path. " +
                    "Rode com alvo local + root, ou use o alvo USB/Wi‑Fi (adb pull)."
            )
        }
        return file.readBytes()
    }

    /**
     * Roda um processo.
     *
     * @return null quando o binário não existe (ex.: sem `su` no aparelho).
     */
    private fun run(command: String, viaRoot: Boolean): ShellResult? {
        val argv = if (viaRoot) arrayOf("su") else arrayOf("sh", "-c", command)
        // Com su, alimentamos o comando pelo stdin e capturamos o código de
        // saída com um marcador (shell interativo não devolve o código direto).
        val stdinText = if (viaRoot) {
            "$command\necho ${Adb.EXIT_MARKER}:\$?\nexit\n"
        } else {
            null
        }

        val process = try {
            ProcessBuilder(*argv).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return null
        }

        // Lê a saída numa thread separada para evitar deadlock com buffer cheio.
        val output = StringBuilder()
        val reader = Thread {
            val ins = InputStreamReader(process.inputStream, Charsets.UTF_8)
            val buf = CharArray(8192)
            try {
                while (true) {
                    val n = ins.read(buf)
                    if (n == -1) break
                    output.append(buf, 0, n)
                }
            } catch (_: IOException) {
                // o processo morreu no meio do stream
            }
        }
        reader.start()

        if (stdinText != null) {
            try {
                process.outputStream.use { os ->
                    os.write(stdinText.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
            } catch (_: IOException) {
                // o su fechou o stdin cedo (permissão negada)
            }
        }

        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(3000)
            return ShellResult(output.toString(), null, viaRoot, false, true)
        }
        reader.join(5000)
        val raw = output.toString()

        if (viaRoot) {
            // O marcador pode colar no fim da saída do comando quando este
            // não termina com quebra de linha — por isso procuramos o token
            // em qualquer posição (usamos a ÚLTIMA ocorrência, que é a do
            // nosso echo, executado por último).
            val parsed = findExitMarker(raw)
            if (parsed == null) {
                // O su nunca executou nosso comando: permissão negada,
                // binário ausente ou su não interativo.
                val msg = when {
                    raw.isBlank() ->
                        "Permissão de ROOT negada (ou não concedida). " +
                            "Toque no botão no canto superior direito para verificar de novo."
                    else -> raw.trim()
                }
                return ShellResult(msg, null, false, true, false)
            }
            val (clean, exitCode) = parsed
            return ShellResult(clean, exitCode, true, false, false)
        }

        return ShellResult(raw, process.exitValue(), false, false, false)
    }
}

/**
 * Logcat local via `sh -c` (sem root). O `sh -c` com comando único executa o
 * logcat diretamente, então o `destroyForcibly` no [stop] mata o próprio
 * logcat — sem processo órfão.
 */
private class ShLogSession(
    command: String,
    private val callbacks: LogcatCallbacks
) : LogSession {
    @Volatile private var stopped = false
    private var process: Process? = null

    init {
        val p = try {
            ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            callbacks.onDone("Erro ao iniciar o logcat: ${e.message}")
            return
        }
        process = p
        startReader(p)
    }

    private fun startReader(p: Process) {
        val thread = Thread {
            var done = false
            fun finish(msg: String?) {
                if (!done) {
                    done = true
                    callbacks.onDone(msg)
                }
            }
            try {
                val ins = p.inputStream
                val buf = ByteArray(8192)
                while (!stopped) {
                    val n = ins.read(buf)
                    if (n == -1) break
                    if (n > 0) callbacks.onChunk(String(buf, 0, n, Charsets.UTF_8))
                }
                finish(null)
            } catch (e: Exception) {
                finish(if (stopped) null else (e.message ?: "Erro no logcat"))
            } finally {
                finish(null)
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    override fun stop() {
        stopped = true
        try {
            process?.destroyForcibly()
        } catch (_: Exception) {
        }
    }
}

/**
 * Logcat local via `su` (com root). O logcat roda em background dentro da
 * sessão interativa do su; ao parar, enviamos `kill $LOGPID` + `exit` —
 * encerramento limpo, sem logcat órfão.
 */
private class SuLogSession(
    command: String,
    private val callbacks: LogcatCallbacks
) : LogSession {
    @Volatile private var stopped = false
    private var process: Process? = null
    private var stdin: java.io.OutputStream? = null

    init {
        val p = try {
            ProcessBuilder("su").redirectErrorStream(true).start()
        } catch (e: Exception) {
            callbacks.onDone("Erro ao iniciar o su: ${e.message}")
            return
        }
        process = p
        val out = p.outputStream
        stdin = out
        try {
            // Rode o logcat em background e espere por ele — assim o stdout
            // flui para cá e, ao parar, matamos só o logcat (e saímos do su).
            out.write("$command & LOGPID=\$!; wait \$LOGPID\n".toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Exception) {
        }
        val thread = Thread {
            var done = false
            fun finish(msg: String?) {
                if (!done) {
                    done = true
                    callbacks.onDone(msg)
                }
            }
            var gotOutput = false
            try {
                val ins = p.inputStream
                val buf = ByteArray(8192)
                while (!stopped) {
                    val n = ins.read(buf)
                    if (n == -1) break
                    if (n > 0) {
                        gotOutput = true
                        callbacks.onChunk(String(buf, 0, n, Charsets.UTF_8))
                    }
                }
                if (!gotOutput && !stopped) {
                    // O su terminou sem rodar nada: permissão negada ou su ausente.
                    finish(
                        "Não foi possível iniciar o logcat com root " +
                            "(permissão negada ou su indisponível)."
                    )
                } else {
                    finish(null)
                }
            } catch (e: Exception) {
                finish(if (stopped) null else (e.message ?: "Erro no logcat"))
            } finally {
                finish(null)
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    override fun stop() {
        stopped = true
        try {
            stdin?.write("kill $LOGPID\nexit\n".toByteArray(Charsets.UTF_8))
            stdin?.flush()
        } catch (_: Exception) {
        }
        try {
            process?.destroyForcibly()
        } catch (_: Exception) {
        }
    }
}
