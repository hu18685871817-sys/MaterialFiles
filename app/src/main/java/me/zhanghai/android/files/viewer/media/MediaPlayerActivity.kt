package me.zhanghai.android.files.viewer.media

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.file.isVideo
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.showToast
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

class MediaPlayerActivity : AppActivity(), TextureView.SurfaceTextureListener {

    enum class AspectMode(val label: String) {
        FIT("适应屏幕"),
        FILL("填充裁剪"),
        STRETCH("全屏拉伸"),
        RATIO_16_9("16:9")
    }

    private var mediaPlayer: MediaPlayer? = null
    private var playbackSurface: Surface? = null
    private var isPrepared = false
    private var isUserSeeking = false
    private var currentSpeed = 1.0f
    private var currentBrightness = 0.75f
    private var currentUri: Uri? = null
    private var currentLocalFile: File? = null
    private var currentTitle: String = ""
    private var isVideoMode: Boolean = true
    private var videoWidthPx: Int = 0
    private var videoHeightPx: Int = 0
    private var aspectMode: AspectMode = AspectMode.FIT

    private var playlistFiles: List<File> = emptyList()
    private var playlistIndex: Int = 0

    // MX Player Overlay & Lock State
    private var areControlsVisible: Boolean = true
    private var isScreenLocked: Boolean = false

    // External Subtitle (SRT / ASS) state
    private var currentSubtitleTrack: SubtitleTrack? = null
    private var isSubtitleEnabled: Boolean = true
    private var subtitleOffsetMs: Long = 0L
    private var subtitleFontSizeSp: Float = 18f
    private var subtitleTextColor: Int = Color.WHITE

    private lateinit var audioManager: AudioManager
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var rootStage: FrameLayout
    private lateinit var textureView: TextureView
    private lateinit var audioCoverContainer: LinearLayout
    private lateinit var audioTitleText: TextView
    private lateinit var audioSubtitleText: TextView

    // Subtitle overlay at bottom of video
    private lateinit var subtitleOverlayCard: MaterialCardView
    private lateinit var subtitleOverlayText: TextView

    // MX Player Center Gesture HUD (Brightness / Volume / Seek)
    private lateinit var gestureHudCard: MaterialCardView
    private lateinit var gestureHudTitle: TextView
    private lateinit var gestureHudProgress: ProgressBar

    // MX Player Top & Bottom Auto-Hiding Overlay Bars
    private lateinit var topOverlayBar: LinearLayout
    private lateinit var topTitleText: TextView
    private lateinit var topDecoderBadge: TextView
    private lateinit var topSpeedPill: TextView
    private lateinit var topSubtitlePill: TextView

    private lateinit var bottomOverlayBar: LinearLayout
    private lateinit var progressSeekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalDurationText: TextView
    private lateinit var playPauseBtn: TextView
    private lateinit var aspectRatioBtn: TextView

    // Floating Unlock Button when screen is locked
    private lateinit var floatingUnlockBtn: MaterialCardView

    // Local Media Picker Overlay when opened directly without a file
    private lateinit var localMediaBrowserOverlay: LinearLayout
    private lateinit var localMediaListContainer: LinearLayout

    private val pickSubtitleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            loadSubtitleFromUri(uri)
        }
    }

    private val pickMediaFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            localMediaBrowserOverlay.visibility = View.GONE
            var name = uri.lastPathSegment ?: "视频播放"
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    name = c.getString(idx) ?: name
                }
            }
            val isAudio = name.endsWith(".mp3", true) ||
                name.endsWith(".flac", true) ||
                name.endsWith(".wav", true) ||
                name.endsWith(".m4a", true)
            loadMediaUri(uri, name, !isAudio)
        }
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            val player = mediaPlayer
            if (player != null && isPrepared && !isUserSeeking) {
                runCatching {
                    val pos = player.currentPosition.coerceAtLeast(0)
                    val dur = player.duration.coerceAtLeast(1)
                    progressSeekBar.max = dur
                    progressSeekBar.progress = pos
                    currentTimeText.text = formatDuration(pos.toLong())
                    totalDurationText.text = formatDuration(dur.toLong())
                    updateSubtitleOverlay(pos.toLong())
                }
            }
            uiHandler.postDelayed(this, 180L)
        }
    }

    private val autoHideControlsRunnable = Runnable {
        if (mediaPlayer?.isPlaying == true && areControlsVisible) {
            setControlsOverlayVisibility(false)
        }
    }

    private val hideHudRunnable = Runnable {
        gestureHudCard.animate().alpha(0f).setDuration(200L).withEndAction {
            gestureHudCard.visibility = View.GONE
        }.start()
    }

    private val hideLockIconRunnable = Runnable {
        floatingUnlockBtn.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setContentView(buildMxPlayerLayout())
        resolveInitialMedia(intent)
        uiHandler.post(progressUpdater)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveInitialMedia(intent)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        playbackSurface?.release()
        playbackSurface = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildMxPlayerLayout(): View {
        rootStage = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Fullscreen Video TextureView
        textureView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            surfaceTextureListener = this@MediaPlayerActivity
        }
        rootStage.addView(textureView)

        // 2. Audio Cover View (only shown for audio files)
        audioCoverContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        val discCard = MaterialCardView(this).apply {
            radius = dp(56).toFloat()
            setCardBackgroundColor(Color.parseColor("#1E293B"))
            strokeColor = Color.parseColor("#38BDF8")
            strokeWidth = dp(2)
            layoutParams = LinearLayout.LayoutParams(dp(112), dp(112)).apply {
                bottomMargin = dp(20)
            }
        }
        val audioIcon = ImageView(this).apply {
            setImageResource(R.drawable.file_audio_icon)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        discCard.addView(audioIcon)
        audioTitleText = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        audioSubtitleText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            text = "音频播放模式 · 单击屏幕隐藏/呼出控制栏"
        }
        audioCoverContainer.addView(discCard)
        audioCoverContainer.addView(audioTitleText)
        audioCoverContainer.addView(audioSubtitleText)
        rootStage.addView(audioCoverContainer)

        // 3. Subtitle Overlay at bottom of video canvas
        subtitleOverlayCard = MaterialCardView(this).apply {
            radius = dp(8).toFloat()
            setCardBackgroundColor(Color.parseColor("#99000000"))
            cardElevation = 0f
            strokeWidth = 0
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                leftMargin = dp(24)
                rightMargin = dp(24)
                bottomMargin = dp(24)
            }
        }
        subtitleOverlayText = TextView(this).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            textSize = subtitleFontSizeSp
            setTextColor(subtitleTextColor)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 2f, Color.BLACK)
        }
        subtitleOverlayCard.addView(subtitleOverlayText)
        rootStage.addView(subtitleOverlayCard)

        // 4. MX Player Center Gesture HUD (Brightness / Volume / Seek)
        gestureHudCard = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            setCardBackgroundColor(Color.parseColor("#CC0F172A"))
            cardElevation = dp(8).toFloat()
            strokeWidth = dp(1)
            strokeColor = Color.parseColor("#33FFFFFF")
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                dp(180),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        val hudCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        gestureHudTitle = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        gestureHudProgress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(6)
            ).apply { topMargin = dp(8) }
        }
        hudCol.addView(gestureHudTitle)
        hudCol.addView(gestureHudProgress)
        gestureHudCard.addView(hudCol)
        rootStage.addView(gestureHudCard)

        // 5. MX Player Top Overlay Bar (Auto-Hiding)
        topOverlayBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#B8000000"))
            setPadding(dp(10), dp(10), dp(12), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        val backBtn = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(12), dp(4))
            setOnClickListener { finish() }
        }
        topTitleText = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topDecoderBadge = makeTopActionPill("HW+", "#2563EB") {
            showToast("MX 硬件加速解码 (HW+) 已启用")
        }
        topSpeedPill = makeTopActionPill("1.0x", "#1E293B") {
            showSpeedPopup()
        }
        topSubtitlePill = makeTopActionPill("字幕 CC", "#1E293B") {
            showSubtitleSettingsSheet()
        }
        val moreBtn = makeTopActionPill("⋮ 设置", "#1E293B") {
            showMoreSettingsDialog()
        }

        topOverlayBar.addView(backBtn)
        topOverlayBar.addView(topTitleText)
        topOverlayBar.addView(topDecoderBadge)
        topOverlayBar.addView(topSpeedPill)
        topOverlayBar.addView(topSubtitlePill)
        topOverlayBar.addView(moreBtn)
        rootStage.addView(topOverlayBar)

        // 6. MX Player Bottom Overlay Bar (Auto-Hiding)
        bottomOverlayBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#B8000000"))
            setPadding(dp(14), dp(8), dp(14), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        // Seek row
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        currentTimeText = TextView(this).apply {
            text = "00:00"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
        }
        progressSeekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        currentTimeText.text = formatDuration(progress.toLong())
                        updateSubtitleOverlay(progress.toLong())
                        showHud("进度 ${formatDuration(progress.toLong())}", ((progress.toFloat() / max.coerceAtLeast(1)) * 100).toInt())
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isUserSeeking = true
                    uiHandler.removeCallbacks(autoHideControlsRunnable)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isUserSeeking = false
                    val target = seekBar?.progress ?: 0
                    if (isPrepared) {
                        runCatching { mediaPlayer?.seekTo(target) }
                        updateSubtitleOverlay(target.toLong())
                    }
                    scheduleAutoHideControls()
                }
            })
        }
        totalDurationText = TextView(this).apply {
            text = "00:00"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#CBD5E1"))
        }
        seekRow.addView(currentTimeText)
        seekRow.addView(progressSeekBar)
        seekRow.addView(totalDurationText)
        bottomOverlayBar.addView(seekRow)

        // Single clean MX Player transport row: Lock | Prev | -10s | Play/Pause | +10s | Next | Aspect Ratio
        val transportRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }

        val lockBtn = makeTransportButton("🔒 锁定") {
            engageScreenLock()
        }
        val prevBtn = makeTransportButton("⏮") {
            playAdjacentFile(-1)
        }
        val rewindBtn = makeTransportButton("⏪ 10s") {
            seekByDelta(-10_000)
        }
        playPauseBtn = makeTransportButton("⏸ 暂停", isPrimary = true) {
            togglePlayPause()
        }
        val forwardBtn = makeTransportButton("10s ⏩") {
            seekByDelta(10_000)
        }
        val nextBtn = makeTransportButton("⏭") {
            playAdjacentFile(1)
        }
        aspectRatioBtn = makeTransportButton("📐 适应") {
            cycleAspectRatio()
        }

        transportRow.addView(lockBtn)
        transportRow.addView(prevBtn)
        transportRow.addView(rewindBtn)
        transportRow.addView(playPauseBtn)
        transportRow.addView(forwardBtn)
        transportRow.addView(nextBtn)
        transportRow.addView(aspectRatioBtn)
        bottomOverlayBar.addView(transportRow)
        rootStage.addView(bottomOverlayBar)

        // 7. Floating Unlock Pill (only shown briefly when screen is locked & tapped)
        floatingUnlockBtn = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            setCardBackgroundColor(Color.parseColor("#CC1E293B"))
            strokeColor = Color.parseColor("#38BDF8")
            strokeWidth = dp(1)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = dp(20)
                topMargin = dp(20)
            }
            setOnClickListener {
                isScreenLocked = false
                visibility = View.GONE
                setControlsOverlayVisibility(true)
                showToast("屏幕已解锁")
            }
        }
        val unlockTv = TextView(this).apply {
            text = "🔒 点击解锁屏幕"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        floatingUnlockBtn.addView(unlockTv)
        rootStage.addView(floatingUnlockBtn)

        // 8. Local Phone Video/Audio Browser Overlay (shown only when opened without a media file)
        localMediaBrowserOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val browserHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val browserBack = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(6), 0, dp(14), 0)
            setOnClickListener { finish() }
        }
        val browserTitle = TextView(this).apply {
            text = "MX 媒体库 · 选择手机本地音视频文件"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pickSysBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "浏览文件"
            textSize = 12f
            setTextColor(Color.WHITE)
            setOnClickListener {
                pickMediaFileLauncher.launch(arrayOf("video/*", "audio/*", "*/*"))
            }
        }
        browserHeader.addView(browserBack)
        browserHeader.addView(browserTitle)
        browserHeader.addView(pickSysBtn)
        localMediaBrowserOverlay.addView(browserHeader)

        val mediaScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        localMediaListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(24))
        }
        mediaScroll.addView(localMediaListContainer)
        localMediaBrowserOverlay.addView(mediaScroll)
        rootStage.addView(localMediaBrowserOverlay)

        setupStageTouchGestures(rootStage)
        return rootStage
    }

    private fun makeTopActionPill(text: String, bgHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor(bgHex))
                setStroke(dp(1), Color.parseColor("#475569"))
            }
            setOnClickListener {
                onClick()
                scheduleAutoHideControls()
            }
        }
    }

    private fun makeTransportButton(
        label: String,
        isPrimary: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = if (isPrimary) 14f else 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (isPrimary) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.parseColor("#2563EB"))
                }
            }
            setOnClickListener {
                onClick()
                scheduleAutoHideControls()
            }
        }
    }

    private fun setControlsOverlayVisibility(visible: Boolean) {
        areControlsVisible = visible
        if (visible && !isScreenLocked) {
            topOverlayBar.visibility = View.VISIBLE
            bottomOverlayBar.visibility = View.VISIBLE
            (subtitleOverlayCard.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.bottomMargin = dp(92)
                subtitleOverlayCard.layoutParams = lp
            }
            scheduleAutoHideControls()
        } else {
            topOverlayBar.visibility = View.GONE
            bottomOverlayBar.visibility = View.GONE
            (subtitleOverlayCard.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.bottomMargin = dp(22)
                subtitleOverlayCard.layoutParams = lp
            }
        }
    }

    private fun scheduleAutoHideControls() {
        uiHandler.removeCallbacks(autoHideControlsRunnable)
        uiHandler.postDelayed(autoHideControlsRunnable, 3200L)
    }

    private fun engageScreenLock() {
        isScreenLocked = true
        setControlsOverlayVisibility(false)
        floatingUnlockBtn.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideLockIconRunnable)
        uiHandler.postDelayed(hideLockIconRunnable, 2200L)
        showToast("已锁定屏幕触控，点击左上角锁图标可解锁")
    }

    private fun setupStageTouchGestures(stageView: View) {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (localMediaBrowserOverlay.visibility == View.VISIBLE) return false
                if (isScreenLocked) {
                    floatingUnlockBtn.visibility = View.VISIBLE
                    uiHandler.removeCallbacks(hideLockIconRunnable)
                    uiHandler.postDelayed(hideLockIconRunnable, 2500L)
                } else {
                    setControlsOverlayVisibility(!areControlsVisible)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isScreenLocked || localMediaBrowserOverlay.visibility == View.VISIBLE) return false
                val w = stageView.width.coerceAtLeast(1)
                when {
                    e.x < w * 0.3f -> seekByDelta(-10_000)
                    e.x > w * 0.7f -> seekByDelta(10_000)
                    else -> togglePlayPause()
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isScreenLocked || localMediaBrowserOverlay.visibility == View.VISIBLE) return false
                val startEvent = e1 ?: return false
                val stageWidth = stageView.width.coerceAtLeast(1)
                val stageHeight = stageView.height.coerceAtLeast(1)

                if (abs(distanceY) > abs(distanceX)) {
                    val deltaRatio = distanceY / (stageHeight * 0.75f)
                    if (startEvent.x < stageWidth / 2f) {
                        setScreenBrightness((currentBrightness + deltaRatio).coerceIn(0.05f, 1.0f))
                    } else {
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val curPct = ((curVol.toFloat() / maxVol.toFloat()) * 100f).toInt()
                        val newPct = (curPct + (deltaRatio * 100f).toInt()).coerceIn(0, 100)
                        setPlayerVolumePercent(newPct)
                    }
                } else {
                    val player = mediaPlayer
                    if (player != null && isPrepared) {
                        val dur = player.duration.coerceAtLeast(1)
                        val seekDelta = ((-distanceX / stageWidth) * 60_000f).toInt()
                        val target = (player.currentPosition + seekDelta).coerceIn(0, dur)
                        player.seekTo(target)
                        progressSeekBar.progress = target
                        currentTimeText.text = formatDuration(target.toLong())
                        updateSubtitleOverlay(target.toLong())
                        val pct = ((target.toFloat() / dur.toFloat()) * 100f).toInt()
                        showHud("进度 ${formatDuration(target.toLong())} / ${formatDuration(dur.toLong())}", pct)
                    }
                }
                return true
            }
        })

        stageView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun resolveInitialMedia(intent: Intent) {
        val pathExtra = intent.extraPath
        val localFile = runCatching { pathExtra?.toFile() }.getOrNull()
        currentLocalFile = localFile

        // Build sibling playlist from parent directory if opening a local media file
        if (localFile != null && localFile.parentFile != null) {
            val siblings = localFile.parentFile?.listFiles()?.filter { f ->
                f.isFile && isMediaExtension(f.extension)
            }?.sortedBy { it.name.lowercase(Locale.ROOT) }.orEmpty()
            playlistFiles = siblings
            playlistIndex = siblings.indexOfFirst { it.absolutePath == localFile.absolutePath }.coerceAtLeast(0)
        }

        val uri: Uri? = intent.data ?: pathExtra?.fileProviderUri
        val title = intent.getStringExtra(EXTRA_TITLE)
            ?: pathExtra?.fileName?.toString()
            ?: uri?.lastPathSegment
            ?: getString(R.string.media_player_title)

        val mime = intent.type?.let { MimeType(it) }
            ?: pathExtra?.let { MimeType.guessFromPath(it.fileName?.toString() ?: "") }
            ?: MimeType.guessFromPath(title)

        isVideoMode = intent.getBooleanExtra(EXTRA_IS_VIDEO, mime.isVideo || !isAudioExtension(title))
        if (uri != null) {
            localMediaBrowserOverlay.visibility = View.GONE
            loadMediaUri(uri, title, isVideoMode)
            localFile?.let { vf ->
                SubtitleParser.autoDetectSubtitleForVideo(vf)?.let { sub ->
                    loadSubtitleFromFile(sub, autoLoaded = true)
                }
            }
        } else {
            // Opened from navigation drawer without a specific file: scan real device media files!
            showLocalPhoneMediaBrowser()
        }
    }

    private fun isMediaExtension(ext: String): Boolean {
        return ext.lowercase(Locale.ROOT) in setOf(
            "mp4", "mkv", "avi", "mov", "webm", "flv", "3gp", "ts",
            "mp3", "flac", "wav", "m4a", "ogg", "aac"
        )
    }

    private fun isAudioExtension(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in setOf("mp3", "flac", "wav", "m4a", "ogg", "aac")
    }

    private fun showLocalPhoneMediaBrowser() {
        localMediaBrowserOverlay.visibility = View.VISIBLE
        localMediaListContainer.removeAllViews()
        val loadingTv = TextView(this).apply {
            text = "正在扫描手机存储中的视频与音频文件..."
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(8), dp(20), dp(8), dp(20))
        }
        localMediaListContainer.addView(loadingTv)

        thread {
            val foundMedia = mutableListOf<File>()
            val rootDir = Environment.getExternalStorageDirectory()
            fun scanDir(dir: File, depth: Int) {
                if (depth > 4 || !dir.exists() || !dir.canRead()) return
                val children = dir.listFiles() ?: return
                for (child in children) {
                    if (child.name.startsWith(".")) continue
                    if (child.isDirectory) {
                        if (!child.name.equals("Android", ignoreCase = true)) {
                            scanDir(child, depth + 1)
                        }
                    } else if (isMediaExtension(child.extension)) {
                        foundMedia.add(child)
                    }
                }
            }
            scanDir(rootDir, 0)
            val sorted = foundMedia.sortedByDescending { it.lastModified() }

            runOnUiThread {
                localMediaListContainer.removeAllViews()
                // Option to input remote HTTP/HTTPS stream or pick via SAF
                val topActionsRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, dp(12))
                }
                val openPickerBtn = MaterialButton(this).apply {
                    text = "📂 浏览手机文件夹选择音视频"
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = dp(8) }
                    setOnClickListener {
                        pickMediaFileLauncher.launch(arrayOf("video/*", "audio/*", "*/*"))
                    }
                }
                val streamBtn = MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = "🌐 播放网络流 URL"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setOnClickListener { showRemoteUrlDialog() }
                }
                topActionsRow.addView(openPickerBtn)
                topActionsRow.addView(streamBtn)
                localMediaListContainer.addView(topActionsRow)

                if (sorted.isEmpty()) {
                    val emptyTv = TextView(this).apply {
                        text = "当前手机目录下暂未扫描到音视频文件。\n可点击上方「浏览手机文件夹选择音视频」打开任意媒体文件。"
                        textSize = 14f
                        setTextColor(Color.parseColor("#94A3B8"))
                        setPadding(dp(8), dp(24), dp(8), dp(24))
                    }
                    localMediaListContainer.addView(emptyTv)
                } else {
                    playlistFiles = sorted
                    for ((idx, file) in sorted.withIndex()) {
                        val isVid = !isAudioExtension(file.name)
                        val card = MaterialCardView(this).apply {
                            radius = dp(12).toFloat()
                            setCardBackgroundColor(Color.parseColor("#1E293B"))
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = dp(8) }
                            setOnClickListener {
                                playlistIndex = idx
                                currentLocalFile = file
                                localMediaBrowserOverlay.visibility = View.GONE
                                val uri = Paths.get(file.absolutePath).fileProviderUri
                                loadMediaUri(uri, file.name, isVid)
                                SubtitleParser.autoDetectSubtitleForVideo(file)?.let { sub ->
                                    loadSubtitleFromFile(sub, autoLoaded = true)
                                }
                            }
                        }
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(14), dp(12), dp(14), dp(12))
                        }
                        val badge = TextView(this).apply {
                            text = if (isVid) "🎬" else "🎵"
                            textSize = 22f
                            setPadding(0, 0, dp(12), 0)
                        }
                        val col = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        val nameTv = TextView(this).apply {
                            text = file.name
                            textSize = 14.5f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(Color.WHITE)
                        }
                        val metaTv = TextView(this).apply {
                            val sizeStr = file.length().asFileSize().formatHumanReadable(this@MediaPlayerActivity)
                            text = "${file.parentFile?.name ?: ""} · $sizeStr"
                            textSize = 12f
                            setTextColor(Color.parseColor("#94A3B8"))
                        }
                        col.addView(nameTv)
                        col.addView(metaTv)
                        row.addView(badge)
                        row.addView(col)
                        card.addView(row)
                        localMediaListContainer.addView(card)
                    }
                }
            }
        }
    }

    private fun playAdjacentFile(delta: Int) {
        if (playlistFiles.isEmpty()) {
            seekByDelta(if (delta > 0) 10_000 else -10_000)
            return
        }
        val nextIdx = (playlistIndex + delta).coerceIn(0, playlistFiles.lastIndex)
        if (nextIdx == playlistIndex) {
            showToast(if (delta > 0) "已是列表最后一个文件" else "已是列表第一个文件")
            return
        }
        playlistIndex = nextIdx
        val nextFile = playlistFiles[nextIdx]
        currentLocalFile = nextFile
        currentSubtitleTrack = null
        val uri = Paths.get(nextFile.absolutePath).fileProviderUri
        loadMediaUri(uri, nextFile.name, !isAudioExtension(nextFile.name))
        SubtitleParser.autoDetectSubtitleForVideo(nextFile)?.let { sub ->
            loadSubtitleFromFile(sub, autoLoaded = true)
        }
    }

    private fun loadMediaUri(uri: Uri, title: String, isVideo: Boolean) {
        currentUri = uri
        currentTitle = title
        isVideoMode = isVideo

        topTitleText.text = title
        audioTitleText.text = title
        audioCoverContainer.visibility = if (isVideo) View.GONE else View.VISIBLE
        setControlsOverlayVisibility(true)

        prepareMediaPlayer(uri)
    }

    private fun prepareMediaPlayer(uri: Uri) {
        releasePlayer()
        isPrepared = false

        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(
                        if (isVideoMode) AudioAttributes.CONTENT_TYPE_MOVIE
                        else AudioAttributes.CONTENT_TYPE_MUSIC
                    )
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            player.setDataSource(this, uri)
            playbackSurface?.let { player.setSurface(it) }
            player.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) {
                    videoWidthPx = w
                    videoHeightPx = h
                    applyTextureAspectRatio()
                }
            }
            player.setOnPreparedListener { mp ->
                isPrepared = true
                videoWidthPx = mp.videoWidth
                videoHeightPx = mp.videoHeight
                applyTextureAspectRatio()

                val dur = mp.duration.coerceAtLeast(0)
                progressSeekBar.max = dur.coerceAtLeast(1)
                totalDurationText.text = formatDuration(dur.toLong())
                mp.start()
                if (currentSpeed != 1.0f) {
                    applyPlaybackSpeed(currentSpeed)
                }
                playPauseBtn.text = "⏸ 暂停"
                scheduleAutoHideControls()
            }
            player.setOnCompletionListener {
                playPauseBtn.text = "▶ 播放"
                setControlsOverlayVisibility(true)
            }
            player.setOnErrorListener { _, what, extra ->
                showToast("媒体加载异常 ($what/$extra)")
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            showToast("无法打开媒体源: ${e.localizedMessage}")
        }
    }

    private fun cycleAspectRatio() {
        val modes = AspectMode.entries
        aspectMode = modes[(aspectMode.ordinal + 1) % modes.size]
        aspectRatioBtn.text = "📐 ${aspectMode.label}"
        applyTextureAspectRatio()
        showHud("画面比例: ${aspectMode.label}", 100)
    }

    private fun applyTextureAspectRatio() {
        val stageW = rootStage.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val stageH = rootStage.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (videoWidthPx <= 0 || videoHeightPx <= 0) return

        val lp = FrameLayout.LayoutParams(stageW, stageH, Gravity.CENTER)
        val videoRatio = if (aspectMode == AspectMode.RATIO_16_9) 16f / 9f
        else videoWidthPx.toFloat() / videoHeightPx.toFloat()
        val screenRatio = stageW.toFloat() / stageH.toFloat()

        when (aspectMode) {
            AspectMode.FIT, AspectMode.RATIO_16_9 -> {
                if (videoRatio > screenRatio) {
                    lp.width = stageW
                    lp.height = (stageW / videoRatio).toInt()
                } else {
                    lp.height = stageH
                    lp.width = (stageH * videoRatio).toInt()
                }
            }
            AspectMode.FILL -> {
                if (videoRatio > screenRatio) {
                    lp.height = stageH
                    lp.width = (stageH * videoRatio).toInt()
                } else {
                    lp.width = stageW
                    lp.height = (stageW / videoRatio).toInt()
                }
            }
            AspectMode.STRETCH -> {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        textureView.layoutParams = lp
    }

    private fun updateSubtitleOverlay(positionMs: Long) {
        val track = currentSubtitleTrack
        if (track == null || !isSubtitleEnabled) {
            subtitleOverlayCard.visibility = View.GONE
            return
        }
        val activeText = track.findActiveText(positionMs, subtitleOffsetMs)
        if (activeText.isEmpty()) {
            subtitleOverlayCard.visibility = View.GONE
        } else {
            subtitleOverlayText.text = activeText
            subtitleOverlayText.textSize = subtitleFontSizeSp
            subtitleOverlayText.setTextColor(subtitleTextColor)
            subtitleOverlayCard.visibility = View.VISIBLE
        }
    }

    private fun loadSubtitleFromFile(file: File, autoLoaded: Boolean = false) {
        runCatching {
            val track = SubtitleParser.parseFromFile(file)
            currentSubtitleTrack = track
            isSubtitleEnabled = true
            topSubtitlePill.text = "字幕:${track.format}"
            val curPos = if (isPrepared) (mediaPlayer?.currentPosition?.toLong() ?: 0L) else 0L
            updateSubtitleOverlay(curPos)
            showToast("${if (autoLoaded) "已自动加载同目录字幕" else "已挂载字幕"}: ${file.name}")
        }.onFailure { e ->
            showToast("解析字幕失败: ${e.localizedMessage}")
        }
    }

    private fun loadSubtitleFromUri(uri: Uri) {
        runCatching {
            var displayName: String? = null
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    displayName = cursor.getString(idx)
                }
            }
            val track = SubtitleParser.parseFromUri(this, uri, displayName)
            currentSubtitleTrack = track
            isSubtitleEnabled = true
            topSubtitlePill.text = "字幕:${track.format}"
            val curPos = if (isPrepared) (mediaPlayer?.currentPosition?.toLong() ?: 0L) else 0L
            updateSubtitleOverlay(curPos)
            showToast("已加载外挂字幕: ${track.title} (${track.cues.size} 条)")
        }.onFailure { e ->
            showToast("加载字幕失败: ${e.localizedMessage}")
        }
    }

    private fun showSubtitleSettingsSheet() {
        val searchDirs = mutableListOf<File>()
        currentLocalFile?.parentFile?.let { searchDirs.add(it) }
        @Suppress("DEPRECATION")
        searchDirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        @Suppress("DEPRECATION")
        searchDirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES))
        val localSubFiles = SubtitleParser.listSubtitleFilesInFolders(searchDirs)

        val container = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        container.addView(content)

        val activeInfo = TextView(this).apply {
            val track = currentSubtitleTrack
            text = if (track != null) {
                "当前外挂字幕: ${track.title} (${track.format} · ${track.cues.size} 条)\n" +
                    "时间轴同步偏移: ${subtitleOffsetMs}ms · 字号: ${subtitleFontSizeSp.toInt()}sp"
            } else {
                "当前未加载外挂字幕（支持加载手机本地 .srt 与 .ass/.ssa 字幕文件）"
            }
            textSize = 13f
            setPadding(0, 0, 0, dp(10))
        }
        content.addView(activeInfo)

        var dialogRef: androidx.appcompat.app.AlertDialog? = null

        val pickFileBtn = MaterialButton(this).apply {
            text = "📂 选择手机中的 .SRT / .ASS 字幕文件"
            setOnClickListener {
                dialogRef?.dismiss()
                pickSubtitleLauncher.launch(arrayOf("*/*"))
            }
        }
        content.addView(pickFileBtn)

        if (localSubFiles.isNotEmpty()) {
            content.addView(TextView(this).apply {
                text = "视频同目录及下载目录发现的字幕文件:"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(4))
            })
            for (subFile in localSubFiles.take(10)) {
                val subBtn = MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = "${subFile.name} (${subFile.extension.uppercase(Locale.ROOT)})"
                    setOnClickListener {
                        loadSubtitleFromFile(subFile)
                        dialogRef?.dismiss()
                    }
                }
                content.addView(subBtn)
            }
        }

        // Font size & color & timing offset controls tucked inside this dialog
        content.addView(TextView(this).apply {
            text = "字幕字号与颜色调节"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        })

        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(15f to "小", 18f to "标准", 22f to "大", 26f to "特大").forEach { (spVal, label) ->
            val btn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4) }
                setOnClickListener {
                    subtitleFontSizeSp = spVal
                    subtitleOverlayText.textSize = spVal
                    activeInfo.text = "字幕字号已设为 ${spVal.toInt()}sp"
                }
            }
            sizeRow.addView(btn)
        }
        content.addView(sizeRow)

        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        listOf(
            Color.WHITE to "纯白",
            Color.parseColor("#FFD54F") to "明黄",
            Color.parseColor("#4DD0E1") to "天青",
            Color.parseColor("#81C784") to "亮绿"
        ).forEach { (colorInt, name) ->
            val btn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = name
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4) }
                setOnClickListener {
                    subtitleTextColor = colorInt
                    subtitleOverlayText.setTextColor(colorInt)
                }
            }
            colorRow.addView(btn)
        }
        content.addView(colorRow)

        val offsetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val minusBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "提前 0.5s"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                subtitleOffsetMs -= 500L
                activeInfo.text = "当前时间轴偏移: ${subtitleOffsetMs}ms"
            }
        }
        val resetBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "同步重置"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
            setOnClickListener {
                subtitleOffsetMs = 0L
                activeInfo.text = "当前时间轴偏移: 0ms"
            }
        }
        val plusBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "延后 0.5s"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                subtitleOffsetMs += 500L
                activeInfo.text = "当前时间轴偏移: +${subtitleOffsetMs}ms"
            }
        }
        offsetRow.addView(minusBtn)
        offsetRow.addView(resetBtn)
        offsetRow.addView(plusBtn)
        content.addView(offsetRow)

        dialogRef = MaterialAlertDialogBuilder(this)
            .setTitle("MX 外挂字幕设置 (SRT / ASS)")
            .setView(container)
            .setNeutralButton(if (isSubtitleEnabled) "隐藏字幕" else "显示字幕") { _, _ ->
                isSubtitleEnabled = !isSubtitleEnabled
                val curPos = if (isPrepared) (mediaPlayer?.currentPosition?.toLong() ?: 0L) else 0L
                updateSubtitleOverlay(curPos)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showMoreSettingsDialog() {
        val items = arrayOf(
            "打开手机中的其他音视频文件",
            "加载外挂字幕 (.SRT / .ASS)",
            "播放倍速选择 (0.5x ~ 2.0x)",
            "画面比例切换 (${aspectMode.label})",
            "输入网络流媒体地址 (HTTP / HTTPS)"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("MX 播放器选项")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showLocalPhoneMediaBrowser()
                    1 -> showSubtitleSettingsSheet()
                    2 -> showSpeedPopup()
                    3 -> cycleAspectRatio()
                    4 -> showRemoteUrlDialog()
                }
            }
            .show()
    }

    private fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (!isPrepared) return
        if (player.isPlaying) {
            player.pause()
            playPauseBtn.text = "▶ 播放"
            showHud("⏸ 已暂停", 0)
        } else {
            player.start()
            playPauseBtn.text = "⏸ 暂停"
            showHud("▶ 继续播放", 100)
            scheduleAutoHideControls()
        }
    }

    private fun seekByDelta(deltaMs: Int) {
        val player = mediaPlayer ?: return
        if (!isPrepared) return
        val dur = player.duration.coerceAtLeast(1)
        val target = (player.currentPosition + deltaMs).coerceIn(0, dur)
        player.seekTo(target)
        progressSeekBar.progress = target
        currentTimeText.text = formatDuration(target.toLong())
        updateSubtitleOverlay(target.toLong())
        val sign = if (deltaMs >= 0) "+" else ""
        val pct = ((target.toFloat() / dur.toFloat()) * 100f).toInt()
        showHud("${sign}${deltaMs / 1000}s · ${formatDuration(target.toLong())}", pct)
    }

    private fun applyPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        topSpeedPill.text = "${speed}x"
        val player = mediaPlayer
        if (player != null && isPrepared && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val params = player.playbackParams ?: PlaybackParams()
                params.speed = speed
                player.playbackParams = params
            }
        }
        showHud("倍速 ${speed}x", ((speed / 2.0f) * 100).toInt())
    }

    private fun setScreenBrightness(brightness: Float) {
        val clamped = brightness.coerceIn(0.05f, 1.0f)
        currentBrightness = clamped
        val lp = window.attributes
        lp.screenBrightness = clamped
        window.attributes = lp
        val pct = (clamped * 100f).toInt()
        showHud("☀️ 亮度 $pct%", pct)
    }

    private fun setPlayerVolumePercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetVol = ((clamped / 100f) * maxVol).toInt().coerceIn(0, maxVol)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
        val norm = clamped / 100f
        runCatching { mediaPlayer?.setVolume(norm, norm) }
        showHud("🔊 音量 $clamped%", clamped)
    }

    private fun showSpeedPopup() {
        val options = arrayOf("0.5x 慢速", "0.75x 较慢", "1.0x 正常速度", "1.25x 较快", "1.5x 快速", "2.0x 二倍速")
        val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        MaterialAlertDialogBuilder(this)
            .setTitle("MX 播放倍速")
            .setItems(options) { _, which ->
                applyPlaybackSpeed(values[which])
            }
            .show()
    }

    private fun showRemoteUrlDialog() {
        val input = EditText(this).apply {
            hint = "输入 http:// 或 https:// 音视频流直链"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(
                currentUri?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString().orEmpty()
            )
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("播放网络音视频流 (HTTP / HTTPS)")
            .setView(input)
            .setPositiveButton("立即播放") { _, _ ->
                val urlStr = input.text?.toString()?.trim().orEmpty()
                if (urlStr.isNotEmpty()) {
                    localMediaBrowserOverlay.visibility = View.GONE
                    val uri = Uri.parse(urlStr)
                    loadMediaUri(
                        uri = uri,
                        title = uri.lastPathSegment ?: "网络媒体流",
                        isVideo = !isAudioExtension(urlStr)
                    )
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showHud(message: String, progressPercent: Int) {
        gestureHudTitle.text = message
        gestureHudProgress.progress = progressPercent.coerceIn(0, 100)
        gestureHudCard.visibility = View.VISIBLE
        gestureHudCard.alpha = 1f
        uiHandler.removeCallbacks(hideHudRunnable)
        uiHandler.postDelayed(hideHudRunnable, 1000L)
    }

    private fun releasePlayer() {
        isPrepared = false
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val s = Surface(surface)
        playbackSurface = s
        runCatching { mediaPlayer?.setSurface(s) }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        applyTextureAspectRatio()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        runCatching { mediaPlayer?.setSurface(null) }
        playbackSurface?.release()
        playbackSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    companion object {
        private const val EXTRA_TITLE = "extra_media_title"
        private const val EXTRA_IS_VIDEO = "extra_is_video"

        fun createIntent(
            context: Context? = null,
            uri: Uri? = null,
            title: String? = null,
            isVideo: Boolean = true
        ): Intent =
            MediaPlayerActivity::class.createIntent().apply {
                if (uri != null) {
                    data = uri
                }
                if (title != null) {
                    putExtra(EXTRA_TITLE, title)
                }
                putExtra(EXTRA_IS_VIDEO, isVideo)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        fun createIntent(
            context: Context,
            path: Path,
            isAudio: Boolean = false
        ): Intent =
            Intent(context, MediaPlayerActivity::class.java).apply {
                extraPath = path
                putExtra(EXTRA_TITLE, path.fileName?.toString() ?: "")
                putExtra(EXTRA_IS_VIDEO, !isAudio)
            }
    }
}
