package com.seizureguard.phone.setup

import android.Manifest
import android.os.Build

/** Runtime permissions the bridge needs at [sdk] that are still not granted. */
fun missingRuntimePermissions(sdk: Int, notificationsGranted: Boolean, bluetoothGranted: Boolean): List<String> =
    buildList {
        if (sdk >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted) add(Manifest.permission.POST_NOTIFICATIONS)
        if (sdk >= Build.VERSION_CODES.S && !bluetoothGranted) add(Manifest.permission.BLUETOOTH_CONNECT)
    }

/** Battery exemption is a separate system screen, not a runtime permission. */
fun needsBatteryExemption(ignoringOptimizations: Boolean): Boolean = !ignoringOptimizations
