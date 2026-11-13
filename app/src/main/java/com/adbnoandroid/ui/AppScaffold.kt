package com.adbnoandroid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator

/**
 * Estrutura principal: barra superior com o aparelho selecionado, navegação
 * inferior (celular) ou lateral (tablet) e a tela atual.
 */
@Composable
fun AppScaffold(
    state: AppState,
    screen: Screen,
    onScreenChange: (Screen) -> Unit,
    darkTheme: Boolean,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit
) {
    val configuration = LocalConfiguration.current
    val isWide = configuration.screenWidthDp >= 720
    val c = LocalAdbColors.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues()
        .calculateBottomPadding()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding()))
                TopBar(
                    state = state,
                    screen = screen,
                    themeMode = themeMode,
                    onThemeChange = onThemeChange,
                    onScreenChange = onScreenChange
                )
            }
        },
        bottomBar = {
            if (!isWide) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    primaryScreens.forEach { item ->
                        NavigationBarItem(
                            selected = screen == item,
                            onClick = { onScreenChange(item) },
                            icon = { Text(item.icon, fontSize = 18.sp) },
                            alwaysShowLabel = false,
                            label = {
                                Text(
                                    item.title,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Row(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isWide) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Spacer(Modifier.height(8.dp))
                    primaryScreens.forEach { item ->
                        NavigationRailItem(
                            selected = screen == item,
                            onClick = { onScreenChange(item) },
                            icon = { Text(item.icon, fontSize = 20.sp) },
                            label = { Text(item.title, fontSize = 11.sp) }
                        )
                    }
                }
                VerticalDivider()
            }
            Box(Modifier.weight(1f)) {
                when (screen) {
                    Screen.DEVICES -> DevicesScreen(state)
                    Screen.TERMINAL -> TerminalScreen(state)
                    Screen.FILES -> FilesScreen(state)
                    Screen.TOOLS -> ToolsScreen(state)
                    Screen.LOGS -> LogsScreen(state)
                    Screen.FASTBOOT -> FastbootScreen(state)
                    Screen.HISTORY -> HistoryScreen(state)
                }
            }
        }
    }
    // Evita "unused" se insets mudarem em versões futuras.
    if (bottomInset < 0.dp) Spacer(Modifier.height(0.dp))
    if (c.isDark == darkTheme) Spacer(Modifier.width(0.dp))
}

/** Abas que cabem na barra inferior / rail. */
val primaryScreens = listOf(
    Screen.DEVICES,
    Screen.TERMINAL,
    Screen.FILES,
    Screen.TOOLS,
    Screen.LOGS
)

@Composable
private fun TopBar(
    state: AppState,
    screen: Screen,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onScreenChange: (Screen) -> Unit
) {
    var themeMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            screen.title,
                            style = MaterialTheme.typography.titleLarge
                        )
                        if (screen != Screen.FASTBOOT && screen != Screen.HISTORY) {
                            Spacer(Modifier.width(6.dp))
                            DeviceDropdown(state)
                        }
                    }
                    val target = state.selectedTarget
                    val status = target?.let { state.statusOf(it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (status != null) {
                            StatusDot(status)
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            subtitleFor(state, target),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (screen == Screen.FASTBOOT || screen == Screen.HISTORY) {
                    TextButton(onClick = { onScreenChange(Screen.DEVICES) }) {
                        Text("‹ Voltar", fontSize = 13.sp)
                    }
                }
                IconButton(onClick = { state.refreshAll() }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Atualizar dispositivos",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    TextButton(onClick = { themeMenu = true }) {
                        Text(
                            when (themeMode) {
                                ThemeMode.SYSTEM -> "🌗"
                                ThemeMode.LIGHT -> "☀️"
                                ThemeMode.DARK -> "🌙"
                            },
                            fontSize = 16.sp
                        )
                    }
                    DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                        ThemeMode.values().forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label) },
                                onClick = {
                                    onThemeChange(mode)
                                    themeMenu = false
                                }
                            )
                        }
                    }
                }
                Box {
                    TextButton(onClick = { moreMenu = true }) {
                        Text("⋯", fontSize = 18.sp)
                    }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("⚡ Fastboot") },
                            onClick = {
                                onScreenChange(Screen.FASTBOOT)
                                moreMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🕘 Histórico de operações") },
                            onClick = {
                                onScreenChange(Screen.HISTORY)
                                moreMenu = false
                            }
                        )
                    }
                }
            }
            if (state.busyMessage != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        state.busyMessage ?: "",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            androidx.compose.material3.HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun DeviceDropdown(state: AppState) {
    var menuOpen by remember { mutableStateOf(false) }
    val targets = state.allTargets()
    Box {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.clip(RoundedCornerShape(50.dp))
        ) {
            Row(
                Modifier
                    .clickable { menuOpen = true }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Aparelhos: " + targets.size,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = "Escolher aparelho",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (targets.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Nenhum aparelho conectado") },
                    onClick = { menuOpen = false }
                )
            }
            targets.forEach { target ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                shortTargetLabel(target),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                statusLabel(state.statusOf(target)) + " · " +
                                    state.snapshotOf(target)?.mode?.label.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        state.select(target)
                        menuOpen = false
                    },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(statusColor(state.statusOf(target)))
                        )
                    }
                )
            }
        }
    }
}

private fun subtitleFor(state: AppState, target: DeviceTarget?): String {
    if (target == null) return "Conecte um cabo USB OTG ou adicione um IP na aba Dispositivos"
    val snap = state.snapshotOf(target)
    val status = state.statusOf(target)
    return if (snap != null && snap.online) {
        buildString {
            append(snap.title())
            if (snap.android.isNotBlank()) append(" · Android ${snap.android}")
            append(" · ")
            append(snap.mode.label)
            if (snap.protocol.isNotBlank()) append(" · ${snap.protocol}")
        }
    } else {
        "${target.label()} · ${statusLabel(status)}"
    }
}
