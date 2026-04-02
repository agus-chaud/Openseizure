package com.seizureguard.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Fase 0.1 — Entry point mínimo del módulo Phone.
 *
 * En fases posteriores esta Activity evolucionará a la pantalla principal
 * con historial de eventos, configuración y la alarma full-screen.
 *
 * TODO Fase 3.1: Mostrar estado de conexión con el watch
 * TODO Fase 3.2: Lanzar AlarmActivity cuando llega alerta del watch
 * TODO Fase 3.5: Mostrar historial de eventos desde Room DB
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SeizureGuardPhoneApp()
        }
    }
}

@Composable
fun SeizureGuardPhoneApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = "SeizureGuard — Companion App v0.1")
            }
        }
    }
}
