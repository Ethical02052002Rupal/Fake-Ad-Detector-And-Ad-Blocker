package com.netprincesingh.addblocker.utils

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class AdBlockerVpnService : VpnService() {

    private val TAG = "AdBlockerVpnService"
    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())

    companion object {
        private const val MAX_LOG_LINES = 100
        private val _logs = MutableStateFlow<List<String>>(emptyList())
        val logs: StateFlow<List<String>> = _logs

        private val _dnsLogs = MutableStateFlow<List<String>>(emptyList())
        val dnsLogs: StateFlow<List<String>> = _dnsLogs

        private val _isVpnRunning = MutableStateFlow(false)
        val isVpnRunning: StateFlow<Boolean> = _isVpnRunning

        fun log(message: String) {
            Log.d("AdBlockerVpnService", message) // Always log to Logcat for debugging
            val currentLogs = _logs.value
            val newLogs = if (currentLogs.size >= MAX_LOG_LINES) {
                currentLogs.drop(1) + message // Remove oldest, add new
            } else {
                currentLogs + message
            }
            _logs.value = newLogs
        }

        fun logDns(domain: String) {
            val currentLogs = _dnsLogs.value
            val newLogs = if (currentLogs.size >= MAX_LOG_LINES) {
                currentLogs.drop(1) + domain
            } else {
                currentLogs + domain
            }
            _dnsLogs.value = newLogs
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopVpn()
            return START_NOT_STICKY
        }

        // Check if the VPN is already running
        if (vpnInterface != null) {
            log("VPN is already running.")
            return START_STICKY
        }

        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        log("Starting VPN...")
        val builder = Builder()

        // Configure the VPN tunnel
        builder.setSession("AddBlockerVpn")
            .addAddress("10.0.0.2", 32) // Local IP for the VPN interface
            .addRoute("0.0.0.0", 0)     // Route all traffic through the VPN
            .addDnsServer("8.8.8.8")    // Example DNS server
            .setMtu(1500)

        // Protect the socket from being routed through the VPN itself
        // This is crucial to prevent a routing loop
        // if (!protect(someSocket)) {
        //     log("Failed to protect socket.")
        //     return
        // }

        vpnInterface = builder.establish()

        if (vpnInterface == null) {
            log("Failed to establish VPN interface.")
            return
        }

        log("VPN established successfully.")
        _isVpnRunning.value = true

        // Start reading and writing to the VPN interface in a coroutine
        scope.launch {
            runVpnTunnel()
        }
    }

    private fun stopVpn() {
        log("Stopping VPN...")
        scope.cancel() // Cancel the coroutine scope
        vpnInterface?.close()
        vpnInterface = null
        _isVpnRunning.value = false
        stopSelf()
        log("VPN stopped.")
    }

    private suspend fun runVpnTunnel() {
        val input = FileInputStream(vpnInterface!!.fileDescriptor)
        val output = FileOutputStream(vpnInterface!!.fileDescriptor)
        val buffer = ByteBuffer.allocate(32767) // Max IP packet size

        while (scope.isActive) {
            try {
                val length = input.read(buffer.array())
                if (length > 0) {
                    buffer.limit(length)
                    buffer.position(0)

                    val bytes = buffer.array()
                    val versionAndIHL = bytes[0].toInt()
                    val version = (versionAndIHL and 0xF0) shr 4
                    
                    if (version == 4 && length >= 20) {
                        val protocolNum = bytes[9].toInt() and 0xFF
                        val protocol = when (protocolNum) {
                            1 -> "ICMP"
                            6 -> "TCP"
                            17 -> "UDP"
                            else -> "Other($protocolNum)"
                        }
                        
                        val srcIp = "${bytes[12].toUByte()}.${bytes[13].toUByte()}.${bytes[14].toUByte()}.${bytes[15].toUByte()}"
                        val destIp = "${bytes[16].toUByte()}.${bytes[17].toUByte()}.${bytes[18].toUByte()}.${bytes[19].toUByte()}"
                        
                        log("[$protocol] $srcIp -> $destIp ($length bytes)")

                        if (protocolNum == 17) { // UDP
                            val ihl = (versionAndIHL and 0x0F) * 4
                            if (length > ihl + 8) {
                                val destPort = ((bytes[ihl + 2].toInt() and 0xFF) shl 8) or (bytes[ihl + 3].toInt() and 0xFF)
                                if (destPort == 53) {
                                    val domain = extractDomainFromDns(bytes, ihl + 20, length)
                                    if (domain != null) {
                                        logDns(domain)
                                    }
                                }
                            }
                        }
                    } else if (version == 6) {
                        log("[IPv6] packet ($length bytes)")
                    } else {
                        log("[Unknown IPv$version] packet ($length bytes)")
                    }

                    // Example: Simply forward all traffic for now (no blocking)
                    // In a real ad blocker, you would conditionally write to output.
                    output.write(buffer.array(), 0, length)
                    buffer.clear()
                }
            } catch (e: Exception) {
                log("Error in VPN tunnel: ${e.message}")
                Log.e(TAG, "Error in VPN tunnel: ${e.message}", e)
                break
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }

    private fun extractDomainFromDns(bytes: ByteArray, offset: Int, length: Int): String? {
        try {
            var i = offset
            val sb = StringBuilder()
            while (i < length) {
                val len = bytes[i].toInt() and 0xFF
                if (len == 0) break
                if ((len and 0xC0) == 0xC0) break // pointer
                if (sb.isNotEmpty()) sb.append(".")
                i++
                for (j in 0 until len) {
                    if (i < length) {
                        sb.append(bytes[i].toInt().toChar())
                        i++
                    }
                }
            }
            return if (sb.isNotEmpty()) sb.toString() else null
        } catch (e: Exception) {
            return null
        }
    }
}