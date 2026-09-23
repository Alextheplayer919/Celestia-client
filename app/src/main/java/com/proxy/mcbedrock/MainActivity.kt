package com.proxy.mcbedrock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * The whole UI: start/stop the proxy and show what it is currently seeing.
 *
 * Everything shown here comes from [StatsRegistry], which the service updates
 * once a second while it runs, so there is no IPC or binding to keep in sync.
 */
class MainActivity : AppCompatActivity() {

    private val vpnPermissionRequestCode = 100
    private val notificationPermissionRequestCode = 101

    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render(StatsRegistry.latest)
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }

        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 24, 0, 24)
        }

        startButton = Button(this).apply {
            text = "Start proxy"
            setOnClickListener { requestVpnPermission() }
        }

        stopButton = Button(this).apply {
            text = "Stop proxy"
            setOnClickListener {
                stopService(Intent(this@MainActivity, MinecraftVpnService::class.java))
            }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            addView(startButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(stopButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        statsView = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, 32, 0, 0)
        }

        root.addView(title)
        root.addView(statusView)
        root.addView(buttons)
        root.addView(
            ScrollView(this).apply { addView(statsView) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)

        if (savedInstanceState == null) {
            requestNotificationPermissionIfNeeded()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun render(stats: ServiceStats) {
        val running = stats.vpnEstablished
        startButton.isEnabled = !running
        stopButton.isEnabled = running

        val minecraftInstalled = isMinecraftInstalled()
        statusView.text = when {
            stats.lastError != null -> stats.lastError
            running -> "Running — relaying Minecraft's UDP traffic."
            !minecraftInstalled -> "Minecraft ($MINECRAFT_PACKAGE) is not installed."
            else -> "Stopped."
        }

        val text = StringBuilder()
        text.append("VPN          ").append(if (stats.vpnEstablished) "established" else "not running").append('\n')
        text.append("Flows        ").append(stats.flows.size).append('\n')
        text.append("Throughput   ↑ ").append(Format.rate(stats.upstreamBytesPerSecond))
            .append("   ↓ ").append(Format.rate(stats.downstreamBytesPerSecond)).append('\n')
        text.append("Total        ↑ ").append(Format.bytes(stats.totalUpstreamBytes))
            .append("   ↓ ").append(Format.bytes(stats.totalDownstreamBytes)).append('\n')
        text.append("Not relayed  ").append(stats.droppedNonUdp).append(" packets")
            .append(" (").append(stats.icmpRejectionsSent).append(" ICMP)\n")
        if (stats.relayErrors > 0 || stats.oversizedReplies > 0) {
            text.append("Errors       relay=").append(stats.relayErrors)
                .append(" oversized=").append(stats.oversizedReplies).append('\n')
        }

        stats.flows.forEach { flow ->
            text.append('\n')
            text.append("── ").append(flow.remoteLabel).append('\n')
            text.append("  Server     ").append(flow.serverDescription).append('\n')
            text.append("  Phase      ").append(flow.phase)
                .append(if (flow.encryptionStarted) " (game traffic encrypted)" else " (cleartext)").append('\n')
            text.append("  Login      ").append(flow.loginDescription).append('\n')
            flow.protocolComparison?.let { text.append("  Versions   ").append(it).append('\n') }
            text.append("  Handshake  ").append(flow.encryptionDescription).append('\n')
            text.append("  Ping       ").append(Format.rtt(flow.rttLastMs))
                .append("  min ").append(Format.rtt(flow.rttMinMs))
                .append(" / avg ").append(Format.rtt(flow.rttAvgMs))
                .append(" / max ").append(Format.rtt(flow.rttMaxMs)).append('\n')
            text.append("  Jitter     ").append(flow.jitterMs).append("ms")
                .append("  (packet arrival ").append(flow.arrivalJitterMs).append("ms)\n")
            text.append("  Loss       up ").append(Format.loss(flow.upstreamLossPermille))
                .append("  down ").append(Format.loss(flow.downstreamLossPermille)).append('\n')
            text.append("  Traffic    ↑ ").append(Format.bytes(flow.upstreamBytes))
                .append("   ↓ ").append(Format.bytes(flow.downstreamBytes))
                .append("  (").append(flow.packets).append(" packets)\n")
            if (flow.mtu > 0 || flow.raknetProtocol > 0) {
                text.append("  RakNet     ")
                if (flow.mtu > 0) text.append("MTU ").append(flow.mtu).append("  ")
                if (flow.raknetProtocol > 0) text.append("protocol ").append(flow.raknetProtocol)
                text.append('\n')
            }
            text.append("  Idle       ").append(flow.idleSeconds).append("s\n")
        }

        text.append("\nRead-only by design: this proxy relays Minecraft's UDP traffic\n")
        text.append("unchanged and only observes it. Ping/jitter/loss come from RakNet's\n")
        text.append("own ping and acknowledgement packets; version and account details\n")
        text.append("come from the login exchange, which is cleartext. Game packets are\n")
        text.append("encrypted end-to-end with a key derived between the client and the\n")
        text.append("server, so a relay cannot read coordinates, chat or inventory.")

        statsView.text = text.toString()
    }

    private fun requestVpnPermission() {
        if (!isMinecraftInstalled()) {
            statusView.text = "Minecraft ($MINECRAFT_PACKAGE) is not installed — nothing to proxy."
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, vpnPermissionRequestCode)
        } else {
            onVpnPermissionGranted()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionRequestCode)
        }
    }

    @Deprecated("Deprecated in the platform, still the simplest way to probe a package.")
    private fun isMinecraftInstalled(): Boolean = try {
        packageManager.getPackageInfo(MINECRAFT_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnPermissionRequestCode && resultCode == RESULT_OK) {
            onVpnPermissionGranted()
        }
    }

    private fun onVpnPermissionGranted() {
        // startForegroundService: the service promotes itself to the foreground
        // immediately, which is what keeps the tunnel alive while the game runs.
        ContextCompat.startForegroundService(this, Intent(this, MinecraftVpnService::class.java))
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 500L
        private const val MINECRAFT_PACKAGE = "com.mojang.minecraftpe"
    }
}
