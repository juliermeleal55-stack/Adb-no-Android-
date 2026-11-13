package com.adbnoandroid

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocolo Fastboot no lado HOST — o mesmo que o `fastboot` do PC fala com o
 * bootloader. É **texto ASCII, síncrono e dirigido pelo host** (nada de
 * pacotes binários como no ADB):
 *
 * - O host envia um comando ASCII (ex.: `getvar:version`) num único pacote.
 * - O aparelho responde com um pacote de até 256 bytes começando por
 *   `OKAY`, `FAIL`, `DATA`, `INFO` ou `TEXT`.
 * - `INFO`/`TEXT` são mensagens intermediárias (progresso/erros); com `OKAY`
 *   ou `FAIL` a resposta termina.
 * - Fase de dados: `download:00001234` faz o aparelho responder
 *   `DATA00001234`; em seguida enviamos exatamente esse número de bytes e
 *   esperamos `OKAY`. Depois `flash:<partição>` grava o que foi baixado.
 *
 * Referência: fastboot/README.md (AOSP) e fastboot_driver.cpp.
 */
object Fastboot {
    /** Identificação do aparelho em modo fastboot (o "serial"). */
    const val VID_GOOGLE = 0x18D1

    /** Classe/subclasse da interface usada pelo bootloader fastboot. */
    const val FB_CLASS = 0xFF
    const val FB_SUBCLASS = 0x42
    const val FB_PROTOCOL = 0x03

    /** Interface alternativa que alguns bootloaders usam. */
    const val FB_SUBCLASS_ALT = 0x42

    const val MAX_RESPONSE = 64
    const val HOST_BUF = 256

    /**
     * Monta `download:%08x` — o tamanho vai em **hexadecimal** com 8 dígitos.
     * (Erro clássico: mandar decimal faz o bootloader rejeitar com FAIL.)
     */
    fun downloadCommand(size: Long): String =
        "download:" + String.format("%08x", size and 0xFFFFFFFFL)

    /** Lê o número de uma resposta `DATA%08x`. */
    fun parseDataSize(response: String): Long? {
        if (!response.startsWith("DATA")) return null
        val hex = response.substring(4).trim()
        return hex.toLongOrNull(16)
    }

    /** Converte `OKAY`/`FAIL`… em resultado. */
    fun isOkay(response: String): Boolean = response.startsWith("OKAY")

    fun isFail(response: String): Boolean = response.startsWith("FAIL")

    fun messageOf(response: String): String = when {
        response.length > 4 -> response.substring(4).trim()
        else -> ""
    }

    /**
     * Interpreta a saída "enxuta" de `getvar all` (uma linha por variável no
     * formato `(bootloader) nome: valor`) em um mapa ordenado.
     */
    fun parseGetvarAll(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        text.lines().forEach { raw ->
            var line = raw.trim()
            if (line.startsWith("(bootloader)")) {
                line = line.removePrefix("(bootloader)").trim()
            }
            if (line.isEmpty()) return@forEach
            val idx = line.indexOf(':')
            if (idx <= 0) return@forEach
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.isEmpty() || key == "all") return@forEach
            map[key] = value
        }
        return map
    }

    /** Partições perigosas: exigem confirmação dupla antes de gravar. */
    val DANGEROUS_PARTITIONS = setOf(
        "bootloader", "bootloader_a", "bootloader_b", "abl", "abl_a", "abl_b",
        "xbl", "xbl_a", "xbl_b", "tz", "tz_a", "tz_b", "hyp", "hyp_a", "hyp_b",
        "vbmeta", "vbmeta_a", "vbmeta_b", "dtbo", "persist", "modem", "radio",
        "efs", "fsg", "super", "system", "vendor", "product", "gpt", "sp"
    )

    /** Partições que nunca devem ser apagadas por acidente. */
    val NEVER_ERASE = setOf(
        "bootloader", "abl", "xbl", "tz", "hyp", "modem", "radio", "efs", "fsg",
        "persist", "gpt", "sp", "super"
    )

    internal fun headerOf(cmd: Int): ByteArray {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(cmd)
        return bb.array()
    }
}

/** Um dispositivo em modo fastboot. */
data class FastbootDeviceInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val serial: String,
    val product: String,
    val hasPermission: Boolean,
    val interfaceIndex: Int,
    val inFastbootd: Boolean = false
) {
    /** O que aparece no "fastboot devices". */
    fun label(): String = serial.ifBlank {
        "fastboot ${String.format("%04x:%04x", vendorId, productId)}"
    }
}

/** Resultado de um comando fastboot (com todas as linhas INFO/TEXT). */
data class FastbootResult(
    val command: String,
    val output: String,
    val ok: Boolean,
    val error: String? = null
)

/** Progresso de operações fastboot longas (download/flash). */
data class FastbootProgress(
    val phase: String,
    val sent: Long,
    val total: Long,
    val message: String = ""
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (sent.toDouble() / total).toFloat().coerceIn(0f, 1f)
}
