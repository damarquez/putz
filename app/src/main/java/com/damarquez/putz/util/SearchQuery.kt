package com.damarquez.putz.util

/**
 * Boolean search query support: AND / OR / NOT (case-insensitive keywords) and parentheses for
 * explicit precedence, e.g. `(man AND NOT superman) OR woman`. Standard precedence applies —
 * NOT binds tightest, then AND, then OR, each left-associative — and adjacent bare terms with
 * no operator between them are treated as an implicit AND (e.g. `foo bar` == `foo AND bar`).
 *
 * Unquoted terms match as a substring (`man` also matches inside `superman`); wrap a term in
 * "double quotes" to require a whole-word match instead (`"man"` needs a word boundary on both
 * sides, so it won't match inside `superman`).
 *
 * A term containing `*` (any run of characters, including none) or `?` (exactly one character)
 * is matched as a shell-style glob against the WHOLE name instead — `2025*.epub` matches a name
 * that starts with "2025" and ends with ".epub" with anything in between, not merely one that
 * contains "2025" and ".epub" as substrings somewhere. Every other character in a wildcard term
 * (including `.`) is matched literally, same as a real filename glob — `test.*` requires a
 * literal dot, it doesn't mean "any character here". This overrides quoting for that term: `*`/
 * `?` presence alone decides wildcard vs. substring/whole-word, regardless of quotes.
 *
 * Malformed input (unbalanced parens, a trailing operator, etc.) falls back to a plain
 * case-insensitive substring match on the raw query text, so a query still being typed never
 * throws or silently shows nothing.
 */
object SearchQuery {

    /** Compiles [query] into a reusable matcher against a file/folder display name. */
    fun compile(query: String): (String) -> Boolean {
        val trimmed = normalizeQuotes(query.trim())
        if (trimmed.isEmpty()) return { true }
        val node = try {
            val tokens = tokenize(trimmed)
            val (result, pos) = parseOr(tokens, 0)
            if (result == null || pos != tokens.size) null else result
        } catch (e: Exception) {
            null
        }
        return node?.let { n -> { name: String -> n.evaluate(name) } } ?: fallback(trimmed)
    }

    /** True if [query] uses any boolean syntax (quotes, parens, AND/OR/NOT keywords) or a glob
     * wildcard (`*`/`?`) — such queries can't be handed off to a remote text-search endpoint
     * (which has no notion of this app's boolean grammar or glob matching) and need local
     * evaluation instead. */
    fun isBooleanQuery(query: String): Boolean {
        val trimmed = normalizeQuotes(query.trim())
        if (trimmed.contains('"') || trimmed.contains('(') || trimmed.contains(')')) return true
        if (trimmed.contains('*') || trimmed.contains('?')) return true
        return tokenize(trimmed).any {
            it.equals("AND", ignoreCase = true) || it.equals("OR", ignoreCase = true) || it.equals("NOT", ignoreCase = true)
        }
    }

    private fun fallback(query: String): (String) -> Boolean {
        val needle = query.lowercase()
        return { name -> name.lowercase().contains(needle) }
    }

    // Gboard and other IMEs auto-punctuate a typed straight quote (") into a curly "smart quote"
    // (U+201C/U+201D) by default, and the TextField here doesn't disable that — so a user typing
    // "power" would otherwise silently end up with curly quotes the tokenizer below doesn't
    // recognize, falling back to plain (fuzzy, remote-search) matching with no visible error.
    private fun normalizeQuotes(query: String): String =
        query.replace('“', '"').replace('”', '"').replace('„', '"')

    private sealed class Node {
        abstract fun evaluate(name: String): Boolean
    }

    private class TermNode(term: String, private val wholeWord: Boolean) : Node() {
        private val needle = term.lowercase()

        // Regex \b is defined in terms of \w (letters/digits/underscore), so it's useless for a
        // punctuation-only needle like "]" — a bracket is essentially never followed by another
        // letter/digit, so \b]\b would almost never match real filenames. Instead: a side of the
        // match only needs a "boundary" when the needle's edge on that side is itself
        // alphanumeric (this is the classic word-boundary behavior for terms like "man" so it
        // won't match inside "superman"); a punctuation-edged needle has no such requirement, so
        // it degrades to a plain substring search, which is what "whole" should mean for it.
        override fun evaluate(name: String): Boolean {
            if (!wholeWord) return name.lowercase().contains(needle)
            if (needle.isEmpty()) return true
            val haystack = name.lowercase()
            var fromIndex = 0
            while (true) {
                val idx = haystack.indexOf(needle, fromIndex)
                if (idx == -1) return false
                val leftOk = !needle.first().isLetterOrDigit() || idx == 0 || !haystack[idx - 1].isLetterOrDigit()
                val afterIdx = idx + needle.length
                val rightOk = !needle.last().isLetterOrDigit() || afterIdx >= haystack.length || !haystack[afterIdx].isLetterOrDigit()
                if (leftOk && rightOk) return true
                fromIndex = idx + 1
            }
        }
    }

    // Full-name glob match (case-insensitive) — see the class doc's wildcard section. `*` -> any
    // run of characters (including empty), `?` -> exactly one character, everything else matched
    // literally (Regex.escape'd per character, so a literal `.`, `[`, `(`, etc. in a filename
    // never gets misread as regex syntax). Regex.matches() already anchors to the whole input,
    // so no explicit ^/$ is needed.
    private class WildcardTermNode(pattern: String) : Node() {
        private val regex = globToRegex(pattern)
        override fun evaluate(name: String): Boolean = regex.matches(name)
    }

    private fun hasWildcard(term: String): Boolean = term.any { it == '*' || it == '?' }

    private fun globToRegex(pattern: String): Regex {
        val sb = StringBuilder()
        for (c in pattern) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    private class AndNode(val left: Node, val right: Node) : Node() {
        override fun evaluate(name: String) = left.evaluate(name) && right.evaluate(name)
    }

    private class OrNode(val left: Node, val right: Node) : Node() {
        override fun evaluate(name: String) = left.evaluate(name) || right.evaluate(name)
    }

    private class NotNode(val inner: Node) : Node() {
        override fun evaluate(name: String) = !inner.evaluate(name)
    }

    // ── Tokenizer — words, "quoted phrases" (kept as one token, quotes included), and lone
    // '(' / ')' as their own tokens. ──────────────────────────────────────────────────────
    private fun tokenize(query: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                tokens.add(sb.toString())
                sb.clear()
            }
        }
        var i = 0
        while (i < query.length) {
            val c = query[i]
            when {
                c == '"' -> {
                    flush()
                    val end = query.indexOf('"', i + 1)
                    if (end == -1) {
                        tokens.add(query.substring(i))
                        i = query.length
                    } else {
                        tokens.add(query.substring(i, end + 1))
                        i = end + 1
                    }
                }
                c == '(' || c == ')' -> {
                    flush()
                    tokens.add(c.toString())
                    i++
                }
                c.isWhitespace() -> {
                    flush()
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        flush()
        return tokens
    }

    // ── Recursive-descent parser: OR < AND < NOT < atom, each Pair is (node, nextTokenIndex).
    // A null node signals a parse failure, which propagates up to compile()'s fallback. ──────

    private fun parseOr(tokens: List<String>, start: Int): Pair<Node?, Int> {
        val (first, firstPos) = parseAnd(tokens, start)
        if (first == null) return null to firstPos
        var node: Node = first
        var pos = firstPos
        while (pos < tokens.size && tokens[pos].equals("OR", ignoreCase = true)) {
            val (right, next) = parseAnd(tokens, pos + 1)
            if (right == null) return null to pos
            node = OrNode(node, right)
            pos = next
        }
        return node to pos
    }

    private fun parseAnd(tokens: List<String>, start: Int): Pair<Node?, Int> {
        val (first, firstPos) = parseUnary(tokens, start)
        if (first == null) return null to firstPos
        var node: Node = first
        var pos = firstPos
        while (pos < tokens.size && canStartUnary(tokens[pos])) {
            val consumedAnd = tokens[pos].equals("AND", ignoreCase = true)
            val (right, next) = parseUnary(tokens, if (consumedAnd) pos + 1 else pos)
            if (right == null) return null to pos
            node = AndNode(node, right)
            pos = next
        }
        return node to pos
    }

    private fun parseUnary(tokens: List<String>, start: Int): Pair<Node?, Int> {
        if (start >= tokens.size) return null to start
        if (tokens[start].equals("NOT", ignoreCase = true)) {
            val (inner, next) = parseUnary(tokens, start + 1)
            if (inner == null) return null to start
            return NotNode(inner) to next
        }
        return parseAtom(tokens, start)
    }

    private fun parseAtom(tokens: List<String>, start: Int): Pair<Node?, Int> {
        if (start >= tokens.size) return null to start
        val token = tokens[start]
        if (token == "(") {
            val (inner, next) = parseOr(tokens, start + 1)
            if (inner == null || next >= tokens.size || tokens[next] != ")") return null to start
            return inner to next + 1
        }
        if (token == ")" || token.equals("AND", ignoreCase = true) || token.equals("OR", ignoreCase = true)) {
            return null to start
        }
        val quoted = token.length >= 2 && token.startsWith("\"") && token.endsWith("\"")
        val value = if (quoted) token.substring(1, token.length - 1) else token
        if (value.isEmpty()) return null to start
        // Wildcard presence wins over quoting — see the class doc's wildcard section for why a
        // glob term always matches the whole name regardless of whether it was quoted.
        if (hasWildcard(value)) return WildcardTermNode(value) to start + 1
        return TermNode(value, wholeWord = quoted) to start + 1
    }

    /** Whether [token] can begin another AND-level operand — i.e. it's not a closing paren or
     * an OR, which belong to an outer precedence level. AND itself passes: the caller detects
     * and consumes it explicitly, while any other token here becomes an implicit AND operand. */
    private fun canStartUnary(token: String): Boolean =
        token != ")" && !token.equals("OR", ignoreCase = true)
}
