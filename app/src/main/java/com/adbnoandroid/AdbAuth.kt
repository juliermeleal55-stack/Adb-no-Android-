package com.adbnoandroid

import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.interfaces.RSAPublicKey

/**
 * Chave RSA 2048 usada na autenticação ADB — equivalente à `~/.android/adbkey`
 * de um PC.
 *
 * Como funciona (confirmado no código do AOSP — `daemon/auth.cpp`):
 *
 * 1. O adbd manda `AUTH TOKEN` com 32 bytes aleatórios.
 * 2. Assinamos o token e devolvemos em `AUTH SIGNATURE`. O adbd valida com
 *    **`RSA_verify(NID_sha1, ...)`**, ou seja PKCS#1 v1.5 **com SHA-1 do
 *    conteúdo** — daí `SHA1withRSA` e não `NONEwithRSA` (que mandaria o token
 *    cru e seria rejeitado).
 * 3. Se a chave ainda não está autorizada, o adbd responde outro `AUTH TOKEN`
 *    e enviamos a chave pública em `AUTH RSAPUBLICKEY`. O aparelho mostra
 *    "Permitir depuração USB?" e guarda a chave.
 *
 * A chave pública **não** vai em PEM padrão: o adbd espera o formato binário
 * do Android (`ANDROID_PUBKEY_ENCODED_SIZE` = 3 × uint32 + 2 × 256 bytes),
 * codificado em base64, seguido de espaço e um identificador qualquer
 * (`crypto_utils/android_pubkey.cpp`).
 */
class AdbAuth(private val keyFile: File) {

    private val keyPair: KeyPair by lazy {
        if (keyFile.exists() && keyFile.readText().isNotBlank()) {
            load()
        } else {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val generated = kpg.generateKeyPair()
            save(generated)
            generated
        }
    }

    /** Assina o token de 32 bytes enviado pelo adbd (PKCS#1 v1.5 + SHA-1). */
    fun sign(token: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(keyPair.private)
        signature.update(token)
        return signature.sign()
    }

    /**
     * Chave pública no formato que o adbd espera:
     * `base64(AndroidPubKey) + " " + nome`.
     *
     * Atenção: **não** é PEM. O adbd decodifica o struct binário do Android
     * (não um SubjectPublicKeyInfo), por isso a codificação manual abaixo.
     */
    fun publicKeyAndroid(): String {
        val pub = keyPair.public as RSAPublicKey
        val encoded = encodeAndroidPublicKey(pub.modulus, pub.publicExponent)
        return Base64.encodeToString(encoded, Base64.NO_WRAP) + " " + KEY_NAME
    }

    /**
     * Serializa a chave no formato `RSAPublicKey` do Android:
     *
     * ```
     * uint32 modulus_size_words   // 2048/8/4 = 64
     * uint32 n0inv                // -1 / n[0]  mod 2^32
     * uint8  modulus[256]         // little-endian
     * uint8  rr[256]              // (2^2048)^2 mod n, little-endian
     * uint32 exponent
     * ```
     */
    private fun encodeAndroidPublicKey(modulus: BigInteger, exponent: BigInteger): ByteArray {
        val modulusSize = MODULUS_BYTES
        val out = ByteArray(3 * 4 + 2 * modulusSize)
        val bb = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        bb.putInt(MODULUS_BYTES / 4)

        // n0inv = -1 / n[0] mod 2^32
        // O adbd usa isso para a multiplicação de Montgomery; o valor precisa
        // bater exatamente com o que o BoringSSL calculou do outro lado.
        val n0 = modulus.mod(LOW_32)
        val n0inv = n0.modInverse(LOW_32).negate().mod(LOW_32)
        bb.putInt(n0inv.toInt())

        // modulus em little-endian, com 256 bytes fixos
        bb.put(bigIntToLittleEndian(modulus, modulusSize))

        // rr = (2^2048)^2 mod n
        val rr = BigInteger.ONE.shiftLeft(modulusSize * 8).pow(2).mod(modulus)
        bb.put(bigIntToLittleEndian(rr, modulusSize))

        // expoente (3 ou 65537) — cabe em uint32
        bb.putInt(exponent.toInt())

        return out
    }

    /** BigInteger → array de tamanho fixo, little-endian. */
    private fun bigIntToLittleEndian(value: BigInteger, size: Int): ByteArray {
        val big = value.toByteArray() // big-endian, possivelmente com zero à esquerda
        val be = if (big.size > size) big.copyOfRange(big.size - size, big.size) else big
        val out = ByteArray(size)
        for (i in be.indices) {
            out[i] = be[be.size - 1 - i]
        }
        return out
    }

    private fun save(pair: KeyPair) {
        keyFile.parentFile?.mkdirs()
        val privB64 = Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP)
            .chunked(64).joinToString("\n")
        val pubB64 = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)
            .chunked(64).joinToString("\n")
        keyFile.writeText(
            "-----BEGIN PRIVATE KEY-----\n$privB64\n-----END PRIVATE KEY-----\n" +
                "-----BEGIN PUBLIC KEY-----\n$pubB64\n-----END PUBLIC KEY-----\n"
        )
    }

    private fun load(): KeyPair {
        val pem = keyFile.readText()
        val factory = KeyFactory.getInstance("RSA")
        val priv = factory.generatePrivate(
            PKCS8EncodedKeySpec(base64Body(pem, "PRIVATE KEY"))
        )
        val pub = factory.generatePublic(
            X509EncodedKeySpec(base64Body(pem, "PUBLIC KEY"))
        )
        return KeyPair(pub, priv)
    }

    private fun base64Body(pem: String, label: String): ByteArray {
        val start = "-----BEGIN $label-----"
        val end = "-----END $label-----"
        val i = pem.indexOf(start)
        val j = pem.indexOf(end)
        require(i >= 0 && j > i) { "Chave ADB inválida (bloco $label não encontrado)." }
        val body = pem.substring(i + start.length, j)
            .replace("\r", "").replace("\n", "").trim()
        return Base64.decode(body, Base64.DEFAULT)
    }

    companion object {
        /** 2048 bits = 256 bytes. */
        private const val MODULUS_BYTES = 256

        private val LOW_32: BigInteger = BigInteger.ONE.shiftLeft(32)

        /** Identificador mostrado no diálogo do aparelho. */
        private const val KEY_NAME = "adbnoandroid@android"
    }
}
