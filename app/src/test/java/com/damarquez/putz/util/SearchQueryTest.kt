package com.damarquez.putz.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {

    @Test
    fun plainSubstringMatch() {
        val m = SearchQuery.compile("man")
        assertTrue(m("Superman"))
        assertTrue(m("A Man Called Otto"))
    }

    @Test
    fun quotedWholeWordMatch() {
        val m = SearchQuery.compile("\"man\"")
        assertFalse(m("Superman"))
        assertTrue(m("A Man Called Otto"))
    }

    @Test
    fun curlySmartQuotesTreatedAsWholeWordQuotes() {
        // Gboard and other IMEs auto-punctuate a typed straight quote into a curly "smart quote"
        // by default; this must behave identically to the straight-quote case above.
        val m = SearchQuery.compile("“man”")
        assertFalse(m("Superman"))
        assertTrue(m("A Man Called Otto"))
        assertTrue(SearchQuery.isBooleanQuery("“man”"))
    }

    @Test
    fun notOperator() {
        val m = SearchQuery.compile("not superman")
        assertFalse(m("Superman Returns"))
        assertTrue(m("Batman Begins"))
    }

    @Test
    fun andOrPrecedenceWithParens() {
        // (man AND NOT superman) OR woman
        val m = SearchQuery.compile("(man AND NOT superman) or woman")
        assertTrue(m("A Man Called Otto"))   // man, not superman
        assertFalse(m("Superman Returns"))   // man AND NOT superman fails; no "woman"
        assertTrue(m("Wonder Woman"))         // matches via OR woman
        assertTrue(m("Batman Begins"))        // "Batman" contains "man" substring, not "superman"
        assertFalse(m("Just Some Movie"))     // no "man" and no "woman"
    }

    @Test
    fun implicitAndBetweenBareTerms() {
        val m = SearchQuery.compile("foo bar")
        assertTrue(m("foobar file"))
        assertFalse(m("foo only"))
    }

    @Test
    fun bracketSearch() {
        // The motivating bug report: searching for files WITHOUT square brackets.
        val notBracket = SearchQuery.compile("not ]")
        assertFalse(notBracket("Book [2020].epub"))
        assertTrue(notBracket("Plain Title.epub"))

        // Quoted whole-word search for a punctuation-only term degrades to plain substring
        // matching (word-boundary is meaningless for a bracket), so it must behave the same way.
        val notQuotedBracket = SearchQuery.compile("not \"]\"")
        assertFalse(notQuotedBracket("Book [2020].epub"))
        assertTrue(notQuotedBracket("Plain Title.epub"))
    }

    @Test
    fun malformedQueryFallsBackToLiteralSubstring() {
        val m = SearchQuery.compile("foo AND")
        assertTrue(m("something foo AND else"))
        assertFalse(m("foo only"))
    }

    @Test
    fun wildcardStarMatchesWholeName() {
        val m = SearchQuery.compile("2025*.epub")
        assertTrue(m("2025 Annual Report.epub"))
        assertTrue(m("2025.epub"))
        assertFalse(m("My 2025 Book.epub")) // doesn't START with 2025
        assertFalse(m("2025 Annual Report.pdf")) // doesn't end with .epub
        assertTrue(SearchQuery.isBooleanQuery("2025*.epub"))
    }

    @Test
    fun wildcardLiteralDotIsNotAnyCharacter() {
        val m = SearchQuery.compile("test.*")
        assertTrue(m("test.epub"))
        assertTrue(m("test."))
        assertFalse(m("testXepub")) // the dot in the pattern must match a literal dot
    }

    @Test
    fun wildcardQuestionMarkMatchesExactlyOneCharacter() {
        val m = SearchQuery.compile("book?.epub")
        assertTrue(m("book1.epub"))
        assertFalse(m("book.epub"))     // needs exactly one character there
        assertFalse(m("book12.epub"))   // too many characters
    }

    @Test
    fun wildcardCombinesWithBooleanOperators() {
        val m = SearchQuery.compile("2025*.epub OR 2026*.epub")
        assertTrue(m("2025 Report.epub"))
        assertTrue(m("2026 Report.epub"))
        assertFalse(m("2027 Report.epub"))
    }

    @Test
    fun wildcardOverridesQuotingAndIsCaseInsensitive() {
        val m = SearchQuery.compile("\"2025*.EPUB\"")
        assertTrue(m("2025 report.epub"))
    }
}
