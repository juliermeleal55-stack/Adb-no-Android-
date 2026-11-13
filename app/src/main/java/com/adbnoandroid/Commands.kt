package com.adbnoandroid

/**
 * Comandos prontos do terminal, organizados por categoria.
 *
 * `ready = true`  → executa direto no aparelho selecionado.
 * `ready = false` → tem um valor para o usuário ajustar (ex.: nome do pacote);
 *                   tocar coloca o texto no campo de entrada.
 */
data class CommandPreset(
    val name: String,
    val command: String,
    val category: String,
    val ready: Boolean = true
)

object Commands {

    val categories = listOf(
        "Dispositivo", "Apps", "Logs", "Sistema", "Rede", "Arquivos", "Ações"
    )

    val all: List<CommandPreset> = listOf(
        // ---------------------------------------------------- Dispositivo
        CommandPreset("Modelo", "getprop ro.product.model", "Dispositivo"),
        CommandPreset("Fabricante", "getprop ro.product.manufacturer", "Dispositivo"),
        CommandPreset("Versão do Android", "getprop ro.build.version.release", "Dispositivo"),
        CommandPreset("API / SDK", "getprop ro.build.version.sdk", "Dispositivo"),
        CommandPreset("Build", "getprop ro.build.display.id", "Dispositivo"),
        CommandPreset("Serial", "getprop ro.serialno", "Dispositivo"),
        CommandPreset("Tipo de build", "getprop ro.build.type", "Dispositivo"),
        CommandPreset("Slot A/B atual", "getprop ro.boot.slot_suffix", "Dispositivo"),
        CommandPreset("Uptime", "uptime", "Dispositivo"),
        CommandPreset("Bateria", "dumpsys battery", "Dispositivo"),
        CommandPreset("Tela (resolução)", "wm size; wm density", "Dispositivo"),
        CommandPreset("Idioma/região", "getprop persist.sys.locale", "Dispositivo"),
        CommandPreset("Todas as props", "getprop", "Dispositivo"),

        // ---------------------------------------------------- Apps
        CommandPreset("Listar apps", "pm list packages", "Apps"),
        CommandPreset("Apps instalados por você", "pm list packages -3", "Apps"),
        CommandPreset("Apps do sistema", "pm list packages -s", "Apps"),
        CommandPreset("Apps desativados", "pm list packages -d", "Apps"),
        CommandPreset("Info de um app", "dumpsys package <pacote>", "Apps", ready = false),
        CommandPreset("Desinstalar app", "pm uninstall <pacote>", "Apps", ready = false),
        CommandPreset("Limpar dados do app", "pm clear <pacote>", "Apps", ready = false),
        CommandPreset("Forçar parada", "am force-stop <pacote>", "Apps", ready = false),
        CommandPreset("Abrir um app", "monkey -p <pacote> 1", "Apps", ready = false),
        CommandPreset(
            "Permissões de um app",
            "dumpsys package <pacote> | grep -A3 'requested permissions'",
            "Apps",
            ready = false
        ),
        CommandPreset("Atividades em foco", "dumpsys activity activities | grep mResumedActivity", "Apps"),

        // ---------------------------------------------------- Logs
        CommandPreset("Logcat (dump)", "logcat -d -b all -v threadtime", "Logs"),
        CommandPreset("Logcat só erros", "logcat -d -b all *:E", "Logs"),
        CommandPreset("Últimas 300 linhas", "logcat -d -b all -t 300", "Logs"),
        CommandPreset("Limpar logcat", "logcat -c -b all", "Logs"),
        CommandPreset("Log de um app", "logcat -d -b all --pid $(pidof <pacote>)", "Logs", ready = false),
        CommandPreset("dmesg (kernel)", "dmesg | tail -n 300", "Logs"),
        CommandPreset("Kernel panic/erros", "dmesg | grep -iE 'error|fail|panic' | tail -50", "Logs"),
        CommandPreset("Propriedades de boot", "getprop | grep -E 'boot|init.svc'", "Logs"),
        CommandPreset("Serviços do sistema", "service list", "Logs"),
        CommandPreset("Bugreport (pode demorar)", "bugreport /data/local/tmp/bugreport.zip", "Logs"),

        // ---------------------------------------------------- Sistema
        CommandPreset("Armazenamento", "df -h", "Sistema"),
        CommandPreset("Memória", "free -m", "Sistema"),
        CommandPreset("Processos", "ps -A | head -n 40", "Sistema"),
        CommandPreset("CPU", "cat /proc/cpuinfo | head -n 20", "Sistema"),
        CommandPreset("Kernel completo", "uname -a", "Sistema"),
        CommandPreset("Partições", "cat /proc/partitions", "Sistema"),
        CommandPreset("Montagens", "mount", "Sistema"),
        CommandPreset("Carga do sistema", "cat /proc/loadavg", "Sistema"),
        CommandPreset("Data/hora do aparelho", "date", "Sistema"),
        CommandPreset("Limpar caches", "pm trim-caches 1024M", "Sistema"),
        CommandPreset("Estado do SELinux", "getenforce", "Sistema"),
        CommandPreset("Verificar root", "id", "Sistema"),

        // ---------------------------------------------------- Rede
        CommandPreset("Endereços IP", "ip addr show", "Rede"),
        CommandPreset("IP do Wi-Fi", "ip -f inet addr show wlan0", "Rede"),
        CommandPreset("Rotas", "ip route", "Rede"),
        CommandPreset("DNS", "getprop | grep dns", "Rede"),
        CommandPreset("Ping (8.8.8.8)", "ping -c 3 8.8.8.8", "Rede"),
        CommandPreset("Wi-Fi (estado)", "dumpsys wifi | head -n 40", "Rede"),
        CommandPreset("Interfaces", "ip link", "Rede"),

        // ---------------------------------------------------- Arquivos
        CommandPreset("Listar /sdcard", "ls -la /sdcard", "Arquivos"),
        CommandPreset("Downloads", "ls -la /sdcard/Download", "Arquivos"),
        CommandPreset("Maiores pastas", "du -sh /sdcard/* 2>/dev/null | sort -rh | head -n 10", "Arquivos"),
        CommandPreset("Espaço por pasta", "df -h /sdcard /data /cache", "Arquivos"),
        CommandPreset("Local do APK instalado", "pm path <pacote>", "Arquivos", ready = false),
        CommandPreset("Tamanho de um arquivo", "ls -la <caminho>", "Arquivos", ready = false),

        // ---------------------------------------------------- Ações
        CommandPreset("Reiniciar", "reboot", "Ações", ready = false),
        CommandPreset("Reiniciar no recovery", "reboot recovery", "Ações", ready = false),
        CommandPreset("Reiniciar no bootloader", "reboot bootloader", "Ações", ready = false),
        CommandPreset("Reboot sideload", "reboot sideload", "Ações", ready = false),
        CommandPreset("adb root", "root", "Ações", ready = false),
        CommandPreset("adb remount", "remount", "Ações", ready = false),
        CommandPreset("Ligar TCP/IP 5555", "tcpip 5555", "Ações", ready = false),
        CommandPreset("Capturar tela", "screencap -p /sdcard/tela.png", "Ações"),
        CommandPreset("Gravar tela (10s)", "screenrecord --time-limit 10 /sdcard/tela.mp4", "Ações"),
        CommandPreset("Travar tela", "input keyevent 26", "Ações"),
        CommandPreset("Desligar", "reboot -p", "Ações", ready = false)
    )
}
