package com.adbnoandroid

/** Um comando pronto, organizado por categoria. */
data class CommandPreset(
    val name: String,
    val command: String,
    val category: String,
    /** true = dá para rodar com um toque. false = o usuário deve ajustar antes (ex.: nome do pacote). */
    val ready: Boolean = true
)

object Commands {

    val categories = listOf(
        "Todos", "Dispositivo", "Apps", "Logs", "Sistema", "Rede", "Arquivos", "Ações"
    )

    val all: List<CommandPreset> = listOf(
        // ---------- Dispositivo ----------
        CommandPreset("Modelo do aparelho", "getprop ro.product.model", "Dispositivo"),
        CommandPreset("Versão do Android", "getprop ro.build.version.release", "Dispositivo"),
        CommandPreset("Build do sistema", "getprop ro.build.display.id", "Dispositivo"),
        CommandPreset("Bateria (detalhes)", "dumpsys battery", "Dispositivo"),
        CommandPreset("Tempo ligado (uptime)", "uptime", "Dispositivo"),
        CommandPreset("Memória RAM", "free -m", "Dispositivo"),
        CommandPreset("Processador (CPU)", "cat /proc/cpuinfo | head -n 12", "Dispositivo"),
        CommandPreset("Kernel", "uname -a", "Dispositivo"),
        CommandPreset(
            "Propriedades do sistema",
            "getprop | grep -E 'ro.product|ro.build' | head -n 40",
            "Dispositivo"
        ),

        // ---------- Apps ----------
        CommandPreset("Listar todos os apps", "pm list packages", "Apps"),
        CommandPreset("Apps instalados por você", "pm list packages -3", "Apps"),
        CommandPreset("Apps do sistema", "pm list packages -s", "Apps"),
        CommandPreset(
            "Desinstalar um app",
            "pm uninstall <pacote>",
            "Apps",
            ready = false
        ),
        CommandPreset(
            "Limpar dados de um app",
            "pm clear <pacote>",
            "Apps",
            ready = false
        ),
        CommandPreset(
            "Instalar um APK",
            "pm install /sdcard/Download/app.apk",
            "Apps",
            ready = false
        ),
        CommandPreset(
            "Forçar parada de um app",
            "am force-stop <pacote>",
            "Apps",
            ready = false
        ),

        // ---------- Logs (coleta igual ao PC) ----------
        CommandPreset("Logcat — tudo (dump)", "logcat -d -b all", "Logs"),
        CommandPreset("Logcat — só erros", "logcat -d -b all *:E", "Logs"),
        CommandPreset("Últimas 500 linhas do log", "logcat -d -b all -t 500", "Logs"),
        CommandPreset("Limpar o logcat", "logcat -c -b all", "Logs"),
        CommandPreset(
            "Log de um app (por PID)",
            "logcat -d -b all --pid $(pidof <pacote>)",
            "Logs",
            ready = false
        ),
        CommandPreset(
            "Gerar bugreport (zip, demora)",
            "bugreport /sdcard/bugreport.zip",
            "Logs"
        ),
        CommandPreset(
            "Gerar dumpstate (txt, demora)",
            "dumpstate -o /sdcard/dumpstate.txt",
            "Logs"
        ),

        // ---------- Sistema ----------
        CommandPreset("Armazenamento (tudo)", "df -h", "Sistema"),
        CommandPreset("Armazenamento interno (/data)", "df -h /data", "Sistema"),
        CommandPreset("Carga do sistema", "cat /proc/loadavg", "Sistema"),
        CommandPreset("Processos em execução", "ps -A | head -n 40", "Sistema"),
        CommandPreset("Serviços do sistema", "service list", "Sistema"),
        CommandPreset("Liberar espaço (limpar caches)", "pm trim-caches 1024M", "Sistema"),

        // ---------- Rede ----------
        CommandPreset("Endereços IP", "ip addr", "Rede"),
        CommandPreset("Tabelas de rota", "ip route", "Rede"),
        CommandPreset("Servidores DNS", "cat /etc/resolv.conf", "Rede"),
        CommandPreset("Ping no Google (8.8.8.8)", "ping -c 3 8.8.8.8", "Rede"),

        // ---------- Arquivos ----------
        CommandPreset("Arquivos no storage", "ls -la /sdcard", "Arquivos"),
        CommandPreset("Pasta de Downloads", "ls -la /sdcard/Download", "Arquivos"),
        CommandPreset(
            "Maiores pastas do storage",
            "du -sh /sdcard/* 2>/dev/null | sort -rh | head -n 10",
            "Arquivos"
        ),
        CommandPreset("Pontos de montagem", "mount", "Arquivos"),

        // ---------- Ações (use com cuidado) ----------
        CommandPreset("Reiniciar o aparelho", "reboot", "Ações", ready = false),
        CommandPreset("Reiniciar em modo recovery", "reboot recovery", "Ações", ready = false)
    )
}
