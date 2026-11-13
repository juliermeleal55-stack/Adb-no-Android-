package com.adbnoandroid

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Estado central do app: lista de aparelhos (USB, Wi‑Fi e fastboot), alvo
 * selecionado, snapshots (modelo/Android/serial/modo) e histórico de operações.
 *
 * Vive no [MainActivity] e é passado para as telas — assim o stream de logcat
 * e as transferências continuam ao trocar de aba.
 */
class AppState(
    private val context: Context,
    private val scope: CoroutineScope
) {
    // ---------------------------------------------------------- dispositivos

    val usbDevices: SnapshotStateList<AdbUsb.UsbDeviceInfo> = mutableStateListOf()
    val fastbootDevices: SnapshotStateList<FastbootDeviceInfo> = mutableStateListOf()
    val networkDevices: SnapshotStateList<AdbDeviceInfo> = mutableStateListOf()

    val usbStatus: SnapshotStateMap<String, DeviceStatus> = mutableStateMapOf()
    val fastbootStatus: SnapshotStateMap<String, DeviceStatus> = mutableStateMapOf()
    val netStatus: SnapshotStateMap<String, DeviceStatus> = mutableStateMapOf()

    val snapshots: SnapshotStateMap<String, DeviceSnapshot> = mutableStateMapOf()

    var selectedTarget by mutableStateOf<DeviceTarget?>(null)
        private set

    /** Mensagem de operação global (mostrada na barra de título). */
    var busyMessage by mutableStateOf<String?>(null)

    fun setBusy(message: String?) {
        busyMessage = message
    }

    var lastError by mutableStateOf<String?>(null)

    val operations: SnapshotStateList<OpRecord> = mutableStateListOf()

    init {
        val saved = DevicesStore.load(context)
        networkDevices.addAll(saved)
    }

    // ----------------------------------------------------------- consultas

    /** Todos os alvos possíveis (USB + Wi‑Fi + fastboot). */
    fun allTargets(includeFastboot: Boolean = true): List<DeviceTarget> {
        val out = ArrayList<DeviceTarget>()
        usbDevices.forEach { out.add(DeviceTarget.Usb(it)) }
        networkDevices.forEach { out.add(DeviceTarget.Net(it)) }
        if (includeFastboot) {
            fastbootDevices.forEach { out.add(DeviceTarget.Fastboot(it)) }
        }
        return out
    }

    /** Alvos que servem para ADB (shell/arquivos/instalação). */
    fun adbTargets(): List<DeviceTarget> = allTargets(includeFastboot = false)

    /** Alvos com o adbd pronto (online/recovery/sideload). */
    fun usableAdbTargets(): List<DeviceTarget> = adbTargets().filter {
        statusOf(it) == DeviceStatus.ONLINE ||
            statusOf(it) == DeviceStatus.RECOVERY ||
            statusOf(it) == DeviceStatus.SIDELOAD
    }

    fun statusOf(target: DeviceTarget): DeviceStatus = when (target) {
        is DeviceTarget.Usb -> usbStatus[target.info.deviceName] ?: DeviceStatus.UNKNOWN
        is DeviceTarget.Net -> netStatus[target.info.address()] ?: DeviceStatus.UNKNOWN
        is DeviceTarget.Fastboot -> fastbootStatus[target.info.deviceName] ?: DeviceStatus.FASTBOOT
    }

    fun snapshotOf(target: DeviceTarget?): DeviceSnapshot? =
        target?.let { snapshots[it.key()] }

    fun selectedSnapshot(): DeviceSnapshot? = snapshotOf(selectedTarget)

    fun selectedMode(): DeviceMode = selectedSnapshot()?.mode ?: DeviceMode.UNKNOWN

    /** Precisa de "Permitir" no aparelho? */
    fun needsAuthorization(target: DeviceTarget): Boolean =
        statusOf(target) == DeviceStatus.NEEDS_AUTH ||
            statusOf(target) == DeviceStatus.UNAUTHORIZED

    // ------------------------------------------------------------ seleção

    fun select(target: DeviceTarget?) {
        selectedTarget = target
        if (target != null && !needsAuthorization(target) &&
            statusOf(target) != DeviceStatus.CHECKING
        ) {
            refreshSnapshot(target)
        }
    }

    /** Mantém a seleção válida quando a lista muda. */
    private fun ensureValidSelection() {
        val key = selectedTarget?.key()
        if (key != null) {
            val stillThere = when (selectedTarget) {
                is DeviceTarget.Usb ->
                    usbDevices.any { it.deviceName == (selectedTarget as DeviceTarget.Usb).info.deviceName }
                is DeviceTarget.Net ->
                    networkDevices.any { it.address() == (selectedTarget as DeviceTarget.Net).info.address() }
                is DeviceTarget.Fastboot ->
                    fastbootDevices.any { it.deviceName == (selectedTarget as DeviceTarget.Fastboot).info.deviceName }
                null -> false
            }
            if (stillThere) return
            selectedTarget = null
        }
        selectFirstUsable()
    }

    private fun selectFirstUsable() {
        val candidates = allTargets()
        val best = candidates.firstOrNull { statusOf(it) == DeviceStatus.ONLINE }
            ?: candidates.firstOrNull { statusOf(it) == DeviceStatus.RECOVERY }
            ?: candidates.firstOrNull { statusOf(it) == DeviceStatus.FASTBOOT }
            ?: candidates.firstOrNull()
        if (best != null) {
            selectedTarget = best
            if (statusOf(best) == DeviceStatus.UNKNOWN) refreshSnapshot(best)
        }
    }

    /** Garante que [target] seja o escolhido, mesmo depois de a lista recarregar. */
    fun selectKeeping(key: String) {
        if (selectedTarget?.key() == key) return
        allTargets().firstOrNull { it.key() == key }?.let { selectedTarget = it }
    }

    // ------------------------------------------------------------ refresh

    /** Relê os dispositivos USB (plugin/remoção e permissão). */
    fun refreshUsb() {
        val list = AdbUsb.listAdbDevices(context)
        val oldNames = usbDevices.map { it.deviceName }.toSet()
        val newNames = list.map { it.deviceName }.toSet()
        usbDevices.clear()
        usbDevices.addAll(list)
        if (newNames != oldNames) {
            val removed = oldNames - newNames
            removed.forEach {
                usbStatus.remove(it)
                snapshots.remove("usb:$it")
            }
        }
        // O estado de permissão pode ter mudado (diálogo USB).
        list.forEach { info ->
            if (!info.hasPermission) {
                usbStatus[info.deviceName] = DeviceStatus.UNAUTHORIZED
            } else if (usbStatus[info.deviceName] == null ||
                usbStatus[info.deviceName] == DeviceStatus.UNAUTHORIZED
            ) {
                usbStatus[info.deviceName] = DeviceStatus.UNKNOWN
                // Permissão concedida agora: já sonda o aparelho.
                if (info.hasPermission) probe(DeviceTarget.Usb(info))
            }
        }
        ensureValidSelection()
    }

    /** Relê os aparelhos em modo fastboot. */
    fun refreshFastboot() {
        val list = FastbootUsb.listDevices(context)
        val old = fastbootDevices.map { it.deviceName }.toSet()
        fastbootDevices.clear()
        fastbootDevices.addAll(list)
        list.forEach { info ->
            fastbootStatus[info.deviceName] = if (info.hasPermission) {
                DeviceStatus.FASTBOOT
            } else {
                DeviceStatus.UNAUTHORIZED
            }
        }
        (old - list.map { it.deviceName }.toSet()).forEach {
            fastbootStatus.remove(it)
            snapshots.remove("fb:$it")
        }
        ensureValidSelection()
    }

    /** Atualiza tudo (botão "Atualizar"). */
    fun refreshAll() {
        refreshUsb()
        refreshFastboot()
        probeAll()
    }

    /** Consulta todos os dispositivos conectados (modelo, Android, serial…). */
    fun probeAll() {
        usbDevices.forEach { info ->
            if (info.hasPermission) probe(DeviceTarget.Usb(info))
            else usbStatus[info.deviceName] = DeviceStatus.UNAUTHORIZED
        }
        networkDevices.forEach { probe(DeviceTarget.Net(it)) }
    }

    /** Consulta um dispositivo e atualiza status + snapshot. */
    fun probe(target: DeviceTarget) {
        if (target is DeviceTarget.Fastboot) {
            fastbootStatus[target.info.deviceName] = DeviceStatus.FASTBOOT
            return
        }
        // Se já existe uma sondagem em andamento para este alvo, cancela: cada
        // uma abre uma conexão ADB completa e, numa sequência de broadcasts USB,
        // várias se atropelavam (a última a terminar sobrescrevia o resultado).
        probeJobs.remove(target.key())?.cancel()
        setStatus(target, DeviceStatus.CHECKING)
        val job = scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                try {
                    AdbClient.probe(target)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Nova sondagem pedida / tela saindo: não inventa estado.
                    throw e
                } catch (e: Exception) {
                    DeviceSnapshot(
                        target = target, online = false, authorized = true,
                        mode = DeviceMode.UNKNOWN, state = "", model = "",
                        manufacturer = "", android = "", sdk = 0, build = "",
                        serial = "", error = e.message ?: "erro"
                    )
                }
            }
            snapshots[target.key()] = snapshot
            val err = snapshot.error.lowercase()
            val status = when {
                snapshot.online && snapshot.mode == DeviceMode.RECOVERY -> DeviceStatus.RECOVERY
                snapshot.online && snapshot.mode == DeviceMode.SIDELOAD -> DeviceStatus.SIDELOAD
                snapshot.online && snapshot.mode == DeviceMode.FASTBOOT -> DeviceStatus.FASTBOOT
                snapshot.online -> DeviceStatus.ONLINE
                !snapshot.authorized -> DeviceStatus.NEEDS_AUTH
                err.contains("permissão usb") || err.contains("sem permissão usb") ->
                    DeviceStatus.UNAUTHORIZED
                err.contains("não foi possível conectar") ||
                    err.contains("encerrou a conexão") ||
                    err.contains("timeout") -> DeviceStatus.OFFLINE
                else -> DeviceStatus.ERROR
            }
            setStatus(target, status)
            if (target is DeviceTarget.Net) {
                val idx = networkDevices.indexOfFirst { it.address() == target.info.address() }
                if (idx >= 0) {
                    networkDevices[idx] = networkDevices[idx].copy(
                        status = status,
                        model = snapshot.model,
                        android = snapshot.android,
                        serial = snapshot.serial,
                        mode = snapshot.mode,
                        lastError = snapshot.error
                    )
                }
            }
            if (selectedTarget == null && status == DeviceStatus.ONLINE) {
                selectedTarget = target
            }
            probeJobs.remove(target.key())
        }
        probeJobs[target.key()] = job
    }

    /** Sondagens em andamento por alvo (chave de [DeviceTarget.key]). */
    private val probeJobs = HashMap<String, Job>()

    private fun setStatus(target: DeviceTarget, status: DeviceStatus) {
        when (target) {
            is DeviceTarget.Usb -> usbStatus[target.info.deviceName] = status
            is DeviceTarget.Net -> netStatus[target.info.address()] = status
            is DeviceTarget.Fastboot -> fastbootStatus[target.info.deviceName] = status
        }
    }

    /** Atualiza o snapshot do alvo atual (após root, reboot, etc.). */
    fun refreshSnapshot(target: DeviceTarget? = selectedTarget) {
        if (target != null) probe(target)
    }

    // ------------------------------------------------------------- Wi‑Fi

    fun addNetworkDevice(raw: String): Pair<Boolean, String> {
        val parsed = parseAddress(raw)
            ?: return false to "Endereço inválido. Use IP:porta (ex.: 192.168.0.10:5555)."
        if (networkDevices.any { it.address() == "${parsed.first}:${parsed.second}" }) {
            return true to "Este aparelho já está na lista."
        }
        val device = AdbDeviceInfo(parsed.first, parsed.second)
        networkDevices.add(device)
        saveNetworkDevices()
        probe(DeviceTarget.Net(device))
        return true to "Aparelho adicionado. Aceite o diálogo de depuração nele se aparecer."
    }

    fun removeNetworkDevice(device: AdbDeviceInfo) {
        val idx = networkDevices.indexOfFirst { it.address() == device.address() }
        if (idx >= 0) networkDevices.removeAt(idx)
        netStatus.remove(device.address())
        snapshots.remove("net:" + device.address())
        saveNetworkDevices()
        ensureValidSelection()
    }

    fun renameNetworkDevice(device: AdbDeviceInfo, name: String) {
        val idx = networkDevices.indexOfFirst { it.address() == device.address() }
        if (idx >= 0) {
            networkDevices[idx] = networkDevices[idx].copy(name = name)
            saveNetworkDevices()
        }
    }

    private fun saveNetworkDevices() {
        DevicesStore.save(context, networkDevices.toList())
    }

    // ---------------------------------------------------------- permissões

    fun requestUsbPermission(info: AdbUsb.UsbDeviceInfo) {
        val device = AdbUsb.deviceByName(context, info.deviceName)
        if (device == null) {
            lastError = "O aparelho USB foi desconectado."
            return
        }
        AdbUsb.requestPermission(context, device)
    }

    fun requestFastbootPermission(info: FastbootDeviceInfo) {
        val device = FastbootUsb.deviceByName(context, info.deviceName)
        if (device == null) {
            lastError = "O aparelho fastboot foi desconectado."
            return
        }
        FastbootUsb.requestPermission(context, device)
    }

    /**
     * Reinicia o aparelho selecionado num modo (recovery, bootloader…).
     * Registra no histórico e atualiza o snapshot depois.
     */
    fun rebootSelected(mode: String) {
        val target = selectedTarget ?: return
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    AdbClient.reboot(target, mode)
                } catch (e: Exception) {
                    ShellResult(e.message ?: "erro", null, failed = true)
                }
            }
            val label = if (mode.isBlank()) "reboot" else "reboot $mode"
            if (res.failed) {
                logFail(label, target, res.combined().take(200))
            } else {
                logOk(label, target, res.combined().take(200))
            }
            // O aparelho demora alguns segundos: relê o estado depois.
            kotlinx.coroutines.delay(4000)
            refreshUsb()
            refreshFastboot()
            probe(target)
        }
    }

    // --------------------------------------------------------- operações

    fun log(op: OpRecord) {
        operations.add(0, op)
        while (operations.size > 200) operations.removeAt(operations.size - 1)
        AdbClient.record(op)
    }

    fun logOk(title: String, target: DeviceTarget?, detail: String = "") {
        log(
            OpRecord(
                timestamp = System.currentTimeMillis(),
                title = title,
                target = target?.label() ?: "—",
                detail = detail,
                success = true
            )
        )
    }

    fun logFail(title: String, target: DeviceTarget?, detail: String) {
        log(
            OpRecord(
                timestamp = System.currentTimeMillis(),
                title = title,
                target = target?.label() ?: "—",
                detail = detail,
                success = false
            )
        )
    }
}
