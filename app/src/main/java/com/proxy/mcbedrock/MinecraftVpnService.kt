package com.proxy.mcbedrock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.proxy.mcbedrock.net.BedrockFlowInspector
import com.proxy.mcbedrock.net.ConnectionPhase
import com.proxy.mcbedrock.net.UdpNatSession
import com.proxy.mcbedrock.net.buildIcmpDestinationUnreachable
import com.proxy.mcbedrock.net.parseIpv4
import com.proxy.mcbedrock.net.parseIpv4Udp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Establishes an Android VPN interface scoped to just the Minecraft Bedrock app,
 * and relays its UDP (RakNet) traffic to the real destination through a per-flow
 * NAT session table.
 *
 * What this service does and does not do (the boundary is deliberate):
 *  - It relays upstream bytes **unchanged and immediately**. Nothing here delays,
 *    reorders or rewrites what the client sends, so it cannot change *when* input
 *    reaches the server.
 *  - Downstream it relays unchanged too. Packet inspection is read-only: it
 *    observes and counts, and never feeds anything back into the traffic.
 *  - Traffic that is not UDP is not guessed at. TCP etc. gets an ICMP
 *    "administratively prohibited" reply so it fails fast instead of hanging.
 *
 * The service runs as a foreground service: a VPN session lives for as long as
 * the user is playing, which is exactly when this app is in the background, so
 * without the foreground notification Android would kill the tunnel mid-session.
 */
class MinecraftVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val tunWriteLock = Mutex()

    /** Keyed by "clientPort:remoteAddress:remotePort" — one socket per flow. */
    private val sessions = ConcurrentHashMap<String, UdpNatSession>()

    @Volatile private var vpnEstablished = false
    @Volatile private var startupError: String? = null

    // Target/scoping state, decided in startVpn() and published to the UI.
    @Volatile private var targetLabel: String = ""
    @Volatile private var scopeDescription: String = ""
    @Volatile private var scopedToTarget: Boolean = false
    @Volatile private var resolvedAddresses: List<String> = emptyList()
    @Volatile private var relayedPackage: String = RelayConfigStore.DEFAULT_PACKAGE

    // Counters surfaced in the UI/notification.
    private var droppedNonUdp = 0L
    private var icmpRejections = 0L
    private var relayErrors = 0L

    private var lastPublishAt = 0L
    private var lastPublishedUpBytes = 0L
    private var lastPublishedDownBytes = 0L
    private var lastUpstreamRate = 0L
    private var lastDownstreamRate = 0L
    private var lastNotificationText: String? = null

    private val notificationId = NOTIFICATION_ID

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Must happen promptly: the service may have been started with
        // startForegroundService(), which gives us a few seconds to promote
        // ourselves or the system kills the process.
        promoteToForeground(notificationText = getString(R.string.notification_starting))
        startVpn()
        startSessionReaper()
        startStatsPublisher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop requested from notification")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startVpn() {
        val config = RelayConfigStore(this)
        val target = config.target()
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(VPN_ADDRESS, 32)
            .setMtu(TUN_MTU)

        // Scoped mode routes only the chosen server's address into the tunnel, so
        // DNS, other apps and every other destination are left completely alone.
        // That is only possible once the address is known as an IP, hence the
        // resolution here; when it fails (or no target was chosen) the relay falls
        // back to the capture-everything mode it has always used.
        val resolved = if (config.scopeToServer && config.hasTarget()) resolveTarget(target.host) else emptyList()
        scopedToTarget = resolved.isNotEmpty()

        if (scopedToTarget) {
            resolved.forEach { address -> builder.addRoute(address.hostAddress ?: "", 32) }
            resolvedAddresses = resolved.mapNotNull { it.hostAddress }
            scopeDescription = "only ${target.label()} (${resolvedAddresses.joinToString(", ")})"
            Log.i(TAG, "Scoped tunnel to ${resolvedAddresses.joinToString(", ")}")
        } else {
            // All IPv4 traffic from the allowed app is captured; only UDP is relayed,
            // everything else is answered with ICMP so it fails fast.
            builder.addRoute(VPN_ROUTE, 0)
            // Minecraft resolves server hostnames through the system resolver, which
            // follows the VPN. Without DNS servers declared here, name resolution
            // breaks for "play.example.com" style addresses. In scoped mode DNS is
            // deliberately *not* routed, so the system resolver keeps working.
            builder.addDnsServer(DNS_PRIMARY)
            builder.addDnsServer(DNS_SECONDARY)
            resolvedAddresses = emptyList()
            scopeDescription = when {
                config.scopeToServer && config.hasTarget() -> "all traffic (could not resolve ${target.host})"
                else -> "all traffic from the app"
            }
        }

        targetLabel = if (config.hasTarget()) target.label() else "no target set"
        relayedPackage = config.packageName

        try {
            builder.addAllowedApplication(relayedPackage)
        } catch (e: PackageManager.NameNotFoundException) {
            // Capturing every app's traffic would be far worse than not starting.
            fail(relayedPackage + ERROR_APP_MISSING_SUFFIX)
            return
        }

        val iface = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish() failed", e)
            null
        }

        if (iface == null) {
            fail(ERROR_VPN_DENIED)
            return
        }

        vpnInterface = iface
        vpnEstablished = true
        Log.i(TAG, "VPN established, relaying ${relayedPackage} UDP traffic")

        serviceScope.launch { relayLoop(iface) }
    }

    /**
     * Resolves the target host to IPv4 addresses for route scoping. Bedrock traffic
     * is UDP over IPv4 here, and servers often publish several A records, so all of
     * them are routed rather than just the first.
     */
    private fun resolveTarget(host: String): List<InetAddress> = try {
        InetAddress.getAllByName(host).filter { it is java.net.Inet4Address }
    } catch (e: Exception) {
        Log.w(TAG, "Could not resolve $host", e)
        emptyList()
    }

    /**
     * Reads IPv4 packets from the TUN interface (device -> "internet") and relays
     * UDP to its real destination. Any exception here must not escape: the coroutine
     * is not supervised by a caller, so an unhandled one would crash the process.
     */
    private suspend fun relayLoop(iface: ParcelFileDescriptor) {
        val input = FileInputStream(iface.fileDescriptor)
        val output = FileOutputStream(iface.fileDescriptor)
        val buffer = ByteArray(MAX_TUN_PACKET)

        try {
            while (serviceScope.isActive) {
                val length = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    break // TUN closed: normal shutdown
                }
                if (length <= 0) continue

                val ipv4 = parseIpv4(buffer, length) ?: continue // not IPv4 (shouldn't happen)

                if (ipv4.protocol != PROTOCOL_UDP) {
                    // TCP and anything else is outside this proxy's job. Answering with
                    // ICMP makes the connection fail immediately instead of hanging,
                    // and makes it obvious in the UI that it was not relayed.
                    droppedNonUdp++
                    if (ipv4.protocol == PROTOCOL_TCP) {
                        val icmp = buildIcmpDestinationUnreachable(
                            fromAddress = ipv4.destAddress,
                            toAddress = ipv4.sourceAddress,
                            code = ICMP_CODE_ADMIN_PROHIBITED,
                            originalPacket = buffer,
                            originalLength = length
                        )
                        tunWriteLock.lock()
                        try {
                            output.write(icmp)
                            icmpRejections++
                        } finally {
                            tunWriteLock.unlock()
                        }
                    }
                    continue
                }

                val udp = parseIpv4Udp(buffer, length) ?: continue

                val key = sessionKey(udp.sourcePort, udp.destAddress, udp.destPort)
                val session = sessionFor(key, udp.sourcePort, udp.destAddress, udp.destPort, output)
                if (session == null) {
                    relayErrors++
                    continue
                }

                // Read-only inspection of the upstream bytes, before they are sent on.
                session.inspector.onUpstream(udp.payload, udp.payloadOffset, udp.payloadLength)

                // Straight passthrough — no delay, no rewrite.
                session.sendUpstream(udp.payload, udp.payloadOffset, udp.payloadLength)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Relay loop ended: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Relay loop failed", e)
            publishError("relay loop: ${e.message}")
        }
    }

    /**
     * Returns a live session for this flow, replacing a closed one instead of
     * handing out a dead socket (and keeping the flow's inspection/stats history
     * by reusing the existing inspector when there is one).
     */
    private fun sessionFor(
        key: String,
        clientPort: Int,
        remoteAddress: InetAddress,
        remotePort: Int,
        tunOutput: FileOutputStream
    ): UdpNatSession? {
        val existing = sessions[key]
        if (existing != null && existing.isOpen) return existing

        val inspector = existing?.inspector ?: BedrockFlowInspector(
            remoteAddress = remoteAddress,
            remotePort = remotePort,
            clientPort = clientPort
        )

        val created = UdpNatSession(
            vpnService = this,
            scope = serviceScope,
            tunOutput = tunOutput,
            tunWriteLock = tunWriteLock,
            clientPort = clientPort,
            remoteAddress = remoteAddress,
            remotePort = remotePort,
            inspector = inspector,
            tunMtu = TUN_MTU,
            onReplyTooLarge = { }
        )

        if (!created.isOpen) {
            created.close()
            Log.w(TAG, "Could not open a relay socket for $key")
            return null
        }

        val replaced = sessions.put(key, created)
        replaced?.close()
        created.start()
        return created
    }

    /** Closes sessions that have gone idle so a long session doesn't leak sockets. */
    private fun startSessionReaper() {
        serviceScope.launch {
            while (serviceScope.isActive) {
                delay(REAPER_INTERVAL_MS)
                val now = System.currentTimeMillis()
                sessions.forEach { (key, session) ->
                    val idle = now - session.lastActivityMillis > SESSION_IDLE_TIMEOUT_MS
                    if (!session.isOpen || idle) {
                        // Remove-if-same: a session replaced meanwhile must not be
                        // closed by this loop.
                        if (sessions.remove(key, session)) {
                            session.close()
                        }
                    }
                }
            }
        }
    }

    /** `Minecraft 1.26.40 (com.mojang.minecraftpe)` for the UI, or the raw package. */
    private fun appDescription(): String {
        val label = InstalledApps.label(this, relayedPackage)
        val version = InstalledApps.versionName(this, relayedPackage)
        return when {
            label != null && version != null -> "$label $version ($relayedPackage)"
            label != null -> "$label ($relayedPackage)"
            else -> relayedPackage
        }
    }

    private fun startStatsPublisher() {
        serviceScope.launch {
            while (serviceScope.isActive) {
                delay(STATS_INTERVAL_MS)
                val snapshot = publishStats()
                maybeUpdateNotification(snapshot)
            }
        }
    }

    private fun publishStats(): ServiceStats {
        val now = System.currentTimeMillis()
        val elapsed = if (lastPublishAt == 0L) STATS_INTERVAL_MS else (now - lastPublishAt).coerceAtLeast(1)
        lastPublishAt = now

        val flows = sessions.values.map { session ->
            val view = session.inspector
            val snapshot = view.stats.snapshot()
            FlowView(
                remoteLabel = "${view.remoteAddress.hostAddress}:${view.remotePort}",
                phase = view.phase.name,
                serverDescription = view.describeServer(),
                rttLastMs = snapshot.rttLastMs,
                rttMinMs = snapshot.rttMinMs,
                rttAvgMs = snapshot.rttAvgMs,
                rttMaxMs = snapshot.rttMaxMs,
                jitterMs = snapshot.jitterMs,
                arrivalJitterMs = snapshot.arrivalJitterMs,
                upstreamLossPermille = snapshot.upstreamLossPermille,
                downstreamLossPermille = snapshot.downstreamLossPermille,
                upstreamBytes = snapshot.upstreamBytes,
                downstreamBytes = snapshot.downstreamBytes,
                packets = snapshot.upstreamPackets + snapshot.downstreamPackets,
                mtu = view.negotiatedMtu,
                raknetProtocol = view.raknetClientProtocol,
                encryptionStarted = view.encryptionStarted,
                loginDescription = view.describeLogin(),
                protocolComparison = view.describeProtocols(),
                encryptionDescription = view.describeEncryption(),
                idleSeconds = (now - session.lastActivityMillis) / 1000
            )
        }

        val totalUp = flows.sumOf { it.upstreamBytes }
        val totalDown = flows.sumOf { it.downstreamBytes }
        lastUpstreamRate = ((totalUp - lastPublishedUpBytes) * 1000 / elapsed).coerceAtLeast(0)
        lastDownstreamRate = ((totalDown - lastPublishedDownBytes) * 1000 / elapsed).coerceAtLeast(0)
        lastPublishedUpBytes = totalUp
        lastPublishedDownBytes = totalDown

        val stats = ServiceStats(
            vpnEstablished = vpnEstablished,
            targetLabel = targetLabel,
            scopeDescription = scopeDescription,
            scopedToTarget = scopedToTarget,
            resolvedAddresses = resolvedAddresses,
            appDescription = appDescription(),
            flows = flows,
            totalUpstreamBytes = totalUp,
            totalDownstreamBytes = totalDown,
            upstreamBytesPerSecond = lastUpstreamRate,
            downstreamBytesPerSecond = lastDownstreamRate,
            droppedNonUdp = droppedNonUdp,
            icmpRejectionsSent = icmpRejections,
            relayErrors = relayErrors + sessions.values.sumOf { it.relayErrors },
            oversizedReplies = sessions.values.sumOf { it.oversizedReplies },
            lastError = startupError
        )
        StatsRegistry.publish(stats)
        return stats
    }

    // ------------------------------------------------------------- notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MinecraftVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_celestia)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_action_stop), stopIntent)
            .build()
    }

    private fun promoteToForeground(notificationText: String) {
        val notification = buildNotification(notificationText)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun maybeUpdateNotification(stats: ServiceStats) {
        val text = notificationText(stats)
        if (text == lastNotificationText) return
        lastNotificationText = text
        try {
            NotificationManagerCompat.from(this).notify(notificationId, buildNotification(text))
        } catch (e: SecurityException) {
            // Notification permission was revoked: the service must keep relaying
            // regardless, so this is not an error worth stopping for.
            Log.w(TAG, "Could not update notification: ${e.message}")
        }
    }

    private fun notificationText(stats: ServiceStats): String {
        if (stats.lastError != null) return stats.lastError!!
        if (!stats.vpnEstablished) return getString(R.string.notification_starting)
        if (stats.flows.isEmpty()) return getString(R.string.notification_idle)

        val flow = stats.flows.first()
        val parts = mutableListOf<String>()
        if (stats.targetLabel.isNotBlank()) parts += stats.targetLabel
        parts += stats.flows.size.let { if (it == 1) "1 flow" else "$it flows" }
        parts += Format.rtt(flow.rttLastMs)
        parts += "loss ${Format.loss(max(flow.upstreamLossPermille, flow.downstreamLossPermille))}"
        parts += "↑${Format.rate(stats.upstreamBytesPerSecond)} ↓${Format.rate(stats.downstreamBytesPerSecond)}"
        if (flow.phase != ConnectionPhase.IDLE.name) parts += flow.phase.lowercase()
        return parts.joinToString(" · ")
    }

    // ------------------------------------------------------------------ lifecycle

    private fun fail(message: String) {
        startupError = message
        Log.e(TAG, message)
        StatsRegistry.publish(ServiceStats.idle().copy(lastError = message))
        // Nothing to relay: don't leave a dead foreground service behind.
        stopSelf()
    }

    private fun publishError(message: String) {
        startupError = message
    }

    override fun onRevoke() {
        // The user (or another VPN app) disconnected us. Android tears the tunnel
        // down after this callback.
        Log.i(TAG, "VPN revoked by the system")
        super.onRevoke()
        stopSelf()
    }

    override fun onDestroy() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        // Cancel rather than join: a relay coroutine can be parked in a blocking
        // read, and onDestroy runs on the main thread. Closing the TUN fd below is
        // what actually unblocks those reads.
        serviceJob.cancel()
        try {
            vpnInterface?.close()
        } catch (_: IOException) {
        }
        vpnInterface = null
        vpnEstablished = false
        StatsRegistry.publish(StatsRegistry.latest.copy(vpnEstablished = false))
        super.onDestroy()
    }

    private fun sessionKey(clientPort: Int, remoteAddress: InetAddress, remotePort: Int) =
        "$clientPort:${remoteAddress.hostAddress}:$remotePort"

    companion object {
        private const val TAG = "CelestiaProxy"

        const val ACTION_STOP = "com.proxy.mcbedrock.action.STOP"
        const val CHANNEL_ID = "celestia_proxy"
        const val NOTIFICATION_ID = 1001

        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val TUN_MTU = 1500
        private const val MAX_TUN_PACKET = 32_767

        private const val PROTOCOL_TCP = 6
        private const val PROTOCOL_UDP = 17
        private const val ICMP_CODE_ADMIN_PROHIBITED = 13

        private const val SESSION_IDLE_TIMEOUT_MS = 2 * 60 * 1000L
        private const val REAPER_INTERVAL_MS = 30_000L
        private const val STATS_INTERVAL_MS = 1_000L

        // DNS has to be declared or hostname resolution inside Minecraft breaks
        // while the VPN is up. These are only used for names the client resolves
        // itself (e.g. play.example.com); they are not used for gameplay traffic.
        private const val DNS_PRIMARY = "1.1.1.1"
        private const val DNS_SECONDARY = "8.8.8.8"

        private const val ERROR_APP_MISSING_SUFFIX = " is not installed — pick another app."
        private const val ERROR_VPN_DENIED =
            "VPN permission was not granted, so the proxy cannot capture the game's traffic."
    }
}
