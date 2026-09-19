package com.seizureguard.phone.setup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.seizureguard.phone.R
import com.seizureguard.phone.bridge.BridgeHistory
import com.seizureguard.phone.bridge.BridgePrefs
import com.seizureguard.phone.bridge.OsdBridgeService
import com.seizureguard.phone.summary.MorningSummaryReceiver

/** One-shot setup (permissions, battery exemption, start/stop). Deliberately no live status screen. */
class SetupActivity : Activity() {

    private lateinit var batteryButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply { setText(R.string.setup_instructions) })
        batteryButton = button(R.string.setup_battery_button) { requestBatteryExemption() }
        column.addView(batteryButton)
        column.addView(button(R.string.setup_start_button) { startBridge() })
        column.addView(button(R.string.setup_stop_button) { stopBridge() })
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        batteryButton.visibility = if (needsBatteryExemption(isIgnoringBatteryOptimizations())) View.VISIBLE else View.GONE
    }

    private fun button(textRes: Int, onClick: () -> Unit) =
        Button(this).apply { setText(textRes); setOnClickListener { onClick() } }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun missingPermissions() = missingRuntimePermissions(
        Build.VERSION.SDK_INT, granted(Manifest.permission.POST_NOTIFICATIONS), granted(Manifest.permission.BLUETOOTH_CONNECT),
    )

    private fun startBridge() {
        val missing = missingPermissions()
        if (missing.isEmpty()) launchService() else requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        // Notifications may be denied (the bridge still runs, only alerts are lost); Bluetooth may not.
        if (granted(Manifest.permission.BLUETOOTH_CONNECT) || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) launchService()
        else toast(R.string.setup_bluetooth_required)
    }

    private fun launchService() {
        BridgePrefs.setWasBridging(this, true)
        MorningSummaryReceiver.arm(this)
        OsdBridgeService.start(this)
        toast(R.string.setup_started)
    }

    private fun stopBridge() {
        BridgePrefs.setWasBridging(this, false)
        BridgeHistory.onCleanStop(this)
        stopService(Intent(this, OsdBridgeService::class.java))
        toast(R.string.setup_stopped)
    }

    private fun isIgnoringBatteryOptimizations() =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun requestBatteryExemption() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    private fun toast(textRes: Int) = android.widget.Toast.makeText(this, textRes, android.widget.Toast.LENGTH_LONG).show()

    private companion object {
        const val REQUEST_PERMISSIONS = 1
    }
}
