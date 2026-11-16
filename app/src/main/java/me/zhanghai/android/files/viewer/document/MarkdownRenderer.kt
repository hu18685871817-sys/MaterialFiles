package me.zhanghai.android.files.viewer.document

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

object MarkdownRenderer {

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /**
     * Renders Markdown source text into a LinearLayout container in real time.
     */
    fun renderToContainer(context: Context, container: LinearLayout, markdown: String) {
        container.removeAllViews()
        val lines = markdown.replace("\r\n", "\n").replace("\r", "\n").lines()

        var i = 0
        while (i < lines.size) {
            val rawLine = lines[i]
            val trimmed = rawLine.trim()

            // 1. Empty line
            if (trimmed.isEmpty()) {
                val spacer = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(context, 6)
                    )
                }
                container.addView(spacer)
                i++
                continue
            }

            // 2. Fenced Code Block ```lang
            if (trimmed.startsWith("```")) {
                val lang = trimmed.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                if (i < lines.size) i++ // consume closing ```
                container.addView(buildCodeBlockView(context, lang, codeLines.joinToString("\n")))
                continue
            }

            // 3. Markdown Table | Col1 | Col2 |
            if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.count { it == '|' } >= 2) {
                val tableRows = mutableListOf<List<String>>()
                while (i < lines.size) {
                    val rowLine = lines[i].trim()
                    if (!rowLine.startsWith("|") || !rowLine.endsWith("|")) break
                    val cells = rowLine.substring(1, rowLine.length - 1)
                        .split("|")
                        .map { it.trim() }
                    // Skip separator line like |---|---|
                    val isSeparator = cells.all { cell ->
                        cell.isNotEmpty() && cell.all { ch -> ch == '-' || ch == ':' || ch == ' ' }
                    }
                    if (!isSeparator) {
                        tableRows.add(cells)
                    }
                    i++
                }
                if (tableRows.isNotEmpty()) {
                    container.addView(buildTableView(context, tableRows))
                }
                continue
            }

            // 4. Horizontal Rule --- or ***
            if (trimmed.matches(Regex("""^([-*_])\1{2,}$"""))) {
                val divider = View(context).apply {
                    setBackgroundColor(Color.parseColor("#CBD5E1"))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(context, 1)
                    ).apply {
                        topMargin = dp(context, 10)
                        bottomMargin = dp(context, 10)
                    }
                }
                container.addView(divider)
                i++
                continue
            }

            // 5. Headings # ~ ######
            val headingMatch = Regex("""^(#{1,6})\s+(.*)$""").find(trimmed)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val headingText = headingMatch.groupValues[2]
                val tv = TextView(context).apply {
                    text = formatInlineMarkdown(headingText)
                    textSize = when (level) {
                        1 -> 22f
                        2 -> 19f
                        3 -> 17f
                        4 -> 15.5f
                        else -> 14.5f
                    }
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                    setPadding(0, dp(context, if (level <= 2) 10 else 6), 0, dp(context, 4))
                }
                container.addView(tv)
                if (level == 1 || level == 2) {
                    val underline = View(context).apply {
                        setBackgroundColor(Color.parseColor("#E2E8F0"))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(context, 1)
                        ).apply { bottomMargin = dp(context, 6) }
                    }
                    container.addView(underline)
                }
                i++
                continue
            }

            // 6. Blockquote > text
            if (trimmed.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoteLines.add(lines[i].trim().removePrefix(">").trimStart())
                    i++
                }
                container.addView(buildBlockquoteView(context, quoteLines.joinToString("\n")))
                continue
            }

            // 7. Task List - [x] or - [ ]
            val taskMatch = Regex("""^[-*+]\s+\[([ xX])\]\s+(.*)$""").find(trimmed)
            if (taskMatch != null) {
                val checked = taskMatch.groupValues[1].equals("x", ignoreCase = true)
                val body = taskMatch.groupValues[2]
                val tv = TextView(context).apply {
                    val prefix = if (checked) "☑️ " else "⬜ "
                    text = formatInlineMarkdown(prefix + body)
                    textSize = 15f
                    setTextColor(if (checked) Color.parseColor("#64748B") else Color.parseColor("#1E293B"))
                    setPadding(dp(context, 8), dp(context, 3), 0, dp(context, 3))
                }
                container.addView(tv)
                i++
                continue
            }

            // 8. Unordered or Ordered List
            val ulMatch = Regex("""^[-*+]\s+(.*)$""").find(trimmed)
            val olMatch = Regex("""^(\d+\.)\s+(.*)$""").find(trimmed)
            if (ulMatch != null || olMatch != null) {
                val bullet = if (ulMatch != null) "•  " else "${olMatch!!.groupValues[1]} "
                val body = ulMatch?.groupValues?.get(1) ?: olMatch!!.groupValues[2]
                val tv = TextView(context).apply {
                    text = formatInlineMarkdown(bullet + body)
                    textSize = 15f
                    setTextColor(Color.parseColor("#1E293B"))
                    setLineSpacing(0f, 1.25f)
                    setPadding(dp(context, 10), dp(context, 2), 0, dp(context, 2))
                }
                container.addView(tv)
                i++
                continue
            }

            // 9. Standard Paragraph
            val tv = TextView(context).apply {
                text = formatInlineMarkdown(rawLine)
                textSize = 15f
                setTextColor(Color.parseColor("#1E293B"))
                setLineSpacing(0f, 1.35f)
                setPadding(0, dp(context, 3), 0, dp(context, 3))
            }
            container.addView(tv)
            i++
        }
    }

    private fun buildCodeBlockView(context: Context, language: String, code: String): View {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 10).toFloat()
                setColor(Color.parseColor("#0F172A"))
            }
            setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 6)
                bottomMargin = dp(context, 6)
            }
        }
        if (language.isNotEmpty()) {
            val langBadge = TextView(context).apply {
                text = language.uppercase()
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
                setPadding(0, 0, 0, dp(context, 4))
            }
            wrapper.addView(langBadge)
        }
        val hScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
        }
        val codeTv = TextView(context).apply {
            text = code
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#E2E8F0"))
            setLineSpacing(0f, 1.25f)
        }
        hScroll.addView(codeTv)
        wrapper.addView(hScroll)
        return wrapper
    }

    private fun buildBlockquoteView(context: Context, text: String): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 8).toFloat()
                setColor(Color.parseColor("#F1F5F9"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 6)
                bottomMargin = dp(context, 6)
            }
        }
        val bar = View(context).apply {
            setBackgroundColor(Color.parseColor("#3B82F6"))
            layoutParams = LinearLayout.LayoutParams(
                dp(context, 4),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val quoteTv = TextView(context).apply {
            this.text = formatInlineMarkdown(text)
            textSize = 14.5f
            setTypeface(typeface, Typeface.ITALIC)
            setTextColor(Color.parseColor("#475569"))
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
        }
        row.addView(bar)
        row.addView(quoteTv)
        return row
    }

    fun buildTableView(context: Context, rows: List<List<String>>): View {
        val hScroll = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 8)
                bottomMargin = dp(context, 8)
            }
        }
        val tableContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 8).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#CBD5E1"))
                setColor(Color.WHITE)
            }
        }
        val colCount = rows.maxOfOrNull { it.size } ?: 1
        rows.forEachIndexed { rowIndex, rowCells ->
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(
                    when {
                        rowIndex == 0 -> Color.parseColor("#EFF6FF")
                        rowIndex % 2 == 1 -> Color.WHITE
                        else -> Color.parseColor("#F8FAFC")
                    }
                )
            }
            for (c in 0 until colCount) {
                val cellText = rowCells.getOrNull(c).orEmpty()
                val cellTv = TextView(context).apply {
                    text = formatInlineMarkdown(cellText)
                    textSize = 13.5f
                    if (rowIndex == 0) {
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Color.parseColor("#1E3A8A"))
                    } else {
                        setTextColor(Color.parseColor("#334155"))
                    }
                    setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9))
                    minWidth = dp(context, 105)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }
                rowLayout.addView(cellTv)
            }
            tableContainer.addView(rowLayout)
        }
        hScroll.addView(tableContainer)
        return hScroll
    }

    /**
     * Parses inline Markdown spans: **bold**, *italic*, ~~strike~~, `code`, [link](url)
     */
    fun formatInlineMarkdown(input: String): CharSequence {
        val ssb = SpannableStringBuilder()
        var i = 0
        while (i < input.length) {
            // Inline code `...`
            if (input[i] == '`') {
                val end = input.indexOf('`', i + 1)
                if (end > i + 1) {
                    val codeContent = input.substring(i + 1, end)
                    val startSpan = ssb.length
                    ssb.append(" $codeContent ")
                    val endSpan = ssb.length
                    ssb.setSpan(TypefaceSpan("monospace"), startSpan, endSpan, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(BackgroundColorSpan(Color.parseColor("#E2E8F0")), startSpan, endSpan, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(ForegroundColorSpan(Color.parseColor("#BE123C")), startSpan, endSpan, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(RelativeSizeSpan(0.92f), startSpan, endSpan, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 1
                    continue
                }
            }
            // Bold **...**
            if (i + 1 < input.length && input.startsWith("**", i)) {
                val end = input.indexOf("**", i + 2)
                if (end > i + 2) {
                    val boldContent = input.substring(i + 2, end)
                    val startSpan = ssb.length
                    ssb.append(boldContent)
                    ssb.setSpan(StyleSpan(Typeface.BOLD), startSpan, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                    continue
                }
            }
            // Strikethrough ~~...~~
            if (i + 1 < input.length && input.startsWith("~~", i)) {
                val end = input.indexOf("~~", i + 2)
                if (end > i + 2) {
                    val strikeContent = input.substring(i + 2, end)
                    val startSpan = ssb.length
                    ssb.append(strikeContent)
                    ssb.setSpan(StrikethroughSpan(), startSpan, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                    continue
                }
            }
            // Italic *...*
            if (input[i] == '*') {
                val end = input.indexOf('*', i + 1)
                if (end > i + 1) {
                    val italicContent = input.substring(i + 1, end)
                    val startSpan = ssb.length
                    ssb.append(italicContent)
                    ssb.setSpan(StyleSpan(Typeface.ITALIC), startSpan, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 1
                    continue
                }
            }
            // Link [label](url)
            if (input[i] == '[') {
                val closeBracket = input.indexOf("](", i + 1)
                if (closeBracket > i + 1) {
                    val closeParen = input.indexOf(')', closeBracket + 2)
                    if (closeParen > closeBracket + 2) {
                        val label = input.substring(i + 1, closeBracket)
                        val startSpan = ssb.length
                        ssb.append(label)
                        ssb.setSpan(ForegroundColorSpan(Color.parseColor("#2563EB")), startSpan, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        ssb.setSpan(UnderlineSpan(), startSpan, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = closeParen + 1
                        continue
                    }
                }
            }
            ssb.append(input[i])
            i++
        }
        return ssb
    }

    fun sampleMarkdownContent(): String = """
# Markdown 实时渲染与编辑引擎

欢迎使用 **Material Files** 内置的 **Markdown 实时渲染与文档工作台**！您可以在上方切换 **实时预览**、**分屏实时编辑** 与 **源码模式**。

## 1. 核心特性一览
- [x] 支持 **Office 文档**（`.docx` Word、`.xlsx` Excel、`.txt` 文本）直接打开预览
- [x] 支持 **Markdown 实时渲染**（输入即时刷新标题、代码、表格与清单）
- [x] 支持 **压缩文件免解压内预览**（查看 ZIP / RAR / 7Z 文件列表与内容）
- [ ] 点击上方工具栏按钮可快速插入语法标签并保存到本地文件

## 2. 代码高亮块示例
```kotlin
fun previewDocument(file: File) {
    when (file.extension.lowercase()) {
        "docx" -> OfficeDocumentParser.parseDocx(file.inputStream())
        "xlsx" -> OfficeDocumentParser.parseXlsx(file.inputStream())
        "md" -> MarkdownRenderer.render(file.readText())
    }
}
```

## 3. 格式对比表格
| 文件格式 | 预览能力 | 编辑保存 | 特色功能 |
| --- | --- | --- | --- |
| DOCX | 段落/标题/表格解析 | 支持导出TXT | OpenXML 直读 |
| XLSX | 多工作表网格视图 | 单元格公式检查 | 双向滚动表格 |
| Markdown | 实时分屏渲染 | 源码实时保存 | 快捷语法工具栏 |
| TXT | 多编码识别预览 | 全文编辑保存 | 一键转入内置阅读器 |

> 提示：点击右上角「保存」可将当前编辑内容写回磁盘文件，或点击「阅读模式」进入带记忆缓存的内置阅读器。
""".trimIndent()
}
