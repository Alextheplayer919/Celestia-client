package com.proxy.mcbedrock

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Establishes an Android VPN interface so this process can see the device's
 * outbound UDP traffic — including Minecraft Bedrock's RakNet packets on
 * port 19132 — before it leaves the device, and relay it (unmodified or
 * lightly buffered) to the real destination.
 *
 * IMPORTANT — scope boundary for this project:
 * This service should only ever READ and RELAY packets, optionally with
 * timing/buffering changes on the DOWNSTREAM (server -> client) side for
 * jitter smoothing. It should never rewrite or delay packets on the
 * UPSTREAM (client -> server) side in a way that changes *when* input
 * reaches the server — that's the line between "connection tooling" and
 * a timing-exploit cheat. Keep upstream relay a straight passthrough.
 */
class MinecraftVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    companion object {
        private const val TAG = "MCBedrockProxy"
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val MINECRAFT_BEDROCK_PORT = 19132
    }

    override fun onCreate() {
        super.onCreate()
        startVpn()
    }

    private fun startVpn() {
        val builder = Builder()
            .setSession("Bedrock Proxy")
            .addAddress(VPN_ADDRESS, 32)
            .addRoute(VPN_ROUTE, 0)
            .setMtu(1500)
            // TODO: once you're only intercepting Minecraft, scope this down
            // with addAllowedApplication(packageNameOfMinecraft) so the rest
            // of the device's traffic doesn't route through here at all.

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
     * Core relay loop. This skeleton just logs that packets arrived — the
     * real implementation needs to:
     *
     *  1. Parse the raw bytes read from the TUN interface as IP packets,
     *     pull out the UDP payload (this is IP-layer, not yet Bedrock-aware).
     *  2. Identify flows destined for MINECRAFT_BEDROCK_PORT (or wherever the
     *     user's server actually is — Bedrock can run on other ports).
     *  3. Open a real UDP socket (via `protect()`, required so the relayed
     *     socket doesn't loop back into the VPN interface) to the true
     *     destination and forward the payload.
     *  4. For downstream packets: optionally buffer briefly to smooth jitter
     *     before writing back into the TUN interface for Minecraft to read.
     *  5. Hand a copy of decoded packets to a stats/overlay collector —
     *     this is where CloudburstMC/Protocol's RakNet + game packet codecs
     *     plug in, once that dependency is wired up in build.gradle.kts.
     */
    private suspend fun relayLoop(iface: ParcelFileDescriptor) {
        val input = FileInputStream(iface.fileDescriptor)
        val output = FileOutputStream(iface.fileDescriptor)
        val buffer = ByteBuffer.allocate(32767)

        while (true) {
            buffer.clear()
            val length = input.read(buffer.array())
            if (length <= 0) continue

            // TODO: replace this stub with real IP/UDP parsing + relay.
            // Left as a placeholder so the service compiles and the VPN
            // interface comes up — nothing is actually relayed yet, so
            // Minecraft traffic through this interface will currently
            // just stall. Don't ship until the relay is implemented.
            Log.d(TAG, "Captured $length bytes from TUN interface (unhandled)")

            // Suppress unused warning on `output` until relay writes to it.
            output.let { }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        vpnInterface?.close()
        vpnInterface = null
    }
}
