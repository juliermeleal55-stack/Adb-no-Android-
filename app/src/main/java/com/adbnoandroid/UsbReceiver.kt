package com.adbnoandroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Sinaliza eventos USB (plugue, permissão) para a UI re-listar os
 * dispositivos. A UI coleta [UsbEvents.version] e atualiza a lista.
 */
object UsbEvents {
    val version = MutableStateFlow(0)

    fun bump() {
        version.value += 1
    }
}

/** Recebe plugue/permite de USB declarado no Manifest. */
class UsbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_USB_PERMISSION,
            android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED,
            android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED -> UsbEvents.bump()
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.adbnoandroid.USB_PERMISSION"
    }
}
