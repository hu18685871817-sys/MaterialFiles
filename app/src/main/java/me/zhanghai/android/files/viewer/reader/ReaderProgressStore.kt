package me.zhanghai.android.files.viewer.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class PageTurnMode(val id: String, val displayName: String) {
    SWIPE_PAGER("swipe_pager", "左右滑动翻页"),
    VERTICAL_SCROLL("vertical_scroll", "上下滚动阅读"),
    INSTANT_TAP("instant_tap", "点击极速翻页");

    companion object {
        fun fromId(id: String?): PageTurnMode =
            entries.firstOrNull { it.id == id } ?: SWIPE_PAGER
    }
}

enum class ReaderTheme(
    val id: String,
    val displayName: String,
    val backgroundColorHex: String,
    val textColorHex: String,
    val secondaryTextHex: String,
    val barBackgroundHex: String
) {
    DAY("day", "经典白昼", "#FFFFFF", "#1E293B", "#64748B", "#F1F5F9"),
    SEPIA("sepia", "羊皮纸护眼", "#F6EFE2", "#3D2B1F", "#7A6248", "#EAE0CC"),
    GREEN("green", "豆沙绿护眼", "#E3F2E6", "#1B3B2B", "#4B6E5C", "#D0E8D5"),
    NIGHT("night", "静谧夜间", "#0F172A", "#E2E8F0", "#94A3B8", "#1E293B");

    companion object {
        fun fromId(id: String?): ReaderTheme =
            entries.firstOrNull { it.id == id } ?: SEPIA
    }
}

data class ReaderBookmark(
    val charOffset: Int,
    val snippet: String,
    val chapterTitle: String,
    val createdAtMillis: Long
)

data class BookReadingProgress(
    val bookKey: String,
    val filePath: String,
    val title: String,
    val charOffset: Int,
    val pageIndex: Int,
    val totalPages: Int,
    val progressPercent: Float,
    val chapterTitle: String,
    val updatedAtMillis: Long,
    val bookmarks: List<ReaderBookmark> = emptyList()
)

data class ReaderPreferences(
    val fontSizeSp: Float = 18f,
    val lineSpacingMultiplier: Float = 1.5f,
    val pageTurnMode: PageTurnMode = PageTurnMode.SWIPE_PAGER,
    val theme: ReaderTheme = ReaderTheme.SEPIA
)

object ReaderProgressStore {
    private const val PREFS_NAME = "material_files_built_in_reader_cache"
    private const val KEY_PREFS_CONFIG = "reader_global_preferences"
    private const val KEY_BOOK_MAP = "reader_book_progress_map"

    fun computeBookKey(filePath: String?, title: String): String {
        val raw = (filePath?.takeIf { it.isNotEmpty() } ?: title).trim()
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun loadPreferences(context: Context): ReaderPreferences {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PREFS_CONFIG, null) ?: return ReaderPreferences()
        return runCatching {
            val obj = JSONObject(raw)
            ReaderPreferences(
                fontSizeSp = obj.optDouble("fontSizeSp", 18.0).toFloat().coerceIn(12f, 32f),
                lineSpacingMultiplier = obj.optDouble("lineSpacingMultiplier", 1.5).toFloat().coerceIn(1.1f, 2.2f),
                pageTurnMode = PageTurnMode.fromId(obj.optString("pageTurnMode")),
                theme = ReaderTheme.fromId(obj.optString("theme"))
            )
        }.getOrDefault(ReaderPreferences())
    }

    fun savePreferences(context: Context, preferences: ReaderPreferences) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val obj = JSONObject().apply {
            put("fontSizeSp", preferences.fontSizeSp.toDouble())
            put("lineSpacingMultiplier", preferences.lineSpacingMultiplier.toDouble())
            put("pageTurnMode", preferences.pageTurnMode.id)
            put("theme", preferences.theme.id)
        }
        prefs.edit().putString(KEY_PREFS_CONFIG, obj.toString()).apply()
    }

    fun getBookProgress(context: Context, bookKey: String): BookReadingProgress? {
        return getAllBookProgress(context).firstOrNull { it.bookKey == bookKey }
    }

    fun saveBookProgress(context: Context, progress: BookReadingProgress) {
        val all = getAllBookProgress(context).toMutableList()
        all.removeAll { it.bookKey == progress.bookKey }
        all.add(0, progress)
        val trimmed = all.take(50)

        val arr = JSONArray()
        for (item in trimmed) {
            val bmArr = JSONArray()
            item.bookmarks.forEach { bm ->
                bmArr.put(
                    JSONObject().apply {
                        put("charOffset", bm.charOffset)
                        put("snippet", bm.snippet)
                        put("chapterTitle", bm.chapterTitle)
                        put("createdAtMillis", bm.createdAtMillis)
                    }
                )
            }
            arr.put(
                JSONObject().apply {
                    put("bookKey", item.bookKey)
                    put("filePath", item.filePath)
                    put("title", item.title)
                    put("charOffset", item.charOffset)
                    put("pageIndex", item.pageIndex)
                    put("totalPages", item.totalPages)
                    put("progressPercent", item.progressPercent.toDouble())
                    put("chapterTitle", item.chapterTitle)
                    put("updatedAtMillis", item.updatedAtMillis)
                    put("bookmarks", bmArr)
                }
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BOOK_MAP, arr.toString())
            .apply()
    }

    fun getAllBookProgress(context: Context): List<BookReadingProgress> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOK_MAP, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val list = mutableListOf<BookReadingProgress>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val bmList = mutableListOf<ReaderBookmark>()
                val bmArr = obj.optJSONArray("bookmarks")
                if (bmArr != null) {
                    for (j in 0 until bmArr.length()) {
                        val bObj = bmArr.getJSONObject(j)
                        bmList.add(
                            ReaderBookmark(
                                charOffset = bObj.optInt("charOffset", 0),
                                snippet = bObj.optString("snippet", ""),
                                chapterTitle = bObj.optString("chapterTitle", ""),
                                createdAtMillis = bObj.optLong("createdAtMillis", 0L)
                            )
                        )
                    }
                }
                list.add(
                    BookReadingProgress(
                        bookKey = obj.optString("bookKey"),
                        filePath = obj.optString("filePath"),
                        title = obj.optString("title"),
                        charOffset = obj.optInt("charOffset", 0),
                        pageIndex = obj.optInt("pageIndex", 0),
                        totalPages = obj.optInt("totalPages", 1),
                        progressPercent = obj.optDouble("progressPercent", 0.0).toFloat(),
                        chapterTitle = obj.optString("chapterTitle", "正文"),
                        updatedAtMillis = obj.optLong("updatedAtMillis", 0L),
                        bookmarks = bmList
                    )
                )
            }
            list.sortedByDescending { it.updatedAtMillis }
        }.getOrDefault(emptyList())
    }
}
