/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import java.io.File
import java.util.Locale

/**
 * Single Source of Truth for v1.1「晴彩 Bright Calm」Design Tokens,
 * 8 File Type Colors, 1.75px Linear Icon Drawables, Empty State Illustrations,
 * and Persisted Settings (§01–§14 of UI & Interaction Design Spec).
 */
object BrightCalmTheme {

    const val PREFS_NAME = "bright_calm_v11_prefs"

    // Theme mode: 0 = System, 1 = Light, 2 = Dark
    const val KEY_THEME_MODE = "theme_mode"
    // Accent color: 0 = 晴彩紫 (#6C5CE7), 1 = 晴空蓝 (#3B9BFF), 2 = 蜜柚橙 (#FF7A59)
    const val KEY_ACCENT_COLOR = "accent_color"
    // Color intensity: 0 = 标准 (16%/18%), 1 = 柔和 (10%/12%), 2 = 关闭彩色 (neutral)
    const val KEY_COLOR_INTENSITY = "color_intensity"
    const val KEY_DEFAULT_VIEW = "default_view" // "list" or "grid"
    const val KEY_DEFAULT_SORT = "default_sort" // "time_desc", "name_asc", "size_desc", "type_asc"
    const val KEY_SHOW_EXTENSIONS = "show_extensions"
    const val KEY_THUMBNAIL_LOAD = "thumbnail_load" // "wifi", "always", "never"
    const val KEY_COMPACT_LIST = "compact_list"
    const val KEY_DEFAULT_OPEN_DIR = "default_open_dir"
    const val KEY_REMEMBER_DIR_PREF = "remember_dir_pref"
    const val KEY_MOVE_TO_RECYCLE_BIN = "move_to_recycle_bin"
    const val KEY_CONFIRM_DELETE = "confirm_delete"
    const val KEY_RECYCLE_AUTO_CLEAN = "recycle_auto_clean" // 7, 30, 90, 0
    const val KEY_ARCHIVE_INNER_PREVIEW = "archive_inner_preview"
    const val KEY_CONFLICT_POLICY = "conflict_policy" // "ask", "rename", "overwrite"
    const val KEY_LARGE_FILE_THRESHOLD_MB = "large_file_threshold_mb" // 100
    const val KEY_APP_LOCK = "app_lock" // "off", "biometric", "pin"
    const val KEY_SECURE_DELETE = "secure_delete"
    const val KEY_NOTIFY_DOWNLOAD = "notify_download"
    const val KEY_NOTIFY_STORAGE = "notify_storage"
    const val KEY_AUTO_BACKUP = "auto_backup"
    const val KEY_FAVORITES = "favorite_paths"
    const val KEY_SEARCH_HISTORY = "search_history"
    const val KEY_CLEANUP_DISMISSED = "cleanup_card_dismissed"

    enum class FileCategory {
        FOLDER, IMAGE, VIDEO, AUDIO, DOC, PDF, SHEET, SLIDE, ZIP, APK, CODE, OTHER
    }

    data class Tokens(
        val isDark: Boolean,
        val intensity: Int,
        val background: Int,
        val card: Int,
        val popover: Int,
        val foreground: Int,
        val muted: Int,
        val mutedForeground: Int,
        val border: Int,
        val input: Int,
        val accent: Int,
        val secondary: Int,
        val primary: Int,
        val primary2: Int,
        val primaryForeground: Int,
        val ring: Int,
        val destructive: Int,
        val success: Int,
        val warning: Int,
        val info: Int,
        val typeFolder: Int,
        val typeImage: Int,
        val typeVideo: Int,
        val typeAudio: Int,
        val typeDoc: Int,
        val typeSheet: Int,
        val typeSlide: Int,
        val typeOther: Int
    ) {
        val iconTintAlpha: Float
            get() = when (intensity) {
                2 -> 0f
                1 -> 0.10f
                else -> if (isDark) 0.14f else 0.16f
            }

        val gridTintAlpha: Float
            get() = when (intensity) {
                2 -> 0f
                1 -> 0.12f
                else -> if (isDark) 0.14f else 0.18f
            }

        val chipTintAlpha: Float
            get() = when (intensity) {
                2 -> 0.10f
                1 -> 0.10f
                else -> 0.14f
            }

        fun typeColor(cat: FileCategory): Int {
            if (intensity == 2) return mutedForeground
            return when (cat) {
                FileCategory.FOLDER -> typeFolder
                FileCategory.IMAGE -> typeImage
                FileCategory.VIDEO -> typeVideo
                FileCategory.AUDIO -> typeAudio
                FileCategory.DOC, FileCategory.PDF -> typeDoc
                FileCategory.SHEET -> typeSheet
                FileCategory.SLIDE -> typeSlide
                FileCategory.ZIP, FileCategory.APK, FileCategory.CODE, FileCategory.OTHER -> typeOther
            }
        }

        fun iconBoxBg(cat: FileCategory): Int {
            if (intensity == 2) return muted
            return blendColor(card, typeColor(cat), iconTintAlpha)
        }

        fun categoryCellBg(cat: FileCategory): Int {
            if (intensity == 2) return muted
            return blendColor(card, typeColor(cat), gridTintAlpha)
        }

        fun chipActiveBg(cat: FileCategory?): Int {
            if (cat == null) return withAlpha(primary, 0.12f)
            if (intensity == 2) return withAlpha(primary, 0.12f)
            return withAlpha(typeColor(cat), chipTintAlpha)
        }
    }

    fun of(context: Context): Tokens {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val themeMode = prefs.getInt(KEY_THEME_MODE, 0)
        val sysNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val isDark = when (themeMode) {
            1 -> false
            2 -> true
            else -> sysNight
        }
        val accentIdx = prefs.getInt(KEY_ACCENT_COLOR, 0)
        val intensity = prefs.getInt(KEY_COLOR_INTENSITY, 0)

        val (primary, primary2) = when (accentIdx) {
            1 -> if (isDark) Color.parseColor("#7CC0FF") to Color.parseColor("#A6D5FF")
            else Color.parseColor("#3B9BFF") to Color.parseColor("#6FB8FF")
            2 -> if (isDark) Color.parseColor("#FFA68C") to Color.parseColor("#FFC2B0")
            else Color.parseColor("#FF7A59") to Color.parseColor("#FF9E85")
            else -> if (isDark) Color.parseColor("#A79BFF") to Color.parseColor("#C0B6FF")
            else Color.parseColor("#6C5CE7") to Color.parseColor("#9B8BFF")
        }

        return if (!isDark) {
            Tokens(
                isDark = false,
                intensity = intensity,
                background = Color.parseColor("#F7F8FE"),
                card = Color.parseColor("#FFFFFF"),
                popover = Color.parseColor("#FFFFFF"),
                foreground = Color.parseColor("#1B1B2B"),
                muted = Color.parseColor("#F0F1FA"),
                mutedForeground = Color.parseColor("#8589A3"),
                border = Color.parseColor("#E6E8F5"),
                input = Color.parseColor("#E6E8F5"),
                accent = Color.parseColor("#EEEDFC"),
                secondary = Color.parseColor("#F0F1FA"),
                primary = primary,
                primary2 = primary2,
                primaryForeground = Color.parseColor("#FFFFFF"),
                ring = Color.parseColor("#B9B0FA"),
                destructive = Color.parseColor("#F0536B"),
                success = Color.parseColor("#22C58B"),
                warning = Color.parseColor("#F5A623"),
                info = Color.parseColor("#3B9BFF"),
                typeFolder = Color.parseColor("#F5A623"),
                typeImage = Color.parseColor("#FF7A59"),
                typeVideo = Color.parseColor("#F0538E"),
                typeAudio = Color.parseColor("#A66BF5"),
                typeDoc = Color.parseColor("#3B9BFF"),
                typeSheet = Color.parseColor("#22C58B"),
                typeSlide = Color.parseColor("#F5B942"),
                typeOther = Color.parseColor("#7C86B8")
            )
        } else {
            Tokens(
                isDark = true,
                intensity = intensity,
                background = Color.parseColor("#12121F"),
                card = Color.parseColor("#1C1C2E"),
                popover = Color.parseColor("#22223A"),
                foreground = Color.parseColor("#EEEEF8"),
                muted = Color.parseColor("#25253C"),
                mutedForeground = Color.parseColor("#9A9DBB"),
                border = Color.argb(26, 255, 255, 255), // rgba(255,255,255,0.10)
                input = Color.argb(38, 255, 255, 255),  // rgba(255,255,255,0.15)
                accent = Color.parseColor("#2A2A45"),
                secondary = Color.parseColor("#25253C"),
                primary = primary,
                primary2 = primary2,
                primaryForeground = Color.parseColor("#171233"),
                ring = Color.parseColor("#6E63D6"),
                destructive = Color.parseColor("#FF8298"),
                success = Color.parseColor("#5FE0AC"),
                warning = Color.parseColor("#FFC46B"),
                info = Color.parseColor("#7CC0FF"),
                typeFolder = Color.parseColor("#FFC46B"),
                typeImage = Color.parseColor("#FFA68C"),
                typeVideo = Color.parseColor("#FF8CB4"),
                typeAudio = Color.parseColor("#C9A6FF"),
                typeDoc = Color.parseColor("#7CC0FF"),
                typeSheet = Color.parseColor("#5FE0AC"),
                typeSlide = Color.parseColor("#FFD07A"),
                typeOther = Color.parseColor("#A6AEDA")
            )
        }
    }

    fun applyNightMode(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        when (prefs.getInt(KEY_THEME_MODE, 0)) {
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    fun withAlpha(color: Int, alphaFraction: Float): Int {
        val a = (alphaFraction.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    fun blendColor(base: Int, overlay: Int, alpha: Float): Int {
        val f = alpha.coerceIn(0f, 1f)
        val r = (Color.red(base) * (1f - f) + Color.red(overlay) * f).toInt()
        val g = (Color.green(base) * (1f - f) + Color.green(overlay) * f).toInt()
        val b = (Color.blue(base) * (1f - f) + Color.blue(overlay) * f).toInt()
        return Color.rgb(r, g, b)
    }

    fun categoryForFile(file: File): FileCategory {
        if (file.isDirectory) return FileCategory.FOLDER
        return categoryForName(file.name, isDirectory = false)
    }

    fun categoryForName(name: String, isDirectory: Boolean): FileCategory {
        if (isDirectory) return FileCategory.FOLDER
        val dot = name.lastIndexOf('.')
        val ext = if (dot >= 0) name.substring(dot + 1).lowercase(Locale.ROOT) else ""
        return when (ext) {
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "svg" -> FileCategory.IMAGE
            "mp4", "mkv", "mov", "avi", "webm", "3gp", "flv", "ts" -> FileCategory.VIDEO
            "mp3", "flac", "m4a", "wav", "ogg", "aac", "opus" -> FileCategory.AUDIO
            "pdf" -> FileCategory.PDF
            "doc", "docx", "txt", "md", "markdown", "rtf", "epub", "log" -> FileCategory.DOC
            "xls", "xlsx", "csv", "numbers", "ods" -> FileCategory.SHEET
            "ppt", "pptx", "key", "odp" -> FileCategory.SLIDE
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> FileCategory.ZIP
            "apk", "apks", "xapk" -> FileCategory.APK
            "kt", "java", "py", "js", "ts", "html", "css", "json", "xml", "sh", "c", "cpp", "h" -> FileCategory.CODE
            else -> FileCategory.OTHER
        }
    }

    fun formatDisplayFileName(context: Context, rawName: String, isDirectory: Boolean): String {
        if (isDirectory) return rawName
        val showExt = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_EXTENSIONS, true)
        if (showExt) return rawName
        val dot = rawName.lastIndexOf('.')
        return if (dot > 0) rawName.substring(0, dot) else rawName
    }

    // Favorites persistence
    fun isFavorite(context: Context, path: String): Boolean {
        val set = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
        return path in set
    }

    fun toggleFavorite(context: Context, path: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = (prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()).toMutableSet()
        val nowFav = if (path in current) {
            current.remove(path)
            false
        } else {
            current.add(path)
            true
        }
        prefs.edit().putStringSet(KEY_FAVORITES, current).apply()
        return nowFav
    }

    fun getFavoriteFiles(context: Context): List<File> {
        val set = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
        return set.map { File(it) }.filter { it.exists() }
    }

    // Search history persistence
    fun getSearchHistory(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SEARCH_HISTORY, "") ?: ""
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun addSearchHistory(context: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val list = getSearchHistory(context).toMutableList()
        list.remove(q)
        list.add(0, q)
        val capped = list.take(10)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SEARCH_HISTORY, capped.joinToString("\n")).apply()
    }

    fun removeSearchHistoryItem(context: Context, query: String) {
        val list = getSearchHistory(context).toMutableList()
        list.remove(query)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SEARCH_HISTORY, list.joinToString("\n")).apply()
    }

    fun clearSearchHistory(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_SEARCH_HISTORY).apply()
    }

    // UI Shape Helpers
    fun cardBackground(tokens: Tokens, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(tokens.card)
            setStroke(1, tokens.border)
        }

    fun roundedBg(color: Int, radiusPx: Float, strokeColor: Int = 0, strokeWidth: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(color)
            if (strokeWidth > 0) {
                setStroke(strokeWidth, strokeColor)
            }
        }

    fun brandGradientBg(tokens: Tokens, radiusPx: Float, isOval: Boolean = false): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR, // 135 deg
            intArrayOf(tokens.primary, tokens.primary2)
        ).apply {
            shape = if (isOval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            if (!isOval) cornerRadius = radiusPx
        }

    /**
     * Builds the 40x40dp (or custom size) File Type Icon Box from §08 & 视觉素材板.svg Section D:
     * 16% type color tinted rounded square (radius 12dp), 1.75px linear icon, no solid border.
     */
    fun createFileTypeIconBox(
        context: Context,
        tokens: Tokens,
        category: FileCategory,
        boxSizeDp: Int = 40,
        cornerRadiusDp: Int = 12
    ): View {
        val density = context.resources.displayMetrics.density
        val sizePx = (boxSizeDp * density).toInt()
        val radiusPx = cornerRadiusDp * density
        return object : View(context) {
            private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.75f * density * (boxSizeDp / 40f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val rect = RectF()
            private val path = Path()

            init {
                layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                rect.set(0f, 0f, w, h)
                bgPaint.color = tokens.iconBoxBg(category)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, bgPaint)

                strokePaint.color = tokens.typeColor(category)
                // Map 32x32 SVG space into inner 62% of box (24dp inside 40dp)
                val pad = w * 0.20f
                val scale = (w - pad * 2f) / 32f
                canvas.save()
                canvas.translate(pad, pad)
                canvas.scale(scale, scale)
                strokePaint.strokeWidth = (1.75f * density) / scale
                drawFileTypeLinearGlyph(canvas, category, strokePaint, path, rect)
                canvas.restore()
            }
        }
    }

    /**
     * Exact 32x32 linear SVG glyphs from 视觉素材板.svg Section D & 配色与素材预览.html SVG map.
     */
    fun drawFileTypeLinearGlyph(
        canvas: Canvas,
        category: FileCategory,
        paint: Paint,
        path: Path,
        rect: RectF
    ) {
        path.reset()
        when (category) {
            FileCategory.FOLDER -> {
                // M3 10h8l3 3h14a2 2 0 012 2v10a2 2 0 01-2 2H5a2 2 0 01-2-2z
                path.moveTo(3f, 10f)
                path.lineTo(11f, 10f)
                path.lineTo(14f, 13f)
                path.lineTo(28f, 13f)
                rect.set(26f, 13f, 30f, 17f)
                path.arcTo(rect, -90f, 90f, false)
                path.lineTo(30f, 25f)
                rect.set(26f, 23f, 30f, 27f)
                path.arcTo(rect, 0f, 90f, false)
                path.lineTo(5f, 27f)
                rect.set(3f, 23f, 7f, 27f)
                path.arcTo(rect, 90f, 90f, false)
                path.close()
                canvas.drawPath(path, paint)
            }
            FileCategory.IMAGE -> {
                // <rect x="3" y="5" width="26" height="22" rx="3"/><circle cx="11" cy="12" r="2.5"/><path d="M4 23l7-7 6 6 4-4 7 7"/>
                rect.set(3f, 5f, 29f, 27f)
                canvas.drawRoundRect(rect, 3f, 3f, paint)
                canvas.drawCircle(11f, 12f, 2.5f, paint)
                path.moveTo(4f, 23f)
                path.lineTo(11f, 16f)
                path.lineTo(17f, 22f)
                path.lineTo(21f, 18f)
                path.lineTo(28f, 25f)
                canvas.drawPath(path, paint)
            }
            FileCategory.VIDEO -> {
                // <rect x="3" y="7" width="20" height="18" rx="3"/><path d="M23 12l6-3v14l-6-3z"/>
                rect.set(3f, 7f, 23f, 25f)
                canvas.drawRoundRect(rect, 3f, 3f, paint)
                path.moveTo(23f, 12f)
                path.lineTo(29f, 9f)
                path.lineTo(29f, 23f)
                path.lineTo(23f, 20f)
                path.close()
                canvas.drawPath(path, paint)
            }
            FileCategory.AUDIO -> {
                // <circle cx="10" cy="23" r="3"/><circle cx="23" cy="21" r="2.5"/><path d="M13 23V7l12-2v16M13 11l12-2"/>
                canvas.drawCircle(10f, 23f, 3f, paint)
                canvas.drawCircle(23f, 21f, 2.5f, paint)
                path.moveTo(13f, 23f)
                path.lineTo(13f, 7f)
                path.lineTo(25f, 5f)
                path.lineTo(25f, 21f)
                path.moveTo(13f, 11f)
                path.lineTo(25f, 9f)
                canvas.drawPath(path, paint)
            }
            FileCategory.DOC -> {
                // <path d="M9 3h10l6 6v19a2 2 0 01-2 2H9a2 2 0 01-2-2V5a2 2 0 012-2z"/><path d="M19 3v6h6M11 16h10M11 21h7"/>
                drawPageOutline(path, rect)
                path.moveTo(19f, 3f)
                path.lineTo(19f, 9f)
                path.lineTo(25f, 9f)
                path.moveTo(11f, 16f)
                path.lineTo(21f, 16f)
                path.moveTo(11f, 21f)
                path.lineTo(18f, 21f)
                canvas.drawPath(path, paint)
            }
            FileCategory.PDF -> {
                // <path d="M9 3h10l6 6v19a2 2 0 01-2 2H9a2 2 0 01-2-2V5a2 2 0 012-2z"/><path d="M19 3v6h6"/><rect x="10" y="15" width="12" height="10" rx="2"/>
                drawPageOutline(path, rect)
                path.moveTo(19f, 3f)
                path.lineTo(19f, 9f)
                path.lineTo(25f, 9f)
                canvas.drawPath(path, paint)
                rect.set(10f, 15f, 22f, 25f)
                canvas.drawRoundRect(rect, 2f, 2f, paint)
            }
            FileCategory.SHEET -> {
                // <rect x="4" y="4" width="24" height="24" rx="3"/><path d="M4 12h24M4 20h24M12 4v24M20 4v24"/>
                rect.set(4f, 4f, 28f, 28f)
                canvas.drawRoundRect(rect, 3f, 3f, paint)
                path.moveTo(4f, 12f); path.lineTo(28f, 12f)
                path.moveTo(4f, 20f); path.lineTo(28f, 20f)
                path.moveTo(12f, 4f); path.lineTo(12f, 28f)
                path.moveTo(20f, 4f); path.lineTo(20f, 28f)
                canvas.drawPath(path, paint)
            }
            FileCategory.SLIDE -> {
                // <rect x="3" y="5" width="26" height="18" rx="3"/><path d="M8 19v-5M14 19v-9M20 19v-6M26 19v-3"/>
                rect.set(3f, 5f, 29f, 23f)
                canvas.drawRoundRect(rect, 3f, 3f, paint)
                path.moveTo(8f, 19f); path.lineTo(8f, 14f)
                path.moveTo(14f, 19f); path.lineTo(14f, 10f)
                path.moveTo(20f, 19f); path.lineTo(20f, 13f)
                path.moveTo(26f, 19f); path.lineTo(26f, 16f)
                canvas.drawPath(path, paint)
            }
            FileCategory.ZIP -> {
                // <rect x="4" y="4" width="24" height="24" rx="3"/><path d="M4 11h24M13 4v24M19 4v24"/><rect x="13" y="16" width="6" height="6" rx="1"/>
                rect.set(4f, 4f, 28f, 28f)
                canvas.drawRoundRect(rect, 3f, 3f, paint)
                path.moveTo(4f, 11f); path.lineTo(28f, 11f)
                path.moveTo(13f, 4f); path.lineTo(13f, 28f)
                path.moveTo(19f, 4f); path.lineTo(19f, 28f)
                canvas.drawPath(path, paint)
                rect.set(13f, 16f, 19f, 22f)
                canvas.drawRoundRect(rect, 1f, 1f, paint)
            }
            FileCategory.APK -> {
                // <path d="M16 3l12 7v12l-12 7L4 22V10z"/><path d="M4 10l12 7 12-7M16 17v12"/>
                path.moveTo(16f, 3f)
                path.lineTo(28f, 10f)
                path.lineTo(28f, 22f)
                path.lineTo(16f, 29f)
                path.lineTo(4f, 22f)
                path.lineTo(4f, 10f)
                path.close()
                path.moveTo(4f, 10f)
                path.lineTo(16f, 17f)
                path.lineTo(28f, 10f)
                path.moveTo(16f, 17f)
                path.lineTo(16f, 29f)
                canvas.drawPath(path, paint)
            }
            FileCategory.CODE -> {
                // <path d="M11 9l-7 7 7 7M21 9l7 7-7 7M18 5l-4 22"/>
                path.moveTo(11f, 9f); path.lineTo(4f, 16f); path.lineTo(11f, 23f)
                path.moveTo(21f, 9f); path.lineTo(28f, 16f); path.lineTo(21f, 23f)
                path.moveTo(18f, 5f); path.lineTo(14f, 27f)
                canvas.drawPath(path, paint)
            }
            FileCategory.OTHER -> {
                // <path d="M9 3h10l6 6v19a2 2 0 01-2 2H9a2 2 0 01-2-2V5a2 2 0 012-2z"/><path d="M19 3v6h6"/>
                drawPageOutline(path, rect)
                path.moveTo(19f, 3f)
                path.lineTo(19f, 9f)
                path.lineTo(25f, 9f)
                canvas.drawPath(path, paint)
            }
        }
    }

    private fun drawPageOutline(path: Path, rect: RectF) {
        path.moveTo(9f, 3f)
        path.lineTo(19f, 3f)
        path.lineTo(25f, 9f)
        path.lineTo(25f, 28f)
        rect.set(21f, 26f, 25f, 30f)
        path.arcTo(rect, 0f, 90f, false)
        path.lineTo(9f, 30f)
        rect.set(7f, 26f, 11f, 30f)
        path.arcTo(rect, 90f, 90f, false)
        path.lineTo(7f, 5f)
        rect.set(7f, 3f, 11f, 7f)
        path.arcTo(rect, 180f, 90f, false)
        path.close()
    }

    /**
     * Creates the App Icon View matching Section C of 视觉素材板.svg (主推 · 渐变文件夹).
     */
    fun createBrandAppIconView(context: Context, tokens: Tokens, sizeDp: Int = 52): View {
        val density = context.resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt()
        return object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.WHITE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val rect = RectF()
            private val path = Path()

            init {
                layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                paint.shader = LinearGradient(
                    0f, 0f, w, h,
                    tokens.primary, tokens.primary2,
                    Shader.TileMode.CLAMP
                )
                rect.set(0f, 0f, w, h)
                val r = w * 0.23f
                canvas.drawRoundRect(rect, r, r, paint)
                paint.shader = null

                // Map 120x120 Section C coordinate space
                val scale = w / 120f
                canvas.save()
                canvas.scale(scale, scale)
                strokePaint.strokeWidth = 4.2f
                rect.set(26f, 44f, 94f, 90f)
                canvas.drawRoundRect(rect, 8f, 8f, strokePaint)
                path.reset()
                path.moveTo(26f, 54f)
                path.lineTo(26f, 50f)
                rect.set(26f, 44f, 38f, 56f)
                path.arcTo(rect, 180f, 90f, false)
                path.lineTo(46f, 44f)
                path.lineTo(52f, 50f)
                path.lineTo(78f, 50f)
                rect.set(72f, 50f, 84f, 62f)
                path.arcTo(rect, -90f, 90f, false)
                canvas.drawPath(path, strokePaint)

                // #FFD07A dot at top right
                paint.color = Color.parseColor("#FFD07A")
                canvas.drawCircle(92f, 30f, 7f, paint)
                canvas.restore()
            }
        }
    }

    /**
     * Builds the Empty State Card / Container (§07.1 & Section E of 视觉素材板.svg):
     * Illustration + Specific Chinese Main Copy + Subtitle Copy + Primary Action Button.
     */
    enum class EmptyStateType {
        EMPTY_FOLDER, SEARCH_NO_RESULT, CATEGORY_EMPTY, RECYCLE_EMPTY, NO_PERMISSION, LOAD_ERROR
    }

    fun buildEmptyStateView(
        context: Context,
        tokens: Tokens,
        type: EmptyStateType,
        titleText: String,
        subtitleText: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ): LinearLayout {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(48))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            // Section E Dual-color Flat Illustration View (<=3 colors, 12% primary circle + 2.6px linear object + solid accent)
            val artView = object : View(context) {
                private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                private val rect = RectF()
                private val path = Path()

                init {
                    layoutParams = LinearLayout.LayoutParams(dp(160), dp(130)).apply {
                        bottomMargin = dp(12)
                    }
                }

                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    val cx = width / 2f
                    val cy = height / 2f
                    val scale = density
                    canvas.save()
                    canvas.translate(cx, cy)
                    canvas.scale(scale, scale)

                    // Base 12% primary circle r=48
                    fillPaint.color = withAlpha(tokens.primary, 0.12f)
                    canvas.drawCircle(0f, 0f, 48f, fillPaint)

                    strokePaint.color = tokens.primary
                    strokePaint.strokeWidth = 2.6f
                    path.reset()

                    when (type) {
                        EmptyStateType.EMPTY_FOLDER, EmptyStateType.CATEGORY_EMPTY, EmptyStateType.RECYCLE_EMPTY -> {
                            // Open folder + 3 floating paper chips (Section E.1)
                            path.moveTo(-28f, -4f)
                            path.lineTo(-10f, -4f)
                            path.lineTo(-4f, 2f)
                            path.lineTo(28f, 2f)
                            path.lineTo(28f, 22f)
                            path.lineTo(-28f, 22f)
                            path.close()
                            canvas.drawPath(path, strokePaint)

                            fillPaint.color = tokens.typeSlide
                            rect.set(-20f, -32f, -4f, -21f)
                            canvas.drawRoundRect(rect, 2f, 2f, fillPaint)

                            fillPaint.color = tokens.typeImage
                            rect.set(0f, -36f, 14f, -26f)
                            canvas.drawRoundRect(rect, 2f, 2f, fillPaint)

                            fillPaint.color = tokens.typeDoc
                            rect.set(18f, -31f, 30f, -22f)
                            canvas.drawRoundRect(rect, 2f, 2f, fillPaint)
                        }
                        EmptyStateType.SEARCH_NO_RESULT -> {
                            // Magnifier + destructive slash + warning dot (Section E.2)
                            canvas.drawCircle(-4f, -4f, 18f, strokePaint)
                            canvas.drawLine(9f, 9f, 24f, 24f, strokePaint)

                            strokePaint.color = tokens.destructive
                            strokePaint.strokeWidth = 3f
                            canvas.drawLine(-16f, -16f, 8f, 8f, strokePaint)

                            fillPaint.color = tokens.typeSlide
                            canvas.drawCircle(38f, -24f, 5f, fillPaint)
                        }
                        EmptyStateType.NO_PERMISSION -> {
                            // Padlock + keyhole dot + success dot (Section E.3)
                            rect.set(-18f, -2f, 18f, 24f)
                            canvas.drawRoundRect(rect, 5f, 5f, strokePaint)
                            rect.set(-11f, -21f, 11f, 1f)
                            canvas.drawArc(rect, 180f, 180f, false, strokePaint)

                            fillPaint.color = tokens.typeSlide
                            canvas.drawCircle(0f, 11f, 4f, fillPaint)

                            fillPaint.color = tokens.success
                            canvas.drawCircle(-32f, -24f, 5f, fillPaint)
                        }
                        EmptyStateType.LOAD_ERROR -> {
                            // Disconnected cloud + slash + warning dot (Section E.4)
                            rect.set(-24f, -8f, 24f, 14f)
                            canvas.drawRoundRect(rect, 10f, 10f, strokePaint)
                            canvas.drawLine(-18f, 22f, 18f, 22f, strokePaint)

                            strokePaint.color = tokens.destructive
                            strokePaint.strokeWidth = 3f
                            canvas.drawLine(-22f, -22f, 12f, 12f, strokePaint)

                            fillPaint.color = tokens.warning
                            canvas.drawCircle(32f, -24f, 5f, fillPaint)
                        }
                    }

                    canvas.restore()
                }
            }
            addView(artView)

            addView(TextView(context).apply {
                text = titleText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(tokens.foreground)
                gravity = Gravity.CENTER
            })

            addView(TextView(context).apply {
                text = subtitleText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(tokens.mutedForeground)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(16))
            })

            if (!actionLabel.isNullOrEmpty() && onAction != null) {
                addView(TextView(context).apply {
                    text = actionLabel
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(tokens.primaryForeground)
                    background = brandGradientBg(tokens, dp(10).toFloat())
                    setPadding(dp(20), dp(10), dp(20), dp(10))
                    minimumHeight = dp(44)
                    gravity = Gravity.CENTER
                    setOnClickListener { onAction() }
                })
            }
        }
    }

    /**
     * 24x24 Linear 1.75px Stroke Icons (§08 Icon System) with Outline & Filled support.
     */
    enum class UiIcon {
        HOME, CLOCK, GRID, SETTINGS,
        SEARCH, ARROW_LEFT, MORE_VERTICAL, LIST_FILTER, LIST,
        CLOSE, CHECK, CHECK_ALL, CHEVRON_RIGHT, PLUS,
        STAR, SHARE, COPY, CUT, TRASH, EDIT, COMPRESS, INFO,
        HARD_DRIVE, SD_CARD, CLOUD, FOLDER_PLUS, UPLOAD, SCAN,
        MOON, PALETTE, DATABASE, SHIELD, BELL, HELP, REFRESH
    }

    fun createUiIconView(
        context: Context,
        icon: UiIcon,
        color: Int,
        sizeDp: Int = 22,
        filled: Boolean = false
    ): View {
        val density = context.resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt()
        return object : View(context) {
            private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                this.color = color
            }
            private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = color
            }
            private val rect = RectF()
            private val path = Path()

            init {
                layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val scale = width / 24f
                canvas.save()
                canvas.scale(scale, scale)
                strokePaint.strokeWidth = 1.75f
                path.reset()

                when (icon) {
                    UiIcon.HOME -> {
                        path.moveTo(3f, 10.5f)
                        path.lineTo(12f, 3f)
                        path.lineTo(21f, 10.5f)
                        path.lineTo(19f, 10.5f)
                        path.lineTo(19f, 20f)
                        path.lineTo(5f, 20f)
                        path.lineTo(5f, 10.5f)
                        path.close()
                        if (filled) {
                            fillPaint.color = withAlpha(color, 0.22f)
                            canvas.drawPath(path, fillPaint)
                        }
                        canvas.drawPath(path, strokePaint)
                        canvas.drawLine(9f, 20f, 9f, 14f, strokePaint)
                        canvas.drawLine(9f, 14f, 15f, 14f, strokePaint)
                        canvas.drawLine(15f, 14f, 15f, 20f, strokePaint)
                    }
                    UiIcon.CLOCK -> {
                        if (filled) {
                            fillPaint.color = withAlpha(color, 0.22f)
                            canvas.drawCircle(12f, 12f, 9f, fillPaint)
                        }
                        canvas.drawCircle(12f, 12f, 9f, strokePaint)
                        canvas.drawLine(12f, 7f, 12f, 12f, strokePaint)
                        canvas.drawLine(12f, 12f, 15.5f, 14f, strokePaint)
                    }
                    UiIcon.GRID -> {
                        val boxes = arrayOf(
                            RectF(3.5f, 3.5f, 10.5f, 10.5f),
                            RectF(13.5f, 3.5f, 20.5f, 10.5f),
                            RectF(3.5f, 13.5f, 10.5f, 20.5f),
                            RectF(13.5f, 13.5f, 20.5f, 20.5f)
                        )
                        for (b in boxes) {
                            if (filled) {
                                fillPaint.color = withAlpha(color, 0.22f)
                                canvas.drawRoundRect(b, 2f, 2f, fillPaint)
                            }
                            canvas.drawRoundRect(b, 2f, 2f, strokePaint)
                        }
                    }
                    UiIcon.SETTINGS -> {
                        if (filled) {
                            fillPaint.color = withAlpha(color, 0.22f)
                            canvas.drawCircle(12f, 12f, 7.5f, fillPaint)
                        }
                        canvas.drawCircle(12f, 12f, 3.2f, strokePaint)
                        canvas.drawCircle(12f, 12f, 7.5f, strokePaint)
                        canvas.drawLine(12f, 2f, 12f, 4.5f, strokePaint)
                        canvas.drawLine(12f, 19.5f, 12f, 22f, strokePaint)
                        canvas.drawLine(2f, 12f, 4.5f, 12f, strokePaint)
                        canvas.drawLine(19.5f, 12f, 22f, 12f, strokePaint)
                    }
                    UiIcon.SEARCH -> {
                        canvas.drawCircle(11f, 11f, 7f, strokePaint)
                        canvas.drawLine(16.2f, 16.2f, 21f, 21f, strokePaint)
                    }
                    UiIcon.ARROW_LEFT -> {
                        canvas.drawLine(19f, 12f, 5f, 12f, strokePaint)
                        canvas.drawLine(11f, 6f, 5f, 12f, strokePaint)
                        canvas.drawLine(11f, 18f, 5f, 12f, strokePaint)
                    }
                    UiIcon.MORE_VERTICAL -> {
                        fillPaint.color = color
                        canvas.drawCircle(12f, 5.5f, 1.6f, fillPaint)
                        canvas.drawCircle(12f, 12f, 1.6f, fillPaint)
                        canvas.drawCircle(12f, 18.5f, 1.6f, fillPaint)
                    }
                    UiIcon.LIST_FILTER -> {
                        canvas.drawLine(3f, 6f, 21f, 6f, strokePaint)
                        canvas.drawLine(6f, 12f, 18f, 12f, strokePaint)
                        canvas.drawLine(10f, 18f, 14f, 18f, strokePaint)
                    }
                    UiIcon.LIST -> {
                        canvas.drawLine(8f, 6f, 21f, 6f, strokePaint)
                        canvas.drawLine(8f, 12f, 21f, 12f, strokePaint)
                        canvas.drawLine(8f, 18f, 21f, 18f, strokePaint)
                        canvas.drawCircle(4f, 6f, 1f, strokePaint)
                        canvas.drawCircle(4f, 12f, 1f, strokePaint)
                        canvas.drawCircle(4f, 18f, 1f, strokePaint)
                    }
                    UiIcon.CLOSE -> {
                        canvas.drawLine(6f, 6f, 18f, 18f, strokePaint)
                        canvas.drawLine(18f, 6f, 6f, 18f, strokePaint)
                    }
                    UiIcon.CHECK -> {
                        path.moveTo(5f, 12.5f)
                        path.lineTo(9.5f, 17f)
                        path.lineTo(19f, 7.5f)
                        canvas.drawPath(path, strokePaint)
                    }
                    UiIcon.CHECK_ALL -> {
                        path.moveTo(3f, 12.5f); path.lineTo(7.5f, 17f); path.lineTo(17f, 7.5f)
                        path.moveTo(9f, 12.5f); path.lineTo(13.5f, 17f); path.lineTo(22f, 7.5f)
                        canvas.drawPath(path, strokePaint)
                    }
                    UiIcon.CHEVRON_RIGHT -> {
                        path.moveTo(9f, 6f)
                        path.lineTo(15f, 12f)
                        path.lineTo(9f, 18f)
                        canvas.drawPath(path, strokePaint)
                    }
                    UiIcon.PLUS -> {
                        canvas.drawLine(12f, 5f, 12f, 19f, strokePaint)
                        canvas.drawLine(5f, 12f, 19f, 12f, strokePaint)
                    }
                    UiIcon.STAR -> {
                        path.moveTo(12f, 3f)
                        path.lineTo(14.8f, 8.6f)
                        path.lineTo(21f, 9.5f)
                        path.lineTo(16.5f, 13.9f)
                        path.lineTo(17.6f, 20.1f)
                        path.lineTo(12f, 17.2f)
                        path.lineTo(6.4f, 20.1f)
                        path.lineTo(7.5f, 13.9f)
                        path.lineTo(3f, 9.5f)
                        path.lineTo(9.2f, 8.6f)
                        path.close()
                        if (filled) {
                            fillPaint.color = color
                            canvas.drawPath(path, fillPaint)
                        }
                        canvas.drawPath(path, strokePaint)
                    }
                    UiIcon.SHARE -> {
                        canvas.drawCircle(18f, 5f, 2.5f, strokePaint)
                        canvas.drawCircle(6f, 12f, 2.5f, strokePaint)
                        canvas.drawCircle(18f, 19f, 2.5f, strokePaint)
                        canvas.drawLine(8.2f, 10.7f, 15.8f, 6.3f, strokePaint)
                        canvas.drawLine(8.2f, 13.3f, 15.8f, 17.7f, strokePaint)
                    }
                    UiIcon.COPY -> {
                        rect.set(9f, 9f, 20f, 20f)
                        canvas.drawRoundRect(rect, 2f, 2f, strokePaint)
                        path.moveTo(5f, 15f)
                        path.lineTo(4f, 15f)
                        path.lineTo(4f, 4f)
                        path.lineTo(15f, 4f)
                        path.lineTo(15f, 5f)
                        canvas.drawPath(path, strokePaint)
                    }
                    UiIcon.CUT -> {
                        canvas.drawCircle(6f, 6f, 2.5f, strokePaint)
                        canvas.drawCircle(6f, 18f, 2.5f, strokePaint)
                        canvas.drawLine(20f, 4f, 8.1f, 15.9f, strokePaint)
                        canvas.drawLine(8.1f, 8.1f, 20f, 20f, strokePaint)
                    }
                    UiIcon.TRASH -> {
                        canvas.drawLine(4f, 7f, 20f, 7f, strokePaint)
                        rect.set(6f, 7f, 18f, 20f)
                        canvas.drawRoundRect(rect, 2f, 2f, strokePaint)
                        canvas.drawLine(9f, 4f, 15f, 4f, strokePaint)
                        canvas.drawLine(10f, 11f, 10f, 16f, strokePaint)
                        canvas.drawLine(14f, 11f, 14f, 16f, strokePaint)
                    }
                    UiIcon.EDIT -> {
                        path.moveTo(16.5f, 3.5f)
                        path.lineTo(20.5f, 7.5f)
                        path.lineTo(8f, 20f)
                        path.lineTo(4f, 20f)
                        path.lineTo(4f, 16f)
                        path.close()
                        canvas.drawPath(path, strokePaint)
                    }
                    UiIcon.COMPRESS -> {
                        rect.set(4f, 4f, 20f, 20f)
                        canvas.drawRoundRect(rect, 2.5f, 2.5f, strokePaint)
                        canvas.drawLine(12f, 4f, 12f, 20f, strokePaint)
                        canvas.drawLine(4f, 10f, 20f, 10f, strokePaint)
                    }
                    UiIcon.INFO -> {
                        canvas.drawCircle(12f, 12f, 9f, strokePaint)
                        canvas.drawLine(12f, 11f, 12f, 16f, strokePaint)
                        fillPaint.color = color
                        canvas.drawCircle(12f, 8f, 1f, fillPaint)
                    }
                    UiIcon.HARD_DRIVE -> {
                        rect.set(3f, 6f, 21f, 18f)
                        canvas.drawRoundRect(rect, 2.5f, 2.5f, strokePaint)
                        canvas.drawLine(3f, 13f, 21f, 13f, strokePaint)
                        fillPaint.color = color
                        canvas.drawCircle(7f, 15.5f, 1f, fillPaint)
                    }
                    UiIcon.SD_CARD -> {
                        path.moveTo(6f, 3f)
                        path.lineTo(15f, 3f)
                        path.lineTo(19f, 7f)
                        path.lineTo(19f, 21f)
                        path.lineTo(6f, 21f)
                        path.close()
                        canvas.drawPath(path, strokePaint)
                        canvas.drawLine(9f, 6f, 9f, 9f, strokePaint)
                        canvas.drawLine(12f, 6f, 12f, 9f, strokePaint)
                    }
                    UiIcon.CLOUD -> {
                        rect.set(4f, 9f, 20f, 19f)
                        canvas.drawRoundRect(rect, 5f, 5f, strokePaint)
                        canvas.drawCircle(10f, 10f, 4f, strokePaint)
                    }
                    UiIcon.FOLDER_PLUS -> {
                        path.moveTo(3f, 7f)
                        path.lineTo(9f, 7f)
                        path.lineTo(11f, 9f)
                        path.lineTo(21f, 9f)
                        path.lineTo(21f, 19f)
                        path.lineTo(3f, 19f)
                        path.close()
                        canvas.drawPath(path, strokePaint)
                        canvas.drawLine(12f, 12f, 12f, 16f, strokePaint)
                        canvas.drawLine(10f, 14f, 14f, 14f, strokePaint)
                    }
                    UiIcon.UPLOAD -> {
                        canvas.drawLine(12f, 16f, 12f, 5f, strokePaint)
                        canvas.drawLine(8f, 9f, 12f, 5f, strokePaint)
                        canvas.drawLine(16f, 9f, 12f, 5f, strokePaint)
                        canvas.drawLine(4f, 19f, 20f, 19f, strokePaint)
                    }
                    UiIcon.SCAN -> {
                        path.moveTo(4f, 8f); path.lineTo(4f, 4f); path.lineTo(8f, 4f)
                        path.moveTo(16f, 4f); path.lineTo(20f, 4f); path.lineTo(20f, 8f)
                        path.moveTo(20f, 16f); path.lineTo(20f, 20f); path.lineTo(16f, 20f)
                        path.moveTo(8f, 20f); path.lineTo(4f, 20f); path.lineTo(4f, 16f)
                        canvas.drawPath(path, strokePaint)
                        canvas.drawLine(5f, 12f, 19f, 12f, strokePaint)
                    }
                    UiIcon.MOON -> {
                        canvas.drawCircle(12f, 12f, 7f, strokePaint)
                    }
                    UiIcon.PALETTE -> {
                        canvas.drawCircle(12f, 12f, 8.5f, strokePaint)
                        fillPaint.color = color
                        canvas.drawCircle(9f, 9f, 1.3f, fillPaint)
                        canvas.drawCircle(14.5f, 9f, 1.3f, fillPaint)
                        canvas.drawCircle(15f, 14f, 1.3f, fillPaint)
                    }
                    UiIcon.DATABASE -> {
                        rect.set(5f, 4f, 19f, 20f)
                        canvas.drawRoundRect(rect, 3f, 3f, strokePaint)
                        canvas.drawLine(5f, 9.5f, 19f, 9.5f, strokePaint)
                        canvas.drawLine(5f, 15f, 19f, 15f, strokePaint)
                    }
                    UiIcon.SHIELD -> {
                        path.moveTo(12f, 3f)
                        path.lineTo(19f, 6f)
                        path.lineTo(19f, 12f)
                        path.lineTo(12f, 21f)
                        path.lineTo(5f, 12f)
                        path.lineTo(5f, 6f)
                        path.close()
                        canvas.drawPath(path, strokePaint)
                    }
                    UiIcon.BELL -> {
                        rect.set(6f, 5f, 18f, 16f)
                        canvas.drawRoundRect(rect, 6f, 6f, strokePaint)
                        canvas.drawLine(4f, 16f, 20f, 16f, strokePaint)
                        canvas.drawLine(10f, 19f, 14f, 19f, strokePaint)
                    }
                    UiIcon.HELP -> {
                        canvas.drawCircle(12f, 12f, 9f, strokePaint)
                        canvas.drawLine(12f, 10f, 12f, 13.5f, strokePaint)
                        fillPaint.color = color
                        canvas.drawCircle(12f, 16.8f, 1f, fillPaint)
                    }
                    UiIcon.REFRESH -> {
                        rect.set(5f, 5f, 19f, 19f)
                        canvas.drawArc(rect, -30f, 300f, false, strokePaint)
                    }
                }
                canvas.restore()
            }
        }
    }

    /**
     * Wraps a 20-22dp icon in a >= 44x44dp touch target container (§08 & §13).
     */
    fun createIconButton(
        context: Context,
        icon: UiIcon,
        color: Int,
        contentDesc: String,
        onClick: () -> Unit
    ): FrameLayout {
        val density = context.resources.displayMetrics.density
        val hitPx = (44 * density).toInt()
        return FrameLayout(context).apply {
            contentDescription = contentDesc
            layoutParams = LinearLayout.LayoutParams(hitPx, hitPx)
            val iconView = createUiIconView(context, icon, color, 21, filled = false)
            addView(
                iconView,
                FrameLayout.LayoutParams(
                    iconView.layoutParams.width,
                    iconView.layoutParams.height,
                    Gravity.CENTER
                )
            )
            setOnClickListener { onClick() }
        }
    }

    /**
     * Enables tabular numerals (`tnum`) on a TextView so file sizes, dates, and counts align vertically (§02 & §10.2).
     */
    fun applyTabularNums(tv: TextView) {
        tv.fontFeatureSettings = "tnum"
    }
}
