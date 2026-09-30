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
import com.seizureguard.wear.alarm.DisplayStatus
import com.seizureguard.wear.alarm.DisplayStatusColors
import com.seizureguard.wear.alarm.DisplayStatusMapper
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

    val pipelineHealth by SeizureMonitorService.pipelineHealth.collectAsState()
    val alarmFreshness by SeizureMonitorService.alarmFreshness.collectAsState()

    // One pure decision (DisplayStatusMapper, unit-tested): a dead link / frozen OSD must never
    // leave the screen saying "Monitoreo activo", and a stale alarm is never shown as current.
    // A FRESH live alarm stays visible even if the pipeline is degraded for another reason.
    // Visual only: no vibration or sound is triggered from here (DEC-057).
    val display = DisplayStatusMapper.map(
        alarmState = alarmState,
        pipelineDegraded = pipelineHealth == SeizureMonitorService.PipelineHealth.DEGRADED,
        alarmStateStale = alarmFreshness.stale
    )
    val degradedAmber = Color(DisplayStatusColors.DEGRADED)   // amber on the black Wear background, ~12:1 contrast

    val statusColor = when (display.status) {
        DisplayStatus.WARNING      -> Color(DisplayStatusColors.WARNING)
        DisplayStatus.ALARM        -> Color(DisplayStatusColors.ALARM)
        DisplayStatus.SYSTEM_FAULT -> Color(DisplayStatusColors.SYSTEM_FAULT)   // fault, not an alarm
        DisplayStatus.DEGRADED     -> degradedAmber
        DisplayStatus.MUTED        -> Color(DisplayStatusColors.MUTED)
        DisplayStatus.NORMAL       -> Color.Unspecified   // Theme default
    }

    val statusText = when (display.status) {
        DisplayStatus.WARNING      -> stringResource(R.string.label_status_warning)
        DisplayStatus.ALARM        -> stringResource(R.string.label_status_alarm)
        DisplayStatus.SYSTEM_FAULT -> stringResource(R.string.label_status_system_fault)
        DisplayStatus.DEGRADED     -> stringResource(R.string.label_status_degraded)
        DisplayStatus.MUTED        -> stringResource(R.string.label_status_muted)
        DisplayStatus.NORMAL       -> if (isMonitoring)
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
            if (display.degradedHint) {
                Text(
                    text = stringResource(R.string.label_degraded_hint),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.caption1,
                    color = degradedAmber
                )
            }

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
