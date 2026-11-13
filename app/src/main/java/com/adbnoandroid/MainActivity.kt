package com.adbnoandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AdbClient.init(this)
        setContent {
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            AdbTheme(darkTheme = dark) {
                AdbApp(
                    darkTheme = dark,
                    themeMode = themeMode,
                    onThemeChange = { themeMode = it }
                )
            }
        }
    }
}

enum class ThemeMode(val label: String) {
    SYSTEM("Sistema"),
    LIGHT("Claro"),
    DARK("Escuro")
}

enum class Screen(val title: String, val icon: String) {
    DEVICES("Dispositivos", "🔌"),
    TERMINAL("Terminal", "⌨️"),
    FILES("Arquivos", "📁"),
    TOOLS("Ferramentas", "🧰"),
    LOGS("Logs", "📋"),
    FASTBOOT("Fastboot", "⚡"),
    HISTORY("Histórico", "🕘")
}

@Composable
fun AdbApp(
    darkTheme: Boolean,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { AppState(context, scope) }
    var screen by remember { mutableStateOf(Screen.DEVICES) }
    var serverStarted by remember { mutableStateOf(false) }

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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        AppScaffold(
            state = state,
            screen = screen,
            onScreenChange = { screen = it },
            darkTheme = darkTheme,
            themeMode = themeMode,
            onThemeChange = onThemeChange
        )
    }
}
