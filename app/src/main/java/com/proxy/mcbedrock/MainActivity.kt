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
import android.text.SpannableString
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.proxy.mcbedrock.net.ConnectionPhase
import com.proxy.mcbedrock.net.LanDiscovery
import com.proxy.mcbedrock.net.ServerTarget
import com.proxy.mcbedrock.net.TargetRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The control panel: pick what to relay, pick where, start/stop, and read the
 * session back.
 *
 * The screen is deliberately organised the way the relay works — target, app,
 * session — rather than as one flat list of numbers, and every value that has a
 * severity (phase, ping, loss) is coloured, so a problem is visible without
 * reading.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var config: RelayConfigStore

    private lateinit var statusPill: TextView
    private lateinit var statusPillDetail: TextView
    private lateinit var hostField: TextInputLayout
    private lateinit var hostInput: TextInputEditText
    private lateinit var portField: TextInputLayout
    private lateinit var portInput: TextInputEditText
    private lateinit var serverStatus: TextView
    private lateinit var scopeSwitch: MaterialSwitch
    private lateinit var recentsLabel: TextView
    private lateinit var recentsGroup: ChipGroup
    private lateinit var appIcon: ImageView
    private lateinit var appLabel: TextView
    private lateinit var appPackage: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var sessionPill: TextView
    private lateinit var emptyHint: TextView
    private lateinit var summaryLine: TextView
    private lateinit var statsText: TextView

    private val ui = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render(StatsRegistry.latest)
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Whether or not the dialog was accepted, establish() decides: it returns
        // null when permission is missing, and the service reports that.
        launchRelay()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        config = RelayConfigStore(this)
        bindViews()
        wireActions()
        loadSavedState()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.cancel()
    }

    // ------------------------------------------------------------------ wiring

    private fun bindViews() {
        statusPill = findViewById(R.id.statusPill)
        statusPillDetail = findViewById(R.id.statusPillDetail)
        hostField = findViewById(R.id.hostField)
        hostInput = findViewById(R.id.hostInput)
        portField = findViewById(R.id.portField)
        portInput = findViewById(R.id.portInput)
        serverStatus = findViewById(R.id.serverStatus)
        scopeSwitch = findViewById(R.id.scopeSwitch)
        recentsLabel = findViewById(R.id.recentsLabel)
        recentsGroup = findViewById(R.id.recentsGroup)
        appIcon = findViewById(R.id.appIcon)
        appLabel = findViewById(R.id.appLabel)
        appPackage = findViewById(R.id.appPackage)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        sessionPill = findViewById(R.id.sessionPill)
        emptyHint = findViewById(R.id.emptyHint)
        summaryLine = findViewById(R.id.summaryLine)
        statsText = findViewById(R.id.statsText)
    }

    private fun wireActions() {
        startButton.setOnClickListener {
            if (!saveTarget(quiet = true)) {
                toast(getString(R.string.error_host))
                return@setOnClickListener
            }
            requestNotificationsIfNeeded()
            val prepare = VpnService.prepare(this)
            if (prepare != null) {
                vpnPermission.launch(prepare)
            } else {
                launchRelay()
            }
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, MinecraftVpnService::class.java))
            StatsRegistry.reset()
            render(StatsRegistry.latest)
        }

        findViewById<MaterialButton>(R.id.saveServerButton).setOnClickListener {
            if (saveTarget(quiet = false)) toast(getString(R.string.server_saved))
        }

        findViewById<MaterialButton>(R.id.scanButton).setOnClickListener { scanNetwork() }

        findViewById<MaterialButton>(R.id.changeAppButton).setOnClickListener { openAppPicker() }

        scopeSwitch.setOnCheckedChangeListener { _, checked ->
            config.scopeToServer = checked
            updateServerStatus()
        }

        hostInput.doAfterTextChanged { updateServerStatus() }
        portInput.doAfterTextChanged { updateServerStatus() }
    }

    private fun loadSavedState() {
        if (hostInput.text.isNullOrBlank()) hostInput.setText(config.host)
        if (portInput.text.isNullOrBlank()) portInput.setText(config.port.toString())
        scopeSwitch.isChecked = config.scopeToServer
        refreshAppRow()
        renderRecents()
        updateServerStatus()
    }

    // ----------------------------------------------------------------- targets

    /** Validates and stores the address. Returns false when it is not usable. */
    private fun saveTarget(quiet: Boolean): Boolean {
        val rawHost = hostInput.text?.toString().orEmpty()
        val hostProblem = TargetRules.validateHost(rawHost)
        if (hostProblem != null) {
            hostField.error = hostProblem
            return false
        }
        hostField.error = null

        val embedded = TargetRules.hostEmbeddedPort(rawHost)
        val port = embedded ?: TargetRules.parsePort(portInput.text?.toString().orEmpty())
        if (port == null) {
            portField.error = getString(R.string.error_host)
            return false
        }
        portField.error = null

        val normalised = TargetRules.normaliseHost(rawHost)
        config.saveTarget(normalised, port)
        if (!quiet) {
            hostInput.setText(normalised)
            portInput.setText(port.toString())
            renderRecents()
            updateServerStatus()
        }
        return true
    }

    private fun updateServerStatus() {
        val rawHost = hostInput.text?.toString().orEmpty()
        val problem = TargetRules.validateHost(rawHost)
        val port = TargetRules.parsePort(portInput.text?.toString().orEmpty())
        val scoping = scopeSwitch.isChecked

        when {
            !scoping -> {
                serverStatus.text = "Every UDP flow from the selected app is relayed; " +
                    "the address above is only used to label what you see."
                serverStatus.setTextColor(color(R.color.text_dim))
            }
            problem != null -> {
                serverStatus.text = "Scoped mode needs a server address: $problem"
                serverStatus.setTextColor(color(R.color.warn))
            }
            port == null -> {
                serverStatus.text = "Scoped mode needs a port between 1 and 65535"
                serverStatus.setTextColor(color(R.color.warn))
            }
            else -> {
                val saved = config.hasTarget() && config.host == TargetRules.normaliseHost(rawHost) && config.port == port
                serverStatus.text = buildString {
                    append(if (saved) "Scoped to " else "Will scope to ")
                    append(TargetRules.normaliseHost(rawHost))
                    if (port != ServerTarget.DEFAULT_PORT) append(":").append(port)
                    append(" · resolved when the relay starts")
                }
                serverStatus.setTextColor(color(R.color.text_dim))
            }
        }
    }

    private fun renderRecents() {
        recentsGroup.removeAllViews()
        val recents = config.recents().filterNot { it.host == config.host && it.port == config.port }
        recentsLabel.visibility = if (recents.isEmpty() && config.recents().isEmpty()) View.GONE else View.VISIBLE
        recents.forEach { target ->
            val chip = Chip(this).apply {
                text = target.label()
                isCheckable = false
                contentDescription = target.describe()
                setOnClickListener {
                    hostInput.setText(target.host)
                    portInput.setText(target.port.toString())
                    config.saveTarget(target.host, target.port)
                    renderRecents()
                    updateServerStatus()
                }
            }
            recentsGroup.addView(chip)
        }
    }

    private fun scanNetwork() {
        serverStatus.setTextColor(color(R.color.accent))
        serverStatus.text = getString(R.string.scan_running)
        ui.launch {
            val found = withContext(Dispatchers.IO) {
                LanDiscovery.scan(timeoutMillis = SCAN_TIMEOUT_MS)
            }
            if (found.isEmpty()) {
                serverStatus.setTextColor(color(R.color.warn))
                serverStatus.text = getString(R.string.scan_empty)
                return@launch
            }
            updateServerStatus()
            val labels = found.map { it.describe() }.toTypedArray()
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.scan_title))
                .setItems(labels) { _, which ->
                    val picked = found[which]
                    hostInput.setText(picked.host)
                    portInput.setText(picked.port.toString())
                    config.saveTarget(picked.host, picked.port)
                    renderRecents()
                    updateServerStatus()
                    toast(getString(R.string.server_saved))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // --------------------------------------------------------------- app picker

    private fun openAppPicker() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_app_picker, null)
        val search = view.findViewById<TextInputEditText>(R.id.appSearchInput)
        val showAll = view.findViewById<MaterialSwitch>(R.id.showAllSwitch)
        val list = view.findViewById<RecyclerView>(R.id.appList)
        val empty = view.findViewById<TextView>(R.id.appsEmpty)

        val adapter = AppAdapter { entry ->
            config.packageName = entry.packageName
            refreshAppRow()
            toast(entry.label)
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        fun reload() {
            val source = if (showAll.isChecked) InstalledApps.launchable(this) else InstalledApps.minecraftCandidates(this)
            val query = search.text?.toString()?.trim()?.lowercase().orEmpty()
            val filtered = if (query.isEmpty()) source else source.filter {
                it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
            adapter.submit(filtered)
            empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        }

        search.doAfterTextChanged { reload() }
        showAll.setOnCheckedChangeListener { _, _ -> reload() }
        reload()

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_apps_title))
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshAppRow() {
        val packageName = config.packageName
        appIcon.setImageDrawable(InstalledApps.icon(this, packageName))
        appLabel.text = InstalledApps.label(this, packageName) ?: packageName
        val version = InstalledApps.versionName(this, packageName)
        appPackage.text = listOfNotNull(packageName, version?.let { "v$it" }).joinToString(" · ")
        if (!InstalledApps.isInstalled(this, packageName)) {
            appPackage.text = "$packageName · not installed"
            appPackage.setTextColor(color(R.color.error))
        } else {
            appPackage.setTextColor(color(R.color.text_dim))
        }
    }

    // ------------------------------------------------------------------ service

    private fun launchRelay() {
        val packageName = config.packageName
        if (!InstalledApps.isInstalled(this, packageName)) {
            statusPill.text = getString(R.string.status_error)
            statusPill.setBackgroundResource(R.drawable.bg_pill_error)
            statusPillDetail.text = getString(R.string.error_mc_missing)
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, MinecraftVpnService::class.java))
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    // ------------------------------------------------------------------ render

    private fun render(stats: ServiceStats) {
        val running = stats.vpnEstablished
        startButton.isEnabled = !running
        stopButton.isEnabled = running

        when {
            stats.lastError != null -> {
                pill(statusPill, getString(R.string.status_error), R.drawable.bg_pill_error)
                statusPillDetail.text = stats.lastError
            }
            running && stats.flows.isEmpty() -> {
                pill(statusPill, getString(R.string.status_waiting), R.drawable.bg_pill_warn)
                statusPillDetail.text = if (stats.targetLabel.isNotBlank()) "Target ${stats.targetLabel}" else ""
            }
            running -> {
                val best = stats.bestRttMs
                pill(statusPill, getString(R.string.status_running), R.drawable.bg_pill_ok)
                statusPillDetail.text = buildString {
                    if (stats.targetLabel.isNotBlank()) append(stats.targetLabel)
                    if (best >= 0) {
                        if (isNotEmpty()) append(" · ")
                        append(Format.rtt(best))
                    }
                }
            }
            else -> {
                pill(statusPill, getString(R.string.status_idle), R.drawable.bg_pill_neutral)
                statusPillDetail.text = ""
            }
        }

        if (stats.flows.isEmpty()) {
            emptyHint.visibility = if (running) View.VISIBLE else View.GONE
            summaryLine.visibility = View.GONE
            statsText.visibility = View.GONE
            sessionPill.text = getString(R.string.status_idle)
            sessionPill.setBackgroundResource(R.drawable.bg_pill_neutral)
            return
        }

        emptyHint.visibility = View.GONE
        summaryLine.visibility = View.VISIBLE
        statsText.visibility = View.VISIBLE

        summaryLine.text = buildString {
            append("Tunnel: ")
            append(stats.scopeDescription.ifBlank { "all traffic from the app" })
        }

        val flow = stats.flows.first()
        val phase = phaseOf(flow)
        sessionPill.text = flow.phase
        sessionPill.setBackgroundResource(backgroundForPhase(phase))

        val sb = SpannableStringBuilder()
        row(sb, "server", flow.serverDescription, color(R.color.text_secondary))
        row(sb, "login", flow.loginDescription, color(R.color.text_secondary))
        flow.protocolComparison?.let { row(sb, "versions", it, versionColor(it)) }
        row(sb, "handshake", flow.encryptionDescription, if (flow.encryptionStarted) color(R.color.warn) else color(R.color.text_dim))
        row(sb, "ping", "${Format.rtt(flow.rttLastMs)}  min ${Format.rtt(flow.rttMinMs)} / avg ${Format.rtt(flow.rttAvgMs)} / max ${Format.rtt(flow.rttMaxMs)}", rttColor(flow.rttLastMs))
        row(sb, "jitter", "${flow.jitterMs}ms (arrival ${flow.arrivalJitterMs}ms)", rttColor(flow.jitterMs))
        row(
            sb,
            "loss",
            "up ${Format.loss(flow.upstreamLossPermille)}  down ${Format.loss(flow.downstreamLossPermille)}",
            lossColor(maxOf(flow.upstreamLossPermille, flow.downstreamLossPermille))
        )
        row(sb, "traffic", "↑ ${Format.bytes(flow.upstreamBytes)}  ↓ ${Format.bytes(flow.downstreamBytes)}  (${flow.packets} packets)", color(R.color.text_secondary))
        val raknet = buildString {
            if (flow.mtu > 0) append("MTU ${flow.mtu}")
            if (flow.raknetProtocol > 0) {
                if (isNotEmpty()) append("  ")
                append("raknet ${flow.raknetProtocol}")
            }
            if (isNotEmpty()) append("  ")
            append("idle ${flow.idleSeconds}s")
        }
        row(sb, "session", raknet.trim(), color(R.color.text_dim))

        if (stats.flows.size > 1) {
            sb.append('\n')
            sb.append(colored("+${stats.flows.size - 1} more flow(s): ", color(R.color.text_dim)))
            sb.append(colored(stats.flows.drop(1).joinToString(", ") { it.remoteLabel }, color(R.color.text_dim)))
        }
        statsText.text = sb
    }

    private fun row(sb: SpannableStringBuilder, name: String, value: String, valueColor: Int) {
        sb.append(colored(name.padEnd(10), color(R.color.text_dim)))
        sb.append(colored(value, valueColor))
        sb.append('\n')
    }

    private fun colored(text: String, color: Int): CharSequence = SpannableString(text).apply {
        setSpan(ForegroundColorSpan(color), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun pill(view: TextView, text: String, background: Int) {
        view.text = text
        view.setBackgroundResource(background)
    }

    private fun phaseOf(flow: FlowView): ConnectionPhase = try {
        ConnectionPhase.valueOf(flow.phase)
    } catch (_: IllegalArgumentException) {
        ConnectionPhase.IDLE
    }

    private fun backgroundForPhase(phase: ConnectionPhase): Int = when (phase) {
        ConnectionPhase.PLAY, ConnectionPhase.ENCRYPTED -> R.drawable.bg_pill_ok
        ConnectionPhase.GAME_LOGIN, ConnectionPhase.RAKNET_HANDSHAKE, ConnectionPhase.SERVER_PING -> R.drawable.bg_pill_warn
        ConnectionPhase.DISCONNECTED -> R.drawable.bg_pill_error
        ConnectionPhase.IDLE -> R.drawable.bg_pill_neutral
    }

    private fun versionColor(text: String): Int =
        if (text.contains("differ")) color(R.color.warn) else color(R.color.ok)

    private fun rttColor(millis: Int): Int = when {
        millis < 0 -> color(R.color.text_dim)
        millis < 80 -> color(R.color.ok)
        millis < 160 -> color(R.color.warn)
        else -> color(R.color.error)
    }

    private fun lossColor(permille: Int): Int = when {
        permille <= 0 -> color(R.color.ok)
        permille < 20 -> color(R.color.warn)
        else -> color(R.color.error)
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------- app list rows

    private class AppAdapter(
        private val onPick: (AppEntry) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.Row>() {

        private val entries = ArrayList<AppEntry>()

        @Suppress("NotifyDataSetChanged")
        fun submit(items: List<AppEntry>) {
            entries.clear()
            entries.addAll(items)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
            Row(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: Row, position: Int) {
            val entry = entries[position]
            holder.icon.setImageDrawable(entry.icon)
            holder.label.text = entry.label
            holder.packageName.text = entry.packageName
            holder.version.text = entry.versionName?.let { "v$it" }.orEmpty()
            if (entry.isMinecraftLike) {
                holder.label.setTypeface(null, Typeface.BOLD)
            } else {
                holder.label.setTypeface(null, Typeface.NORMAL)
            }
            holder.itemView.setOnClickListener { onPick(entry) }
        }

        class Row(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appRowIcon)
            val label: TextView = view.findViewById(R.id.appRowLabel)
            val packageName: TextView = view.findViewById(R.id.appRowPackage)
            val version: TextView = view.findViewById(R.id.appRowVersion)
        }
    }

    private companion object {
        const val REFRESH_MS = 500L
        const val SCAN_TIMEOUT_MS = 1500
        const val REQUEST_NOTIFICATIONS = 102
    }
}
