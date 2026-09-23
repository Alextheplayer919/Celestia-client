package com.proxy.mcbedrock.hud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.proxy.mcbedrock.MainActivity
import com.proxy.mcbedrock.R
import com.proxy.mcbedrock.StatsRegistry
import com.proxy.mcbedrock.music.NowPlaying
import com.proxy.mcbedrock.music.NowPlayingBridge
import com.proxy.mcbedrock.music.NowPlayingText
import com.proxy.mcbedrock.net.ConnectionPhase
import kotlin.math.abs

/**
 * Draws the stats HUD over whatever is on screen, and hosts the click panel that
 * configures it.
 *
 * Three overlay windows, all `TYPE_APPLICATION_OVERLAY`:
 *  - the HUD itself: a small non-focusable panel the user can drag;
 *  - a floating button that opens the panel (optional);
 *  - the click panel, only while it is open.
 *
 * Everything it shows comes from [StatsRegistry], which the relay publishes; this
 * service never touches the tunnel or the packets. Touches are only accepted on the
 * HUD and the button, and neither takes input focus, so the game underneath keeps
 * working normally.
 */
class HudOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var settings: HudSettings
    private val handler = Handler(Looper.getMainLooper())
    private val history = StatsHistory(capacity = HISTORY_SAMPLES)

    private var hudView: View? = null
    private var hudParams: WindowManager.LayoutParams? = null
    private var hudLines: LinearLayout? = null
    private var hudTitle: TextView? = null
    private var hudGraph: SparklineView? = null
    private var musicBlock: View? = null
    private var musicButtons: View? = null
    private var musicArt: android.widget.ImageView? = null
    private var musicTitle: TextView? = null
    private var musicSubtitle: TextView? = null
    private var musicProgress: android.widget.ProgressBar? = null
    private var musicTime: TextView? = null
    private var fabView: View? = null
    private var menuView: View? = null

    private var lastLineSignature: String? = null

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settings = HudSettings(this)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_MENU -> {
                if (menuView == null) openMenu() else closeMenu()
            }
            ACTION_CLOSE_MENU -> closeMenu()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            // Without the permission there is nothing to draw; say so and go away
            // rather than sitting in the notification shade doing nothing.
            Toast.makeText(this, getString(R.string.hud_permission_missing), Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (NowPlayingBridge.hasAccess(this)) {
            NowPlayingBridge.attachContext(this)
            NowPlayingBridge.start(this)
        }
        syncWindows()

        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        NowPlayingBridge.stop(this)
        removeView(menuView)
        removeView(hudView)
        removeView(fabView)
        menuView = null
        hudView = null
        fabView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------- windows

    private fun baseParams(width: Int, height: Int, touchable: Boolean): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun addHud() {
        val view = LayoutInflater.from(this).inflate(R.layout.hud_panel, null, false)
        hudLines = view.findViewById(R.id.hudLines)
        hudTitle = view.findViewById(R.id.hudTitle)
        hudGraph = view.findViewById(R.id.hudGraph)
        musicBlock = view.findViewById(R.id.hudMusic)
        musicButtons = view.findViewById(R.id.hudMusicButtons)
        musicArt = view.findViewById(R.id.musicArt)
        musicTitle = view.findViewById(R.id.musicTitle)
        musicSubtitle = view.findViewById(R.id.musicSubtitle)
        musicProgress = view.findViewById(R.id.musicProgress)
        musicTime = view.findViewById(R.id.musicTime)
        view.findViewById<View>(R.id.musicPrev).setOnClickListener { NowPlayingBridge.previous() }
        view.findViewById<View>(R.id.musicPlayPause).setOnClickListener { NowPlayingBridge.playPause() }
        view.findViewById<View>(R.id.musicNext).setOnClickListener { NowPlayingBridge.next() }
        val params = baseParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true
        )
        // A tap on the panel is the shortcut to the click panel; dragging moves it.
        attachDrag(view, params, isHud = true) { if (menuView == null) openMenu() else closeMenu() }
        addView(view, params)
        hudView = view
        hudParams = params
        restoreHudPosition()
    }

    /** Puts the panel back where the user left it. */
    private fun restoreHudPosition() {
        val view = hudView ?: return
        val params = hudParams ?: return
        val metrics = resources.displayMetrics
        val (x, y) = HudLayout.anchorPosition(
            settings.corner,
            metrics.widthPixels,
            metrics.heightPixels,
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1)
        )
        params.x = x + settings.offsetX
        params.y = y + settings.offsetY
        safeUpdate(view, params)
    }

    private fun addFab() {
        val view = LayoutInflater.from(this).inflate(R.layout.hud_fab, null, false)
        val size = (FAB_SIZE_DP * resources.displayMetrics.density).toInt()
        val params = baseParams(size, size, touchable = true)
        val metrics = resources.displayMetrics
        val (anchorX, anchorY) = HudLayout.anchorPosition(
            HudCorner.BOTTOM_END,
            metrics.widthPixels,
            metrics.heightPixels,
            size,
            size,
            20,
            120
        )
        val savedX = settings.fabX
        val savedY = settings.fabY
        params.x = if (savedX >= 0) savedX else anchorX
        params.y = if (savedY >= 0) savedY else anchorY

        // Tap opens or closes the click panel; drag moves the button.
        attachDrag(view, params, isHud = false) {
            if (menuView == null) openMenu() else closeMenu()
        }
        addView(view, params)
        fabView = view
    }

    /**
     * Drag *and* tap on the same view.
     *
     * The touch listener consumes the gesture, so an `OnClickListener` on the same
     * view would never fire — which is exactly what happened to the round button
     * before. Instead the tap is recognised here: a press that never moves beyond
     * the touch slop counts as a tap and calls [onTap], anything else is a drag that
     * gets clamped on screen and, by default, snaps to the nearest corner.
     *
     * The window is never re-created, only moved.
     */
    private fun attachDrag(
        view: View,
        params: WindowManager.LayoutParams,
        isHud: Boolean,
        onTap: (() -> Unit)? = null
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false
        val slop = TOUCH_SLOP_DP * resources.displayMetrics.density

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!dragged && abs(event.rawX - touchX) + abs(event.rawY - touchY) > slop) dragged = true
                    if (dragged) {
                        val metrics = resources.displayMetrics
                        val (x, y) = HudLayout.clamp(
                            startX + dx,
                            startY + dy,
                            metrics.widthPixels,
                            metrics.heightPixels,
                            view.width.coerceAtLeast(1),
                            view.height.coerceAtLeast(1)
                        )
                        params.x = x
                        params.y = y
                        safeUpdate(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragged) {
                        settle(view, params, isHud)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        onTap?.invoke()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun settle(view: View, params: WindowManager.LayoutParams, isHud: Boolean) {
        val metrics = resources.displayMetrics
        val (position, corner) = HudLayout.settle(
            params.x,
            params.y,
            metrics.widthPixels,
            metrics.heightPixels,
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            snapToCorner = isHud && settings.snapToCorner
        )
        params.x = position.first
        params.y = position.second
        safeUpdate(view, params)
        if (isHud) {
            settings.offsetX = 0
            settings.offsetY = 0
            settings.corner = corner
        } else {
            settings.fabX = position.first
            settings.fabY = position.second
        }
    }

    private fun safeUpdate(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "Could not move overlay: ${e.message}")
        }
    }

    private fun addView(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            // Some OEM builds refuse overlays even with the permission; losing the
            // HUD must never take the relay down with it.
            Log.e(TAG, "Could not add overlay window", e)
            Toast.makeText(this, getString(R.string.hud_permission_missing), Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    /**
     * Adds or removes each window to match the saved settings. Everything the HUD
     * draws is optional: the stats panel and the round button can each be turned
     * off from the click panel, and turning both off leaves the relay running
     * untouched.
     */
    private fun syncWindows() {
        if (settings.statsPanelVisible) {
            if (hudView == null) addHud()
        } else if (hudView != null) {
            removeView(hudView)
            hudView = null
            hudParams = null
            hudLines = null
            hudTitle = null
            hudGraph = null
        }

        if (settings.showFab) {
            if (fabView == null) addFab()
        } else if (fabView != null) {
            removeView(fabView)
            fabView = null
        }
    }

    private fun removeView(view: View?) {
        if (view == null) return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }

    // --------------------------------------------------------------------- menu

    private fun openMenu() {
        if (menuView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.hud_click_gui, null, false)
        val params = baseParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            touchable = true
        )
        params.x = dp(24)
        params.y = dp(96)
        // Drag by the header only: the rest of the panel must keep scrolling and
        // letting its switches and buttons receive taps normally.
        view.findViewById<TextView>(R.id.clickGuiTitle)?.let { header ->
            attachDrag(header, params, isHud = false)
        }

        val list = view.findViewById<LinearLayout>(R.id.clickGuiModules)
        val corners = view.findViewById<LinearLayout>(R.id.clickGuiCorners)

        for (module in HudModule.ordered) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_hud_module, list, false)
            val name = row.findViewById<TextView>(R.id.moduleName)
            val toggle = row.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.moduleSwitch)
            name.text = module.label
            name.contentDescription = module.description
            toggle.isChecked = module.id in settings.enabledIds
            toggle.setOnCheckedChangeListener { _, checked ->
                settings.toggle(module, checked)
                lastLineSignature = null
                render()
            }
            row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
            list.addView(row)
        }

        for (corner in HudCorner.entries) {
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = cornerLabel(corner)
                isCheckable = false
                isClickable = true
                setOnClickListener {
                    settings.corner = corner
                    repositionHud()
                }
            }
            corners.addView(chip)
        }

        val overlaySwitch = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.clickGuiOverlaySwitch)
        val fabSwitch = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.clickGuiFabSwitch)
        val snapSwitch = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.clickGuiSnapSwitch)
        val musicNote = view.findViewById<TextView>(R.id.clickGuiMusicNote)
        val musicGrant = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.clickGuiMusicGrant)
        fun updateMusicAccess() {
            val granted = NowPlayingBridge.hasAccess(this)
            musicNote.visibility = if (granted) View.GONE else View.VISIBLE
            musicGrant.visibility = if (granted) View.GONE else View.VISIBLE
            if (!granted) {
                musicGrant.setOnClickListener {
                    // Straight to the system page: the wording there is Android's,
                    // which is why the note above it explains what we actually read.
                    val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { startActivity(intent) }
                }
            }
        }
        updateMusicAccess()

        overlaySwitch.isChecked = settings.statsPanelVisible
        fabSwitch.isChecked = settings.showFab
        snapSwitch.isChecked = settings.snapToCorner
        overlaySwitch.setOnCheckedChangeListener { _, checked ->
            settings.statsPanelVisible = checked
            lastLineSignature = null
            syncWindows()
        }
        fabSwitch.setOnCheckedChangeListener { _, checked ->
            settings.showFab = checked
            syncWindows()
        }
        snapSwitch.setOnCheckedChangeListener { _, checked -> settings.snapToCorner = checked }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.clickGuiReset).setOnClickListener {
            settings.resetPositions()
            repositionHud()
        }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.clickGuiClose).setOnClickListener {
            closeMenu()
        }

        addView(view, params)
        menuView = view
    }

    private fun closeMenu() {
        removeView(menuView)
        menuView = null
    }

    private fun repositionHud() {
        val view = hudView ?: return
        val params = hudParams ?: return
        val metrics = resources.displayMetrics
        val (x, y) = HudLayout.anchorPosition(
            settings.corner,
            metrics.widthPixels,
            metrics.heightPixels,
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1)
        )
        params.x = x + settings.offsetX
        params.y = y + settings.offsetY
        safeUpdate(view, params)
    }

    // ------------------------------------------------------------------- render

    private fun render() {
        val view = hudView ?: return
        val lines = hudLines ?: return
        val stats = StatsRegistry.latest
        val flow = stats.flows.firstOrNull()

        val phases = flow?.let { listOf(it) } ?: emptyList()
        history.record(
            rttMs = flow?.rttLastMs ?: -1,
            jitterMs = flow?.jitterMs ?: -1,
            upstreamBytesPerSecond = stats.upstreamBytesPerSecond,
            downstreamBytesPerSecond = stats.downstreamBytesPerSecond
        )

        val enabled = settings.enabledModules()
        val signature = buildString {
            append(enabled.joinToString(",") { it.id })
            append('|').append(stats.vpnEstablished)
            append('|').append(flow?.remoteLabel)
            append('|').append(flow?.rttLastMs).append('/').append(flow?.rttAvgMs)
            append('|').append(flow?.jitterMs).append('/').append(flow?.arrivalJitterMs)
            append('|').append(flow?.upstreamLossPermille).append('/').append(flow?.downstreamLossPermille)
            append('|').append(stats.upstreamBytesPerSecond).append('/').append(stats.downstreamBytesPerSecond)
            append('|').append(flow?.phase).append('/').append(flow?.idleSeconds)
            append('|').append(stats.targetLabel).append('|').append(stats.scopeDescription)
            append('|').append(flow?.serverDescription).append('|').append(flow?.loginDescription)
            append('|').append(flow?.encryptionDescription)
            append('|').append(flow?.relayOverheadAvgMs).append('/').append(flow?.relayOverheadP95Ms)
            append('|').append(flow?.serverRttLastMs).append('/').append(flow?.serverRttMinMs)
            append('|').append(NowPlayingBridge.current.title).append('/')
                .append(NowPlayingBridge.current.isPlaying).append('/')
                .append(NowPlayingBridge.current.positionMs / 1000)
        }
        // Rebuilding the line views only when something changed keeps this off the
        // game's critical path: idle ticks cost one string comparison.
        if (signature == lastLineSignature) return
        lastLineSignature = signature

        renderTitle(stats, flow, phases)
        renderLines(lines, stats, flow)
        renderGraph(enabled.any { it == HudModule.HISTORY })
        renderMusic(enabled.any { it == HudModule.MUSIC })
        repositionHudIfSizeChanged()
    }

    private fun renderTitle(stats: com.proxy.mcbedrock.ServiceStats, flow: com.proxy.mcbedrock.FlowView?, phases: List<Any>) {
        val title = hudTitle ?: return
        title.text = when {
            stats.lastError != null -> "Celestia · error"
            !stats.vpnEstablished -> "Celestia · relay off"
            flow == null -> "Celestia · waiting"
            else -> "Celestia · ${flow.remoteLabel}"
        }
    }

    private fun renderLines(
        container: LinearLayout,
        stats: com.proxy.mcbedrock.ServiceStats,
        flow: com.proxy.mcbedrock.FlowView?
    ) {
        container.removeAllViews()
        for (module in settings.enabledModules()) {
            val value = moduleValue(module, stats, flow) ?: continue
            val row = LayoutInflater.from(this).inflate(R.layout.hud_line, container, false)
            row.findViewById<TextView>(R.id.lineLabel).text = module.label
            val valueView = row.findViewById<TextView>(R.id.lineValue)
            valueView.text = value
            valueView.setTextColor(moduleColor(module, flow))
            container.addView(row)
        }
    }

    private fun moduleValue(
        module: HudModule,
        stats: com.proxy.mcbedrock.ServiceStats,
        flow: com.proxy.mcbedrock.FlowView?
    ): String? = when (module) {
        HudModule.PING -> flow?.let { HudText.ping(it.rttLastMs, it.rttAvgMs) } ?: "·"
        HudModule.JITTER -> flow?.let { HudText.jitter(it.jitterMs, it.arrivalJitterMs) } ?: "·"
        HudModule.LOSS -> flow?.let { HudText.loss(it.upstreamLossPermille, it.downstreamLossPermille) } ?: "·"
        HudModule.THROUGHPUT -> HudText.throughput(stats.upstreamBytesPerSecond, stats.downstreamBytesPerSecond)
        HudModule.PHASE -> flow?.let { HudText.phase(it.phase, it.idleSeconds) } ?: "not relaying"
        HudModule.TARGET -> buildString {
            append(stats.targetLabel.ifBlank { "no target set" })
            if (stats.scopedToTarget) append(" · scoped") else append(" · all traffic")
        }
        HudModule.SERVER -> flow?.serverDescription
        HudModule.LOGIN -> flow?.loginDescription
        HudModule.HANDSHAKE -> flow?.encryptionDescription
        HudModule.OVERHEAD -> flow?.let { HudText.overhead(it.relayOverheadAvgMs, it.relayOverheadP95Ms) } ?: "·"
        HudModule.SERVER_RTT -> flow?.let {
            HudText.serverRtt(it.serverRttLastMs, it.serverRttMinMs, it.serverRttMaxMs)
        } ?: "·"
        HudModule.MUSIC -> null // drawn as the widget, not as a line
        HudModule.HISTORY -> null // drawn as the graph, not as a line
    }

    /**
     * The music widget. Redrawn only when the track, position or play state actually
     * changed, and the artwork is fetched through the bridge's per-track cache, so a
     * 2.5 Hz refresh never decodes a bitmap.
     */
    private fun renderMusic(enabled: Boolean) {
        val block = musicBlock ?: return
        val buttons = musicButtons ?: return
        val state = NowPlayingBridge.current
        val show = enabled && state.hasAnything
        block.visibility = if (show) View.VISIBLE else View.GONE
        buttons.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        musicTitle?.text = NowPlayingText.fit(state.title)
        musicSubtitle?.text = NowPlayingText.fit(NowPlayingText.subtitle(state), 40)
        musicTime?.text = NowPlayingText.progress(state)
        musicProgress?.progress = ((state.progress ?: 0f) * 1000).toInt()

        musicButtons?.findViewById<android.widget.ImageButton>(R.id.musicPlayPause)?.setImageResource(
            if (state.isPlaying) R.drawable.ic_music_pause else R.drawable.ic_music_play
        )
        musicButtons?.findViewById<android.widget.ImageButton>(R.id.musicPrev)?.alpha =
            if (state.canSkipPrevious) 1f else 0.3f
        musicButtons?.findViewById<android.widget.ImageButton>(R.id.musicNext)?.alpha =
            if (state.canSkipNext) 1f else 0.3f
        musicButtons?.findViewById<android.widget.ImageButton>(R.id.musicPlayPause)?.alpha =
            if (state.canPlayPause) 1f else 0.3f

        val art = musicArt ?: return
        val bitmap = NowPlayingBridge.artwork(dp(34) * 2)
        if (bitmap != null) {
            art.setImageBitmap(bitmap)
        } else {
            art.setImageResource(R.drawable.ic_music_play)
        }
    }

    private fun renderGraph(enabled: Boolean) {
        val graph = hudGraph ?: return
        if (!enabled) {
            graph.visibility = View.GONE
            return
        }
        graph.visibility = View.VISIBLE
        val height = dp(GRAPH_HEIGHT_DP)
        val bars = history.latency.bars(height, ceiling = GRAPH_CEILING_MS)
        graph.submit(bars, moduleColor(HudModule.PING, null))
    }

    /** If a long line wrapped and the panel grew, keep it inside the screen. */
    private fun repositionHudIfSizeChanged() {
        val view = hudView ?: return
        val params = hudParams ?: return
        val metrics = resources.displayMetrics
        val (x, y) = HudLayout.clamp(
            params.x,
            params.y,
            metrics.widthPixels,
            metrics.heightPixels,
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1)
        )
        if (x != params.x || y != params.y) {
            params.x = x
            params.y = y
            safeUpdate(view, params)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun moduleColor(module: HudModule, flow: com.proxy.mcbedrock.FlowView?): Int {
        val healthy = Color.parseColor("#3DDC97")
        val warning = Color.parseColor("#FFC857")
        val bad = Color.parseColor("#FF6B6B")
        val dim = Color.parseColor("#8FA1B3")
        val neutral = Color.parseColor("#C2CFDD")
        val target = flow ?: return dim
        return when (module) {
            HudModule.PING -> when {
                target.rttLastMs < 0 -> dim
                target.rttLastMs < 80 -> healthy
                target.rttLastMs < 160 -> warning
                else -> bad
            }
            HudModule.JITTER -> when {
                target.jitterMs < 0 -> dim
                target.jitterMs < 30 -> healthy
                target.jitterMs < 80 -> warning
                else -> bad
            }
            HudModule.LOSS -> when {
                maxOf(target.upstreamLossPermille, target.downstreamLossPermille) <= 0 -> healthy
                maxOf(target.upstreamLossPermille, target.downstreamLossPermille) < 20 -> warning
                else -> bad
            }
            HudModule.PHASE -> when (target.phase) {
                ConnectionPhase.PLAY.name, ConnectionPhase.ENCRYPTED.name -> healthy
                ConnectionPhase.DISCONNECTED.name -> bad
                ConnectionPhase.IDLE.name -> dim
                else -> warning
            }
            HudModule.HANDSHAKE -> if (target.encryptionStarted) warning else dim
            HudModule.MUSIC -> neutral
            // Above ~1 ms per packet the relay is doing measurable damage; above
            // ~3 ms it is worth investigating before blaming the server.
            HudModule.OVERHEAD -> when {
                target.relayOverheadAvgMs <= 0.0 -> dim
                target.relayOverheadAvgMs < 1.0 -> healthy
                target.relayOverheadAvgMs < 3.0 -> warning
                else -> bad
            }
            HudModule.SERVER_RTT -> when {
                target.serverRttLastMs < 0 -> dim
                target.serverRttLastMs < 80 -> healthy
                target.serverRttLastMs < 160 -> warning
                else -> bad
            }
            else -> neutral
        }
    }

    private fun cornerLabel(corner: HudCorner): String = when (corner) {
        HudCorner.TOP_START -> "Top left"
        HudCorner.TOP_END -> "Top right"
        HudCorner.BOTTOM_START -> "Bottom left"
        HudCorner.BOTTOM_END -> "Bottom right"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.hud_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.hud_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, HudOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_celestia)
            .setContentTitle(getString(R.string.hud_notification_title))
            .setContentText(getString(R.string.hud_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_action_stop), stop)
            .build()
    }

    /** True when the overlay permission has been granted (used by the activity too). */
    companion object {
        private const val TAG = "HudOverlay"
        private const val CHANNEL_ID = "celestia_hud"
        private const val NOTIFICATION_ID = 1002
        private const val REFRESH_MS = 400L
        private const val HISTORY_SAMPLES = 48
        private const val GRAPH_HEIGHT_DP = 18
        private const val GRAPH_CEILING_MS = 160
        private const val FAB_SIZE_DP = 44
        private const val TOUCH_SLOP_DP = 6

        const val ACTION_TOGGLE_MENU = "com.proxy.mcbedrock.hud.TOGGLE_MENU"
        const val ACTION_CLOSE_MENU = "com.proxy.mcbedrock.hud.CLOSE_MENU"
        const val ACTION_STOP = "com.proxy.mcbedrock.hud.STOP"

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        /** Starts the HUD, or asks for the overlay permission first. */
        fun start(context: Context) {
            val intent = Intent(context, HudOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Brings the click panel up on an already-running HUD. */
        fun toggleMenu(context: Context) {
            context.startService(Intent(context, HudOverlayService::class.java).setAction(ACTION_TOGGLE_MENU))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HudOverlayService::class.java))
        }
    }
}
