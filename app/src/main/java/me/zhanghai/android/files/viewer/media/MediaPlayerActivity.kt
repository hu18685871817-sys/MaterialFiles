package me.zhanghai.android.files.viewer.media

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.guessFromPath
import me.zhanghai.android.files.file.isVideo
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.showToast
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * VLC for Android (videolan/vlc) Port & Adaptation for Material Files.
 * Features VLC's signature Traffic Cone Orange (#FF8800) OSD, 200% Audio Boost (LoudnessEnhancer),
 * multi-band Hardware Equalizer + BassBoost, A-B Repeat Loop, Sleep Timer, Subtitle Sync (+/- 50ms),
 * Adaptive Landscape/Portrait Orientation, and Status-Bar Inset Protection.
 */
class MediaPlayerActivity : AppActivity(), TextureView.SurfaceTextureListener {

    enum class AspectMode(val label: String) {
        BEST_FIT("最佳适应"),
        FIT_SCREEN("适应屏幕"),
        FILL("填充裁剪"),
        STRETCH("全屏拉伸"),
        RATIO_16_9("16:9"),
        RATIO_4_3("4:3"),
        ORIGINAL("居中原始")
    }

    enum class OrientationMode(val label: String, val shortLabel: String, val iconOnly: String) {
        AUTO_BY_VIDEO("自适应横竖屏 (按视频比例)", "🔄 自适应", "🔄"),
        SENSOR("随重力自动旋转", "🔄 重力旋转", "🧭"),
        LANDSCAPE("强制横屏", "↔️ 横屏", "↔️"),
        PORTRAIT("强制竖屏", "↕️ 竖屏", "↕️")
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
    private var aspectMode: AspectMode = AspectMode.BEST_FIT
    private var orientationMode: OrientationMode = OrientationMode.AUTO_BY_VIDEO
    private var isCurrentlyLandscape: Boolean = false
    private var vlcHardwareAccelMode: Int = 2 // 0: 解码禁用, 1: 仅解码加速, 2: LibVLC完全硬件加速

    // VLC Audio DSP: 200% Volume Boost + Equalizer + BassBoost
    private var allowVolumeBoost200: Boolean = true
    private var currentVolumePercent: Int = 100 // 0 .. 200%
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var vlcEqualizer: Equalizer? = null
    private var vlcBassBoost: BassBoost? = null
    private var isEqualizerEnabled: Boolean = false
    private var selectedEqPresetIndex: Int = 0
    private var bassBoostStrength: Int = 0 // 0..1000

    // VLC A-B Repeat Loop & Sleep Timer
    private var loopPointAMs: Long = -1L
    private var loopPointBMs: Long = -1L
    private var sleepTimerDeadlineMs: Long = 0L

    // Gesture & Playback Global Preferences
    private var autoLoadSameNameSubtitle: Boolean = true
    private var enableVerticalGestureBrightVol: Boolean = true
    private var enableHorizontalGestureSeek: Boolean = true
    private var enableDoubleTapSeekPause: Boolean = true
    private var enableAutoHideControls: Boolean = true

    private var playlistFiles: List<File> = emptyList()
    private var playlistIndex: Int = 0

    // VLC Overlay & Lock State
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

    // VLC Center Gesture HUD (Brightness / 200% Volume Boost / Seek)
    private lateinit var gestureHudCard: MaterialCardView
    private lateinit var gestureHudTitle: TextView
    private lateinit var gestureHudProgress: ProgressBar

    // VLC Top & Bottom Auto-Hiding Overlay Bars
    private lateinit var topOverlayBar: LinearLayout
    private lateinit var topTitleText: TextView
    private lateinit var topRotatePill: TextView
    private lateinit var topAbLoopPill: TextView
    private lateinit var topEqPill: TextView
    private lateinit var topSubtitlePill: TextView

    private lateinit var bottomOverlayBar: LinearLayout
    private lateinit var progressSeekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalDurationText: TextView
    private lateinit var playPauseBtn: TextView
    private lateinit var speedBtn: TextView
    private lateinit var orientationBtn: TextView
    private lateinit var aspectRatioBtn: TextView

    // Floating Unlock Button when screen is locked
    private lateinit var floatingUnlockBtn: MaterialCardView

    // Dedicated VLC Settings Overlay when opened from Home/Sidebar without a file
    private lateinit var localMediaBrowserOverlay: LinearLayout
    private lateinit var settingsHeaderBar: LinearLayout
    private lateinit var localMediaListContainer: LinearLayout

    private val pickSubtitleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            loadSubtitleFromUri(uri)
        }
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            val player = mediaPlayer
            if (player != null && isPrepared && !isUserSeeking) {
                runCatching {
                    val pos = player.currentPosition.coerceAtLeast(0)
                    val dur = player.duration.coerceAtLeast(1)

                    // Check VLC A-B Repeat Loop
                    if (loopPointAMs >= 0L && loopPointBMs > loopPointAMs && pos >= loopPointBMs) {
                        player.seekTo(loopPointAMs.toInt())
                    }

                    // Check VLC Sleep Timer
                    if (sleepTimerDeadlineMs > 0L && System.currentTimeMillis() >= sleepTimerDeadlineMs) {
                        sleepTimerDeadlineMs = 0L
                        if (player.isPlaying) {
                            player.pause()
                            playPauseBtn.text = "▶"
                            showToast("🔶 VLC 睡眠定时已到，已自动暂停播放")
                        }
                    }

                    progressSeekBar.max = dur
                    progressSeekBar.progress = pos
                    currentTimeText.text = formatDuration(pos.toLong())
                    totalDurationText.text = formatDuration(dur.toLong())
                    updateSubtitleOverlay(pos.toLong())
                }
            }
            uiHandler.postDelayed(this, 160L)
        }
    }

    private val autoHideControlsRunnable = Runnable {
        if (enableAutoHideControls && mediaPlayer?.isPlaying == true && areControlsVisible) {
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

        loadPlayerGlobalPreferences()
        setContentView(buildVlcPlayerLayout())
        resolveInitialMedia(intent)
        uiHandler.post(progressUpdater)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadPlayerGlobalPreferences()
        resolveInitialMedia(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isCurrentlyLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        updateOrientationBadges()
        ViewCompat.requestApplyInsets(rootStage)
        rootStage.post {
            applyTextureAspectRatio()
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        playbackSurface?.release()
        playbackSurface = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun getFallbackStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val sysHeight = if (resId > 0) resources.getDimensionPixelSize(resId) else 0
        return maxOf(sysHeight, dp(28))
    }

    private fun loadPlayerGlobalPreferences() {
        val prefs = getSharedPreferences(PREFS_VLC_SETTINGS, Context.MODE_PRIVATE)
        orientationMode = runCatching {
            OrientationMode.valueOf(
                prefs.getString("orientation_mode", OrientationMode.AUTO_BY_VIDEO.name)
                    ?: OrientationMode.AUTO_BY_VIDEO.name
            )
        }.getOrDefault(OrientationMode.AUTO_BY_VIDEO)

        aspectMode = runCatching {
            AspectMode.valueOf(
                prefs.getString("aspect_mode", AspectMode.BEST_FIT.name) ?: AspectMode.BEST_FIT.name
            )
        }.getOrDefault(AspectMode.BEST_FIT)

        currentSpeed = prefs.getFloat("default_speed", 1.0f).coerceIn(0.25f, 4.0f)
        vlcHardwareAccelMode = prefs.getInt("vlc_hw_accel_mode", 2).coerceIn(0, 2)
        allowVolumeBoost200 = prefs.getBoolean("allow_volume_boost_200", true)
        isEqualizerEnabled = prefs.getBoolean("equalizer_enabled", false)
        selectedEqPresetIndex = prefs.getInt("equalizer_preset_idx", 0)
        bassBoostStrength = prefs.getInt("bass_boost_strength", 0).coerceIn(0, 1000)

        autoLoadSameNameSubtitle = prefs.getBoolean("auto_load_subtitle", true)
        subtitleFontSizeSp = prefs.getFloat("subtitle_font_size_sp", 18f).coerceIn(14f, 28f)
        subtitleTextColor = prefs.getInt("subtitle_text_color", Color.WHITE)
        enableVerticalGestureBrightVol = prefs.getBoolean("gesture_bright_vol", true)
        enableHorizontalGestureSeek = prefs.getBoolean("gesture_horizontal_seek", true)
        enableDoubleTapSeekPause = prefs.getBoolean("gesture_double_tap", true)
        enableAutoHideControls = prefs.getBoolean("auto_hide_controls", true)
        isCurrentlyLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        // Initialize currentVolumePercent from system STREAM_MUSIC
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        currentVolumePercent = ((curVol.toFloat() / maxVol.toFloat()) * 100f).toInt().coerceIn(0, 100)
    }

    private fun savePlayerGlobalPreferences() {
        getSharedPreferences(PREFS_VLC_SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putString("orientation_mode", orientationMode.name)
            .putString("aspect_mode", aspectMode.name)
            .putFloat("default_speed", currentSpeed)
            .putInt("vlc_hw_accel_mode", vlcHardwareAccelMode)
            .putBoolean("allow_volume_boost_200", allowVolumeBoost200)
            .putBoolean("equalizer_enabled", isEqualizerEnabled)
            .putInt("equalizer_preset_idx", selectedEqPresetIndex)
            .putInt("bass_boost_strength", bassBoostStrength)
            .putBoolean("auto_load_subtitle", autoLoadSameNameSubtitle)
            .putFloat("subtitle_font_size_sp", subtitleFontSizeSp)
            .putInt("subtitle_text_color", subtitleTextColor)
            .putBoolean("gesture_bright_vol", enableVerticalGestureBrightVol)
            .putBoolean("gesture_horizontal_seek", enableHorizontalGestureSeek)
            .putBoolean("gesture_double_tap", enableDoubleTapSeekPause)
            .putBoolean("auto_hide_controls", enableAutoHideControls)
            .apply()
    }

    private fun buildVlcPlayerLayout(): View {
        val initialStatusBarTop = getFallbackStatusBarHeight()

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

        // 2. VLC Audio Stage View (Traffic Cone Orange Theme for Audio Playback)
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
        val coneDiscCard = MaterialCardView(this).apply {
            radius = dp(60).toFloat()
            setCardBackgroundColor(Color.parseColor("#1C1917"))
            strokeColor = Color.parseColor(VLC_ORANGE_HEX)
            strokeWidth = dp(3)
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(120)).apply {
                bottomMargin = dp(20)
            }
        }
        val coneIconTv = TextView(this).apply {
            text = "🔶"
            textSize = 48f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        coneDiscCard.addView(coneIconTv)
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
            setTextColor(Color.parseColor("#FDBA74"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            text = "VLC Audio Engine · 支持 10 段均衡器与 200% 音量增益"
        }
        audioCoverContainer.addView(coneDiscCard)
        audioCoverContainer.addView(audioTitleText)
        audioCoverContainer.addView(audioSubtitleText)
        rootStage.addView(audioCoverContainer)

        // 3. VLC Subtitle Overlay at bottom of video canvas
        subtitleOverlayCard = MaterialCardView(this).apply {
            radius = dp(8).toFloat()
            setCardBackgroundColor(Color.parseColor("#B3000000"))
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

        // 4. VLC Center Gesture OSD HUD (Brightness / 200% Volume Boost / Seek)
        gestureHudCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            setCardBackgroundColor(Color.parseColor("#D918181B"))
            cardElevation = dp(8).toFloat()
            strokeWidth = dp(2)
            strokeColor = Color.parseColor(VLC_ORANGE_HEX)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                dp(210),
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
            textSize = 14.5f
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
            progressTintList = ColorStateList.valueOf(Color.parseColor(VLC_ORANGE_HEX))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(6)
            ).apply { topMargin = dp(8) }
        }
        hudCol.addView(gestureHudTitle)
        hudCol.addView(gestureHudProgress)
        gestureHudCard.addView(hudCol)
        rootStage.addView(gestureHudCard)

        // 5. VLC Top OSD Bar (Protected from Status Bar & Cutout Obstruction)
        topOverlayBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#CC121212"))
            setPadding(dp(10), initialStatusBarTop + dp(8), dp(12), dp(10))
            elevation = dp(6).toFloat()
            isClickable = true
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
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { finish() }
        }
        val vlcConeBadge = TextView(this).apply {
            text = "🔶"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(VLC_ORANGE_HEX))
            setPadding(0, 0, dp(6), 0)
        }
        topTitleText = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRotatePill = makeTopActionPill("🔄", "#27272A") {
            toggleOrSelectOrientation()
        }
        topAbLoopPill = makeTopActionPill("🔁", "#27272A") {
            cycleAbRepeatLoop()
        }
        topEqPill = makeTopActionPill("🎛️", "#27272A") {
            showVlcEqualizerDialog()
        }
        topSubtitlePill = makeTopActionPill("💬", "#27272A") {
            showSubtitleSettingsSheet()
        }
        val moreBtn = makeTopActionPill("⋮", VLC_ORANGE_DARK_HEX) {
            showVlcToolsDialog()
        }

        topOverlayBar.addView(backBtn)
        topOverlayBar.addView(vlcConeBadge)
        topOverlayBar.addView(topTitleText)
        topOverlayBar.addView(topRotatePill)
        topOverlayBar.addView(topAbLoopPill)
        topOverlayBar.addView(topEqPill)
        topOverlayBar.addView(topSubtitlePill)
        topOverlayBar.addView(moreBtn)
        rootStage.addView(topOverlayBar)

        // 6. VLC Bottom OSD Bar (Traffic Cone Orange Timeline + Transport)
        bottomOverlayBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC121212"))
            setPadding(dp(14), dp(8), dp(14), dp(14))
            elevation = dp(6).toFloat()
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        currentTimeText = TextView(this).apply {
            text = "00:00"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor(VLC_ORANGE_HEX))
            setTypeface(typeface, Typeface.BOLD)
        }
        progressSeekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            max = 1000
            progressTintList = ColorStateList.valueOf(Color.parseColor(VLC_ORANGE_HEX))
            thumbTintList = ColorStateList.valueOf(Color.parseColor(VLC_ORANGE_HEX))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        currentTimeText.text = formatDuration(progress.toLong())
                        updateSubtitleOverlay(progress.toLong())
                        showHud(
                            "🔶 定位 ${formatDuration(progress.toLong())}",
                            ((progress.toFloat() / max.coerceAtLeast(1)) * 100).toInt()
                        )
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
            setTextColor(Color.parseColor("#D4D4D8"))
            setOnClickListener {
                showJumpToTimeDialog()
            }
        }
        seekRow.addView(currentTimeText)
        seekRow.addView(progressSeekBar)
        seekRow.addView(totalDurationText)
        bottomOverlayBar.addView(seekRow)

        // VLC Transport Row: Lock | Speed | Prev | -10s | Orange Play/Pause | +10s | Next | Rotate | Aspect
        val transportRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }

        val lockBtn = makeTransportButton("🔒") {
            engageScreenLock()
        }
        speedBtn = makeTransportButton("${currentSpeed}x") {
            showSpeedPopup()
        }
        val prevBtn = makeTransportButton("⏮") {
            playAdjacentFile(-1)
        }
        val rewindBtn = makeTransportButton("⏪") {
            seekByDelta(-10_000)
        }
        playPauseBtn = makeTransportButton("⏸", isPrimary = true) {
            togglePlayPause()
        }
        val forwardBtn = makeTransportButton("⏩") {
            seekByDelta(10_000)
        }
        val nextBtn = makeTransportButton("⏭") {
            playAdjacentFile(1)
        }
        orientationBtn = makeTransportButton(orientationMode.iconOnly) {
            showOrientationModeDialog()
        }
        aspectRatioBtn = makeTransportButton("📐") {
            cycleAspectRatio()
        }

        transportRow.addView(lockBtn)
        transportRow.addView(speedBtn)
        transportRow.addView(prevBtn)
        transportRow.addView(rewindBtn)
        transportRow.addView(playPauseBtn)
        transportRow.addView(forwardBtn)
        transportRow.addView(nextBtn)
        transportRow.addView(orientationBtn)
        transportRow.addView(aspectRatioBtn)
        bottomOverlayBar.addView(transportRow)
        rootStage.addView(bottomOverlayBar)

        // 7. Floating Unlock Pill (Status Bar Inset Safe)
        floatingUnlockBtn = MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            setCardBackgroundColor(Color.parseColor("#D918181B"))
            strokeColor = Color.parseColor(VLC_ORANGE_HEX)
            strokeWidth = dp(2)
            visibility = View.GONE
            elevation = dp(8).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                dp(44),
                dp(44),
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = dp(20)
                topMargin = initialStatusBarTop + dp(14)
            }
            setOnClickListener {
                isScreenLocked = false
                visibility = View.GONE
                setControlsOverlayVisibility(true)
                showToast("🔶 VLC 屏幕锁已解除")
            }
        }
        val unlockTv = TextView(this).apply {
            text = "🔓"
            textSize = 18f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        floatingUnlockBtn.addView(unlockTv)
        rootStage.addView(floatingUnlockBtn)

        // 8. Dedicated VLC Global Settings Center (shown when opened from Home/Sidebar without a media file)
        localMediaBrowserOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            visibility = View.GONE
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        settingsHeaderBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1C1917"))
            setPadding(dp(14), initialStatusBarTop + dp(10), dp(14), dp(12))
        }
        val browserBack = TextView(this).apply {
            text = "←"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(14), dp(4))
            setOnClickListener { finish() }
        }
        val browserTitle = TextView(this).apply {
            text = "🔶 VLC 音视频播放器 · 全局功能设置"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(VLC_ORANGE_HEX))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        settingsHeaderBar.addView(browserBack)
        settingsHeaderBar.addView(browserTitle)
        localMediaBrowserOverlay.addView(settingsHeaderBar)

        val mediaScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        localMediaListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(28))
        }
        mediaScroll.addView(localMediaListContainer)
        localMediaBrowserOverlay.addView(mediaScroll)
        rootStage.addView(localMediaBrowserOverlay)

        // Apply WindowInsets so topOverlayBar, floatingUnlockBtn, bottomOverlayBar, and settingsHeaderBar
        // are NEVER blocked by the status bar or display cutout
        ViewCompat.setOnApplyWindowInsetsListener(rootStage) { _, insets ->
            val sysBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val safeTop = maxOf(sysBars.top, getFallbackStatusBarHeight())
            val safeBottom = maxOf(sysBars.bottom, 0)
            val safeLeft = maxOf(sysBars.left, dp(10))
            val safeRight = maxOf(sysBars.right, dp(12))

            topOverlayBar.setPadding(safeLeft, safeTop + dp(8), safeRight, dp(10))
            bottomOverlayBar.setPadding(
                maxOf(sysBars.left, dp(14)),
                dp(8),
                maxOf(sysBars.right, dp(14)),
                safeBottom + dp(12)
            )
            settingsHeaderBar.setPadding(
                maxOf(sysBars.left, dp(14)),
                safeTop + dp(10),
                maxOf(sysBars.right, dp(14)),
                dp(12)
            )
            (floatingUnlockBtn.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.topMargin = safeTop + dp(14)
                lp.leftMargin = maxOf(sysBars.left, dp(20))
                floatingUnlockBtn.layoutParams = lp
            }
            insets
        }

        setupStageTouchGestures(rootStage)
        return rootStage
    }

    private fun makeTopActionPill(text: String, bgHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor(bgHex))
                setStroke(dp(1), Color.parseColor(VLC_ORANGE_HEX))
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
            textSize = if (isPrimary) 16f else 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (isPrimary) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.parseColor(VLC_ORANGE_HEX))
                }
            }
            setOnClickListener {
                onClick()
                scheduleAutoHideControls()
            }
        }
    }

    /**
     * VLC A-B Repeat Loop: Point A -> Point B -> Clear.
     */
    private fun cycleAbRepeatLoop() {
        val pos = mediaPlayer?.currentPosition?.toLong() ?: 0L
        when {
            loopPointAMs < 0L -> {
                loopPointAMs = pos
                loopPointBMs = -1L
                topAbLoopPill.text = "🔁A"
                showHud("🔁 已设置 A 点: ${formatDuration(loopPointAMs)}", 50)
            }
            loopPointBMs < 0L -> {
                if (pos <= loopPointAMs + 500L) {
                    showToast("B 点时间需大于 A 点 (${formatDuration(loopPointAMs)})")
                } else {
                    loopPointBMs = pos
                    topAbLoopPill.text = "🔁AB"
                    showHud(
                        "🔁 A-B 循环: ${formatDuration(loopPointAMs)} → ${formatDuration(loopPointBMs)}",
                        100
                    )
                }
            }
            else -> {
                loopPointAMs = -1L
                loopPointBMs = -1L
                topAbLoopPill.text = "🔁"
                showHud("🔁 已关闭 A-B 区间循环", 0)
            }
        }
    }

    /**
     * VLC Hardware Equalizer, Preset Selector & BassBoost Dialog.
     */
    private fun showVlcEqualizerDialog() {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        scroll.addView(col)

        val enableCheck = CheckBox(this).apply {
            text = "启用 VLC 硬件均衡器与重低音引擎 (Equalizer + BassBoost)"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            isChecked = isEqualizerEnabled
            setOnCheckedChangeListener { _, checked ->
                isEqualizerEnabled = checked
                applyAudioEffectsState()
                savePlayerGlobalPreferences()
            }
        }
        col.addView(enableCheck)

        // VLC Presets
        val eq = vlcEqualizer
        val presetNames = if (eq != null && eq.numberOfPresets > 0) {
            (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }
        } else {
            listOf("Flat 原声", "Classical 古典", "Club 俱乐部", "Dance 舞曲", "Full Bass 超重低音", "Full Treble 高音增强", "Pop 流行", "Rock 摇滚", "Live 现场")
        }

        col.addView(TextView(this).apply {
            text = "VLC 音效预设 (点击切换):"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(6))
        })

        val presetWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        presetNames.chunked(3).forEach { rowPresets ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            rowPresets.forEach { pName ->
                val idx = presetNames.indexOf(pName)
                val btn = MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = if (idx == selectedEqPresetIndex) "✓ $pName" else pName
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = dp(4) }
                    setOnClickListener {
                        selectedEqPresetIndex = idx
                        isEqualizerEnabled = true
                        enableCheck.isChecked = true
                        runCatching {
                            if (eq != null && idx < eq.numberOfPresets) {
                                eq.usePreset(idx.toShort())
                            }
                        }
                        applyAudioEffectsState()
                        savePlayerGlobalPreferences()
                        showToast("🔶 VLC 音效已切换为: $pName")
                    }
                }
                row.addView(btn)
            }
            presetWrap.addView(row)
        }
        col.addView(presetWrap)

        // Bass Boost slider
        col.addView(TextView(this).apply {
            text = "VLC 重低音增益 (Bass Boost): ${(bassBoostStrength / 10)}%"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        })
        val bassSeek = SeekBar(this).apply {
            max = 1000
            progress = bassBoostStrength
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        bassBoostStrength = progress
                        isEqualizerEnabled = true
                        enableCheck.isChecked = true
                        applyAudioEffectsState()
                        savePlayerGlobalPreferences()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        col.addView(bassSeek)

        // Volume Boost 0..200% slider
        val volLabel = TextView(this).apply {
            text = "VLC 音量与 200% 硬件增益: $currentVolumePercent%"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }
        col.addView(volLabel)
        val volSeek = SeekBar(this).apply {
            max = if (allowVolumeBoost200) 200 else 100
            progress = currentVolumePercent.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        setPlayerVolumePercent(progress)
                        volLabel.text = "VLC 音量与 200% 硬件增益: $progress%"
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        col.addView(volSeek)

        MaterialAlertDialogBuilder(this)
            .setTitle("🔶 VLC 音频均衡器与 200% 增益")
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun setupAudioEffectsForSession(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        releaseAudioEffects()
        runCatching {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId)
        }
        runCatching {
            vlcEqualizer = Equalizer(0, audioSessionId).apply {
                if (selectedEqPresetIndex in 0 until numberOfPresets.toInt()) {
                    usePreset(selectedEqPresetIndex.toShort())
                }
            }
        }
        runCatching {
            vlcBassBoost = BassBoost(0, audioSessionId)
        }
        applyAudioEffectsState()
    }

    private fun applyAudioEffectsState() {
        runCatching {
            vlcEqualizer?.enabled = isEqualizerEnabled
            vlcBassBoost?.let { bb ->
                bb.enabled = isEqualizerEnabled && bassBoostStrength > 0
                if (bb.strengthSupported) {
                    bb.setStrength(bassBoostStrength.toShort())
                }
            }
            if (::topEqPill.isInitialized) {
                topEqPill.text = "🎛️"
            }
        }
        applyLoudnessBoost()
    }

    private fun applyLoudnessBoost() {
        runCatching {
            val enhancer = loudnessEnhancer ?: return
            if (allowVolumeBoost200 && currentVolumePercent > 100) {
                // Map 101%..200% to +20mB..+1200mB (+12dB gain)
                val targetGainMb = ((currentVolumePercent - 100) * 12)
                enhancer.setTargetGain(targetGainMb)
                enhancer.enabled = true
            } else {
                enhancer.setTargetGain(0)
                enhancer.enabled = false
            }
        }
    }

    private fun releaseAudioEffects() {
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        runCatching { vlcEqualizer?.release() }
        vlcEqualizer = null
        runCatching { vlcBassBoost?.release() }
        vlcBassBoost = null
    }

    private fun toggleOrSelectOrientation() {
        val currentIsLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (currentIsLand) {
            orientationMode = OrientationMode.PORTRAIT
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            isCurrentlyLandscape = false
            showHud("↕️ VLC 已切换为竖屏播放", 100)
        } else {
            orientationMode = OrientationMode.LANDSCAPE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            isCurrentlyLandscape = true
            showHud("↔️ VLC 已切换为横屏播放", 100)
        }
        savePlayerGlobalPreferences()
        updateOrientationBadges()
        rootStage.postDelayed({ applyTextureAspectRatio() }, 180L)
    }

    private fun showOrientationModeDialog() {
        val modes = OrientationMode.entries
        val labels = modes.map { mode ->
            if (mode == orientationMode) "✓ ${mode.label}" else mode.label
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("🔶 VLC 自适应横竖屏与旋转模式")
            .setItems(labels) { _, which ->
                orientationMode = modes[which]
                savePlayerGlobalPreferences()
                applyOrientationForCurrentMedia(showFeedback = true)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun applyOrientationForCurrentMedia(showFeedback: Boolean = false) {
        when (orientationMode) {
            OrientationMode.AUTO_BY_VIDEO -> {
                if (!isVideoMode) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    if (showFeedback) showHud("🔶 VLC 音频模式 · 保持当前方向", 100)
                } else if (videoWidthPx > 0 && videoHeightPx > 0) {
                    if (videoWidthPx >= videoHeightPx) {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        isCurrentlyLandscape = true
                        if (showFeedback) {
                            showHud("↔️ 自适应横屏 (${videoWidthPx}×${videoHeightPx})", 100)
                        }
                    } else {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        isCurrentlyLandscape = false
                        if (showFeedback) {
                            showHud("↕️ 自适应竖屏 (${videoWidthPx}×${videoHeightPx})", 100)
                        }
                    }
                } else {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                }
            }
            OrientationMode.SENSOR -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                if (showFeedback) showHud("🔄 已开启随重力自动旋转", 100)
            }
            OrientationMode.LANDSCAPE -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                isCurrentlyLandscape = true
                if (showFeedback) showHud("↔️ 已锁定横屏模式", 100)
            }
            OrientationMode.PORTRAIT -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                isCurrentlyLandscape = false
                if (showFeedback) showHud("↕️ 已锁定竖屏模式", 100)
            }
        }
        updateOrientationBadges()
        rootStage.postDelayed({ applyTextureAspectRatio() }, 180L)
    }

    private fun updateOrientationBadges() {
        val landNow = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (::topRotatePill.isInitialized) {
            topRotatePill.text = if (landNow) "↕️" else "↔️"
        }
        if (::orientationBtn.isInitialized) {
            orientationBtn.text = orientationMode.iconOnly
        }
    }

    private fun setControlsOverlayVisibility(visible: Boolean) {
        areControlsVisible = visible
        if (visible && !isScreenLocked) {
            topOverlayBar.visibility = View.VISIBLE
            bottomOverlayBar.visibility = View.VISIBLE
            (subtitleOverlayCard.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.bottomMargin = dp(96)
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
        if (enableAutoHideControls) {
            uiHandler.postDelayed(autoHideControlsRunnable, 3500L)
        }
    }

    private fun engageScreenLock() {
        isScreenLocked = true
        setControlsOverlayVisibility(false)
        floatingUnlockBtn.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideLockIconRunnable)
        uiHandler.postDelayed(hideLockIconRunnable, 2200L)
        showToast("🔶 VLC 已锁定屏幕触控，点击左上角锁图标可解锁")
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
                if (!enableDoubleTapSeekPause) return false
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

                // Ignore gestures starting inside the top status/control bar area
                if (startEvent.y < dp(78)) return false

                if (abs(distanceY) > abs(distanceX)) {
                    if (!enableVerticalGestureBrightVol) return false
                    val deltaRatio = distanceY / (stageHeight * 0.75f)
                    if (startEvent.x < stageWidth / 2f) {
                        setScreenBrightness((currentBrightness + deltaRatio).coerceIn(0.05f, 1.0f))
                    } else {
                        val maxAllowed = if (allowVolumeBoost200) 200 else 100
                        val deltaPct = (deltaRatio * 120f).toInt()
                        val newPct = (currentVolumePercent + deltaPct).coerceIn(0, maxAllowed)
                        setPlayerVolumePercent(newPct)
                    }
                } else {
                    if (!enableHorizontalGestureSeek) return false
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
                        showHud("🔶 进度 ${formatDuration(target.toLong())} / ${formatDuration(dur.toLong())}", pct)
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
            if (autoLoadSameNameSubtitle) {
                localFile?.let { vf ->
                    SubtitleParser.autoDetectSubtitleForVideo(vf)?.let { sub ->
                        loadSubtitleFromFile(sub, autoLoaded = true)
                    }
                }
            }
        } else {
            // Opened from Home Dashboard or Navigation Drawer without a file:
            // Show the dedicated VLC Global Settings Center!
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            showVlcPlayerSettingsScreen()
        }
    }

    private fun isMediaExtension(ext: String): Boolean {
        return ext.lowercase(Locale.ROOT) in setOf(
            "mp4", "mkv", "avi", "mov", "webm", "flv", "3gp", "ts", "m4v",
            "mp3", "flac", "wav", "m4a", "ogg", "aac", "opus"
        )
    }

    private fun isAudioExtension(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in setOf("mp3", "flac", "wav", "m4a", "ogg", "aac", "opus")
    }

    /**
     * Dedicated VLC Media Player Global Settings Screen when launched from Home Dashboard or Sidebar.
     */
    private fun showVlcPlayerSettingsScreen() {
        releasePlayer()
        topOverlayBar.visibility = View.GONE
        bottomOverlayBar.visibility = View.GONE
        localMediaBrowserOverlay.visibility = View.VISIBLE
        localMediaListContainer.removeAllViews()

        // 0. VLC Branding & Info Banner Card
        val infoCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            setCardBackgroundColor(Color.parseColor("#1C1917"))
            strokeColor = Color.parseColor(VLC_ORANGE_HEX)
            strokeWidth = dp(2)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        infoCol.addView(TextView(this).apply {
            text = "🔶 VLC Media Player (VideoLAN Port) · 全局设置中心"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(VLC_ORANGE_HEX))
        })
        infoCol.addView(TextView(this).apply {
            text = "已移植并适配 VLC 音视频播放器核心能力：支持 200% 音量硬件增益、10 预设硬件均衡器与重低音、A-B 区间循环、睡眠定时器、外挂 SRT/ASS 字幕同步偏移及自适应横竖屏。\n在文件列表中点击任意视频或音频文件即可直接以 VLC 引擎播放。"
            textSize = 12.5f
            setTextColor(Color.parseColor("#D6D3D1"))
            setPadding(0, dp(6), 0, 0)
        })
        infoCard.addView(infoCol)
        localMediaListContainer.addView(infoCard)

        // 1. Adaptive Landscape / Portrait Orientation Card
        val orientCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            setCardBackgroundColor(Color.parseColor("#1C1917"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val orientCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        orientCol.addView(TextView(this).apply {
            text = "🔄 VLC 视频自适应横竖屏与屏幕旋转策略"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        orientCol.addView(TextView(this).apply {
            text = "当前模式: ${orientationMode.label}\n开启「自适应横竖屏」后，根据视频宽高比自动匹配横屏或竖屏显示。"
            textSize = 12f
            setTextColor(Color.parseColor("#A8A29E"))
            setPadding(0, dp(4), 0, dp(10))
        })

        val orientGrid1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val orientGrid2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val orientModes = OrientationMode.entries
        for (i in orientModes.indices) {
            val mode = orientModes[i]
            val selected = (mode == orientationMode)
            val btn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = if (selected) "✓ ${mode.shortLabel}" else mode.shortLabel
                textSize = 12f
                setTextColor(if (selected) Color.parseColor(VLC_ORANGE_HEX) else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i % 2 == 0) marginEnd = dp(6) }
                setOnClickListener {
                    orientationMode = mode
                    savePlayerGlobalPreferences()
                    showToast("🔶 VLC 默认横竖屏策略已设为: ${mode.label}")
                    showVlcPlayerSettingsScreen()
                }
            }
            if (i < 2) orientGrid1.addView(btn) else orientGrid2.addView(btn)
        }
        orientCol.addView(orientGrid1)
        orientCol.addView(orientGrid2)
        orientCard.addView(orientCol)
        localMediaListContainer.addView(orientCard)

        // 2. VLC Audio Boost (200%), Hardware Acceleration & Default Speed/Aspect Card
        val playbackCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            setCardBackgroundColor(Color.parseColor("#1C1917"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val playbackCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        playbackCol.addView(TextView(this).apply {
            text = "🔊 VLC 200% 音量增益、硬件加速与画面比例"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })

        val boostCheck = CheckBox(this).apply {
            text = "启用 VLC 200% 音量增益 (LoudnessEnhancer +12dB 放大)"
            textSize = 13f
            setTextColor(Color.parseColor("#FDE68A"))
            isChecked = allowVolumeBoost200
            setOnCheckedChangeListener { _, checked ->
                allowVolumeBoost200 = checked
                savePlayerGlobalPreferences()
            }
        }
        playbackCol.addView(boostCheck)

        // Default speed row
        playbackCol.addView(TextView(this).apply {
            text = "默认播放倍速 (当前: ${currentSpeed}x)"
            textSize = 12.5f
            setTextColor(Color.parseColor("#D6D3D1"))
            setPadding(0, dp(10), 0, dp(6))
        })
        val speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
            val selected = abs(currentSpeed - spd) < 0.01f
            val btn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = if (selected) "✓${spd}x" else "${spd}x"
                textSize = 11f
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setTextColor(if (selected) Color.parseColor(VLC_ORANGE_HEX) else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4) }
                setOnClickListener {
                    currentSpeed = spd
                    speedBtn.text = "${spd}x"
                    savePlayerGlobalPreferences()
                    showVlcPlayerSettingsScreen()
                }
            }
            speedRow.addView(btn)
        }
        playbackCol.addView(speedRow)

        // Default Aspect Mode row
        playbackCol.addView(TextView(this).apply {
            text = "默认画面比例与裁剪 (当前: ${aspectMode.label})"
            textSize = 12.5f
            setTextColor(Color.parseColor("#D6D3D1"))
            setPadding(0, dp(10), 0, dp(6))
        })
        val aspectRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(AspectMode.BEST_FIT, AspectMode.FILL, AspectMode.RATIO_16_9, AspectMode.RATIO_4_3).forEach { mode ->
            val selected = (mode == aspectMode)
            val btn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = if (selected) "✓${mode.label}" else mode.label
                textSize = 11f
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setTextColor(if (selected) Color.parseColor(VLC_ORANGE_HEX) else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4) }
                setOnClickListener {
                    aspectMode = mode
                    aspectRatioBtn.text = "📐"
                    savePlayerGlobalPreferences()
                    showVlcPlayerSettingsScreen()
                }
            }
            aspectRow.addView(btn)
        }
        playbackCol.addView(aspectRow)

        // VLC Hardware Acceleration Mode
        playbackCol.addView(TextView(this).apply {
            text = "VLC 硬件加速模式"
            textSize = 12.5f
            setTextColor(Color.parseColor("#D6D3D1"))
            setPadding(0, dp(10), 0, dp(6))
        })
        val hwRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(2 to "🚀 完全硬件加速", 1 to "⚡ 仅解码加速", 0 to "🧩 禁用硬解").forEach { (hwVal, label) ->
            val selected = (vlcHardwareAccelMode == hwVal)
            val btn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = if (selected) "✓$label" else label
                textSize = 11f
                setTextColor(if (selected) Color.parseColor(VLC_ORANGE_HEX) else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4) }
                setOnClickListener {
                    vlcHardwareAccelMode = hwVal
                    savePlayerGlobalPreferences()
                    showVlcPlayerSettingsScreen()
                }
            }
            hwRow.addView(btn)
        }
        playbackCol.addView(hwRow)

        val openEqBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "🎛️ 配置 VLC 10 预设均衡器与重低音增益"
            textSize = 12.5f
            setTextColor(Color.parseColor(VLC_ORANGE_HEX))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener {
                showVlcEqualizerDialog()
            }
        }
        playbackCol.addView(openEqBtn)

        playbackCard.addView(playbackCol)
        localMediaListContainer.addView(playbackCard)

        // 3. VLC External Subtitle (.SRT / .ASS) Settings Card
        val subCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            setCardBackgroundColor(Color.parseColor("#1C1917"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val subCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        subCol.addView(TextView(this).apply {
            text = "💬 VLC 字幕轨与外挂字幕设置 (.SRT / .ASS / .SSA)"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })

        val autoLoadSubCheck = CheckBox(this).apply {
            text = "打开视频时自动检索并加载同目录同名 .srt / .ass 字幕"
            textSize = 13f
            setTextColor(Color.parseColor("#E7E5E4"))
            isChecked = autoLoadSameNameSubtitle
            setOnCheckedChangeListener { _, checked ->
                autoLoadSameNameSubtitle = checked
                savePlayerGlobalPreferences()
            }
        }
        subCol.addView(autoLoadSubCheck)

        subCol.addView(TextView(this).apply {
            text = "默认字幕字号 (当前: ${subtitleFontSizeSp.toInt()}sp)"
            textSize = 12.5f
            setTextColor(Color.parseColor("#D6D3D1"))
            setPadding(0, dp(8), 0, dp(6))
        })
        val subSizeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(15f to "小 15sp", 18f to "标准 18sp", 22f to "大 22sp", 26f to "特大 26sp").forEach { (spVal, label) ->
            val selected = abs(subtitleFontSizeSp - spVal) < 0.5f
            val btn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = if (selected) "✓$label" else label
                textSize = 11f
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setTextColor(if (selected) Color.parseColor(VLC_ORANGE_HEX) else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4) }
                setOnClickListener {
                    subtitleFontSizeSp = spVal
                    subtitleOverlayText.textSize = spVal
                    savePlayerGlobalPreferences()
                    showVlcPlayerSettingsScreen()
                }
            }
            subSizeRow.addView(btn)
        }
        subCol.addView(subSizeRow)

        subCol.addView(TextView(this).apply {
            text = "默认字幕颜色"
            textSize = 12.5f
            setTextColor(Color.parseColor("#D6D3D1"))
            setPadding(0, dp(8), 0, dp(6))
        })
        val subColorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Color.WHITE to "纯白",
            Color.parseColor("#FFD54F") to "明黄",
            Color.parseColor(VLC_ORANGE_HEX) to "VLC橙",
            Color.parseColor("#4DD0E1") to "天青"
        ).forEach { (colorInt, name) ->
            val selected = (subtitleTextColor == colorInt)
            val btn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = if (selected) "✓ $name" else name
                textSize = 11.5f
                setTextColor(colorInt)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(4) }
                setOnClickListener {
                    subtitleTextColor = colorInt
                    subtitleOverlayText.setTextColor(colorInt)
                    savePlayerGlobalPreferences()
                    showVlcPlayerSettingsScreen()
                }
            }
            subColorRow.addView(btn)
        }
        subCol.addView(subColorRow)
        subCard.addView(subCol)
        localMediaListContainer.addView(subCard)

        // 4. VLC Touch Gestures & Reset Card
        val gestureCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            setCardBackgroundColor(Color.parseColor("#1C1917"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        val gestureCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        gestureCol.addView(TextView(this).apply {
            text = "👆 VLC 触控手势与 OSD 自动隐藏设置"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })

        fun makeGestureCheck(label: String, initial: Boolean, onChange: (Boolean) -> Unit): CheckBox {
            return CheckBox(this).apply {
                text = label
                textSize = 13f
                setTextColor(Color.parseColor("#E7E5E4"))
                isChecked = initial
                setOnCheckedChangeListener { _, isChecked ->
                    onChange(isChecked)
                    savePlayerGlobalPreferences()
                }
            }
        }

        gestureCol.addView(
            makeGestureCheck("左半屏上下滑动调亮度 / 右半屏上下滑动调音量 (最高 200%)", enableVerticalGestureBrightVol) {
                enableVerticalGestureBrightVol = it
            }
        )
        gestureCol.addView(
            makeGestureCheck("屏幕水平左右滑动精准快进 / 快退调节进度", enableHorizontalGestureSeek) {
                enableHorizontalGestureSeek = it
            }
        )
        gestureCol.addView(
            makeGestureCheck("双击屏幕左区快退 10 秒 / 右区快进 10 秒 / 中区播放暂停", enableDoubleTapSeekPause) {
                enableDoubleTapSeekPause = it
            }
        )
        gestureCol.addView(
            makeGestureCheck("播放 3.5 秒后自动隐藏顶部与底部 VLC OSD 控制栏", enableAutoHideControls) {
                enableAutoHideControls = it
            }
        )

        val resetDefaultsBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "🔄 恢复 VLC 播放器默认设置"
            textSize = 12.5f
            setTextColor(Color.parseColor(VLC_ORANGE_HEX))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener {
                getSharedPreferences(PREFS_VLC_SETTINGS, Context.MODE_PRIVATE).edit().clear().apply()
                loadPlayerGlobalPreferences()
                showToast("🔶 已恢复 VLC 音视频播放器默认设置")
                showVlcPlayerSettingsScreen()
            }
        }
        gestureCol.addView(resetDefaultsBtn)
        gestureCard.addView(gestureCol)
        localMediaListContainer.addView(gestureCard)
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
        loopPointAMs = -1L
        loopPointBMs = -1L
        topAbLoopPill.text = "🔁"
        val uri = Paths.get(nextFile.absolutePath).fileProviderUri
        loadMediaUri(uri, nextFile.name, !isAudioExtension(nextFile.name))
        if (autoLoadSameNameSubtitle) {
            SubtitleParser.autoDetectSubtitleForVideo(nextFile)?.let { sub ->
                loadSubtitleFromFile(sub, autoLoaded = true)
            }
        }
    }

    private fun loadMediaUri(uri: Uri, title: String, isVideo: Boolean) {
        currentUri = uri
        currentTitle = title
        isVideoMode = isVideo

        topTitleText.text = title
        audioTitleText.text = title
        audioCoverContainer.visibility = if (isVideo) View.GONE else View.VISIBLE
        speedBtn.text = "${currentSpeed}x"
        aspectRatioBtn.text = "📐"
        updateOrientationBadges()
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
                    val firstDimension = (videoWidthPx == 0 || videoHeightPx == 0)
                    videoWidthPx = w
                    videoHeightPx = h
                    if (firstDimension) {
                        applyOrientationForCurrentMedia(showFeedback = false)
                    }
                    applyTextureAspectRatio()
                }
            }
            player.setOnPreparedListener { mp ->
                isPrepared = true
                videoWidthPx = mp.videoWidth
                videoHeightPx = mp.videoHeight
                setupAudioEffectsForSession(mp.audioSessionId)
                applyOrientationForCurrentMedia(showFeedback = true)
                applyTextureAspectRatio()

                val dur = mp.duration.coerceAtLeast(0)
                progressSeekBar.max = dur.coerceAtLeast(1)
                totalDurationText.text = formatDuration(dur.toLong())
                mp.start()
                if (currentSpeed != 1.0f) {
                    applyPlaybackSpeed(currentSpeed)
                }
                playPauseBtn.text = "⏸"
                scheduleAutoHideControls()
            }
            player.setOnCompletionListener {
                playPauseBtn.text = "▶"
                setControlsOverlayVisibility(true)
            }
            player.setOnErrorListener { _, what, extra ->
                showToast("🔶 VLC 媒体解码异常 ($what/$extra)")
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
        aspectRatioBtn.text = "📐"
        savePlayerGlobalPreferences()
        applyTextureAspectRatio()
        showHud("🔶 画面比例: ${aspectMode.label}", 100)
    }

    private fun applyTextureAspectRatio() {
        val stageW = rootStage.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val stageH = rootStage.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (videoWidthPx <= 0 || videoHeightPx <= 0) return

        val lp = FrameLayout.LayoutParams(stageW, stageH, Gravity.CENTER)
        val videoRatio = when (aspectMode) {
            AspectMode.RATIO_16_9 -> 16f / 9f
            AspectMode.RATIO_4_3 -> 4f / 3f
            else -> videoWidthPx.toFloat() / videoHeightPx.toFloat()
        }
        val screenRatio = stageW.toFloat() / stageH.toFloat()

        when (aspectMode) {
            AspectMode.BEST_FIT, AspectMode.FIT_SCREEN, AspectMode.RATIO_16_9, AspectMode.RATIO_4_3 -> {
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
            AspectMode.ORIGINAL -> {
                lp.width = videoWidthPx.coerceAtMost(stageW)
                lp.height = videoHeightPx.coerceAtMost(stageH)
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
            topSubtitlePill.text = "💬"
            val curPos = if (isPrepared) (mediaPlayer?.currentPosition?.toLong() ?: 0L) else 0L
            updateSubtitleOverlay(curPos)
            showToast("🔶 VLC ${if (autoLoaded) "已自动挂载同目录字幕" else "已加载外挂字幕"}: ${file.name}")
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
            topSubtitlePill.text = "💬"
            val curPos = if (isPrepared) (mediaPlayer?.currentPosition?.toLong() ?: 0L) else 0L
            updateSubtitleOverlay(curPos)
            showToast("🔶 VLC 已加载字幕: ${track.title} (${track.cues.size} 条)")
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
                "当前字幕轨: ${track.title} (${track.format} · ${track.cues.size} 条)\n" +
                    "VLC 字幕延迟偏移: ${subtitleOffsetMs}ms · 字号: ${subtitleFontSizeSp.toInt()}sp"
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
            setBackgroundColor(Color.parseColor(VLC_ORANGE_HEX))
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

        content.addView(TextView(this).apply {
            text = "VLC 字幕同步延迟微调 (Subtitle Delay ±50ms / ±500ms)"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        })

        val offsetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        listOf(-500L to "-500ms", -50L to "-50ms", 0L to "重置0ms", 50L to "+50ms", 500L to "+500ms").forEach { (delta, label) ->
            val btn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                textSize = 10.5f
                setPadding(dp(2), dp(6), dp(2), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(3) }
                setOnClickListener {
                    subtitleOffsetMs = if (delta == 0L) 0L else subtitleOffsetMs + delta
                    activeInfo.text = "当前 VLC 字幕延迟偏移: ${subtitleOffsetMs}ms"
                }
            }
            offsetRow.addView(btn)
        }
        content.addView(offsetRow)

        dialogRef = MaterialAlertDialogBuilder(this)
            .setTitle("🔶 VLC 字幕轨道与同步延迟")
            .setView(container)
            .setNeutralButton(if (isSubtitleEnabled) "禁用字幕轨" else "启用字幕轨") { _, _ ->
                isSubtitleEnabled = !isSubtitleEnabled
                val curPos = if (isPrepared) (mediaPlayer?.currentPosition?.toLong() ?: 0L) else 0L
                updateSubtitleOverlay(curPos)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showVlcToolsDialog() {
        val items = arrayOf(
            "🎛️ VLC 10段均衡器与 200% 音量增益",
            "🔁 A-B 区间循环 (${if (loopPointAMs >= 0) "运行中" else "未开启"})",
            "⏲️ VLC 睡眠定时器 (Sleep Timer)",
            "⏱️ 跳转到指定时间 (Jump to Time)",
            "🔄 横竖屏模式 (${orientationMode.label})",
            "💬 字幕轨与同步延迟 (.SRT / .ASS)",
            "⚡ 播放倍速 (0.25x ~ 4.0x)",
            "📐 画面裁剪比例 (${aspectMode.label})",
            "🌐 打开网络串流 (Open Network Stream)"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("🔶 VLC 播放控制面板")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showVlcEqualizerDialog()
                    1 -> cycleAbRepeatLoop()
                    2 -> showSleepTimerDialog()
                    3 -> showJumpToTimeDialog()
                    4 -> showOrientationModeDialog()
                    5 -> showSubtitleSettingsSheet()
                    6 -> showSpeedPopup()
                    7 -> cycleAspectRatio()
                    8 -> showRemoteUrlDialog()
                }
            }
            .show()
    }

    private fun showSleepTimerDialog() {
        val options = arrayOf("关闭睡眠定时", "15 分钟后暂停", "30 分钟后暂停", "45 分钟后暂停", "60 分钟后暂停")
        val mins = intArrayOf(0, 15, 30, 45, 60)
        MaterialAlertDialogBuilder(this)
            .setTitle("⏲️ VLC 睡眠定时器")
            .setItems(options) { _, which ->
                val m = mins[which]
                if (m <= 0) {
                    sleepTimerDeadlineMs = 0L
                    showToast("已关闭 VLC 睡眠定时器")
                } else {
                    sleepTimerDeadlineMs = System.currentTimeMillis() + m * 60_000L
                    showToast("🔶 VLC 将在 $m 分钟后自动暂停播放")
                }
            }
            .show()
    }

    private fun showJumpToTimeDialog() {
        val input = EditText(this).apply {
            hint = "输入时间 (格式如 01:25 或秒数 85)"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("⏱️ VLC 跳转到指定时间")
            .setView(input)
            .setPositiveButton("跳转") { _, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                val parts = raw.split(":").mapNotNull { it.trim().toLongOrNull() }
                val seconds = when (parts.size) {
                    3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                    2 -> parts[0] * 60 + parts[1]
                    1 -> parts[0]
                    else -> -1L
                }
                val player = mediaPlayer
                if (seconds >= 0L && player != null && isPrepared) {
                    val targetMs = (seconds * 1000L).toInt().coerceIn(0, player.duration.coerceAtLeast(1))
                    player.seekTo(targetMs)
                    showHud("🔶 已跳转至 ${formatDuration(targetMs.toLong())}", 100)
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (!isPrepared) return
        if (player.isPlaying) {
            player.pause()
            playPauseBtn.text = "▶"
            showHud("⏸ 已暂停", 0)
        } else {
            player.start()
            playPauseBtn.text = "⏸"
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
        showHud("🔶 ${sign}${deltaMs / 1000}s · ${formatDuration(target.toLong())}", pct)
    }

    private fun applyPlaybackSpeed(speed: Float) {
        currentSpeed = speed
        speedBtn.text = "${speed}x"
        val player = mediaPlayer
        if (player != null && isPrepared && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val params = player.playbackParams ?: PlaybackParams()
                params.speed = speed
                player.playbackParams = params
            }
        }
        showHud("🔶 VLC 倍速 ${speed}x", ((speed / 2.0f) * 100).toInt().coerceIn(0, 100))
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
        val maxCap = if (allowVolumeBoost200) 200 else 100
        val clamped = percent.coerceIn(0, maxCap)
        currentVolumePercent = clamped

        val maxSysVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val sysRatio = (clamped.coerceAtMost(100) / 100f)
        val targetSysVol = (sysRatio * maxSysVol).toInt().coerceIn(0, maxSysVol)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetSysVol, 0)
        runCatching { mediaPlayer?.setVolume(sysRatio, sysRatio) }

        applyLoudnessBoost()
        val label = if (clamped > 100) {
            "🔊 VLC 增益 $clamped% (+${String.format(Locale.US, "%.1f", (clamped - 100) * 0.12f)}dB)"
        } else {
            "🔊 音量 $clamped%"
        }
        showHud(label, (clamped * 100 / maxCap).coerceIn(0, 100))
    }

    private fun showSpeedPopup() {
        val options = arrayOf("0.5x 慢速", "0.75x 较慢", "1.0x 正常速度", "1.25x 较快", "1.5x 快速", "2.0x 二倍速", "3.0x 三倍速")
        val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
        MaterialAlertDialogBuilder(this)
            .setTitle("🔶 VLC 播放速度")
            .setItems(options) { _, which ->
                applyPlaybackSpeed(values[which])
            }
            .show()
    }

    private fun showRemoteUrlDialog() {
        val input = EditText(this).apply {
            hint = "输入 http://, https://, rtsp:// 或 m3u8 串流地址"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(
                currentUri?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString().orEmpty()
            )
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("🔶 VLC 打开网络串流 (Network Stream)")
            .setView(input)
            .setPositiveButton("立即串流播放") { _, _ ->
                val urlStr = input.text?.toString()?.trim().orEmpty()
                if (urlStr.isNotEmpty()) {
                    localMediaBrowserOverlay.visibility = View.GONE
                    val uri = Uri.parse(urlStr)
                    loadMediaUri(
                        uri = uri,
                        title = uri.lastPathSegment ?: "VLC 网络串流",
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
        releaseAudioEffects()
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
        private const val PREFS_VLC_SETTINGS = "vlc_media_player_global_settings"
        private const val VLC_ORANGE_HEX = "#FF8800"
        private const val VLC_ORANGE_DARK_HEX = "#C2410C"

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
