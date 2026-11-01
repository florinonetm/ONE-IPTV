package com.onelabs.oneplayer

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "stream_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_CHANNELS_JSON = "channels_json"
        const val EXTRA_CHANNEL_INDEX = "channel_index"
        private const val AUTO_HIDE_DELAY_MS = 3500L

        @Volatile
        var inMemoryPlaylist: List<ChannelItem>? = null
    }

    data class ChannelItem(
        val title: String,
        val url: String,
        val group: String,
        val logo: String,
        val num: Int
    )

    private var player: ExoPlayer? = null
    private lateinit var trackSelector: DefaultTrackSelector

    private lateinit var playerRoot: View
    private lateinit var playerView: PlayerView
    private lateinit var gestureOverlay: View
    private lateinit var loading: ProgressBar
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var retryBtn: Button
    private lateinit var hudToast: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var btnBack: Button
    private lateinit var playerTitle: TextView
    private lateinit var playerSubtitle: TextView
    private lateinit var playerLogo: ImageView
    private lateinit var liveBadge: TextView
    private lateinit var centerControls: LinearLayout
    private lateinit var btnPrev: Button
    private lateinit var btnRewind: Button
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnForward: Button
    private lateinit var btnNext: Button
    private lateinit var bottomBar: LinearLayout
    private lateinit var btnPlayPauseBottom: Button
    private lateinit var tvCurrentTime: TextView
    private lateinit var playerProgress: SeekBar
    private lateinit var tvDuration: TextView
    private lateinit var btnAudio: Button
    private lateinit var btnSubtitles: Button
    private lateinit var btnFullscreen: Button
    private lateinit var btnChannels: Button
    private lateinit var btnSettings: Button

    private lateinit var audioManager: AudioManager
    private var maxVolume = 15
    private var currentBrightness = 0.5f

    private var streamUrl: String = ""
    private var channelsList = mutableListOf<ChannelItem>()
    private var currentChannelIndex = -1

    private var controlsVisible = true
    private var isUserTrackingProgress = false
    private var gesturesEnabled = true
    private var hasAttemptedFallback = false
    private var sleepTimer: Handler? = null

    private var subTextSize = "M"
    private var subColor = "#FFFFFF"
    private var subBgColor = "#80000000"

    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }

    private val progressHandler = Handler(Looper.getMainLooper())
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, 1000)
        }
    }

    private val hudHideHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        currentBrightness = window.attributes.screenBrightness.takeIf { it in 0.0f..1.0f } ?: 0.5f

        streamUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "ONE™"
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE) ?: ""

        // Parse channels list if passed
        parseChannelsIntent()

        initViews()
        playerTitle.text = title
        playerSubtitle.text = subtitle

        setupButtons()
        setupProgressBar()
        setupGestures()
    }

    private fun parseChannelsIntent() {
        currentChannelIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, -1)
        val memList = inMemoryPlaylist
        if (!memList.isNullOrEmpty()) {
            channelsList.clear()
            channelsList.addAll(memList)
            return
        }

        val jsonStr = intent.getStringExtra(EXTRA_CHANNELS_JSON)
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val arr = JSONArray(jsonStr)
                channelsList.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    channelsList.add(
                        ChannelItem(
                            title = obj.optString("title", "Channel ${i + 1}"),
                            url = obj.optString("url", ""),
                            group = obj.optString("group", "Channels"),
                            logo = obj.optString("logo", ""),
                            num = obj.optInt("num", i + 1)
                        )
                    )
                }
            } catch (_: Exception) {}
        }
    }

    private fun initViews() {
        playerRoot = findViewById(R.id.player_root)
        playerView = findViewById(R.id.player_view)
        gestureOverlay = findViewById(R.id.gesture_overlay)
        loading = findViewById(R.id.loading)
        errorPanel = findViewById(R.id.error_panel)
        errorText = findViewById(R.id.error_text)
        retryBtn = findViewById(R.id.retry_btn)
        hudToast = findViewById(R.id.hud_toast)
        topBar = findViewById(R.id.top_bar)
        btnBack = findViewById(R.id.btn_back)
        playerTitle = findViewById(R.id.player_title)
        playerSubtitle = findViewById(R.id.player_subtitle)
        playerLogo = findViewById(R.id.player_logo)
        liveBadge = findViewById(R.id.live_badge)
        centerControls = findViewById(R.id.center_controls)
        btnPrev = findViewById(R.id.btn_prev)
        btnRewind = findViewById(R.id.btn_rewind)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnForward = findViewById(R.id.btn_forward)
        btnNext = findViewById(R.id.btn_next)
        bottomBar = findViewById(R.id.bottom_bar)
        btnPlayPauseBottom = findViewById(R.id.btn_play_pause_bottom)
        tvCurrentTime = findViewById(R.id.tv_current_time)
        playerProgress = findViewById(R.id.player_progress)
        tvDuration = findViewById(R.id.tv_duration)
        btnAudio = findViewById(R.id.btn_audio)
        btnSubtitles = findViewById(R.id.btn_subtitles)
        btnFullscreen = findViewById(R.id.btn_fullscreen)
        btnChannels = findViewById(R.id.btn_channels)
        btnSettings = findViewById(R.id.btn_settings)

        // Show Next/Prev if playlist has items
        if (channelsList.size > 1) {
            btnPrev.visibility = View.VISIBLE
            btnNext.visibility = View.VISIBLE
            btnChannels.visibility = View.VISIBLE
        } else {
            btnPrev.visibility = View.GONE
            btnNext.visibility = View.GONE
            btnChannels.visibility = View.GONE
        }
    }

    private fun setupButtons() {
        btnBack.setOnClickListener { finish() }

        val toggleAction = View.OnClickListener {
            togglePlayPause()
        }
        btnPlayPause.setOnClickListener(toggleAction)
        btnPlayPauseBottom.setOnClickListener(toggleAction)

        btnPrev.setOnClickListener {
            switchChannel(currentChannelIndex - 1)
            resetAutoHide()
        }

        btnNext.setOnClickListener {
            switchChannel(currentChannelIndex + 1)
            resetAutoHide()
        }

        btnRewind.setOnClickListener {
            player?.let { p ->
                val current = p.currentPosition
                p.seekTo(maxOf(0L, current - 10000L))
                showHudToast("⏪ -10s")
            }
            resetAutoHide()
        }

        btnForward.setOnClickListener {
            player?.let { p ->
                val current = p.currentPosition
                val dur = p.duration
                val target = if (dur > 0) minOf(dur, current + 10000L) else current + 10000L
                p.seekTo(target)
                showHudToast("⏩ +10s")
            }
            resetAutoHide()
        }

        btnFullscreen.setOnClickListener {
            cycleAspectRatio()
            resetAutoHide()
        }

        btnChannels.setOnClickListener {
            showChannelListPicker()
            resetAutoHide()
        }

        btnAudio.setOnClickListener {
            showAudioPicker()
            resetAutoHide()
        }

        btnSubtitles.setOnClickListener {
            showSubtitlePicker()
            resetAutoHide()
        }

        btnSettings.setOnClickListener {
            showSettingsPicker()
            resetAutoHide()
        }

        retryBtn.setOnClickListener {
            errorPanel.visibility = View.GONE
            initializePlayer()
        }
    }

    private fun switchChannel(newIndex: Int) {
        if (channelsList.isEmpty()) return
        val count = channelsList.size
        val normalizedIndex = if (newIndex < 0) count - 1 else newIndex % count
        currentChannelIndex = normalizedIndex

        val ch = channelsList[normalizedIndex]
        streamUrl = ch.url
        playerTitle.text = ch.title
        playerSubtitle.text = "${ch.num}. ${ch.group}"

        showHudToast("📺 ${ch.num}. ${ch.title}")
        hasAttemptedFallback = false
        initializePlayer()
    }

    private fun showChannelListPicker() {
        if (channelsList.isEmpty()) return
        val titles = channelsList.mapIndexed { idx, ch ->
            val prefix = if (idx == currentChannelIndex) "▶ " else "   "
            "$prefix${ch.num}. ${ch.title} (${ch.group})"
        }.toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Channels (${channelsList.size})")
            .setItems(titles) { _, which ->
                switchChannel(which)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun setupProgressBar() {
        playerProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player?.let { p ->
                        val dur = p.duration
                        if (dur > 0) {
                            val newPos = (dur * progress) / 1000L
                            tvCurrentTime.text = formatTime(newPos)
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingProgress = true
                hideControlsHandler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { sb ->
                    player?.let { p ->
                        val dur = p.duration
                        if (dur > 0) {
                            val newPos = (dur * sb.progress) / 1000L
                            p.seekTo(newPos)
                        }
                    }
                }
                isUserTrackingProgress = false
                resetAutoHide()
            }
        })
    }

    private var volumeDeltaAcc = 0f

    private fun setupGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                volumeDeltaAcc = 0f
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val width = gestureOverlay.width
                if (e.x < width / 2) {
                    btnRewind.performClick()
                } else {
                    btnForward.performClick()
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (!gesturesEnabled || e1 == null) return false
                val width = gestureOverlay.width
                val height = gestureOverlay.height
                if (height <= 0 || width <= 0) return false

                // distanceY is positive when scrolling up, negative when scrolling down
                val delta = distanceY / height.toFloat()

                if (e1.x < width * 0.45f) {
                    // Brightness (Left side)
                    currentBrightness = (currentBrightness + delta).coerceIn(0.05f, 1.0f)
                    val lp = window.attributes
                    lp.screenBrightness = currentBrightness
                    window.attributes = lp
                    showHudToast("Brightness: ${(currentBrightness * 100).toInt()}%")
                } else if (e1.x > width * 0.55f) {
                    // Volume (Right side)
                    volumeDeltaAcc += delta
                    val stepThreshold = 1.0f / maxVolume.coerceAtLeast(1)
                    if (kotlin.math.abs(volumeDeltaAcc) >= stepThreshold) {
                        val steps = (volumeDeltaAcc / stepThreshold).toInt()
                        volumeDeltaAcc -= steps * stepThreshold
                        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val newVol = (currentVol + steps).coerceIn(0, maxVolume)
                        if (newVol != currentVol) {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                        }
                        showHudToast("Volume: ${(newVol * 100 / maxVolume.coerceAtLeast(1))}%")
                    }
                }
                return true
            }
        })

        gestureOverlay.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun initializePlayer() {
        if (streamUrl.isBlank()) {
            showError("No stream URL provided")
            return
        }

        releasePlayer()
        loading.visibility = View.VISIBLE
        errorPanel.visibility = View.GONE

        trackSelector = DefaultTrackSelector(this)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("ONEPlayer/1.02")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                playerView.player = this
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> loading.visibility = View.VISIBLE
                            Player.STATE_READY -> {
                                loading.visibility = View.GONE
                                updatePlayPauseIcons()
                                updateProgress()
                            }
                            Player.STATE_ENDED -> {
                                loading.visibility = View.GONE
                                updatePlayPauseIcons()
                            }
                            Player.STATE_IDLE -> loading.visibility = View.GONE
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updatePlayPauseIcons()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        loading.visibility = View.GONE
                        if (!hasAttemptedFallback && (streamUrl.endsWith(".m3u8", ignoreCase = true) || streamUrl.contains("m3u8"))) {
                            hasAttemptedFallback = true
                            showHudToast("Retrying stream with standard decoder...")
                            initializePlayer()
                        } else {
                            showError("Stream error: ${error.message ?: "Failed to play"}")
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        updateTrackBadges(tracks)
                    }
                })

                val mediaItem = buildMediaItem(streamUrl)
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }

        progressHandler.post(updateProgressRunnable)
        resetAutoHide()
    }

    private fun buildMediaItem(url: String): MediaItem {
        val lower = url.lowercase()
        val builder = MediaItem.Builder().setUri(Uri.parse(url))
        if (lower.contains(".m3u8") || lower.contains("format=m3u8")) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        } else if (lower.contains(".mpd")) {
            builder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return builder.build()
    }

    private fun togglePlayPause() {
        player?.let { p ->
            if (p.isPlaying) {
                p.pause()
                showControls()
                hideControlsHandler.removeCallbacks(hideControlsRunnable)
            } else {
                p.play()
                resetAutoHide()
            }
            updatePlayPauseIcons()
        }
    }

    private fun updatePlayPauseIcons() {
        val isPlaying = player?.isPlaying == true
        if (isPlaying) {
            btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            btnPlayPauseBottom.text = "⏸"
        } else {
            btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
            btnPlayPauseBottom.text = "▶"
        }
    }

    private fun updateProgress() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration

        if (dur <= 0) {
            // Live stream
            liveBadge.visibility = View.VISIBLE
            tvCurrentTime.text = formatTime(pos)
            tvDuration.text = "LIVE"
            playerProgress.visibility = View.INVISIBLE
            btnRewind.visibility = View.GONE
            btnForward.visibility = View.GONE
        } else {
            liveBadge.visibility = View.GONE
            playerProgress.visibility = View.VISIBLE
            btnRewind.visibility = View.VISIBLE
            btnForward.visibility = View.VISIBLE
            if (!isUserTrackingProgress) {
                tvCurrentTime.text = formatTime(pos)
                tvDuration.text = formatTime(dur)
                val progress = ((pos * 1000L) / dur).toInt()
                playerProgress.progress = progress
            }
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSec = millis / 1000
        val sec = totalSec % 60
        val min = (totalSec / 60) % 60
        val hrs = totalSec / 3600
        return if (hrs > 0) {
            String.format("%02d:%02d:%02d", hrs, min, sec)
        } else {
            String.format("%02d:%02d", min, sec)
        }
    }

    private fun toggleControls() {
        if (controlsVisible) {
            hideControls()
        } else {
            showControls()
            resetAutoHide()
        }
    }

    private fun showControls() {
        controlsVisible = true
        topBar.visibility = View.VISIBLE
        centerControls.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
    }

    private fun hideControls() {
        controlsVisible = false
        topBar.visibility = View.GONE
        centerControls.visibility = View.GONE
        bottomBar.visibility = View.GONE
    }

    private fun resetAutoHide() {
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        if (player?.isPlaying == true) {
            hideControlsHandler.postDelayed(hideControlsRunnable, AUTO_HIDE_DELAY_MS)
        }
    }

    private fun showHudToast(text: String) {
        hudHideHandler.removeCallbacksAndMessages(null)
        hudToast.text = text
        hudToast.visibility = View.VISIBLE
        hudToast.alpha = 1.0f
        hudHideHandler.postDelayed({
            hudToast.animate().alpha(0f).setDuration(300).withEndAction {
                hudToast.visibility = View.GONE
            }
        }, 1500)
    }

    private fun showError(msg: String) {
        errorText.text = msg
        errorPanel.visibility = View.VISIBLE
    }

    private fun cycleAspectRatio() {
        val modes = listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit (Original)",
            AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch (Fill)",
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom (Crop)"
        )
        val current = playerView.resizeMode
        val currentIndex = modes.indexOfFirst { it.first == current }.coerceAtLeast(0)
        val next = modes[(currentIndex + 1) % modes.size]
        playerView.resizeMode = next.first
        btnFullscreen.text = "⛶ " + next.second.substringBefore(" ")
        showHudToast("Aspect Ratio: ${next.second}")
    }

    private fun updateTrackBadges(tracks: Tracks) {
        var hasAudio = false
        var hasSub = false
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) hasAudio = true
            if (group.type == C.TRACK_TYPE_TEXT) hasSub = true
        }
        btnAudio.visibility = if (hasAudio) View.VISIBLE else View.GONE
        btnSubtitles.visibility = if (hasSub) View.VISIBLE else View.GONE
    }

    private fun showAudioPicker() {
        val exo = player ?: return
        val audioTracks = mutableListOf<String>()
        val trackGroups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

        trackGroups.forEachIndexed { gIdx, g ->
            for (tIdx in 0 until g.length) {
                val format = g.getTrackFormat(tIdx)
                val lang = format.language?.uppercase() ?: "Track ${gIdx + 1}"
                val label = format.label ?: ""
                audioTracks.add("$lang ${if (label.isNotEmpty()) "($label)" else ""}".trim())
            }
        }

        if (audioTracks.isEmpty()) {
            showHudToast("No alternate audio tracks")
            return
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Select Audio Track")
            .setItems(audioTracks.toTypedArray()) { _, which ->
                var targetGroup: Tracks.Group? = null
                var targetTrackIdx = 0
                var counter = 0
                for (g in trackGroups) {
                    for (t in 0 until g.length) {
                        if (counter == which) {
                            targetGroup = g
                            targetTrackIdx = t
                            break
                        }
                        counter++
                    }
                    if (targetGroup != null) break
                }
                targetGroup?.let { tg ->
                    val builder = trackSelector.buildUponParameters()
                    val override = androidx.media3.common.TrackSelectionOverride(tg.mediaTrackGroup, targetTrackIdx)
                    trackSelector.parameters = builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO).addOverride(override).build()
                }
                showHudToast("Audio: ${audioTracks[which]}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubtitlePicker() {
        val exo = player ?: return
        val subOptions = mutableListOf("Off")
        val subGroups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

        subGroups.forEachIndexed { gIdx, g ->
            for (tIdx in 0 until g.length) {
                val format = g.getTrackFormat(tIdx)
                val lang = format.language?.uppercase() ?: "Track ${gIdx + 1}"
                val label = format.label ?: ""
                subOptions.add("$lang ${if (label.isNotEmpty()) "($label)" else ""}".trim())
            }
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Subtitles & Closed Captions")
            .setItems(subOptions.toTypedArray()) { _, which ->
                if (which == 0) {
                    trackSelector.parameters = trackSelector.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
                    btnSubtitles.text = "💬 CC Off"
                    showHudToast("Subtitles Disabled")
                } else {
                    var targetGroup: Tracks.Group? = null
                    var targetTrackIdx = 0
                    var counter = 0
                    for (g in subGroups) {
                        for (t in 0 until g.length) {
                            if (counter == which - 1) {
                                targetGroup = g
                                targetTrackIdx = t
                                break
                            }
                            counter++
                        }
                        if (targetGroup != null) break
                    }
                    val builder = trackSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    targetGroup?.let { tg ->
                        val override = androidx.media3.common.TrackSelectionOverride(tg.mediaTrackGroup, targetTrackIdx)
                        builder.clearOverridesOfType(C.TRACK_TYPE_TEXT).addOverride(override)
                    }
                    trackSelector.parameters = builder.build()
                    btnSubtitles.text = "💬 " + subOptions[which].substringBefore(" ")
                    showHudToast("Subtitles: ${subOptions[which]}")
                }
            }
            .setPositiveButton("Customize Style") { _, _ ->
                showSubtitleStyleDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubtitleStyleDialog() {
        val sizes = arrayOf("Small (S)", "Medium (M)", "Large (L)", "Extra Large (XL)")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Subtitle Text Size")
            .setSingleChoiceItems(sizes, 1) { dialog, which ->
                subTextSize = listOf("S", "M", "L", "XL")[which]
                val sp = when (which) {
                    0 -> 14f
                    1 -> 18f
                    2 -> 24f
                    else -> 30f
                }
                playerView.subtitleView?.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
                showHudToast("Subtitle size: $subTextSize")
                dialog.dismiss()
            }
            .show()
    }

    private fun showSettingsPicker() {
        val options = arrayOf(
            "Playback Speed",
            "Sleep Timer",
            "Screen Orientation",
            "Touch Gestures (${if (gesturesEnabled) "Enabled" else "Disabled"})"
        )

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Player Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSpeedDialog()
                    1 -> showSleepTimerDialog()
                    2 -> cycleOrientation()
                    3 -> {
                        gesturesEnabled = !gesturesEnabled
                        showHudToast("Gestures: ${if (gesturesEnabled) "Enabled" else "Disabled"}")
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSpeedDialog() {
        val speeds = arrayOf("0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "2.0x")
        val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Playback Speed")
            .setItems(speeds) { _, which ->
                val s = values[which]
                player?.setPlaybackSpeed(s)
                showHudToast("Speed: ${speeds[which]}")
            }
            .show()
    }

    private fun showSleepTimerDialog() {
        val times = arrayOf("Turn Off", "15 minutes", "30 minutes", "60 minutes", "120 minutes")
        val minutes = intArrayOf(0, 15, 30, 60, 120)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Sleep Timer")
            .setItems(times) { _, which ->
                val m = minutes[which]
                sleepTimer?.removeCallbacksAndMessages(null)
                if (m > 0) {
                    sleepTimer = Handler(Looper.getMainLooper()).apply {
                        postDelayed({
                            player?.pause()
                            finish()
                        }, m * 60 * 1000L)
                    }
                    showHudToast("Sleep timer set: $m min")
                } else {
                    showHudToast("Sleep timer cancelled")
                }
            }
            .show()
    }

    private fun cycleOrientation() {
        requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        showHudToast("Orientation toggled")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                btnRewind.performClick()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                btnForward.performClick()
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                switchChannel(currentChannelIndex + 1)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                switchChannel(currentChannelIndex - 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (channelsList.isNotEmpty()) {
                    showChannelListPicker()
                } else if (!controlsVisible) {
                    showControls()
                } else {
                    hideControls()
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (controlsVisible) {
                    hideControls()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player?.isPlaying == true) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Exception) {}
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            hideControls()
            gestureOverlay.visibility = View.GONE
        } else {
            gestureOverlay.visibility = View.VISIBLE
            showControls()
        }
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.let { p ->
            p.stop()
            p.release()
        }
        player = null
        progressHandler.removeCallbacks(updateProgressRunnable)
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
    }

    override fun onStart() {
        super.onStart()
        if (player == null) initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode) {
            player?.playWhenReady = false
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            // Stay alive in PiP
        } else {
            releasePlayer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sleepTimer?.removeCallbacksAndMessages(null)
        releasePlayer()
        inMemoryPlaylist = null
    }
}
