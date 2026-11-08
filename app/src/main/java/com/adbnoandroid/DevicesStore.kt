package com.adbnoandroid

import android.content.Context

/**
 * Persistência da lista de dispositivos remotos (SharedPreferences).
 * Formato: uma linha por dispositivo "host|porta|apelido".
 */
object DevicesStore {

    private const val PREFS = "devices"
    private const val KEY = "list"

    fun load(context: Context): List<AdbDeviceInfo> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split("|")
            val host = p.getOrNull(0)?.trim().orEmpty()
            if (host.isEmpty()) return@mapNotNull null
            val port = p.getOrNull(1)?.toIntOrNull() ?: Adb.DEFAULT_PORT
            val name = p.getOrNull(2).orEmpty()
            AdbDeviceInfo(host, port, name)
        }
    }

    fun save(context: Context, devices: List<AdbDeviceInfo>) {
        val raw = devices.joinToString("\n") { "${it.host}|${it.port}|${it.name}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, raw)
            .apply()
    }
}
