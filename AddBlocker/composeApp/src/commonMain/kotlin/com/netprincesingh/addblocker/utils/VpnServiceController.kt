package com.netprincesingh.addblocker.utils

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

interface VpnServiceController {
    val logs: StateFlow<List<String>>
    val dnsLogs: StateFlow<List<String>>
    val isVpnRunning: StateFlow<Boolean>
    // This suspend function will return an object that the Android actual can interpret as an Intent
    // and other platforms can ignore or return null.
    suspend fun prepareVpnPermissionIntent(): Any?
    fun handleVpnPermissionResult(isGranted: Boolean)
    fun startVpnService()
    fun stopVpnService()
}

@Composable
expect fun getVpnServiceController(): VpnServiceController