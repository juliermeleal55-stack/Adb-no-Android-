package com.adbnoandroid

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File

/** Status de um dispositivo na lista. */
enum class DeviceStatus {
    UNKNOWN,
    CHECKING,
    ONLINE,
    OFFLINE,
    NEEDS_AUTH,
    UNAUTHORIZED,
    RECOVERY,
    SIDELOAD,
    FASTBOOT,
    ERROR
}

/** Modo em que o aparelho está (define o que a UI pode oferecer). */
enum class DeviceMode {
    ADB,        // shell/sync/instalação disponíveis
    RECOVERY,   // recovery com adb (sideload, shell limitado)
    SIDELOAD,   // recovery aguardando "Apply update from ADB"
    FASTBOOT,   // bootloader
    OFFLINE,
    UNKNOWN;

    val label: String
        get() = when (this) {
            ADB -> "ADB"
            RECOVERY -> "Recovery"
            SIDELOAD -> "Sideload"
            FASTBOOT -> "Fastboot"
            OFFLINE -> "Offline"
            UNKNOWN -> "Desconhecido"
        }

    val supportsShell: Boolean get() = this == ADB || this == RECOVERY
    val supportsFiles: Boolean get() = this == ADB
    val supportsSideload: Boolean get() = this == SIDELOAD || this == RECOVERY
    val supportsFastboot: Boolean get() = this == FASTBOOT
}

/** Um dispositivo remoto alcançado por Wi‑Fi (ADB TCP). */
data class AdbDeviceInfo(
    val host: String,
    val port: Int = Adb.DEFAULT_PORT,
    val name: String = "",
    val status: DeviceStatus = DeviceStatus.UNKNOWN,
    val banner: String = "",
    val model: String = "",
    val android: String = "",
    val serial: String = "",
    val mode: DeviceMode = DeviceMode.UNKNOWN,
    val features: List<String> = emptyList(),
    val lastError: String = ""
) {
    fun address(): String = "$host:$port"

    fun label(): String = name.ifBlank { address() }

    /** Título para exibição (modelo quando conhecido, senão endereço). */
    fun title(): String = model.ifBlank { label() }
}

/** Sessão de stream (logcat, shell contínuo) em execução. */
interface StreamSession {
    fun stop()
}

/** Callbacks de um stream de texto. */
class StreamCallbacks(
    val onChunk: (String) -> Unit,
    val onError: (String) -> Unit = {},
    val onDone: (String?) -> Unit = {}
)

/** Um alvo concreto para operações (USB ou rede). */
sealed class DeviceTarget {
    data class Usb(val info: AdbUsb.UsbDeviceInfo) : DeviceTarget() {
        override fun label(): String = "USB · " + info.label()
    }

    data class Net(val info: AdbDeviceInfo) : DeviceTarget() {
        override fun label(): String = "Wi-Fi · " + info.label()
    }

    data class Fastboot(val info: FastbootDeviceInfo) : DeviceTarget() {
        override fun label(): String = "Fastboot · " + info.label()
    }

    abstract fun label(): String

    fun key(): String = when (this) {
        is Usb -> "usb:" + info.deviceName
        is Net -> "net:" + info.address()
        is Fastboot -> "fb:" + info.deviceName
    }
}

/**
 * Camada de alto nível — a "platform-tools" dentro do app.
 *
 * - Abre conexões ADB por **USB (OTG)** ou **Wi‑Fi**.
 * - Executa shell (v2 com streaming), push/pull, ls, install, reboot, root,
 *   remount, tcpip, sideload, logcat, bugreport, dumpsys…
 * - Sessões fastboot (getvar, flash, erase, boot, reboot).
 *
 * Todas as funções são **bloqueantes**: chame em Dispatchers.IO.
 */
object AdbClient {

    private var auth: AdbAuth? = null
    private var appContext: Context? = null

    /** Histórico de operações para a aba "Histórico". */
    val history = java.util.Collections.synchronizedList(ArrayList<OpRecord>())

    fun init(context: Context) {
        if (auth == null) {
            appContext = context.applicationContext
            auth = AdbAuth(File(context.filesDir, "adb/adbkey.pem"))
        }
    }

    fun appContext(): Context = appContext
        ?: throw AdbException("App não inicializado.")

    internal fun authOrNull(): AdbAuth? = auth

    /** Registra uma operação no histórico (usado pelas telas). */
    fun record(op: OpRecord) {
        history.add(0, op)
        while (history.size > 300) history.removeAt(history.size - 1)
    }

    // ------------------------------------------------------------ conexões

    /**
     * Abre uma conexão ADB para o alvo ([DeviceTarget.Usb] ou [DeviceTarget.Net]).
     * A conexão volta já conectada (banner/auth feitos).
     */
    /**
     * Um lock por dispositivo.
     *
     * A interface ADB é exclusiva: se duas operações abrirem o mesmo aparelho
     * ao mesmo tempo (probe + transferência), o Android recusa o segundo
     * `claimInterface` com "device busy". Serializar por aparelho faz a
     * segunda operação ESPERAR em vez de falhar.
     *
     * É um [java.util.concurrent.Semaphore] e não `synchronized` porque
     * operações longas (logcat, sideload) seguram o aparelho por minutos — com
     * um lock comum, um `refreshUsb()` casual ficaria pendurado esperando.
     */
    /** Quanto esperar pelo aparelho livre antes de desistir (ms). */
    private const val DEFAULT_LOCK_WAIT_MS = 20_000L

    private val deviceLocks =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Semaphore>()

    private fun lockFor(key: String): java.util.concurrent.Semaphore =
        deviceLocks.getOrPut(key) { java.util.concurrent.Semaphore(1, true) }

    private fun deviceKey(target: DeviceTarget): String = when (target) {
        is DeviceTarget.Usb -> "usb:${target.info.deviceName}"
        is DeviceTarget.Net -> "net:${target.info.address()}"
        is DeviceTarget.Fastboot -> "fb:${target.info.deviceName}"
    }

    /**
     * Reserva o aparelho e devolve a conexão com o lock ATRELADO à vida dela.
     *
     * Use quando a conexão fica aberta depois que este método retorna (logcat,
     * sideload, shell interativo). O lock só é liberado em [close] — sem isso
     * outra operação tentaria `claimInterface` no meio de uma transferência.
     */
    private fun connectHeld(target: DeviceTarget): AdbConnection {
        val lock = lockFor(deviceKey(target))
        if (!lock.tryAcquire(DEFAULT_LOCK_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            throw AdbConnectException(
                "O aparelho está ocupado com outra operação. Tente de novo em instantes."
            )
        }
        return try {
            connect(target).also { it.heldLock = lock }
        } catch (e: Exception) {
            lock.release()
            throw e
        }
    }

    /**
     * Executa [block] com o aparelho reservado. Espera no máximo [waitMs] —
     * se o aparelho estiver ocupado por uma operação longa, devolve uma
     * mensagem clara em vez de travar a interface.
     */
    private fun <T> withDeviceLock(
        target: DeviceTarget,
        waitMs: Long = DEFAULT_LOCK_WAIT_MS,
        busy: String = "O aparelho está ocupado com outra operação. Tente de novo em instantes.",
        block: () -> T
    ): T {
        val lock = lockFor(deviceKey(target))
        if (!lock.tryAcquire(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            throw AdbConnectException(busy)
        }
        try {
            return block()
        } finally {
            lock.release()
        }
    }

    fun connect(target: DeviceTarget): AdbConnection {
        val ctx = appContext()
        return when (target) {
            is DeviceTarget.Usb -> {
                val device = AdbUsb.deviceByName(ctx, target.info.deviceName)
                    ?: throw AdbConnectException(
                        "O aparelho USB foi desconectado. Reconecte o cabo e atualize a lista."
                    )
                AdbUsb.open(ctx, device, auth)
            }
            is DeviceTarget.Net -> AdbConnection(
                TcpTransport(target.info.host, target.info.port), auth
            ).also { it.connect() }
            is DeviceTarget.Fastboot -> throw AdbException(
                "Este alvo está em modo fastboot. Use as ferramentas Fastboot."
            )
        }
    }

    /** Abre uma conexão e executa [block], fechando no fim. */
    private fun <T> using(target: DeviceTarget, block: (AdbConnection) -> T): T =
        withDeviceLock(target) {
            val connection = connect(target)
            try {
                block(connection)
            } finally {
                try {
                    connection.close()
                } catch (_: Exception) {
                }
            }
        }

    // -------------------------------------------------------------- shell

    /** `adb shell <cmd>` com saída completa. */
    fun shell(
        target: DeviceTarget,
        command: String,
        timeoutMs: Int = 120_000
    ): ShellResult = try {
        using(target) { conn -> AdbShell.exec(conn, command, timeoutMs) }
    } catch (e: AdbException) {
        ShellResult(e.message ?: "Falha de conexão.", null, failed = true)
    }

    /**
     * Executa com saída ao vivo ([callbacks] recebem cada bloco).
     * **Bloqueante** até o comando terminar; devolve o resultado final.
     */
    fun shellStream(
        target: DeviceTarget,
        command: String,
        timeoutMs: Int,
        callbacks: ShellStreamCallbacks,
        stdin: StdSource? = null
    ): ShellResult {
        val collected = StringBuilder()
        val err = StringBuilder()
        var exit: Int? = null
        val hook = ShellStreamCallbacks(
            onStdout = { chunk ->
                collected.append(chunk)
                callbacks.onStdout(chunk)
            },
            onStderr = { chunk ->
                err.append(chunk)
                callbacks.onStderr(chunk)
            },
            onExit = { code ->
                exit = code
                callbacks.onExit(code)
            }
        )
        val started = System.currentTimeMillis()
        return try {
            val connection = withDeviceLock(target) { connect(target) }
            try {
                AdbShell.run(connection, command, timeoutMs, stdin, hook)
                ShellResult(
                    output = collected.toString().trimEnd(),
                    exitCode = exit,
                    stderr = err.toString().trimEnd(),
                    durationMs = System.currentTimeMillis() - started,
                    protocol = if (connection.supportsShellV2) "shell_v2" else "shell"
                )
            } finally {
                connection.close()
            }
        } catch (e: StreamClosed) {
            val message = e.message
            if (message != null && collected.isEmpty()) callbacks.onError(message)
            ShellResult(
                output = collected.toString().trimEnd(),
                exitCode = exit,
                stderr = listOf(err.toString().trimEnd(), message.orEmpty())
                    .filter { it.isNotBlank() }.joinToString("\n"),
                failed = message != null,
                timedOut = e.timedOut,
                durationMs = System.currentTimeMillis() - started
            )
        } catch (e: Exception) {
            callbacks.onError(e.message ?: "Falha de conexão.")
            ShellResult(
                output = collected.toString(), exitCode = null,
                failed = true, durationMs = System.currentTimeMillis() - started
            )
        }
    }

    // -------------------------------------------------------- dispositivos

    /**
     * Consulta o aparelho e devolve tudo o que a UI precisa: modelo, versão do
     * Android, serial, modo, features e estado de autorização.
     */
    fun probe(target: DeviceTarget): DeviceSnapshot {
        return try {
            using(target) { conn ->
                val info = AdbConnection.parseBanner(conn.banner ?: "")
                val mode = modeOf(info.state)
                val props = readProps(conn)
                val serial = props["ro.serialno"].orEmpty()
                    .ifBlank { info.serial }
                    .ifBlank {
                        when (target) {
                            is DeviceTarget.Usb -> target.info.serial
                            else -> ""
                        }
                    }
                DeviceSnapshot(
                    target = target,
                    online = true,
                    authorized = true,
                    mode = mode,
                    state = info.state,
                    model = props["ro.product.model"] ?: info.props["ro.product.model"].orEmpty(),
                    manufacturer = props["ro.product.manufacturer"].orEmpty(),
                    android = props["ro.build.version.release"].orEmpty(),
                    sdk = props["ro.build.version.sdk"].orEmpty().toIntOrNull() ?: 0,
                    build = props["ro.build.display.id"].orEmpty(),
                    serial = serial,
                    features = conn.deviceFeatures,
                    protocol = if (conn.supportsShellV2) "shell_v2" else "shell",
                    maxPayload = conn.maxPayload,
                    state2 = info.state
                )
            }
        } catch (e: AdbAuthException) {
            DeviceSnapshot(
                target = target, online = false, authorized = false,
                mode = DeviceMode.UNKNOWN, state = "", model = "", manufacturer = "",
                android = "", sdk = 0, build = "", serial = "",
                error = e.message ?: "Autorização pendente"
            )
        } catch (e: AdbException) {
            DeviceSnapshot(
                target = target, online = false, authorized = true,
                mode = DeviceMode.UNKNOWN, state = "", model = "", manufacturer = "",
                android = "", sdk = 0, build = "", serial = "",
                error = e.message ?: "Sem conexão"
            )
        }
    }

    private fun readProps(connection: AdbConnection): Map<String, String> {
        val keys = listOf(
            "ro.product.model", "ro.product.manufacturer", "ro.product.brand",
            "ro.product.device", "ro.product.name", "ro.build.version.release",
            "ro.build.version.sdk", "ro.build.display.id", "ro.serialno",
            "ro.build.type", "ro.build.tags", "ro.boot.verifiedbootstate",
            "ro.boot.slot_suffix", "ro.product.cpu.abi", "ro.build.characteristics"
        )
        val result = LinkedHashMap<String, String>()
        // Um único round-trip: `getprop` filtrado por essas chaves.
        val filter = keys.joinToString("|") { Regex.escape(it) }
        val res = AdbShell.exec(
            connection, "getprop | grep -E '^\\[($filter)\\]'", timeoutMs = 20_000
        )
        if (res.failed) {
            // Fallback: getprop inteiro (recovery às vezes não tem grep).
            val all = AdbShell.exec(connection, "getprop", timeoutMs = 20_000)
            parseGetprop(all.output).forEach { (k, v) -> if (keys.contains(k)) result[k] = v }
            return result
        }
        parseGetprop(res.output).forEach { (k, v) -> result[k] = v }
        if (result.isEmpty()) {
            val all = AdbShell.exec(connection, "getprop", timeoutMs = 20_000)
            parseGetprop(all.output).forEach { (k, v) -> if (keys.contains(k)) result[k] = v }
        }
        return result
    }

    /** Interpreta a saída de `getprop` (`[chave]: [valor]`). */
    fun parseGetprop(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val regex = Regex("^\\[([^\\]]+)\\]: \\[(.*)\\]\\s*$")
        text.lines().forEach { line ->
            val m = regex.find(line.trim()) ?: return@forEach
            map[m.groupValues[1]] = m.groupValues[2]
        }
        return map
    }

    fun modeOf(state: String): DeviceMode = when (state.trim().lowercase()) {
        "device" -> DeviceMode.ADB
        "recovery" -> DeviceMode.RECOVERY
        "rescue" -> DeviceMode.RECOVERY
        "sideload" -> DeviceMode.SIDELOAD
        "bootloader", "fastboot" -> DeviceMode.FASTBOOT
        "offline", "connecting" -> DeviceMode.OFFLINE
        else -> DeviceMode.UNKNOWN
    }

    // ------------------------------------------------------------ arquivos

    /** `adb ls` no aparelho (explorer). */
    fun listDir(target: DeviceTarget, path: String): List<RemoteEntry> =
        using(target) { conn -> AdbFiles.listEntries(conn, path) }

    fun statPath(target: DeviceTarget, path: String): RemoteStat =
        using(target) { conn -> AdbFiles.stat(conn, path) }

    /** `adb pull` de um arquivo. */
    fun pull(
        target: DeviceTarget,
        remotePath: String,
        dest: File,
        onProgress: (Long, Long?) -> Unit = { _, _ -> }
    ) {
        using(target) { conn ->
            AdbFiles.pull(conn, remotePath, dest, onProgress)
        }
    }

    /** `adb pull` de uma pasta inteira (tar + extração local). */
    fun pullDir(
        target: DeviceTarget,
        remoteDir: String,
        destDir: File,
        onProgress: (String, Long) -> Unit = { _, _ -> }
    ) {
        using(target) { conn ->
            AdbFiles.pullDirectory(conn, remoteDir, destDir, onProgress)
        }
    }

    /** `adb push` de um arquivo local. */
    fun push(
        target: DeviceTarget,
        local: File,
        remotePath: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        using(target) { conn ->
            AdbFiles.push(conn, local, remotePath, SyncCommands.MODE_FILE, onProgress)
        }
    }

    /** Cria diretório no aparelho (SEND com modo de diretório). */
    fun mkdir(target: DeviceTarget, remotePath: String) {
        using(target) { conn ->
            val remoteId = conn.openService("sync:")
            val ch = SyncChannel(conn, remoteId)
            try {
                // SEND com modo de diretório: o adbd cria criando o diretório.
                ch.request(SyncCommands.SEND_V1, "$remotePath,${SyncCommands.MODE_DIR}")
                when (val frame = ch.readFrame()) {
                    is SyncFrame.Okay -> {
                    }
                    is SyncFrame.Fail -> throw AdbServiceException(
                        "Não foi possível criar a pasta: ${frame.message}"
                    )
                    else -> throw AdbServiceException(
                        "Resposta inesperada ao criar a pasta (${frame.javaClass.simpleName})."
                    )
                }
                ch.request(SyncCommands.DONE, 0)
                when (val done = ch.readFrame()) {
                    is SyncFrame.Okay -> {
                    }
                    is SyncFrame.Fail -> throw AdbServiceException(
                        "Não foi possível criar a pasta: ${done.message}"
                    )
                    else -> {
                        // Alguns adbd não respondem ao DONE de diretório; aceita.
                    }
                }
            } finally {
                try {
                    ch.quit()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Remove arquivo/pasta no aparelho (rm -rf via shell, respeitando permissões). */
    fun remove(target: DeviceTarget, remotePath: String): ShellResult =
        shell(target, "rm -rf " + shellEscape(remotePath), timeoutMs = 60_000)

    /** Renomeia/move no aparelho. */
    fun move(target: DeviceTarget, from: String, to: String): ShellResult =
        shell(
            target,
            "mv " + shellEscape(from) + " " + shellEscape(to),
            timeoutMs = 60_000
        )

    /** Copia no aparelho. */
    fun copy(target: DeviceTarget, from: String, to: String): ShellResult =
        shell(
            target,
            "cp -r " + shellEscape(from) + " " + shellEscape(to),
            timeoutMs = 120_000
        )

    // ------------------------------------------------------------- pacotes

    /**
     * `adb install` — igual ao platform-tools:
     *
     * 1. Tenta **instalação por streaming** (`cmd package install -S <tamanho>`
     *    no serviço `exec:`), enviando o APK pelo stdin. Nada é copiado no
     *    aparelho e funciona também para APKs grandes.
     * 2. Se o aparelho não aceitar (Android antigo, `exec:` bloqueado, pm sem
     *    streaming), cai para o modo clássico: `push` para /data/local/tmp e
     *    `pm install` por lá.
     */
    fun install(
        target: DeviceTarget,
        apk: File,
        reinstall: Boolean = true,
        grantPermissions: Boolean = false,
        downgrade: Boolean = false,
        onProgress: (String, Float) -> Unit = { _, _ -> }
    ): ShellResult {
        if (!apk.exists()) {
            return ShellResult("APK não encontrado: ${apk.absolutePath}", null, failed = true)
        }
        if (!apk.name.lowercase().endsWith(".apk") && !apk.name.lowercase().endsWith(".apks")) {
            return ShellResult(
                "O arquivo \"${apk.name}\" não é um APK. Selecione um arquivo .apk.",
                null, failed = true
            )
        }
        val size = apk.length()
        onProgress("Validando o APK…", 0f)
        val valid = validateApk(apk)
        if (!valid.first) {
            return ShellResult(
                "APK inválido: ${valid.second}. A instalação foi cancelada para não " +
                    "gravar um pacote corrompido no aparelho.",
                null, failed = true
            )
        }

        val flags = buildString {
            if (reinstall) append(" -r")
            if (grantPermissions) append(" -g")
            if (downgrade) append(" -d")
        }

        onProgress("Conectando…", 0.01f)
        val connection = try {
            withDeviceLock(target) { connect(target) }
        } catch (e: AdbException) {
            return ShellResult(e.message ?: "Sem conexão.", null, failed = true)
        }
        try {
            // 1) streaming install
            onProgress("Instalando por streaming…", 0.02f)
            var sent = 0L
            val sink = StdSource(
                size = size,
                reader = run {
                    val input = apk.inputStream().buffered(128 * 1024)
                    var closed = false
                    {
                        if (closed) null else {
                            val buf = ByteArray(64 * 1024)
                            val n = input.read(buf)
                            if (n <= 0) {
                                closed = true
                                try {
                                    input.close()
                                } catch (_: Exception) {
                                }
                                null
                            } else {
                                buf.copyOf(n)
                            }
                        }
                    }
                },
                onProgress = { uploaded ->
                    sent = uploaded
                    onProgress(
                        "Enviando o APK… ${percent(uploaded, size)}%",
                        0.02f + 0.9f * (uploaded.toFloat() / maxOf(1L, size))
                    )
                }
            )
            val out = StringBuilder()
            val errText = StringBuilder()
            val streaming = try {
                AdbShell.run(
                    connection,
                    "cmd package install$flags -S $size",
                    timeoutMs = 900_000,
                    stdin = sink,
                    callbacks = ShellStreamCallbacks(
                        onStdout = { out.append(it) },
                        onStderr = { errText.append(it) }
                    )
                )
                ShellResult(output = out.toString(), exitCode = 0, stderr = errText.toString())
            } catch (e: StreamClosed) {
                ShellResult(
                    out.toString(), null,
                    stderr = errText.toString() + "\n" + (e.message ?: ""),
                    failed = true
                )
            } catch (e: Exception) {
                ShellResult(e.message ?: "falha", null, failed = true)
            }

            val output = streaming.combined()
            val success = output.contains("Success", ignoreCase = true)
            if (success) {
                onProgress("Instalado.", 1f)
                return ShellResult(output.trim(), 0)
            }

            val unreachable = streaming.failed ||
                output.contains("Unknown option") ||
                output.contains("not found") ||
                output.contains("inaccessible") ||
                output.isBlank()
            if (!unreachable) {
                // O aparelho respondeu e recusou o pacote: mostra o erro real.
                onProgress("Falhou.", 1f)
                return ShellResult(output.trim(), 1, failed = true)
            }

            // 2) modo clássico: push + pm install
            onProgress("Método alternativo (push + pm install)…", 0.05f)
            val remote = "/data/local/tmp/adbna_install_${System.currentTimeMillis()}.apk"
            AdbFiles.push(connection, apk, remote, SyncCommands.MODE_FILE) { done, total ->
                onProgress(
                    "Copiando o APK… ${percent(done, total)}%",
                    0.05f + 0.85f * (done.toFloat() / maxOf(1L, total))
                )
            }
            onProgress("Instalando…", 0.92f)
            val pm = AdbShell.exec(
                connection,
                "pm install$flags -S $size \"$remote\"",
                timeoutMs = 600_000
            )
            var pmOut = pm.combined()
            if (!pmOut.contains("Success", ignoreCase = true)) {
                // Android moderno não aceita -S em `pm install`; tenta com o
                // caminho do arquivo e o próprio pacote já copiado.
                val pm2 = AdbShell.exec(
                    connection,
                    "pm install$flags \"$remote\"",
                    timeoutMs = 600_000
                )
                if (pm2.combined().contains("Success", ignoreCase = true)) {
                    pmOut = pm2.combined()
                } else {
                    pmOut = pmOut.trim() + "\n" + pm2.combined().trim()
                }
            }
            AdbShell.exec(connection, "rm -f \"$remote\"", timeoutMs = 30_000)
            val ok = pmOut.contains("Success", ignoreCase = true)
            onProgress(if (ok) "Instalado." else "Falhou.", 1f)
            return ShellResult(
                pmOut.trim(),
                if (ok) 0 else 1,
                failed = !ok
            )
        } catch (e: AdbException) {
            return ShellResult(e.message ?: "Falha na instalação.", null, failed = true)
        } finally {
            try {
                connection.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Valida o APK antes de instalar: assinatura ZIP ("PK\u0003\u0004") e
     * existência de `AndroidManifest.xml` — evita enviar lixo ao aparelho.
     */
    fun validateApk(file: File): Pair<Boolean, String> {
        if (file.length() < 1024) return false to "arquivo muito pequeno"
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                if (zip.getEntry("AndroidManifest.xml") == null) {
                    return false to "falta o AndroidManifest.xml (não é um APK)"
                }
                true to ""
            }
        } catch (e: Exception) {
            false to "o ZIP está corrompido (${e.message ?: "formato inválido"})"
        }
    }

    /**
     * `adb uninstall`. [keepData] equivale ao `-k` do pc.
     */
    fun uninstall(target: DeviceTarget, packageName: String, keepData: Boolean = false): ShellResult {
        val cmd = "pm uninstall" + (if (keepData) " -k" else "") + " " + shellEscape(packageName)
        return shell(target, cmd, timeoutMs = 120_000)
    }

    /** Lista pacotes: [onlyThirdParty] equivale a `-3`. */
    fun listPackages(target: DeviceTarget, onlyThirdParty: Boolean = false): ShellResult =
        shell(target, "pm list packages" + (if (onlyThirdParty) " -3" else ""), timeoutMs = 60_000)

    /** Informações detalhadas de um pacote. */
    fun packageInfo(target: DeviceTarget, packageName: String): ShellResult =
        shell(target, "dumpsys package " + shellEscape(packageName), timeoutMs = 60_000)

    // ------------------------------------------------------ ações do sistema

    /** `adb reboot [modo]`. */
    fun reboot(target: DeviceTarget, mode: String = ""): ShellResult {
        val cmd = if (mode.isBlank()) "reboot" else "reboot $mode"
        return shell(target, cmd, timeoutMs = 30_000)
    }

    /**
     * Backup de um app via `bmgr` (o caminho que funciona hoje em dia).
     *
     * O `adb backup` clássico foi descontinuado: no Android 12+ ele é recusado
     * por padrão (`ro.adb.backup`/`backup_disabled`) e mesmo antes disso
     * dependia do app aceitar backup. Em vez de fingir sucesso, usamos o
     * Backup Manager do próprio aparelho e expomos a saída real.
     */
    fun backupApp(target: DeviceTarget, packageName: String): ShellResult {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) {
            return ShellResult("Informe o pacote (ex.: com.whatsapp).", null, failed = true)
        }
        if (!pkg.matches(Regex("[A-Za-z0-9_.]+"))) {
            return ShellResult(
                "Nome de pacote inválido: \"$pkg\". Use só letras, números, ponto e _.",
                null, failed = true
            )
        }
        return shell(
            target,
            "bmgr backupnow $pkg; echo; dumpsys backup | grep -A3 '$pkg'",
            timeoutMs = 300_000
        )
    }

    /**
     * `adb backup -f <arquivo>` (aparelhos antigos).
     *
     * Só funciona até o Android 11 e com o app permitindo backup; devolvemos a
     * saída real do aparelho para o usuário ver o motivo quando falha.
     */
    fun backup(target: DeviceTarget, packageName: String, destFile: File): ShellResult {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) {
            return ShellResult("Informe o pacote.", null, failed = true)
        }
        if (!pkg.matches(Regex("[A-Za-z0-9_.]+"))) {
            return ShellResult("Nome de pacote inválido: \"$pkg\".", null, failed = true)
        }
        val remote = "/data/local/tmp/adbna_backup_${System.currentTimeMillis()}.ab"
        val gen = shell(
            target,
            "bu backup -f $remote $pkg 2>&1 || backup -f $remote $pkg 2>&1",
            timeoutMs = 600_000
        )
        val stat = try {
            statPath(target, remote)
        } catch (_: Exception) {
            null
        }
        if (stat == null || !stat.exists || stat.size <= 0) {
            return ShellResult(
                "O aparelho não gerou o backup. " + gen.combined().ifBlank {
                    "No Android 12+ o backup por ADB foi desativado."
                },
                null, failed = true
            )
        }
        try {
            destFile.parentFile?.mkdirs()
            pull(target, remote, destFile)
        } finally {
            try {
                using(target) { conn ->
                    AdbShell.exec(conn, "rm -f $remote", timeoutMs = 20_000)
                }
            } catch (_: Exception) {
            }
        }
        return ShellResult(
            "Backup salvo em ${destFile.absolutePath} (${destFile.length()} bytes).",
            0
        )
    }

    /**
     * `adb reconnect` — derruba e refaz a conexão com este aparelho.
     *
     * O `adb kill-server`/`start-server` do PC não existe no app: o "servidor"
     * é o próprio processo do app e o adbd do aparelho, então reiniciá-lo não
     * faria nada. O efeito útil equivalente é forçar uma conexão nova, que é o
     * que fazemos aqui.
     */
    fun reconnect(target: DeviceTarget): ShellResult = using(target) { conn ->
        val remoteId = try {
            conn.openService("reconnect:")
        } catch (e: AdbException) {
            return@using ShellResult(
                "O aparelho não aceitou reconectar: ${e.message}",
                null, failed = true
            )
        }
        // O serviço TCP do adbd que atende 'reconnect:' quebra a conexão de
        // propósito — o timeout/CLSE aqui é o comportamento esperado, não erro.
        val out = try {
            conn.readUntilClose(remoteId).trim()
        } catch (_: Exception) {
            ""
        }
        ShellResult(out.ifBlank { "Conexão reiniciada." }, 0)
    }

    /**
     * `adb root` — reinicia o adbd como root (só builds userdebug/eng).
     * Devolve a mensagem real do aparelho (não inventamos sucesso).
     */
    fun root(target: DeviceTarget): ShellResult = using(target) { conn ->
        val remoteId = try {
            conn.openService("root:")
        } catch (e: AdbException) {
            return@using ShellResult(e.message ?: "Falha", null, failed = true)
        }
        val text = conn.readUntilClose(remoteId)
        val lower = text.lowercase()
        val ok = lower.contains("restarting adbd as root") ||
            lower.contains("adbd is already running as root") ||
            lower.contains("restarting in")
        ShellResult(text.trim(), if (ok) 0 else 1, failed = !ok)
    }

    /** `adb unroot`. */
    fun unroot(target: DeviceTarget): ShellResult = using(target) { conn ->
        val remoteId = conn.openService("unroot:")
        ShellResult(conn.readUntilClose(remoteId).trim(), 0)
    }

    /** `adb remount` (precisa de root; AVB/verity pode bloquear). */
    fun remount(target: DeviceTarget): ShellResult = using(target) { conn ->
        val remoteId = try {
            conn.openService("remount:")
        } catch (e: AdbException) {
            return@using ShellResult(e.message ?: "Falha", null, failed = true)
        }
        val text = conn.readUntilClose(remoteId)
        val lower = text.lowercase()
        val ok = lower.contains("remount succeeded") || lower.contains("succeeded")
        ShellResult(text.trim(), if (ok) 0 else 1, failed = !ok)
    }

    /** `adb tcpip <porta>` — liga o adbd em TCP. */
    fun tcpip(target: DeviceTarget, port: Int = 5555): ShellResult = using(target) { conn ->
        val remoteId = try {
            conn.openService("tcpip:$port")
        } catch (e: AdbException) {
            return@using ShellResult(e.message ?: "Falha", null, failed = true)
        }
        val text = conn.readUntilClose(remoteId)
        ShellResult(text.trim(), if (text.lowercase().contains("restarting")) 0 else 1)
    }

    /** `adb usb` — volta o adbd para USB. */
    fun usbMode(target: DeviceTarget): ShellResult = using(target) { conn ->
        val remoteId = conn.openService("usb:")
        ShellResult(conn.readUntilClose(remoteId).trim(), 0)
    }

    /**
     * IP do aparelho na rede Wi‑Fi (para o "adb connect").
     */
    fun wifiIp(target: DeviceTarget): String? {
        val res = shell(
            target,
            "ip -f inet addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*' | head -1",
            timeoutMs = 20_000
        )
        val f = Regex("(\\d{1,3}(\\.\\d{1,3}){3})").find(res.output) ?: return null
        return f.value
    }

    // --------------------------------------------------------------- logs

    /**
     * Logcat ao vivo por streaming (shell v2). Retorna uma sessão com stop().
     * [callbacks.onChunk] é chamado em thread de fundo.
     */
    fun logcat(
        target: DeviceTarget,
        args: String,
        callbacks: StreamCallbacks
    ): StreamSession {
        val holder = StreamHolder()
        val thread = Thread {
            var connection: AdbConnection? = null
            var remoteId = 0
            try {
                connection = connectHeld(target)
                holder.connection = connection
                val v2 = connection.supportsShellV2
                connection.setTimeout(0) // sem timeout: logcat pode ficar em silêncio
                remoteId = connection.openService(
                    if (v2) "shell,v2,raw:logcat $args" else "shell:logcat $args"
                )
                // Buffer de frames do shell v2 (um WRTE pode trazer pedaços).
                val buf = ByteArrayOutputStream()
                while (!holder.stopped) {
                    val payload = connection.readServicePayload(remoteId) ?: break
                    if (payload.isEmpty()) continue
                    if (!v2) {
                        // v1: os bytes são o texto puro do logcat.
                        callbacks.onChunk(payload.toString(Charsets.UTF_8))
                        continue
                    }
                    buf.write(payload, 0, payload.size)
                    val bytes = buf.toByteArray()
                    var off = 0
                    while (off + ShellProtocol.HEADER_SIZE <= bytes.size) {
                        val id = bytes[off].toInt() and 0xFF
                        val len = java.nio.ByteBuffer.wrap(bytes, off + 1, 4)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                        if (len < 0 || off + ShellProtocol.HEADER_SIZE + len > bytes.size) break
                        val start = off + ShellProtocol.HEADER_SIZE
                        if (id == ShellProtocol.STDOUT && len > 0) {
                            callbacks.onChunk(String(bytes, start, len, Charsets.UTF_8))
                        } else if (id == ShellProtocol.STDERR && len > 0) {
                            callbacks.onChunk(String(bytes, start, len, Charsets.UTF_8))
                        }
                        off = start + len
                    }
                    if (off > 0) {
                        buf.reset()
                        if (off < bytes.size) buf.write(bytes, off, bytes.size - off)
                    }
                }
                callbacks.onDone(if (holder.stopped) null else "O aparelho encerrou o logcat.")
            } catch (e: Exception) {
                callbacks.onDone(
                    if (holder.stopped) null else (e.message ?: e.javaClass.simpleName)
                )
            } finally {
                try {
                    connection?.close()
                } catch (_: Exception) {
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        holder.thread = thread
        return holder
    }

    /** Sessão que pode ser parada (fecha a conexão). */
    class StreamHolder : StreamSession {
        @Volatile
        var stopped = false

        @Volatile
        var connection: AdbConnection? = null

        @Volatile
        var thread: Thread? = null

        fun closeConnection() {
            try {
                connection?.close()
            } catch (_: Exception) {
            }
            connection = null
        }

        override fun stop() {
            stopped = true
            closeConnection()
            try {
                thread?.interrupt()
            } catch (_: Exception) {
            }
        }
    }

    /** Captura um dump de logcat (`logcat -d`). */
    fun logcatDump(target: DeviceTarget, args: String = "-b all -d -v threadtime"): ShellResult =
        shell(target, "logcat $args", timeoutMs = 180_000)

    /** `dumpsys <serviço>`. */
    fun dumpsys(target: DeviceTarget, service: String = ""): ShellResult {
        val cmd = if (service.isBlank()) "dumpsys" else "dumpsys $service"
        return shell(target, cmd, timeoutMs = 240_000)
    }

    /** `cat /proc/kmsg` (precisa de root; devolve o erro real quando não dá). */
    fun kmsg(target: DeviceTarget, lines: Int = 300): ShellResult =
        shell(target, "su -c 'dmesg | tail -n $lines' 2>&1 || dmesg | tail -n $lines", 60_000)

    /** `dmesg` (info de boot/kernel). */
    fun dmesg(target: DeviceTarget, lines: Int = 500): ShellResult =
        shell(target, "dmesg | tail -n $lines", 60_000)

    /**
     * Gera o bugreport como ZIP no aparelho e baixa para [destDir].
     * (É um comando longo: o progresso é aproximado por etapa.)
     */
    fun bugreport(
        target: DeviceTarget,
        destDir: File,
        onProgress: (String, Float) -> Unit
    ): File {
        onProgress("Gerando o bugreport no aparelho…", 0.05f)
        // Em Android 7+ o bugreport vai para o diretório de bugreports.
        val remote = "/data/local/tmp/adbna_bugreport.zip"
        val cmd = buildString {
            append("(bugreportz -p ")
            append(remote)
            append(" || bugreport ")
            append("/data/local/tmp/bugreport.txt)")
        }
        val res = shell(target, cmd, timeoutMs = 1_800_000)
        val text = res.combined()
        val pathMatch = Regex("(/[^\\s]+\\.zip)").find(text)
        val remotePath = pathMatch?.groupValues?.get(1) ?: remote
        onProgress("Baixando o bugreport…", 0.6f)
        val stat = try {
            statPath(target, remotePath)
        } catch (e: Exception) {
            null
        }
        if (stat == null || !stat.exists) {
            throw AdbServiceException(
                "O bugreport não foi gerado no aparelho. Saída: " +
                    text.lines().takeLast(20).joinToString("\n")
            )
        }
        destDir.mkdirs()
        val dest = File(destDir, "bugreport_${System.currentTimeMillis()}.zip")
        pull(target, remotePath, dest) { done, total ->
            val frac = if (total != null && total > 0) done.toFloat() / total else 0f
            onProgress("Baixando o bugreport… ${(frac * 100).toInt()}%", 0.6f + 0.4f * frac)
        }
        shell(target, "rm -f \"$remotePath\"", 30_000)
        onProgress("Bugreport salvo.", 1f)
        return dest
    }

    // ------------------------------------------------------------ sideload

    /** `adb sideload <zip>` no alvo (USB ou rede). */
    fun sideload(
        target: DeviceTarget,
        file: File,
        onProgress: (AdbSideload.Progress) -> Unit
    ) {
        using(target) { conn ->
            AdbSideload.sideload(conn, file, onProgress)
        }
    }

    /** Entra em modo sideload (`adb reboot sideload`). */
    fun rebootSideload(target: DeviceTarget): ShellResult = reboot(target, "sideload")

    // ------------------------------------------------------------ fastboot

    /** Sessão fastboot para o dispositivo USB informado. */
    fun fastbootSession(info: FastbootDeviceInfo): FastbootSession {
        val ctx = appContext()
        val device = FastbootUsb.deviceByName(ctx, info.deviceName)
            ?: throw AdbConnectException("O aparelho fastboot foi desconectado.")
        val transport = FastbootUsb.open(ctx, device, info.interfaceIndex)
        return FastbootSession(transport)
    }

    /** `fastboot devices` (lista). */
    fun fastbootDevices(): List<FastbootDeviceInfo> = FastbootUsb.listDevices(appContext())

    /**
     * Grava uma imagem numa partição: download + flash, com validação da
     * imagem e relato real do bootloader.
     */
    fun fastbootFlash(
        info: FastbootDeviceInfo,
        partition: String,
        image: File,
        onProgress: (FastbootProgress) -> Unit
    ): FastbootResult {
        if (!image.exists()) {
            return FastbootResult("flash", "", false, "Imagem não encontrada.")
        }
        val session = fastbootSession(info)
        try {
            onProgress(FastbootProgress("download", 0, image.length(), "Enviando a imagem…"))
            val dl = session.download(image) { sent ->
                onProgress(
                    FastbootProgress(
                        "download", sent, image.length(),
                        "Enviando a imagem… ${percent(sent, image.length())}%"
                    )
                )
            }
            if (!dl.ok) return dl
            onProgress(FastbootProgress("flash", image.length(), image.length(), "Gravando…"))
            val flash = session.flash(partition)
            val extra = if (flash.ok) {
                "✓ ${partition} gravada (${image.name})."
            } else {
                flash.error ?: "falha ao gravar"
            }
            return flash.copy(output = (flash.output + "\n" + extra).trim())
        } finally {
            session.close()
        }
    }

    /** `fastboot boot <imagem>` (boot temporário). */
    fun fastbootBoot(
        info: FastbootDeviceInfo,
        image: File,
        onProgress: (FastbootProgress) -> Unit
    ): FastbootResult {
        val session = fastbootSession(info)
        try {
            val dl = session.download(image) { sent ->
                onProgress(
                    FastbootProgress(
                        "download", sent, image.length(),
                        "Enviando a imagem… ${percent(sent, image.length())}%"
                    )
                )
            }
            if (!dl.ok) return dl
            onProgress(FastbootProgress("boot", image.length(), image.length(), "Iniciando…"))
            return session.bootDownloaded()
        } finally {
            session.close()
        }
    }

    // -------------------------------------------------------------- utils

    /** Escapa um caminho para uso num comando do shell do aparelho. */
    fun shellEscape(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    private fun percent(done: Long, total: Long): Int =
        if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)
}

/** Retrato completo de um aparelho, pronto para o dashboard. */
data class DeviceSnapshot(
    val target: DeviceTarget,
    val online: Boolean,
    val authorized: Boolean,
    val mode: DeviceMode,
    val state: String,
    val model: String,
    val manufacturer: String,
    val android: String,
    val sdk: Int,
    val build: String,
    val serial: String,
    val features: List<String> = emptyList(),
    val protocol: String = "",
    val maxPayload: Int = 0,
    val error: String = "",
    val state2: String = ""
) {
    fun title(): String = model.ifBlank { target.label() }

    fun subtitle(): String = listOfNotNull(
        manufacturer.takeIf { it.isNotBlank() },
        ("Android " + android).takeIf { android.isNotBlank() },
        mode.label
    ).joinToString(" · ")
}

/** Registro de uma operação no histórico. */
data class OpRecord(
    val timestamp: Long,
    val title: String,
    val target: String,
    val detail: String,
    val success: Boolean
)
