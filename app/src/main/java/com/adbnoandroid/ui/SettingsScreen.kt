package com.adbnoandroid.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Temas disponíveis. SYSTEM segue o aparelho; os outros forçam. */
enum class ThemeChoice(val label: String) {
    SYSTEM("Seguir o sistema"),
    LIGHT("Claro"),
    DARK("Escuro")
}

private const val REPO_URL = "https://github.com/juliermeleal55-stack/Adb-no-Android-"
private const val APP_VERSION = "2.0"

/**
 * Configurações: aparência, fonte, créditos e informações do app.
 *
 * O tema escolhido aqui é o mesmo botão ☀/☾ do topo da tela CMD — mudar num
 * lugar muda no outro, porque o estado vive num só lugar ([MainActivity]).
 */
@Composable
fun SettingsScreen(
    themeChoice: ThemeChoice,
    onThemeChoice: (ThemeChoice) -> Unit,
    darkTheme: Boolean
) {
    val context = LocalContext.current

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        // ------------------------------------------------ aparência
        item {
            SettingsCard(title = "Aparência", subtitle = "Tema do aplicativo") {
                ThemeChoice.entries.forEach { choice ->
                    ThemeRow(
                        label = choice.label,
                        detail = when (choice) {
                            ThemeChoice.SYSTEM ->
                                if (darkTheme) "usando escuro agora" else "usando claro agora"
                            ThemeChoice.LIGHT -> "fundo claro sempre"
                            ThemeChoice.DARK -> "fundo preto sempre"
                        },
                        selected = themeChoice == choice,
                        onClick = { onThemeChoice(choice) }
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "No Android 12+ as cores vêm do seu papel de parede (Material You). " +
                        "A opção Escuro usa preto puro nos textos e superfícies.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ------------------------------------------------ código-fonte
        item {
            SettingsCard(title = "Código-fonte", subtitle = "Este app é aberto") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🐙", fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "GitHub",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "juliermeleal55-stack/Adb-no-Android-",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(REPO_URL))
                            )
                        } catch (_: Exception) {
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Abrir a página do projeto", fontSize = 13.sp)
                }
                Text(
                    REPO_URL,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }

        // ------------------------------------------------ sobre
        item {
            SettingsCard(title = "Sobre", subtitle = null) {
                InfoLine("Aplicativo", "ADB no Android")
                InfoLine("Versão", APP_VERSION)
                InfoLine("Pacote", "com.adbnoandroid")
                InfoLine("Protocolo", "ADB sobre USB (OTG)")
                InfoLine("Android mínimo", "7.0 (API 24)")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Sem PC, sem root, sem adbd no celular que controla.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ------------------------------------------------ créditos
        item {
            CreditsCard()
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Créditos — o pedido explícito do autor, no rodapé das configurações. */
@Composable
private fun CreditsCard() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("📟", fontSize = 22.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "by: julier >_<",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "feito com Kotlin + Jetpack Compose",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------- peças

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String?,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ThemeRow(
    label: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "selecionado",
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
