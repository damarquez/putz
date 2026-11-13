package com.damarquez.putz.util

import com.damarquez.putz.util.MetadataUtils.ParseIssue
import com.damarquez.putz.util.MetadataUtils.ParsedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The send-to-Calibre file-name parser, "re-derive title/author" and "fix title" buttons. */
class TitleTextParsingTest {
    private fun parse(fileName: String) = MetadataUtils.extractMetadata(fileName)

    // --- file-name parsing --------------------------------------------------------------------

    @Test
    fun `spaced dash is trusted without review`() {
        assertEquals(ParsedName("The Stand", "Stephen King"), parse("Stephen King - The Stand (epub).epub"))
        // A hyphen elsewhere doesn't matter once there's a real " - " separator.
        assertEquals(ParsedName("Spider-Man Tales", "Stan Lee"), parse("Stan Lee - Spider-Man Tales (epub).epub"))
    }

    @Test
    fun `format tag in parens is never taken as the author`() {
        // The recurring bug: "(epub)" ended up as the author whenever the dash had no spaces.
        assertEquals(ParsedName("The Stand", "Stephen King", ParseIssue.UNSPACED_DASH), parse("Stephen King-The Stand (epub).epub"))
        assertEquals(ParsedName("The Stand", "", ParseIssue.NO_AUTHOR), parse("The Stand (epub).epub"))
        assertEquals(ParsedName("The Stand", "", ParseIssue.NO_AUTHOR), parse("The Stand (retail) (EPUB).epub"))
    }

    @Test
    fun `dash with a space on one side splits but needs review`() {
        assertEquals(ParsedName("The Stand", "Stephen King", ParseIssue.UNSPACED_DASH), parse("Stephen King -The Stand (epub).epub"))
        assertEquals(ParsedName("The Stand", "Stephen King", ParseIssue.UNSPACED_DASH), parse("Stephen King- The Stand (epub).epub"))
        // en-dash without spaces is normalized to a plain hyphen first
        assertEquals(ParsedName("The Stand", "Stephen King", ParseIssue.UNSPACED_DASH), parse("Stephen King–The Stand.epub"))
    }

    @Test
    fun `several bare hyphens are too ambiguous to split`() {
        assertEquals(
            ParsedName("Jean-Paul Sartre-Nausea", "", ParseIssue.NO_AUTHOR),
            parse("Jean-Paul Sartre-Nausea (epub).epub"),
        )
        // ...but a one-sided dash still wins over hyphenated words.
        assertEquals(
            ParsedName("Nausea", "Jean-Paul Sartre", ParseIssue.UNSPACED_DASH),
            parse("Jean-Paul Sartre -Nausea (epub).epub"),
        )
    }

    @Test
    fun `by and parens authors need review`() {
        assertEquals(ParsedName("Some Title", "Someone", ParseIssue.BY_AUTHOR), parse("Some Title by Someone (epub).epub"))
        assertEquals(ParsedName("The Hobbit", "J.R.R. Tolkien", ParseIssue.PAREN_AUTHOR), parse("The Hobbit (J.R.R. Tolkien) (epub).epub"))
    }

    @Test
    fun `parens with digits are a series or edition, not an author`() {
        assertEquals(ParsedName("Dune (Book 1)", "", ParseIssue.NO_AUTHOR), parse("Dune (Book 1) (epub).epub"))
        assertEquals(ParsedName("Dune (1965)", "", ParseIssue.NO_AUTHOR), parse("Dune (1965).epub"))
    }

    @Test
    fun `kindle asin names may leave the author blank`() {
        val parsed = parse("B0GQ6VJ43X_EBOK.kfx-zip")
        assertNull(parsed.issue)
        assertEquals("", parsed.author)
        assertFalse(parsed.authorMissing(parsed.title, parsed.author))
        // ...until a real title is typed: then the author is needed too.
        assertTrue(parsed.authorMissing("Os imortais", ""))
    }

    @Test
    fun `flags clear once either field is edited`() {
        val parsed = parse("Stephen King-The Stand.epub")
        assertEquals(ParseIssue.UNSPACED_DASH, parsed.pendingReview(parsed.title, parsed.author))
        assertNull(parsed.pendingReview("The Stand", "Stephen  King"))
        assertNull(parsed.pendingReview("The Stand: Uncut", parsed.author))

        val noAuthor = parse("The Stand (epub).epub")
        assertTrue(noAuthor.authorMissing(noAuthor.title, ""))
        assertFalse(noAuthor.authorMissing(noAuthor.title, "Stephen King"))
        assertNull(noAuthor.pendingReview(noAuthor.title, "")) // reported via authorMissing instead
    }

    // --- re-derive from the Title field -------------------------------------------------------

    @Test
    fun `re-derive keeps periods that a file name parse would treat as an extension`() {
        assertEquals(ParsedName("The Hobbit", "Tolkien, J.R.R."), MetadataUtils.extractMetadataFromTitleText("Tolkien, J.R.R. - The Hobbit"))
        assertEquals(ParsedName("Dune Vol. 2", "Frank Herbert"), MetadataUtils.extractMetadataFromTitleText("Frank Herbert - Dune Vol. 2"))
    }

    @Test
    fun `re-derive applies the same rules as the file name parse`() {
        val fromFile = parse("Author Name – [Series 01] Some Title (retail).epub")
        assertEquals(fromFile, MetadataUtils.extractMetadataFromTitleText("Author Name – [Series 01] Some Title (retail)"))
        assertEquals(ParsedName("Series 01 - Some Title", "Author Name"), fromFile)
        assertEquals(
            ParsedName("The Stand", "Stephen King", ParseIssue.UNSPACED_DASH),
            MetadataUtils.extractMetadataFromTitleText("Stephen King -The Stand (epub)"),
        )
    }

    @Test
    fun `re-derive returns null when nothing splits, so fields stay untouched`() {
        assertNull(MetadataUtils.extractMetadataFromTitleText("The Hobbit"))
        assertNull(MetadataUtils.extractMetadataFromTitleText("Dune Vol. 2"))
        assertNull(MetadataUtils.extractMetadataFromTitleText("The Stand (epub)"))
    }

    // --- fix title ----------------------------------------------------------------------------

    @Test
    fun `fix title keeps deliberate casing`() {
        assertEquals("NASA and the WWII Program", MetadataUtils.fixTitleCapitalization("NASA and the WWII program"))
        assertEquals("Rocky II", MetadataUtils.fixTitleCapitalization("rocky II"))
        assertEquals("The McCarthy Era", MetadataUtils.fixTitleCapitalization("the McCarthy era"))
        assertEquals("My iPhone Book", MetadataUtils.fixTitleCapitalization("my iPhone book"))
        assertEquals("The O'Brien File", MetadataUtils.fixTitleCapitalization("the O'Brien file"))
    }

    @Test
    fun `fix title still re-cases ordinary words`() {
        assertEquals("The Lord of the Rings", MetadataUtils.fixTitleCapitalization("The Lord Of The Rings"))
        assertEquals("The Lord of the Rings", MetadataUtils.fixTitleCapitalization("the_lord_of_the_rings (epub)"))
        // A minor word right after a standalone "-" starts a clause, so it's capitalised.
        assertEquals("A Tale - Of Two Cities", MetadataUtils.fixTitleCapitalization("a tale - of two cities"))
    }

    @Test
    fun `fix title re-cases an all-caps title`() {
        assertEquals("The Hobbit", MetadataUtils.fixTitleCapitalization("THE HOBBIT"))
        assertEquals("War and Peace", MetadataUtils.fixTitleCapitalization("WAR AND PEACE"))
    }
}
