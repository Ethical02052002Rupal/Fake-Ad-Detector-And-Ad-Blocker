package com.netprincesingh.addblocker.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.netprincesingh.addblocker.utils.VpnServiceController
import com.netprincesingh.addblocker.utils.getVpnServiceController
import kotlinx.coroutines.launch

@Composable
fun HomeScreen() {
    val vpnServiceController = getVpnServiceController()
    val logs by vpnServiceController.logs.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Home Screen",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Button(onClick = {
            coroutineScope.launch {
                // This will trigger the preparation for VPN permission on Android
                // The actual launching of the ActivityResultLauncher will happen in Android-specific code
                vpnServiceController.prepareVpnPermissionIntent()
            }
        }) {
            Text("Request VPN Permission")
        }

        Button(onClick = {
            vpnServiceController.startVpnService()
        }) {
            Text("Start VPN Service")
        }

        Button(onClick = {
            vpnServiceController.stopVpnService()
        }) {
            Text("Stop VPN Service")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Network Logs:", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier.fillMaxSize()
                .weight(1f) // Take remaining space
                .padding(top = 8.dp)
        ) {
            items(logs) {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}