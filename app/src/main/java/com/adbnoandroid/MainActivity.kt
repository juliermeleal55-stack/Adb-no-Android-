package com.adbnoandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.adbnoandroid.ui.AdbTheme
import com.adbnoandroid.ui.FilesScreen
import com.adbnoandroid.ui.HomeScreen
import com.adbnoandroid.ui.SettingsScreen
import com.adbnoandroid.ui.ThemeChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AdbClient.init(this)
        setContent {
            // Material You: SYSTEM segue o aparelho; claro/escuro forçam.
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            var themeChoice by rememberSaveable {
                mutableStateOf(ThemeChoice.SYSTEM)
            }
            val dark = when (themeChoice) {
                ThemeChoice.SYSTEM -> systemDark
                ThemeChoice.LIGHT -> false
                ThemeChoice.DARK -> true
            }
            AdbTheme(darkTheme = dark) {
                AdbApp(
                    themeChoice = themeChoice,
                    onThemeChoice = { themeChoice = it },
                    darkTheme = dark
                )
            }
        }
    }
}



/** As três telas do app, na ordem da barra de baixo. */
enum class AppTab(val label: String, val icon: String) {
    CMD("CMD", "⌨"),
    FILES("Arquivos", "📁"),
    SETTINGS("Config", "⚙")
}

@Composable
fun AdbApp(
    themeChoice: ThemeChoice,
    onThemeChoice: (ThemeChoice) -> Unit,
    darkTheme: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { AppState(context, scope) }
    var serverStarted by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(AppTab.CMD) }

    // Eventos USB (plugue/remoção/permissão) → re-lista dispositivos.
    LaunchedEffect(Unit) {
        UsbEvents.version.collect {
            state.refreshUsb()
            state.refreshFastboot()
        }
    }

    // "adb start-server" do app: prepara a chave e faz o primeiro scan.
    LaunchedEffect(Unit) {
        if (!serverStarted) {
            serverStarted = true
            withContext(Dispatchers.IO) {
                try {
                    AdbClient.init(context)
                    // Garante que a chave RSA existe antes da primeira conexão.
                    AdbClient.authOrNull()
                } catch (_: Exception) {
                }
            }
            state.refreshUsb()
            state.refreshFastboot()
            state.probeAll()
        }
    }

    // Ao voltar do background (ex.: usuário foi autorizar no aparelho), relê.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.refreshUsb()
                state.refreshFastboot()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(item.icon, fontSize = 17.sp) },
                        label = { Text(item.label, fontSize = 10.sp) }
                    )
                }
            }
        }
        ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                AppTab.CMD -> HomeScreen(
                    state = state,
                    darkTheme = darkTheme,
                    // O botão do topo também muda a escolha das Configurações,
                    // para as duas telas nunca discordarem.
                    onToggleTheme = {
                        onThemeChoice(
                            if (darkTheme) ThemeChoice.LIGHT else ThemeChoice.DARK
                        )
                    }
                )
                AppTab.FILES -> FilesScreen(state)
                AppTab.SETTINGS -> SettingsScreen(
                    themeChoice = themeChoice,
                    onThemeChoice = onThemeChoice,
                    darkTheme = darkTheme
                )
            }
        }
    }
}
