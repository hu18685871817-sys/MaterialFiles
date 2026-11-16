package me.zhanghai.android.files.viewer.document

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

sealed class DocxBlock {
    data class Paragraph(
        val text: String,
        val headingLevel: Int = 0, // 0 = body, 1 = H1, 2 = H2, 3 = H3
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val isBullet: Boolean = false
    ) : DocxBlock()

    data class Table(
        val rows: List<List<String>>
    ) : DocxBlock()
}

data class DocxDocument(
    val title: String,
    val blocks: List<DocxBlock>,
    val paragraphCount: Int,
    val tableCount: Int,
    val characterCount: Int
) {
    fun toPlainText(): String = buildString {
        blocks.forEach { block ->
            when (block) {
                is DocxBlock.Paragraph -> {
                    if (block.isBullet) append("• ")
                    appendLine(block.text)
                    appendLine()
                }
                is DocxBlock.Table -> {
                    block.rows.forEach { row ->
                        appendLine(row.joinToString(" | "))
                    }
                    appendLine()
                }
            }
        }
    }.trim()
}

data class XlsxCell(
    val reference: String, // e.g. "A1", "B2"
    val row: Int,
    val col: Int,
    val value: String,
    val formula: String? = null
)

data class XlsxSheet(
    val name: String,
    val rowCount: Int,
    val colCount: Int,
    val rows: List<List<XlsxCell>>
)

data class XlsxWorkbook(
    val fileName: String,
    val sheets: List<XlsxSheet>
)

object OfficeDocumentParser {

    /**
     * Parses an OpenXML Word (.docx) file from an InputStream.
     */
    fun parseDocx(inputStream: InputStream, fileName: String = "Document.docx"): DocxDocument {
        val entries = readZipEntries(inputStream)
        val docXmlBytes = entries["word/document.xml"]
            ?: entries.entries.firstOrNull { it.key.endsWith("document.xml", true) }?.value

        if (docXmlBytes == null) {
            return DocxDocument(
                title = fileName,
                blocks = listOf(DocxBlock.Paragraph("无法找到 word/document.xml，请确认该文件为有效的 .docx 文档。")),
                paragraphCount = 1,
                tableCount = 0,
                characterCount = 0
            )
        }

        val blocks = mutableListOf<DocxBlock>()
        var paragraphCount = 0
        var tableCount = 0
        var charCount = 0

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(docXmlBytes), "UTF-8")

        var inParagraph = false
        var inRun = false
        var inText = false
        var inTable = false
        var inTableRow = false
        var inTableCell = false

        var currentParaText = StringBuilder()
        var currentHeadingLevel = 0
        var currentParaBold = false
        var currentParaItalic = false
        var currentParaBullet = false
        var currentRunBold = false
        var currentRunItalic = false

        var currentTableRows = mutableListOf<List<String>>()
        var currentRowCells = mutableListOf<String>()
        var currentCellText = StringBuilder()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            val rawName = parser.name.orEmpty()
            val localName = rawName.substringAfter(':')

            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (localName) {
                        "tbl" -> {
                            inTable = true
                            currentTableRows = mutableListOf()
                        }
                        "tr" -> if (inTable) {
                            inTableRow = true
                            currentRowCells = mutableListOf()
                        }
                        "tc" -> if (inTableRow) {
                            inTableCell = true
                            currentCellText = StringBuilder()
                        }
                        "p" -> {
                            if (!inTable) {
                                inParagraph = true
                                currentParaText = StringBuilder()
                                currentHeadingLevel = 0
                                currentParaBold = false
                                currentParaItalic = false
                                currentParaBullet = false
                            } else if (inTableCell && currentCellText.isNotEmpty()) {
                                currentCellText.append("\n")
                            }
                        }
                        "pStyle" -> if (inParagraph) {
                            val styleVal = getAttrValue(parser, "val").lowercase(Locale.ROOT)
                            currentHeadingLevel = when {
                                styleVal.contains("heading1") || styleVal == "1" || styleVal.contains("title") -> 1
                                styleVal.contains("heading2") || styleVal == "2" || styleVal.contains("subtitle") -> 2
                                styleVal.contains("heading3") || styleVal == "3" -> 3
                                else -> 0
                            }
                        }
                        "numPr", "numId" -> if (inParagraph) {
                            currentParaBullet = true
                        }
                        "r" -> {
                            inRun = true
                            currentRunBold = false
                            currentRunItalic = false
                        }
                        "b" -> {
                            val v = getAttrValue(parser, "val")
                            if (v != "0" && v != "false") {
                                if (inRun) currentRunBold = true
                                if (inParagraph) currentParaBold = true
                            }
                        }
                        "i" -> {
                            val v = getAttrValue(parser, "val")
                            if (v != "0" && v != "false") {
                                if (inRun) currentRunItalic = true
                                if (inParagraph) currentParaItalic = true
                            }
                        }
                        "t" -> {
                            inText = true
                        }
                        "tab" -> {
                            if (inTableCell) currentCellText.append("\t")
                            else if (inParagraph) currentParaText.append("    ")
                        }
                        "br", "cr" -> {
                            if (inTableCell) currentCellText.append("\n")
                            else if (inParagraph) currentParaText.append("\n")
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inText) {
                        val txt = parser.text.orEmpty()
                        if (inTableCell) {
                            currentCellText.append(txt)
                        } else if (inParagraph) {
                            currentParaText.append(txt)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (localName) {
                        "t" -> inText = false
                        "r" -> inRun = false
                        "p" -> {
                            if (!inTable && inParagraph) {
                                inParagraph = false
                                val text = currentParaText.toString().trimEnd()
                                if (text.isNotEmpty()) {
                                    blocks.add(
                                        DocxBlock.Paragraph(
                                            text = text,
                                            headingLevel = currentHeadingLevel,
                                            isBold = currentParaBold || currentHeadingLevel > 0,
                                            isItalic = currentParaItalic,
                                            isBullet = currentParaBullet
                                        )
                                    )
                                    paragraphCount++
                                    charCount += text.length
                                }
                            }
                        }
                        "tc" -> if (inTableRow) {
                            inTableCell = false
                            val cellStr = currentCellText.toString().trim()
                            currentRowCells.add(cellStr)
                            charCount += cellStr.length
                        }
                        "tr" -> if (inTable) {
                            inTableRow = false
                            if (currentRowCells.isNotEmpty()) {
                                currentTableRows.add(currentRowCells)
                            }
                        }
                        "tbl" -> if (inTable) {
                            inTable = false
                            if (currentTableRows.isNotEmpty()) {
                                blocks.add(DocxBlock.Table(currentTableRows))
                                tableCount++
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return DocxDocument(
            title = fileName,
            blocks = blocks,
            paragraphCount = paragraphCount,
            tableCount = tableCount,
            characterCount = charCount
        )
    }

    /**
     * Parses an OpenXML Excel (.xlsx) file from an InputStream.
     */
    fun parseXlsx(inputStream: InputStream, fileName: String = "Workbook.xlsx"): XlsxWorkbook {
        val entries = readZipEntries(inputStream)
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) }
            ?: emptyList()
        val sheetNames = entries["xl/workbook.xml"]?.let { parseWorkbookSheetNames(it) }
            ?: emptyList()

        val worksheetEntries = entries.entries
            .filter { it.key.startsWith("xl/worksheets/sheet") && it.key.endsWith(".xml") }
            .sortedBy {
                it.key.substringAfter("sheet").substringBefore(".xml").toIntOrNull() ?: 999
            }

        val sheets = worksheetEntries.mapIndexed { idx, entry ->
            val sheetTitle = sheetNames.getOrNull(idx) ?: "Sheet${idx + 1}"
            parseWorksheet(entry.value, sheetTitle, sharedStrings)
        }

        return XlsxWorkbook(
            fileName = fileName,
            sheets = if (sheets.isNotEmpty()) sheets else listOf(
                XlsxSheet("Sheet1", 1, 1, listOf(listOf(XlsxCell("A1", 0, 0, "空工作表"))))
            )
        )
    }

    /**
     * Parses CSV text into an XlsxWorkbook for unified spreadsheet grid preview.
     */
    fun parseCsv(content: String, fileName: String = "Data.csv"): XlsxWorkbook {
        val lines = content.replace("\r\n", "\n").replace("\r", "\n").lines()
            .filter { it.isNotBlank() }
        var maxCols = 0
        val rawRows = lines.map { line ->
            val cols = parseCsvLine(line)
            if (cols.size > maxCols) maxCols = cols.size
            cols
        }
        val colCount = maxCols.coerceAtLeast(1)
        val gridRows = rawRows.mapIndexed { rIdx, cols ->
            (0 until colCount).map { cIdx ->
                val ref = "${columnIndexToName(cIdx)}${rIdx + 1}"
                XlsxCell(
                    reference = ref,
                    row = rIdx,
                    col = cIdx,
                    value = cols.getOrNull(cIdx).orEmpty()
                )
            }
        }
        return XlsxWorkbook(
            fileName = fileName,
            sheets = listOf(
                XlsxSheet(
                    name = fileName.substringBeforeLast('.').ifEmpty { "CSV" },
                    rowCount = gridRows.size,
                    colCount = colCount,
                    rows = gridRows
                )
            )
        )
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == ',' && !inQuotes -> {
                    result.add(sb.toString().trim())
                    sb.clear()
                }
                else -> sb.append(ch)
            }
            i++
        }
        result.add(sb.toString().trim())
        return result
    }

    private fun parseSharedStrings(xmlBytes: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

        var inSi = false
        var inT = false
        val currentSi = StringBuilder()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            val localName = parser.name.orEmpty().substringAfter(':')
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (localName == "si") {
                        inSi = true
                        currentSi.clear()
                    } else if (inSi && localName == "t") {
                        inT = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inSi && inT) {
                        currentSi.append(parser.text.orEmpty())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (localName == "t") {
                        inT = false
                    } else if (localName == "si") {
                        inSi = false
                        strings.add(currentSi.toString())
                    }
                }
            }
            eventType = parser.next()
        }
        return strings
    }

    private fun parseWorkbookSheetNames(xmlBytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            val localName = parser.name.orEmpty().substringAfter(':')
            if (eventType == XmlPullParser.START_TAG && localName == "sheet") {
                val sheetName = getAttrValue(parser, "name")
                if (sheetName.isNotEmpty()) {
                    names.add(sheetName)
                }
            }
            eventType = parser.next()
        }
        return names
    }

    private fun parseWorksheet(
        xmlBytes: ByteArray,
        sheetName: String,
        sharedStrings: List<String>
    ): XlsxSheet {
        val cellMap = mutableMapOf<Pair<Int, Int>, XlsxCell>()
        var maxRow = 0
        var maxCol = 0

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")

        var currentRowIdx = 0
        var currentColIdx = 0
        var currentCellRef = ""
        var currentCellType = ""
        var inValue = false
        var inInlineText = false
        var inFormula = false
        val valueBuilder = StringBuilder()
        val formulaBuilder = StringBuilder()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            val localName = parser.name.orEmpty().substringAfter(':')
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (localName) {
                        "row" -> {
                            val rAttr = getAttrValue(parser, "r").toIntOrNull()
                            currentRowIdx = if (rAttr != null && rAttr > 0) rAttr - 1 else currentRowIdx
                            currentColIdx = 0
                        }
                        "c" -> {
                            currentCellRef = getAttrValue(parser, "r")
                            currentCellType = getAttrValue(parser, "t")
                            if (currentCellRef.isNotEmpty()) {
                                val (r, c) = parseCellRef(currentCellRef, currentRowIdx, currentColIdx)
                                currentRowIdx = r
                                currentColIdx = c
                            }
                            valueBuilder.clear()
                            formulaBuilder.clear()
                        }
                        "v" -> inValue = true
                        "t" -> inInlineText = true
                        "f" -> inFormula = true
                    }
                }
                XmlPullParser.TEXT -> {
                    val txt = parser.text.orEmpty()
                    when {
                        inValue || inInlineText -> valueBuilder.append(txt)
                        inFormula -> formulaBuilder.append(txt)
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (localName) {
                        "v" -> inValue = false
                        "t" -> inInlineText = false
                        "f" -> inFormula = false
                        "c" -> {
                            val rawVal = valueBuilder.toString()
                            val displayVal = when (currentCellType) {
                                "s" -> {
                                    val sIdx = rawVal.trim().toIntOrNull() ?: -1
                                    sharedStrings.getOrNull(sIdx) ?: rawVal
                                }
                                "b" -> if (rawVal == "1" || rawVal.equals("true", true)) "TRUE" else "FALSE"
                                else -> rawVal
                            }
                            val ref = currentCellRef.ifEmpty {
                                "${columnIndexToName(currentColIdx)}${currentRowIdx + 1}"
                            }
                            val cell = XlsxCell(
                                reference = ref,
                                row = currentRowIdx,
                                col = currentColIdx,
                                value = displayVal,
                                formula = formulaBuilder.toString().takeIf { it.isNotEmpty() }
                            )
                            cellMap[currentRowIdx to currentColIdx] = cell
                            if (currentRowIdx + 1 > maxRow) maxRow = currentRowIdx + 1
                            if (currentColIdx + 1 > maxCol) maxCol = currentColIdx + 1
                            currentColIdx++
                        }
                        "row" -> {
                            currentRowIdx++
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        val totalRows = maxRow.coerceIn(1, 300)
        val totalCols = maxCol.coerceIn(1, 30)
        val rows = (0 until totalRows).map { r ->
            (0 until totalCols).map { c ->
                cellMap[r to c] ?: XlsxCell(
                    reference = "${columnIndexToName(c)}${r + 1}",
                    row = r,
                    col = c,
                    value = ""
                )
            }
        }

        return XlsxSheet(
            name = sheetName,
            rowCount = totalRows,
            colCount = totalCols,
            rows = rows
        )
    }

    private fun parseCellRef(ref: String, defaultRow: Int, defaultCol: Int): Pair<Int, Int> {
        val letters = ref.takeWhile { it.isLetter() }.uppercase(Locale.ROOT)
        val digits = ref.dropWhile { it.isLetter() }
        val col = if (letters.isNotEmpty()) {
            var acc = 0
            for (ch in letters) {
                acc = acc * 26 + (ch - 'A' + 1)
            }
            (acc - 1).coerceAtLeast(0)
        } else defaultCol
        val row = (digits.toIntOrNull()?.minus(1))?.coerceAtLeast(0) ?: defaultRow
        return row to col
    }

    fun columnIndexToName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A'.code + rem).toChar())
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun getAttrValue(parser: XmlPullParser, localAttrName: String): String {
        for (i in 0 until parser.attributeCount) {
            val attrName = parser.getAttributeName(i).orEmpty()
            if (attrName == localAttrName || attrName.endsWith(":$localAttrName")) {
                return parser.getAttributeValue(i).orEmpty()
            }
        }
        return ""
    }

    private fun readZipEntries(inputStream: InputStream): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(inputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            val buffer = ByteArray(8192)
            while (entry != null) {
                if (!entry.isDirectory) {
                    val baos = ByteArrayOutputStream()
                    var read: Int
                    while (zis.read(buffer).also { read = it } != -1) {
                        baos.write(buffer, 0, read)
                    }
                    result[entry.name] = baos.toByteArray()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return result
    }

    /**
     * Generates a valid, standards-compliant OpenXML .docx file on disk for instant previewing/testing.
     */
    fun writeSampleDocxFile(file: File) {
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""".toByteArray(Charsets.UTF_8)
            )
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    <w:p>
      <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
      <w:r><w:rPr><w:b/></w:rPr><w:t>2026 企业项目技术方案与架构规划 (DOCX)</w:t></w:r>
    </w:p>
    <w:p>
      <w:r><w:t>本文档由 Material Files 内置 Office OpenXML 引擎直接解析渲染，无需安装外部办公套件即可查看标题层级、段落样式与嵌入式数据表格。</w:t></w:r>
    </w:p>
    <w:p>
      <w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
      <w:r><w:t>一、核心模块能力清单</w:t></w:r>
    </w:p>
    <w:p>
      <w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>
      <w:r><w:rPr><w:b/></w:rPr><w:t>Office 文档直读：</w:t></w:r>
      <w:r><w:t>原生支持 .docx Word 文档、.xlsx Excel 多工作表电子表格与 .txt 文本预览。</w:t></w:r>
    </w:p>
    <w:p>
      <w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>
      <w:r><w:rPr><w:b/></w:rPr><w:t>Markdown 实时渲染：</w:t></w:r>
      <w:r><w:t>支持源码与所见即所得实时分屏渲染及一键保存。</w:t></w:r>
    </w:p>
    <w:p>
      <w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>
      <w:r><w:rPr><w:b/></w:rPr><w:t>增强型压缩与解压：</w:t></w:r>
      <w:r><w:t>支持免解压内部文件预览、分卷压缩（Part1/Part2）、AES-256 密码加密与三级压缩率调节。</w:t></w:r>
    </w:p>
    <w:p>
      <w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
      <w:r><w:t>二、里程碑排期与资源矩阵表</w:t></w:r>
    </w:p>
    <w:tbl>
      <w:tr>
        <w:tc><w:p><w:r><w:t>阶段模块</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>关键特性</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>性能指标</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>验收状态</w:t></w:r></w:p></w:tc>
      </w:tr>
      <w:tr>
        <w:tc><w:p><w:r><w:t>文档引擎</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>DOCX / XLSX / Markdown</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>首屏解析 &lt; 80ms</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>已完成</w:t></w:r></w:p></w:tc>
      </w:tr>
      <w:tr>
        <w:tc><w:p><w:r><w:t>压缩引擎</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>分卷压缩 / 加密 / 压缩等级</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>支持自定义分卷大小</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>已完成</w:t></w:r></w:p></w:tc>
      </w:tr>
      <w:tr>
        <w:tc><w:p><w:r><w:t>阅读与影音</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>记忆缓存阅读器 / SRT&amp;ASS字幕</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>毫秒级字幕同步</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>已完成</w:t></w:r></w:p></w:tc>
      </w:tr>
    </w:tbl>
  </w:body>
</w:document>""".toByteArray(Charsets.UTF_8)
            )
            zos.closeEntry()
        }
    }

    /**
     * Generates a valid, standards-compliant OpenXML .xlsx file with 2 worksheets on disk.
     */
    fun writeSampleXlsxFile(file: File) {
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("xl/workbook.xml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheets>
    <sheet name="季度财务报表" sheetId="1" r:id="rId1" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/>
    <sheet name="存储节点监控" sheetId="2" r:id="rId2" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/>
  </sheets>
</workbook>""".toByteArray(Charsets.UTF_8)
            )
            zos.closeEntry()

            val shared = listOf(
                "业务线名称", "Q1 营收(万)", "Q2 营收(万)", "Q3 营收(万)", "Q4 营收(万)", "年度同比增长",
                "智能终端云盘", "1,280.5", "1,490.0", "1,725.8", "2,110.4", "+34.2%",
                "企业文档引擎", "860.0", "945.2", "1,120.0", "1,380.6", "+28.7%",
                "多媒体播放器", "540.8", "612.5", "730.0", "895.0", "+41.5%",
                "合计 / 均值", "2,681.3", "3,047.7", "3,575.8", "4,386.0", "+34.8%",
                "节点区域", "总容量(TB)", "已用空间(TB)", "可用空间(TB)", "健康状态",
                "亚太主集群-01", "512.0", "348.5", "163.5", "正常 (99.99%)",
                "欧洲备集群-02", "256.0", "142.0", "114.0", "正常 (99.98%)"
            )
            zos.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            val sstXml = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
                shared.forEach { s ->
                    append("<si><t>$s</t></si>")
                }
                append("</sst>")
            }
            zos.write(sstXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // Sheet 1
            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            val sheet1Xml = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                for (r in 0 until 5) {
                    append("""<row r="${r + 1}">""")
                    for (c in 0 until 6) {
                        val ref = "${columnIndexToName(c)}${r + 1}"
                        val sIdx = r * 6 + c
                        val formulaTag = if (r == 4 && c in 1..4) "<f>SUM(${columnIndexToName(c)}2:${columnIndexToName(c)}4)</f>" else ""
                        append("""<c r="$ref" t="s">$formulaTag<v>$sIdx</v></c>""")
                    }
                    append("</row>")
                }
                append("</sheetData></worksheet>")
            }
            zos.write(sheet1Xml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // Sheet 2
            zos.putNextEntry(ZipEntry("xl/worksheets/sheet2.xml"))
            val sheet2Xml = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                for (r in 0 until 3) {
                    append("""<row r="${r + 1}">""")
                    for (c in 0 until 5) {
                        val ref = "${columnIndexToName(c)}${r + 1}"
                        val sIdx = 30 + r * 5 + c
                        append("""<c r="$ref" t="s"><v>$sIdx</v></c>""")
                    }
                    append("</row>")
                }
                append("</sheetData></worksheet>")
            }
            zos.write(sheet2Xml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }
}
