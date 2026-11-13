package com.adbnoandroid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Aba Histórico — registro de tudo o que o app executou (instalações,
 * transferências, comandos, sideload, fastboot), com hora, aparelho e
 * resultado. Útil para auditar o que foi feito em cada dispositivo.
 */
@Composable
fun HistoryScreen(state: AppState) {
    val context = LocalContext.current
    val c = LocalAdbColors.current
    val formatter = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Histórico de operações", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${state.operations.size} registro(s) — fica só neste aparelho",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = {
                val text = state.operations.joinToString("\n\n") { op ->
                    buildString {
                        append(formatter.format(Date(op.timestamp)))
                        append(op.timestamp.toString().let { "" })
                        append(" · ")
                        append(op.title)
                        append(" · ")
                        append(op.target)
                        append(if (op.success) "\n✓ " else "\n✗ ")
                        append(op.detail)
                    }
                }
                if (text.isNotBlank()) {
                    val f = FileSaver.saveText(context, "historico", text)
                    FileSaver.shareFile(context, f)
                }
            }) { Text("Exportar", fontSize = 12.sp) }
            IconButton(onClick = { state.operations.clear() }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Limpar histórico",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        if (state.operations.isEmpty()) {
            EmptyHint(
                "Nada por aqui ainda.\nAs operações (instalar, enviar, comandos, sideload, " +
                    "fastboot) aparecem aqui com data, aparelho e resultado."
            )
            return@Column
        }

        LazyColumn(Modifier.weight(1f)) {
            items(state.operations) { op ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                ) {
                    Row(Modifier.padding(12.dp)) {
                        Text(
                            if (op.success) "✓" else "✗",
                            color = if (op.success) c.success else c.error,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                op.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                formatter.format(Date(op.timestamp)) + " · " + op.target,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (op.detail.isNotBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    op.detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 4
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
