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
}
