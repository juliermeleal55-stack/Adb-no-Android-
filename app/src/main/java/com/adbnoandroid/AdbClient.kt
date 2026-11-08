package com.adbnoandroid

import android.content.Context
import java.io.File

/** Status de um dispositivo remoto na lista. */
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
 * Camada de alto nível do ADB sem fio: conecta em outros aparelhos pela rede
 * (o "adbd" deles, porta 5555 ou a porta de depuração sem fio) e roda
 * comandos no shell — igual ao `adb` de um PC, mas inteiro dentro do app.
 */
object AdbClient {

    private var auth: AdbAuth? = null

    /** Cria/carrega a chave RSA do app. Chamar no onCreate da Activity. */
    fun init(context: Context) {
        if (auth == null) {
            auth = AdbAuth(File(context.filesDir, "adb/adbkey.pem"))
        }
    }

    /**
     * Conecta e lê o banner do aparelho (ex.:
     * "device:0.0.1-4498046:SERIAL:walleye:walleye:Google").
     *
     * @throws AdbConnectException sem rede/portal fechado
     * @throws AdbAuthException autenticação não aceita
     */
    fun probe(host: String, port: Int, connectTimeoutMs: Int = 5000): String {
        val connection = AdbConnection(host, port, auth = auth, connectTimeoutMs)
        try {
            return connection.connect()
        } finally {
            connection.close()
        }
    }

    /** Extrai o modelo do banner (4º campo: produto). */
    fun parseModel(banner: String): String {
        val parts = banner.split(":")
        return if (parts.size >= 4) parts[3].trim() else ""
    }

    /**
     * Roda um comando no shell do aparelho remoto.
     *
     * A saída vem com o marcador de exit code anexado (mesma técnica do modo
     * local), assim o console mostra o código de saída como o adb do PC.
     */
    fun shell(host: String, port: Int, command: String, timeoutMs: Long = 60_000): ShellResult {
        val a = auth ?: return ShellResult("App não inicializado.", null, false, true, false)
        val connection = AdbConnection(host, port, auth = a)
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
