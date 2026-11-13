package com.adbnoandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContentColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adbnoandroid.DeviceStatus

/**
 * Só o que a tela única usa. As outras 700 linhas de componentes (ConsoleView,
 * TargetSelector, OutputDialog, InfoRow, ProgressBlock…) existiam para as sete
 * telas antigas e saíram com elas — código que nenhuma tela chama é peso morto
 * no APK e mais superfície para bug.
 */

/** Cor do estado do aparelho. */
@Composable
fun statusColor(status: DeviceStatus): Color {
    val c = LocalAdbColors.current
    return when (status) {
        DeviceStatus.ONLINE -> c.success
        DeviceStatus.RECOVERY -> c.warning
        DeviceStatus.SIDELOAD -> c.accent
        DeviceStatus.FASTBOOT -> c.info
        DeviceStatus.NEEDS_AUTH, DeviceStatus.UNAUTHORIZED -> c.warning
        DeviceStatus.CHECKING -> c.info
        DeviceStatus.ERROR -> c.error
        DeviceStatus.OFFLINE -> c.error
        DeviceStatus.UNKNOWN -> LocalContentColor.current
    }
}

/** Texto curto do estado, em português. */
fun statusLabel(status: DeviceStatus): String = when (status) {
    DeviceStatus.ONLINE -> "online"
    DeviceStatus.RECOVERY -> "recovery"
    DeviceStatus.SIDELOAD -> "sideload"
    DeviceStatus.FASTBOOT -> "fastboot"
    DeviceStatus.NEEDS_AUTH -> "aguardando autorização"
    DeviceStatus.UNAUTHORIZED -> "não autorizado"
    DeviceStatus.CHECKING -> "verificando…"
    DeviceStatus.ERROR -> "erro"
    DeviceStatus.OFFLINE -> "offline"
    DeviceStatus.UNKNOWN -> "sem aparelho"
}

/**
 * Confirmação de operação destrutiva.
 *
 * [extraConfirmWord] exige que a pessoa digite a palavra (usado no apagar de
 * partição, onde um toque sem querer custa caro). [confirmEnabled] fecha o
 * botão quando a operação não é possível.
 */
@Composable
fun ConfirmDangerDialog(
    title: String,
    targetLabel: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    extraConfirmWord: String? = null,
    confirmEnabled: Boolean = true
) {
    val c = LocalAdbColors.current
    var typed by remember { mutableStateOf("") }
    val wordOk = extraConfirmWord == null || typed.trim() == extraConfirmWord

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = c.warning) },
        title = { Text(title) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .width(8.dp)
                            .height(8.dp)
                            .background(statusColor(DeviceStatus.ONLINE), CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        targetLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (extraConfirmWord != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Para confirmar, digite \"$extraConfirmWord\":",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled && wordOk,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(confirmText, fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", fontSize = 13.sp) }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

/** Linha de detalhe usada em blocos de informação. */
@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false) {
    if (value.isBlank()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}
