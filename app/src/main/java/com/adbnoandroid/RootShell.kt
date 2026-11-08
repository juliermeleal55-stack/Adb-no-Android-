package com.adbnoandroid

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

    private const val EXIT_MARKER = "__ADBNOANDROID_EXIT__"
    private const val TIMEOUT_SECONDS = 90L

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
     * Roda um processo.
     *
     * @return null quando o binário não existe (ex.: sem `su` no aparelho).
     */
    private fun run(command: String, viaRoot: Boolean): ShellResult? {
        val argv = if (viaRoot) arrayOf("su") else arrayOf("sh", "-c", command)
        // Com su, alimentamos o comando pelo stdin e capturamos o código de
        // saída com um marcador (shell interativo não devolve o código direto).
        val stdinText = if (viaRoot) {
            "$command\necho $EXIT_MARKER:$?\nexit\n"
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
            val markerRegex = Regex("${Regex.escape(EXIT_MARKER)}:(-?\\d+)")
            val lastMatch = markerRegex.findAll(raw).lastOrNull()
            if (lastMatch == null) {
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
            val exitCode = lastMatch.groupValues[1].toIntOrNull()
            val clean = raw.substring(0, lastMatch.range.first).trimEnd()
            return ShellResult(clean, exitCode, true, false, false)
        }

        return ShellResult(raw, process.exitValue(), false, false, false)
    }
}
