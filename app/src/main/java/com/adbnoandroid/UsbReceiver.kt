package com.adbnoandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Sinaliza eventos USB (plugue, remoção, permissão) para a UI re-listar os
 * dispositivos. A UI coleta [UsbEvents.version] e atualiza as listas de USB e
 * fastboot.
 */
object UsbEvents {
    val version = MutableStateFlow(0)

    fun bump() {
        version.value += 1
    }
}

/**
 * Recebe plugue/remoção/concessão de permissão USB declarados no Manifest.
 *
 * Não abre conexão nem fala com o aparelho aqui (broadcast tem tempo curto):
 * apenas sinaliza para a Activity que algo mudou.
 */
class UsbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_USB_PERMISSION -> {
                // O Android manda EXTRA_PERMISSION_GRANTED; registramos para a
                // UI poder avisar quando o usuário nega.
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                lastPermissionGranted = granted
                UsbEvents.bump()
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED,
            UsbManager.ACTION_USB_DEVICE_DETACHED -> UsbEvents.bump()
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.adbnoandroid.USB_PERMISSION"

        /** Resultado do último pedido de permissão (null = nenhum ainda). */
        @Volatile
        var lastPermissionGranted: Boolean? = null
    }
}
