package com.damarquez.putz.util

import java.io.File

/** RTF groups that hold non-content data (fonts, colors, styles, embedded objects, field codes...). */
private val SKIP_DESTINATIONS = setOf(
    "fonttbl", "colortbl", "stylesheet", "info", "generator", "pict", "object",
    "footer", "footerl", "footerr", "footerf", "header", "headerl", "headerr", "headerf",
    "footnote", "comment", "themedata", "colorschememapping", "latentstyles",
    "rsidtbl", "datastore", "xmlnstbl", "listtable", "listoverridetable",
    "revtbl", "panose", "fldinst", "nonshppict", "shppict", "bkmkstart", "bkmkend",
    "docvar",
)

/**
 * Minimal RTF-to-plain-text converter: strips control words/groups and keeps the readable text.
 * No formatting (bold/italic/tables/images) — this is a "what's in this document" preview, not
 * a renderer. Font/color/style tables, field codes, and embedded objects are skipped entirely
 * rather than leaking their control-word noise into the output.
 */
object RtfExtractor {

    fun extractText(rtfFile: File): String {
        // RTF control syntax is 7-bit ASCII; decode as Latin-1 so every byte maps 1:1 to a char,
        // then reinterpret escaped bytes/codepoints explicitly below.
        return parse(rtfFile.readText(Charsets.ISO_8859_1))
    }

    private fun parse(rtf: String): String {
        val out = StringBuilder()
        val n = rtf.length
        var i = 0
        val skipStack = ArrayDeque<Boolean>()
        var skipping = false
        var uc = 1
        val ucStack = ArrayDeque<Int>()
        var pendingSkip = 0

        fun consumePending(): Boolean {
            if (pendingSkip > 0) {
                pendingSkip--
                return true
            }
            return false
        }

        var depth = 0
        while (i < n) {
            when (val c = rtf[i]) {
                '{' -> {
                    skipStack.addLast(skipping)
                    ucStack.addLast(uc)
                    depth++
                    i++
                }
                '}' -> {
                    skipping = skipStack.removeLastOrNull() ?: false
                    uc = ucStack.removeLastOrNull() ?: 1
                    depth--
                    i++
                }
                '\\' -> {
                    i++
                    if (i >= n) break
                    val ctl = rtf[i]
                    when {
                        ctl == '\\' || ctl == '{' || ctl == '}' -> {
                            i++
                            if (!skipping && !consumePending()) out.append(ctl)
                        }
                        ctl == '\'' -> {
                            i++
                            if (i + 1 < n) {
                                val value = rtf.substring(i, i + 2).toIntOrNull(16)
                                i += 2
                                if (value != null && !skipping && !consumePending()) out.append(value.toChar())
                            }
                        }
                        ctl == '\n' || ctl == '\r' -> i++
                        ctl.isLetter() -> {
                            val start = i
                            while (i < n && rtf[i].isLetter()) i++
                            val word = rtf.substring(start, i)
                            var negative = false
                            if (i < n && rtf[i] == '-') { negative = true; i++ }
                            val digitsStart = i
                            while (i < n && rtf[i].isDigit()) i++
                            val param = if (i > digitsStart) {
                                rtf.substring(digitsStart, i).toIntOrNull()?.let { if (negative) -it else it }
                            } else null
                            if (i < n && rtf[i] == ' ') i++

                            when (word) {
                                "par", "line" -> if (!skipping) out.append('\n')
                                "tab" -> if (!skipping) out.append('\t')
                                "uc" -> if (param != null) uc = param
                                "u" -> if (param != null) {
                                    val codePoint = if (param < 0) param + 65536 else param
                                    if (!skipping) out.appendCodePoint(codePoint)
                                    pendingSkip = uc
                                }
                                in SKIP_DESTINATIONS -> skipping = true
                                else -> {}
                            }
                        }
                        else -> {
                            // control symbol (\~ \- \_ \* ...) — only \~ (non-breaking space) carries visible content
                            if (ctl == '~' && !skipping && !consumePending()) out.append(' ')
                            i++
                        }
                    }
                }
                else -> {
                    if (depth > 0 && !skipping && !consumePending()) out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
