package com.adbnoandroid

import android.util.Base64
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Chave RSA 2048 usada na autenticação ADB — a mesma que um PC guarda em
 * `~/.android/adbkey`.
 *
 * - Na **primeira** conexão com um aparelho, o adbd rejeita a assinatura e o
 *   app envia a chave pública; o aparelho mostra o diálogo
 *   "Permitir depuração USB deste computador?" (com a impressa digital da
 *   chave). Ao tocar **Permitir**, o aparelho salva a chave e as conexões
 *   seguintes passam direto.
 * - A chave privada fica gravada no diretório privado do app (não sai do
 *   aparelho).
 *
 * A assinatura é `NONEwithRSA` (PKCS#1 v1.5 sem digest), exatamente o que o
 * `adbd` valida (`RSA_verify(NID_undef, …)`).
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

    /** Assina o token de 32 bytes enviado pelo adbd. */
    fun sign(token: ByteArray): ByteArray {
        val signature = Signature.getInstance("NONEwithRSA")
        signature.initSign(keyPair.private)
        signature.update(token)
        return signature.sign()
    }

    /** Chave pública em PEM (formato que o adbd espera no AUTH_RSAPUBLICKEY). */
    fun publicKeyPem(): String {
        val base64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        return "-----BEGIN PUBLIC KEY-----\n" +
            base64.chunked(64).joinToString("\n") +
            "\n-----END PUBLIC KEY-----\n"
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
}
