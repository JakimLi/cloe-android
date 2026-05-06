package com.cloe.android

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.JsonReader
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.media.MediaPlayer
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.bumptech.glide.Glide
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.URI
import kotlin.math.abs
import kotlin.math.roundToInt

class CloeService : Service() {

    companion object {
        var isRunning = false
        private const val TAG = "CloeService"
        private const val NOTIFICATION_ID = 1001
        private const val WS_PORT = 19850
        /** Max interval between two taps to count as double-tap (ms) */
        private const val DOUBLE_TAP_MS = 320L

        /** Built-in asset mapping (same logical names as Electron / bridge) */
        val DEFAULT_ACTION_MAP = mapOf(
            "smile" to "smile.gif",
            "blink" to "blink.gif",
            "kiss" to "kiss.gif",
            "nod" to "nod.gif",
            "wave" to "wave.gif",
            "think" to "think.gif",
            "tease" to "tease.gif",
            "speak" to "speak.gif",
            "shake_head" to "shake_head.gif",
            "working" to "working.gif",
            "clap" to "clap.gif",
            "shy" to "shy.gif",
            "yawn" to "yawn.gif",
            "laugh" to "laugh.gif"
        )

        private val DEFAULT_IDLE_ACTIONS = listOf(
            "blink", "blink", "smile", "smile",
            "kiss", "think", "nod", "shake_head"
        )

        fun copyAssetToFile(context: Context, assetPath: String): String? {
            return try {
                val cacheFile = File(context.cacheDir, assetPath.replace("/", "_"))
                if (cacheFile.exists()) return cacheFile.absolutePath
                cacheFile.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                }
                cacheFile.absolutePath
            } catch (e: Exception) {
                null
            }
        }

        fun getAvailableActions(context: Context): Map<String, String> {
            val paths = linkedMapOf<String, String>()
            for ((action, assetName) in DEFAULT_ACTION_MAP) {
                val file = copyAssetToFile(context, "gifs/$assetName")
                if (file != null) paths[action] = file
            }
            val displaySetId = ActionSync.readFullMeta(context)?.activeSetIdAtSync ?: "default"
            paths.putAll(ActionSync.loadRemoteActionPathsForSet(context, displaySetId))
            return paths
        }
    }

    private lateinit var windowManager: WindowManager
    private var expandedView: View? = null
    private var collapsedView: View? = null
    private var paramsExpanded: WindowManager.LayoutParams? = null
    private var paramsCollapsed: WindowManager.LayoutParams? = null
    /** Collapsed chip: circular preview of current action */
    private var collapsedThumbView: ImageView? = null

    /** Expanded overlay: GIF layer (not the root FrameLayout). */
    private var expandedGifView: ImageView? = null
    private var contextBarHud: FrameLayout? = null
    private var contextBarFill: View? = null
    private var contextBarText: TextView? = null
    private var contextBarTrackWidthPx = 0
    private var contextBarPulse: ObjectAnimator? = null

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == CloePrefs.KEY_CONTEXT_BAR_VISIBLE) {
                mainHandler.post { syncContextBarVisibility() }
            }
        }

    private var wsClient: WebSocketClient? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var idleJob: Job? = null
    private var speakJob: Job? = null
    private var isWorking = false
    private var isSpeaking = false
    private var mediaPlayer: MediaPlayer? = null
    private var lastAction: String = ""
    private var host: String = ""
    private var sessionStarted = false

    /** Which synced set folder to use (follows PC WebSocket set-config.setId). */
    private var displaySetId: String = "default"

    /** action name → absolute path (bundled copy and/or remote GIF) */
    private val defaultPathByAction = linkedMapOf<String, String>()
    private val pathByAction = linkedMapOf<String, String>()
    private val idleActions = mutableListOf<String>()

    // === Service lifecycle ===

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        bootstrapActionPaths()
        getSharedPreferences(CloePrefs.NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun bootstrapActionPaths() {
        defaultPathByAction.clear()
        for ((action, assetName) in DEFAULT_ACTION_MAP) {
            val file = copyAssetToFile(this, "gifs/$assetName")
            if (file != null) defaultPathByAction[action] = file
        }
        displaySetId = ActionSync.readFullMeta(this)?.activeSetIdAtSync ?: "default"
        rebuildPathAndIdleMaps()
    }

    private fun rebuildPathAndIdleMaps() {
        pathByAction.clear()
        pathByAction.putAll(defaultPathByAction)
        pathByAction.putAll(ActionSync.loadRemoteActionPathsForSet(this, displaySetId))
        idleActions.clear()
        val full = ActionSync.readFullMeta(this)
        val idleFromMeta = full?.initialIdlePlaylist.orEmpty()
        if (idleFromMeta.isNotEmpty() && idleFromMeta.all { pathByAction.containsKey(it) }) {
            idleActions.addAll(idleFromMeta)
        } else {
            idleActions.addAll(DEFAULT_IDLE_ACTIONS)
        }
    }

    fun reloadActionPaths() {
        ActionSync.readFullMeta(this)?.activeSetIdAtSync?.let { displaySetId = it }
        rebuildPathAndIdleMaps()
        Log.i(TAG, "reloadActionPaths set=$displaySetId actions=${pathByAction.size} idle=${idleActions.size}")
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("reload_actions", false) == true) {
            reloadActionPaths()
            if (sessionStarted) return START_STICKY
        }

        if (intent?.getBooleanExtra("save_overlay_position", false) == true) {
            if (sessionStarted) saveCurrentOverlayPositionToPrefs()
            return START_STICKY
        }

        val h = intent?.getStringExtra("host")?.trim().orEmpty()
        if (h.isNotBlank()) host = h

        if (host.isBlank()) return START_NOT_STICKY

        if (sessionStarted) return START_STICKY

        sessionStarted = true
        startForegroundNotification()
        isRunning = true

        createExpandedView()
        createCollapsedView()
        showExpanded()
        connectWebSocket()

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        sessionStarted = false
        stopContextBarPulse()
        getSharedPreferences(CloePrefs.NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        idleJob?.cancel()
        speakJob?.cancel()
        releaseMediaPlayer()
        scope.cancel()
        wsClient?.close()
        expandedView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        collapsedView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        super.onDestroy()
    }

    // === Notification ===

    private fun startForegroundNotification() {
        val channel = NotificationChannel(
            "cloe_service", "Cloe Service", NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        val notification = Notification.Builder(this, "cloe_service")
            .setContentTitle("Cloe")
            .setContentText("正在桌面上等待你")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // === Overlay views ===

    private val layoutParamsType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    private fun readOverlayXYFromPrefs(): Pair<Int, Int> {
        val sp = getSharedPreferences(CloePrefs.NAME, Context.MODE_PRIVATE)
        if (!sp.getBoolean(CloePrefs.KEY_OVERLAY_SAVED, false)) {
            return CloePrefs.DEFAULT_OVERLAY_X to CloePrefs.DEFAULT_OVERLAY_Y
        }
        val x = sp.getInt(CloePrefs.KEY_OVERLAY_X, CloePrefs.DEFAULT_OVERLAY_X)
        val y = sp.getInt(CloePrefs.KEY_OVERLAY_Y, CloePrefs.DEFAULT_OVERLAY_Y)
        return x to y
    }

    private fun currentOverlayLayoutParamsOffset(): Pair<Int, Int>? = when {
        expandedView?.isAttachedToWindow == true ->
            paramsExpanded?.let { it.x to it.y }
        collapsedView?.isAttachedToWindow == true ->
            paramsCollapsed?.let { it.x to it.y }
        else -> null
    }

    private fun saveCurrentOverlayPositionToPrefs() {
        val (x, y) = currentOverlayLayoutParamsOffset() ?: return
        getSharedPreferences(CloePrefs.NAME, Context.MODE_PRIVATE).edit()
            .putInt(CloePrefs.KEY_OVERLAY_X, x)
            .putInt(CloePrefs.KEY_OVERLAY_Y, y)
            .putBoolean(CloePrefs.KEY_OVERLAY_SAVED, true)
            .commit()
        paramsExpanded?.apply { this.x = x; this.y = y }
        paramsCollapsed?.apply { this.x = x; this.y = y }
        syncOverlayLayoutFromParams()
    }

    private fun syncOverlayLayoutFromParams() {
        try {
            expandedView?.let { v ->
                if (v.isAttachedToWindow) paramsExpanded?.let { windowManager.updateViewLayout(v, it) }
            }
            collapsedView?.let { v ->
                if (v.isAttachedToWindow) paramsCollapsed?.let { windowManager.updateViewLayout(v, it) }
            }
        } catch (_: Exception) {
        }
    }

    private fun syncCollapsedParamsFromExpanded() {
        val e = paramsExpanded ?: return
        val c = paramsCollapsed ?: return
        c.x = e.x
        c.y = e.y
    }

    private fun syncExpandedParamsFromCollapsed() {
        val e = paramsExpanded ?: return
        val c = paramsCollapsed ?: return
        e.x = c.x
        e.y = c.y
    }

    private fun isContextBarPrefVisible(): Boolean =
        getSharedPreferences(CloePrefs.NAME, Context.MODE_PRIVATE)
            .getBoolean(CloePrefs.KEY_CONTEXT_BAR_VISIBLE, true)

    private fun syncContextBarVisibility() {
        contextBarHud?.visibility = if (isContextBarPrefVisible()) View.VISIBLE else View.GONE
    }

    private fun stopContextBarPulse() {
        contextBarPulse?.cancel()
        contextBarPulse = null
        contextBarFill?.alpha = 1f
    }

    private fun startContextBarPulse(fill: View) {
        if (contextBarPulse != null) return
        contextBarPulse = ObjectAnimator.ofFloat(fill, View.ALPHA, 1f, 0.7f).apply {
            duration = 750L
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun fillGradientForUsage(fill: View, pct: Double, dp: Float) {
        val colors = when {
            pct >= 90 -> intArrayOf(Color.parseColor("#ff4466"), Color.parseColor("#cc2244"))
            pct >= 75 -> intArrayOf(Color.parseColor("#ff8844"), Color.parseColor("#ff6622"))
            pct >= 50 -> intArrayOf(Color.parseColor("#ffe04c"), Color.parseColor("#ffbb22"))
            else -> intArrayOf(Color.parseColor("#4cff88"), Color.parseColor("#22cc55"))
        }
        fill.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = 6f * dp
        }
    }

    private fun updateContextUsageHud(pct: Double) {
        val fill = contextBarFill ?: return
        val label = contextBarText ?: return
        syncContextBarVisibility()
        val tw = contextBarTrackWidthPx
        if (tw <= 0) return
        val clamped = pct.coerceIn(0.0, 100.0)
        val fillW = (tw * clamped / 100.0).roundToInt().coerceIn(0, tw)
        (fill.layoutParams as FrameLayout.LayoutParams).width = fillW
        fill.requestLayout()
        label.text = "${clamped.roundToInt()}%"
        val dp = resources.displayMetrics.density
        fillGradientForUsage(fill, clamped, dp)
        if (clamped >= 90.0) startContextBarPulse(fill) else stopContextBarPulse()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createExpandedView() {
        val dp = resources.displayMetrics.density
        val w = (200 * dp).toInt()
        val h = (280 * dp).toInt()

        val gifView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "双击收起为小头像，拖动可移动"
        }
        expandedGifView = gifView

        val barWidth = (w * 0.7f).toInt().coerceAtLeast(1)
        val barHeight = maxOf((14 * dp).toInt(), 1)
        contextBarTrackWidthPx = barWidth
        val marginTop = (8 * dp).toInt()

        val track = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(barWidth, barHeight).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = marginTop
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 7f * dp
                setColor(Color.argb(140, 0, 0, 0))
                setStroke(maxOf(1, (1 * dp).toInt()), Color.argb(38, 255, 255, 255))
            }
        }

        val fill = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
        }
        fillGradientForUsage(fill, 0.0, dp)
        contextBarFill = fill
        track.addView(fill)

        val pctLabel = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 9f
            setShadowLayer(2f, 0f, 1f, Color.argb(204, 0, 0, 0))
            text = "0%"
        }
        contextBarText = pctLabel
        track.addView(pctLabel)

        val hud = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            addView(track)
        }
        contextBarHud = hud
        syncContextBarVisibility()

        val root = FrameLayout(this).apply {
            addView(gifView)
            addView(hud)
        }
        expandedView = root

        paramsExpanded = WindowManager.LayoutParams(
            w, h, layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            val (ox, oy) = readOverlayXYFromPrefs()
            x = ox
            y = oy
        }

        var lastX = 0; var lastY = 0; var moved = false
        var lastUpMs = 0L
        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX.toInt(); lastY = event.rawY.toInt(); moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - lastX
                    val dy = event.rawY.toInt() - lastY
                    if (abs(dx) > 5 || abs(dy) > 5) moved = true
                    paramsExpanded?.let { p ->
                        p.x -= dx; p.y += dy
                        windowManager.updateViewLayout(expandedView, p)
                    }
                    lastX = event.rawX.toInt(); lastY = event.rawY.toInt()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val t = SystemClock.uptimeMillis()
                        if (lastUpMs > 0L && t - lastUpMs < DOUBLE_TAP_MS) {
                            showCollapsed()
                            lastUpMs = 0L
                        } else {
                            lastUpMs = t
                        }
                    } else {
                        lastUpMs = 0L
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    lastUpMs = 0L
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createCollapsedView() {
        val dp = resources.displayMetrics.density
        val size = (56 * dp).toInt()

        val shell = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6161820"))
                setStroke(maxOf(1, (1.5f * dp).toInt()), Color.parseColor("#5EFFFFFF"))
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            elevation = 6f * dp
            contentDescription = "双击展开形象，拖动可移动"
        }

        val thumb = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        shell.addView(thumb)
        collapsedThumbView = thumb
        collapsedView = shell

        paramsCollapsed = WindowManager.LayoutParams(
            size, size, layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            val (ox, oy) = readOverlayXYFromPrefs()
            x = ox
            y = oy
        }

        var lastX = 0; var lastY = 0; var moved = false
        var lastUpMs = 0L
        shell.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX.toInt(); lastY = event.rawY.toInt(); moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - lastX
                    val dy = event.rawY.toInt() - lastY
                    if (abs(dx) > 5 || abs(dy) > 5) moved = true
                    paramsCollapsed?.let { p ->
                        p.x -= dx; p.y += dy
                        windowManager.updateViewLayout(collapsedView, p)
                    }
                    lastX = event.rawX.toInt(); lastY = event.rawY.toInt()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val t = SystemClock.uptimeMillis()
                        if (lastUpMs > 0L && t - lastUpMs < DOUBLE_TAP_MS) {
                            showExpanded()
                            lastUpMs = 0L
                        } else {
                            lastUpMs = t
                        }
                    } else {
                        lastUpMs = 0L
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    lastUpMs = 0L
                    true
                }
                else -> false
            }
        }
    }

    private fun showExpanded() {
        collapsedView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        syncExpandedParamsFromCollapsed()
        expandedView?.let { try { windowManager.addView(it, paramsExpanded) } catch (_: Exception) {} }
        // Collapse (pink dot) sets isWorking=true; expanding again must resume idle/PC-driven state.
        isWorking = false
        val resume =
            if (lastAction.isNotBlank() && pathByAction.containsKey(lastAction)) lastAction else "smile"
        lastAction = resume
        loadGif(resume)
        if (wsClient?.isOpen == true && !isSpeaking) startIdleLoop()
    }

    private fun showCollapsed() {
        isWorking = true
        expandedView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        syncCollapsedParamsFromExpanded()
        collapsedView?.let { try { windowManager.addView(it, paramsCollapsed) } catch (_: Exception) {} }
        refreshCollapsedChip()
    }

    /** Small circular preview matching current (or smile) action */
    private fun refreshCollapsedChip() {
        val iv = collapsedThumbView ?: return
        val action =
            if (lastAction.isNotBlank() && pathByAction.containsKey(lastAction)) lastAction else "smile"
        val path = pathByAction[action] ?: return
        val px = (128 * resources.displayMetrics.density).toInt().coerceAtLeast(96)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Glide.with(this@CloeService)
                .asGif()
                .load(path)
                .override(px, px)
                .into(iv)
        }
    }

    private fun getGifView(): ImageView? = expandedGifView

    private fun loadGif(action: String) {
        val filePath = pathByAction[action] ?: return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            getGifView()?.let { iv ->
                Glide.with(this@CloeService).asGif().load(filePath).into(iv)
            }
            if (isWorking) refreshCollapsedChip()
        }
    }

    // === WebSocket ===

    private fun connectWebSocket() {
        val uri = URI("ws://$host:$WS_PORT")

        wsClient = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(this@CloeService, "已连接 ✅", Toast.LENGTH_SHORT).show()
                }
                if (!isWorking) startIdleLoop()
            }

            override fun onMessage(message: String?) {
                message ?: return
                handleMessage(message)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                scope.launch { delay(3000); connectWebSocket() }
            }

            override fun onError(ex: Exception?) {
                ex?.printStackTrace()
            }
        }
        wsClient?.connect()
    }

    private fun handleMessage(raw: String) {
        try {
            val trimmed = raw.trim()
            if (trimmed.startsWith("{")) {
                val o = JSONObject(trimmed)
                if (o.optString("type") == "context-usage") {
                    val pct = o.optDouble("usage_pct", 0.0).coerceIn(0.0, 100.0)
                    mainHandler.post { updateContextUsageHud(pct) }
                    return
                }
                if (o.optString("type") == "set-config") {
                    applySetConfig(o)
                    return
                }
                val action = o.optString("action", "")
                if (action.isNotEmpty()) {
                    dispatchAction(action, o)
                    return
                }
            }
            // Legacy: minimal JSON with only action (parse without full JSONObject)
            val actionOnly = parseLegacyAction(raw)
            if (actionOnly != null) dispatchAction(actionOnly, null)
        } catch (_: Exception) {
        }
    }

    private fun parseLegacyAction(raw: String): String? {
        return try {
            var action = ""
            JsonReader(StringReader(raw)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "action" -> action = reader.nextString()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
            action.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun applySetConfig(o: JSONObject) {
        val full = ActionSync.readFullMeta(this)
        val sid = o.optString("setId", "")
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        if (sid.isNotEmpty()) {
            if (full != null && !full.hasSet(sid)) {
                mainHandler.post {
                    Toast.makeText(
                        this,
                        "PC 当前套装未在本地缓存，请重新「从 PC 拉取动作」",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            displaySetId = sid
            rebuildPathAndIdleMaps()
        }

        val idleJa = o.optJSONArray("idlePlaylist") ?: return
        val candidates = buildList {
            for (i in 0 until idleJa.length()) add(idleJa.getString(i))
        }
        if (candidates.isEmpty()) return
        if (candidates.all { pathByAction.containsKey(it) }) {
            idleActions.clear()
            idleActions.addAll(candidates)
        }
    }

    private fun dispatchAction(action: String, full: JSONObject?) {
        when (action) {
            "idle" -> {
                if (isSpeaking) return // 音频播放中不切idle，等音频结束自动恢复
                isWorking = false
                startIdleLoop()
            }
            "working" -> {
                isWorking = true
                idleJob?.cancel()
                // 不杀音频——语音和动画解耦。只切换GIF到working，音频播完自行结束。
                if (isSpeaking) {
                    lastAction = "working"
                    loadGif("working")
                } else {
                    playAction("working")
                }
            }
            "wave" -> {
                showExpanded()
                // 不杀音频——语音和动画解耦。只切换GIF到wave，音频播完自行结束。
                if (isSpeaking) {
                    lastAction = "wave"
                    loadGif("wave")
                } else {
                    playAction("wave")
                }
            }
            "speak" -> {
                val audioName = full?.optString("audio", "") ?: ""
                val audioUrl = full?.optString("audio_url", "") ?: ""
                if (audioName.isNotEmpty() || audioUrl.isNotEmpty()) {
                    // 新speak请求：中断旧音频，播放新的
                    playSpeakWithAudio(audioName, audioUrl)
                } else {
                    // 纯动画speak，音频播放中则忽略（避免画面闪跳）
                    if (!isSpeaking) playAction("speak")
                }
            }
            else -> {
                // 音画解耦：音频播放中也可以切换GIF，但音频不受任何影响
                playAction(action)
            }
        }
    }

    private fun playAction(action: String, isReaction: Boolean = true) {
        if (!pathByAction.containsKey(action)) {
            Log.w(TAG, "No local GIF for action: $action (pull from PC or check套装)")
            return
        }
        // Skip only if same as last AND it's an idle-triggered action
        if (action == lastAction && !isReaction) return
        lastAction = action
        loadGif(action)

        if (action != "working") {
            idleJob?.cancel()
            idleJob = scope.launch {
                // Reactions from Hermes/curl get extra display time before idle resumes
                val cooldownMs = if (isReaction) 4000L else 3000L
                delay(cooldownMs)
                if (!isWorking) scheduleNextIdle()
            }
        }
    }

    private fun startIdleLoop() {
        idleJob?.cancel()
        if (idleActions.isEmpty()) return
        // Immediately show a random idle GIF (no delay — used after speak/reconnect)
        var next = idleActions.random()
        var guard = 0
        while (next == lastAction && idleActions.size > 1 && guard++ < 8) {
            next = idleActions.random()
        }
        lastAction = next
        loadGif(next)
        // Then schedule the next idle with the usual delay
        scheduleNextIdle()
    }

    private fun scheduleNextIdle() {
        idleJob?.cancel()
        if (idleActions.isEmpty()) return
        idleJob = scope.launch {
            delay((8000..15000).random().toLong())
            if (!isWorking) {
                var next = idleActions.random()
                var guard = 0
                while (next == lastAction && idleActions.size > 1 && guard++ < 8) {
                    next = idleActions.random()
                }
                lastAction = next
                loadGif(next)
                // Continue idle loop (isReaction=false: don't force-replay same action)
                idleJob?.cancel()
                idleJob = scope.launch {
                    delay(3000)
                    if (!isWorking) scheduleNextIdle()
                }
            }
        }
    }

    // === Speak with audio ===

    private fun playSpeakWithAudio(audioName: String, audioUrl: String) {
        // 新speak请求：取消旧下载、停旧音频
        speakJob?.cancel()
        releaseMediaPlayer()

        idleJob?.cancel()
        isSpeaking = true

        // Show a transition animation while downloading (not speaking yet!)
        lastAction = "smile"
        loadGif("smile")

        // Build download URL
        val url = when {
            audioUrl.isNotEmpty() -> {
                // Replace localhost/127.0.0.1 with the connected host
                audioUrl
                    .replace("localhost", host)
                    .replace("127.0.0.1", host)
            }
            audioName.isNotEmpty() -> "http://$host:19851/tts/${audioName}.mp3"
            else -> {
                isSpeaking = false
                return
            }
        }

        speakJob = scope.launch {
            try {
                val audioFile = downloadAudio(url)
                if (!isSpeaking) return@launch // cancelled while downloading
                withContext(Dispatchers.Main) {
                    playAudioWithSpeakGif(audioFile)
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "Speak download cancelled (new speak started)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download/play audio: ${e.message}")
                isSpeaking = false
                if (!isWorking) startIdleLoop()
            }
        }
    }

    private suspend fun downloadAudio(urlString: String): File {
        val fileName = urlString.substringAfterLast("/").substringBefore("?")
        val cacheDir = File(cacheDir, "audio")
        val cacheFile = File(cacheDir, fileName)
        if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile

        // Delete corrupted/empty cached file
        if (cacheFile.exists()) cacheFile.delete()

        cacheDir.mkdirs()
        Log.i(TAG, "Downloading audio: $urlString")

        // Download with timeout
        val connection = java.net.URL(urlString).openConnection()
        connection.connectTimeout = 10000
        connection.readTimeout = 30000
        val responseCode = (connection as java.net.HttpURLConnection).responseCode
        if (responseCode != 200) {
            throw IOException("HTTP $responseCode for $urlString")
        }

        cacheFile.outputStream().use { output ->
            connection.getInputStream().use { input ->
                input.copyTo(output)
            }
        }
        Log.i(TAG, "Audio downloaded: ${cacheFile.name} (${cacheFile.length()} bytes)")
        return cacheFile
    }

    private fun playAudioWithSpeakGif(file: File) {
        releaseMediaPlayer()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { mp ->
                    // Audio ready → switch to speak GIF and play simultaneously
                    lastAction = "speak"
                    loadGif("speak")
                    mp.start()
                    Log.i(TAG, "Audio playing: ${file.name}")
                }
                setOnCompletionListener {
                    Log.i(TAG, "Audio completed: ${file.name}")
                    isSpeaking = false
                    // isWorking 可能被 working 事件在播放期间设置，不要强制清除
                    // 如果 working 正在进行，恢复 working.gif；否则恢复 idle
                    if (isWorking) {
                        lastAction = "working"
                        loadGif("working")
                    } else {
                        startIdleLoop()
                    }
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Audio error: what=$what extra=$extra")
                    isSpeaking = false
                    if (!isWorking) startIdleLoop()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer error: ${e.message}")
            isSpeaking = false
            if (!isWorking) startIdleLoop()
        }
    }

    private fun stopSpeaking() {
        if (isSpeaking) {
            releaseMediaPlayer()
            isSpeaking = false
        }
    }

    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
    }
}
