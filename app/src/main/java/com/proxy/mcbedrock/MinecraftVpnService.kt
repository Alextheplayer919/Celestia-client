package com.proxy.mcbedrock

import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.proxy.mcbedrock.net.UdpNatSession
import com.proxy.mcbedrock.net.parseIpv4Udp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Establishes an Android VPN interface scoped to just the Minecraft Bedrock
 * app, and relays its UDP (RakNet) traffic to the real destination via a
 * per-flow NAT session table — a straight passthrough proxy, with a hook
 * point for read-only packet inspection as features get layered on.
 *
 * IMPORTANT — scope boundary for this project:
 * This service should only ever READ and RELAY packets. It should never
 * rewrite or delay packets on the UPSTREAM (client -> server) side in a way
 * that changes *when* input reaches the server — that's the line between
 * "connection tooling" and a timing-exploit cheat. Upstream relay here is a
 * straight passthrough, on purpose.
 */
class MinecraftVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val tunWriteLock = Mutex()

    // Keyed by "clientPort:remoteAddr:remotePort" — one real socket per flow.
    private val sessions = ConcurrentHashMap<String, UdpNatSession>()

    companion object {
        private const val TAG = "MCBedrockProxy"
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"

        // Standard Bedrock package name on the Play Store build. If you're
        // running a different build (Preview, Windows Store sideload, etc.)
        // update this — addAllowedApplication throws NameNotFoundException
        // for a package that isn't installed, which we handle below.
        private const val MINECRAFT_PACKAGE = "com.mojang.minecraftpe"

        // A session with no activity for this long is considered dead and
        // gets torn down, so we're not leaking sockets over a long play
        // session with lots of short-lived flows (server pings, etc.).
        private const val SESSION_IDLE_TIMEOUT_MS = 2 * 60 * 1000L
    }

    override fun onCreate() {
        super.onCreate()
        startVpn()
        startSessionReaper()
    }

    private fun startVpn() {
        val builder = Builder()
            .setSession("Bedrock Proxy")
            .addAddress(VPN_ADDRESS, 32)
            .addRoute(VPN_ROUTE, 0)
            .setMtu(1500)

        try {
            builder.addAllowedApplication(MINECRAFT_PACKAGE)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "$MINECRAFT_PACKAGE not installed — update MINECRAFT_PACKAGE " +
                "to match your actual Minecraft build, or the VPN will capture " +
                "every app's traffic instead of just Minecraft's.")
        }

        vpnInterface = builder.establish()

        val iface = vpnInterface ?: run {
            Log.e(TAG, "Failed to establish VPN interface")
            return
        }

        serviceScope.launch {
            relayLoop(iface)
        }
    }

    /**
     * Reads packets from the TUN interface (device -> "internet", from the
     * app's point of view) and relays each to its real destination.
     */
    private suspend fun relayLoop(iface: ParcelFileDescriptor) {
        val input = FileInputStream(iface.fileDescriptor)
        val output = FileOutputStream(iface.fileDescriptor)
        val buffer = ByteArray(32767)

        while (true) {
            val length = input.read(buffer)
            if (length <= 0) continue

            val udp = parseIpv4Udp(buffer, length) ?: continue // not IPv4/UDP — drop

            val sessionKey = "${udp.sourcePort}:${udp.destAddress.hostAddress}:${udp.destPort}"
            val session = sessions.getOrPut(sessionKey) {
                UdpNatSession(
                    vpnService = this,
                    scope = serviceScope,
                    tunOutput = output,
                    tunWriteLock = tunWriteLock,
                    clientPort = udp.sourcePort,
                    remoteAddress = udp.destAddress,
                    remotePort = udp.destPort
                ).also { it.start() }
            }

            // TODO: read-only decode hook for upstream (device -> server)
            // packets goes here too, if a feature needs to see both
            // directions — e.g. matching request/response for a stats
            // overlay. Never mutate udp.payload before sendUpstream below.

            session.sendUpstream(udp.payload, udp.payloadOffset, udp.payloadLength)
        }
    }

    /** Periodically closes NAT sessions that have gone idle. */
    private fun startSessionReaper() {
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                val now = System.currentTimeMillis()
                val stale = sessions.filterValues { now - it.lastActivityMillis > SESSION_IDLE_TIMEOUT_MS }
                stale.forEach { (key, session) ->
                    session.close()
                    sessions.remove(key)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        sessions.values.forEach { it.close() }
        sessions.clear()
        vpnInterface?.close()
        vpnInterface = null
    }
}
