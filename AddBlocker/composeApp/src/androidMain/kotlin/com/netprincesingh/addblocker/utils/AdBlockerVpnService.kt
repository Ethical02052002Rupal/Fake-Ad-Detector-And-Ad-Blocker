package com.netprincesingh.addblocker.utils

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class AdBlockerVpnService : VpnService() {

    private val TAG = "AdBlockerVpnService"
    private var vpnInterface: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Executors.newFixedThreadPool(4).asCoroutineDispatcher())
    private val writeMutex = Mutex()

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

        // Configure the VPN tunnel for DNS Proxy mode
        builder.setSession("AddBlockerVpn")
            .addAddress("10.0.0.2", 32) // Local IP for the VPN interface
            .addRoute("10.0.0.3", 32)   // ONLY route traffic going to our dummy DNS server!
            .addDnsServer("10.0.0.3")   // Tell Android to use our dummy DNS server
            .setMtu(1500)

        vpnInterface = builder.establish()

        if (vpnInterface == null) {
            log("Failed to establish VPN interface.")
            return
        }

        log("VPN established successfully.")
        _isVpnRunning.value = true

        // Start reading from the VPN interface in a coroutine
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
                        
                        // We are only routing 10.0.0.3 (DNS). Expect UDP (17).
                        if (protocolNum == 17) {
                            val ihl = (versionAndIHL and 0x0F) * 4
                            if (length > ihl + 8) {
                                val srcPort = ((bytes[ihl].toInt() and 0xFF) shl 8) or (bytes[ihl + 1].toInt() and 0xFF)
                                val destPort = ((bytes[ihl + 2].toInt() and 0xFF) shl 8) or (bytes[ihl + 3].toInt() and 0xFF)
                                
                                if (destPort == 53) {
                                    val domain = extractDomainFromDns(bytes, ihl + 20, length)
                                    if (domain != null) {
                                        logDns(domain)
                                        log("[DNS Query] $domain")
                                    }
                                    
                                    val dnsPayloadLength = length - ihl - 8
                                    val dnsPayload = ByteArray(dnsPayloadLength)
                                    System.arraycopy(bytes, ihl + 8, dnsPayload, 0, dnsPayloadLength)
                                    
                                    // Make a copy of the packet to use for the response
                                    val originalPacket = bytes.copyOf(length)
                                    
                                    // Proxy the request asynchronously so we don't block the read loop
                                    scope.launch(Dispatchers.IO) {
                                        proxyDnsRequest(originalPacket, ihl, srcPort, dnsPayload)
                                    }
                                }
                            }
                        }
                    }
                    buffer.clear()
                }
            } catch (e: Exception) {
                log("Error in VPN tunnel: ${e.message}")
                Log.e(TAG, "Error in VPN tunnel: ${e.message}", e)
                break
            }
        }
    }

    private suspend fun proxyDnsRequest(originalPacket: ByteArray, ihl: Int, originalSrcPort: Int, dnsPayload: ByteArray) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            // CRITICAL: Protect the socket from being routed back into the VPN!
            // (Even though we don't route 8.8.8.8, it's good practice)
            protect(socket)
            
            val realDnsIp = InetAddress.getByName("8.8.8.8")
            val sendPacket = DatagramPacket(dnsPayload, dnsPayload.size, realDnsIp, 53)
            socket.soTimeout = 3000
            socket.send(sendPacket)

            val recvBuffer = ByteArray(2048)
            val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
            socket.receive(recvPacket)

            val responsePayload = recvPacket.data.copyOf(recvPacket.length)
            
            // Reconstruct the raw IP and UDP packet
            val responseIpPacket = buildIpUdpPacket(originalPacket, ihl, originalSrcPort, responsePayload)
            
            // Write it back to the VPN interface securely
            writeMutex.withLock {
                if (vpnInterface != null) {
                    val output = FileOutputStream(vpnInterface!!.fileDescriptor)
                    output.write(responseIpPacket)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "DNS proxy error", e)
        } finally {
            socket?.close()
        }
    }

    private fun buildIpUdpPacket(original: ByteArray, ihl: Int, originalSrcPort: Int, payload: ByteArray): ByteArray {
        val totalLength = ihl + 8 + payload.size
        val packet = ByteArray(totalLength)
        
        // Copy original IP header
        System.arraycopy(original, 0, packet, 0, ihl)
        
        // Swap Source and Destination IPs
        for (i in 0..3) {
            packet[12 + i] = original[16 + i]
            packet[16 + i] = original[12 + i]
        }
        
        // Update IP Total Length
        packet[2] = (totalLength shr 8).toByte()
        packet[3] = totalLength.toByte()
        
        // Recalculate IP Checksum
        packet[10] = 0
        packet[11] = 0
        val ipChecksum = calculateChecksum(packet, 0, ihl)
        packet[10] = (ipChecksum shr 8).toByte()
        packet[11] = ipChecksum.toByte()
        
        // Build UDP Header
        val udpOffset = ihl
        // Source Port becomes 53 (DNS)
        packet[udpOffset] = 0
        packet[udpOffset + 1] = 53
        // Destination Port becomes originalSrcPort
        packet[udpOffset + 2] = (originalSrcPort shr 8).toByte()
        packet[udpOffset + 3] = originalSrcPort.toByte()
        
        // UDP Length
        val udpLength = 8 + payload.size
        packet[udpOffset + 4] = (udpLength shr 8).toByte()
        packet[udpOffset + 5] = udpLength.toByte()
        
        // UDP Checksum = 0 (No checksum calculated for IPv4 UDP responses to save computation)
        packet[udpOffset + 6] = 0
        packet[udpOffset + 7] = 0
        
        // Append Payload
        System.arraycopy(payload, 0, packet, udpOffset + 8, payload.size)
        
        return packet
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
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