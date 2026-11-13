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
import com.adbnoandroid.ui.AdbTheme
import com.adbnoandroid.ui.HomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AdbClient.init(this)
        setContent {
            // Material You: segue o tema do sistema; o botão do topo inverte.
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            var darkOverride by remember { mutableStateOf<Boolean?>(null) }
            val dark = darkOverride ?: systemDark
            AdbTheme(darkTheme = dark) {
                AdbApp(
                    darkTheme = dark,
                    onToggleTheme = { darkOverride = !dark }
                )
            }
        }
    }
}



@Composable
fun AdbApp(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { AppState(context, scope) }
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
        HomeScreen(
            state = state,
            darkTheme = darkTheme,
            onToggleTheme = onToggleTheme
        )
    }
}
