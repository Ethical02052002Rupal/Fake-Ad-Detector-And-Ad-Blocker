package com.netprincesingh.addblocker.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.StateFlow

// Actual implementation for Android
class AndroidVpnServiceController(private val context: Context) : VpnServiceController {

    override val logs: StateFlow<List<String>> = AdBlockerVpnService.logs
    override val dnsLogs: StateFlow<List<String>> = AdBlockerVpnService.dnsLogs
    override val isVpnRunning: StateFlow<Boolean> = AdBlockerVpnService.isVpnRunning

    // This will hold the ActivityResultLauncher to be set by the composable context
    private var permissionLauncher: ActivityResultLauncher<Intent>? = null
    private var permissionResultCallback: ((Boolean) -> Unit)? = null

    // This function needs to be called from a @Composable context to set the launcher
    @Composable
    fun SetPermissionLauncher(onPermissionResult: (Boolean) -> Unit) {
        permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val granted = result.resultCode == Activity.RESULT_OK
            AdBlockerVpnService.log("VPN permission result: ${if (granted) "Granted" else "Denied"}")
            permissionResultCallback?.invoke(granted)
            permissionResultCallback = null // Clear callback after use
        }
        permissionResultCallback = onPermissionResult
    }

    override suspend fun prepareVpnPermissionIntent(): Any? {
        val intent = VpnService.prepare(context)
        if (intent != null) {
            // Launch the intent using the launcher set from the composable
            permissionLauncher?.launch(intent)
        } else {
            AdBlockerVpnService.log("VPN is already prepared.")
            permissionResultCallback?.invoke(true)
        }
        return intent // Return intent for potential use in other platforms (though null for Android here)
    }

    override fun handleVpnPermissionResult(isGranted: Boolean) {
        // This function is called by the ActivityResult callback in the composable
        // The actual handling is done via the permissionResultCallback set in SetPermissionLauncher
        // The `SetPermissionLauncher` already invokes `permissionResultCallback`
    }

    override fun startVpnService() {
        val intent = VpnService.prepare(context)
        if (intent == null) {
            // Only start if permission is already granted/prepared
            context.startService(Intent(context, AdBlockerVpnService::class.java))
            AdBlockerVpnService.log("Attempting to start VPN service.")
        } else {
            AdBlockerVpnService.log("VPN not prepared. Request permission first.")
        }
    }

    override fun stopVpnService() {
        val stopIntent = Intent(context, AdBlockerVpnService::class.java).apply {
            action = "stop"
        }
        context.startService(stopIntent)
        AdBlockerVpnService.log("Attempting to stop VPN service.")
    }
}

@Composable
actual fun getVpnServiceController(): VpnServiceController {
    val context = LocalContext.current
    val controller = remember { AndroidVpnServiceController(context) }

    // Set the launcher from within the Composable context
    controller.SetPermissionLauncher {
        // Handle the result here, e.g., update UI state if needed
        if (it) {
            // Permission granted
        } else {
            // Permission denied
        }
    }

    return controller
}