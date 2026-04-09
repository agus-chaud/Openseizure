package com.seizureguard.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.seizureguard.wear.alarm.AlarmStateManager
import com.seizureguard.wear.service.SeizureMonitorService

/**
 * Fase 1.1 — UI principal del monitoreo nocturno.
 *
 * Responsabilidades de esta Activity:
 *   1. Mostrar el estado actual del monitoreo (ON u OFF).
 *   2. Permitir al usuario iniciar y detener el Service con un botón.
 *   3. Delegar la lógica de monitoreo al ForegroundService — esta Activity
 *      puede cerrarse o irse a background sin detener el monitoreo.
 *
 * Por qué se usa startService() / stopService() en lugar de binding:
 *   El Service es un "started service", no un "bound service".
 *   La Activity no necesita comunicación continua con él — solo envía
 *   Intents de inicio y stop. Esto simplifica el lifecycle considerablemente.
 *
 * Estado local con remember/mutableStateOf:
 *   El estado ON/OFF vive en la Activity. En Fase 2.x se reemplazará con
 *   un ViewModel que escucha el estado real del Service.
 *   Por ahora es suficiente para el entregable de Fase 1.1.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SeizureGuardWearApp(
                onStartMonitoring = {
                    startService(SeizureMonitorService.startIntent(this))
                },
                onStopMonitoring = {
                    startService(SeizureMonitorService.stopIntent(this))
                }
            )
        }
    }
}

/**
 * UI del reloj: un botón toggle para iniciar/detener el monitoreo.
 *
 * Fase 2.2: muestra el alarmState actual (OK/WARNING/ALARM) con color según la severidad.
 *
 * Diseño mínimo para Wear OS (pantalla redonda ~1.4 pulgadas, 450x450px):
 *   - Texto de estado en la parte superior (con color según alarmState)
 *   - Botón prominente en el centro
 *
 * En Fase 4.5 se agrega el Tile de Wear OS y la Complicación.
 */
@Composable
fun SeizureGuardWearApp(
    onStartMonitoring: () -> Unit,
    onStopMonitoring: () -> Unit
) {
    var isMonitoring by remember { mutableStateOf(false) }
    val alarmState by SeizureMonitorService.alarmState.collectAsState()

    // Color del texto según alarmState
    val statusColor = when (alarmState) {
        AlarmStateManager.ALARM_WARNING -> Color(0xFFF57F17)   // Amarillo
        in 2..Int.MAX_VALUE             -> Color(0xFFB71C1C)   // Rojo
        else                            -> Color.Unspecified   // Color por defecto del tema
    }

    // Texto del estado
    val statusText = when (alarmState) {
        AlarmStateManager.ALARM_WARNING -> stringResource(R.string.label_status_warning)
        in 2..Int.MAX_VALUE             -> stringResource(R.string.label_status_alarm)
        else                            -> if (isMonitoring)
                                               stringResource(R.string.label_monitoring_on)
                                           else
                                               stringResource(R.string.label_monitoring_off)
    }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = statusText,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.body1,
                color = statusColor
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    if (isMonitoring) {
                        onStopMonitoring()
                    } else {
                        onStartMonitoring()
                    }
                    isMonitoring = !isMonitoring
                },
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isMonitoring) Color(0xFFB71C1C) else Color(0xFF1B5E20)
                )
            ) {
                Text(
                    text = if (isMonitoring)
                        stringResource(R.string.btn_stop_monitoring)
                    else
                        stringResource(R.string.btn_start_monitoring)
                )
            }
        }
    }
}
