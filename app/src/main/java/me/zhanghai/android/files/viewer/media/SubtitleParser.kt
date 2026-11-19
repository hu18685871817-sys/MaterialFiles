package me.zhanghai.android.files.viewer.media

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale

data class SubtitleCue(
    val index: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
    val styleName: String = "Default"
)

data class SubtitleTrack(
    val title: String,
    val format: String, // "SRT" or "ASS"
    val cues: List<SubtitleCue>,
    val sourcePath: String? = null
) {
    fun findActiveText(positionMs: Long, offsetMs: Long = 0L): String {
        val adjustedTime = positionMs - offsetMs
        if (cues.isEmpty() || adjustedTime < 0L) return ""
        val active = cues.filter { adjustedTime in it.startTimeMs..it.endTimeMs }
        if (active.isEmpty()) return ""
        return active.joinToString("\n") { it.text }
    }
}

object SubtitleParser {

    fun parseFromFile(file: File): SubtitleTrack {
        val bytes = file.readBytes()
        val content = decodeSubtitleBytes(bytes)
        val ext = file.extension.lowercase(Locale.ROOT)
        return if (ext == "ass" || ext == "ssa" || content.contains("[Events]")) {
            parseAss(content, file.name, file.absolutePath)
        } else {
            parseSrt(content, file.name, file.absolutePath)
        }
    }

    fun parseFromUri(context: Context, uri: Uri, displayName: String? = null): SubtitleTrack {
        val name = displayName ?: uri.lastPathSegment ?: "external_subtitle.srt"
        val bytes = context.contentResolver.openInputStream(uri)?.use(InputStream::readBytes)
            ?: ByteArray(0)
        val content = decodeSubtitleBytes(bytes)
        val lower = name.lowercase(Locale.ROOT)
        return if (lower.endsWith(".ass") || lower.endsWith(".ssa") || content.contains("[Events]")) {
            parseAss(content, name, uri.toString())
        } else {
            parseSrt(content, name, uri.toString())
        }
    }

    private fun decodeSubtitleBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // Check BOM
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        // If replacement character appears frequently, fallback to GBK for Chinese subtitles
        val replacementCount = utf8.count { it == '\uFFFD' }
        if (replacementCount > 2) {
            return runCatching {
                String(bytes, Charset.forName("GBK"))
            }.getOrDefault(utf8)
        }
        return utf8
    }

    /**
     * Parses SubRip (.srt) format:
     * 1
     * 00:00:01,000 --> 00:00:04,500
     * Subtitle line 1
     */
    fun parseSrt(rawContent: String, title: String = "SRT 字幕", sourcePath: String? = null): SubtitleTrack {
        val normalized = rawContent.replace("\r\n", "\n").replace("\r", "\n").trim()
        if (normalized.isEmpty()) {
            return SubtitleTrack(title, "SRT", emptyList(), sourcePath)
        }
        val blocks = normalized.split(Regex("\n\\s*\n"))
        val cues = mutableListOf<SubtitleCue>()
        val timeLineRegex = Regex(
            """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
        )

        var indexCounter = 1
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            var timeLineIdx = -1
            var matchResult: MatchResult? = null
            for (i in lines.indices) {
                val m = timeLineRegex.find(lines[i])
                if (m != null) {
                    timeLineIdx = i
                    matchResult = m
                    break
                }
            }
            if (timeLineIdx == -1 || matchResult == null) continue
            val g = matchResult.groupValues
            val startMs = toMillis(g[1], g[2], g[3], g[4].padEnd(3, '0'))
            val endMs = toMillis(g[5], g[6], g[7], g[8].padEnd(3, '0'))
            val textLines = lines.subList(timeLineIdx + 1, lines.size)
            val cleanedText = textLines.joinToString("\n") { stripSubtitleTags(it) }.trim()
            if (cleanedText.isNotEmpty() && endMs >= startMs) {
                cues.add(
                    SubtitleCue(
                        index = indexCounter++,
                        startTimeMs = startMs,
                        endTimeMs = endMs,
                        text = cleanedText
                    )
                )
            }
        }
        cues.sortBy { it.startTimeMs }
        return SubtitleTrack(title = title, format = "SRT", cues = cues, sourcePath = sourcePath)
    }

    /**
     * Parses Advanced SubStation Alpha (.ass / .ssa) format:
     * [Events]
     * Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
     * Dialogue: 0,0:00:01.20,0:00:04.80,Default,,0,0,0,,{\an8}Hello World\N第二行
     */
    fun parseAss(rawContent: String, title: String = "ASS 特效字幕", sourcePath: String? = null): SubtitleTrack {
        val lines = rawContent.replace("\r\n", "\n").replace("\r", "\n").lines()
        var inEventsSection = false
        var formatColumns = listOf(
            "layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text"
        )
        val cues = mutableListOf<SubtitleCue>()
        var indexCounter = 1

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                inEventsSection = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEventsSection && !line.startsWith("Dialogue:", ignoreCase = true)) continue

            if (line.startsWith("Format:", ignoreCase = true)) {
                val cols = line.substringAfter(":").split(",").map { it.trim().lowercase(Locale.ROOT) }
                if (cols.isNotEmpty()) {
                    formatColumns = cols
                }
                continue
            }

            if (line.startsWith("Dialogue:", ignoreCase = true)) {
                val payload = line.substringAfter(":").trimStart()
                val maxSplits = formatColumns.size.coerceAtLeast(2)
                val parts = payload.split(",", limit = maxSplits)
                val startIdx = formatColumns.indexOf("start").takeIf { it >= 0 } ?: 1
                val endIdx = formatColumns.indexOf("end").takeIf { it >= 0 } ?: 2
                val styleIdx = formatColumns.indexOf("style").takeIf { it >= 0 } ?: 3
                val textIdx = formatColumns.indexOf("text").takeIf { it >= 0 } ?: (maxSplits - 1)

                if (parts.size > textIdx && parts.size > endIdx) {
                    val startMs = parseAssTimestamp(parts[startIdx].trim())
                    val endMs = parseAssTimestamp(parts[endIdx].trim())
                    val styleName = parts.getOrNull(styleIdx)?.trim().orEmpty().ifEmpty { "Default" }
                    val rawText = parts[textIdx]
                    val cleanedText = stripSubtitleTags(
                        rawText.replace("\\N", "\n")
                            .replace("\\n", "\n")
                            .replace("\\h", " ")
                    ).trim()
                    if (cleanedText.isNotEmpty() && startMs >= 0 && endMs >= startMs) {
                        cues.add(
                            SubtitleCue(
                                index = indexCounter++,
                                startTimeMs = startMs,
                                endTimeMs = endMs,
                                text = cleanedText,
                                styleName = styleName
                            )
                        )
                    }
                }
            }
        }
        cues.sortBy { it.startTimeMs }
        return SubtitleTrack(title = title, format = "ASS", cues = cues, sourcePath = sourcePath)
    }

    private fun parseAssTimestamp(ts: String): Long {
        // Format: H:MM:SS.cc (centiseconds) or HH:MM:SS.mmm
        val regex = Regex("""(\d+):(\d{2}):(\d{2})[.:](\d{1,3})""")
        val m = regex.find(ts) ?: return -1L
        val g = m.groupValues
        val frac = g[4]
        val msStr = when (frac.length) {
            1 -> "${frac}00"
            2 -> "${frac}0"
            else -> frac.take(3)
        }
        return toMillis(g[1], g[2], g[3], msStr)
    }

    private fun toMillis(hStr: String, mStr: String, sStr: String, msStr: String): Long {
        val h = hStr.toLongOrNull() ?: 0L
        val m = mStr.toLongOrNull() ?: 0L
        val s = sStr.toLongOrNull() ?: 0L
        val ms = msStr.toLongOrNull() ?: 0L
        return ((h * 3600L + m * 60L + s) * 1000L) + ms
    }

    private fun stripSubtitleTags(input: String): String {
        return input
            .replace(Regex("""\{[^}]*\}"""), "") // ASS override tags like {\an8\pos(10,20)}
            .replace(Regex("""<[^>]*>"""), "") // HTML tags like <i>, <b>, <font color="...">
    }

    fun autoDetectSubtitleForVideo(videoFile: File): File? {
        val parent = videoFile.parentFile ?: return null
        if (!parent.exists() || !parent.isDirectory) return null
        val baseName = videoFile.nameWithoutExtension
        val candidates = parent.listFiles()?.filter {
            it.isFile && it.extension.lowercase(Locale.ROOT) in setOf("srt", "ass", "ssa")
        }.orEmpty()
        return candidates.firstOrNull {
            it.nameWithoutExtension.equals(baseName, ignoreCase = true) ||
                it.name.lowercase(Locale.ROOT).startsWith(baseName.lowercase(Locale.ROOT) + ".")
        }
    }

    fun listSubtitleFilesInFolders(folders: List<File>): List<File> {
        val result = mutableListOf<File>()
        val seenPaths = mutableSetOf<String>()
        for (folder in folders) {
            if (!folder.exists() || !folder.isDirectory) continue
            folder.listFiles()?.forEach { f ->
                if (f.isFile && f.extension.lowercase(Locale.ROOT) in setOf("srt", "ass", "ssa")) {
                    if (seenPaths.add(f.absolutePath)) {
                        result.add(f)
                    }
                }
            }
        }
        return result.sortedBy { it.name.lowercase(Locale.ROOT) }
    }
}
