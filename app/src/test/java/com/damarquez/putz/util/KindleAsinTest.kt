package com.damarquez.putz.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Must agree with the daemon's _ASIN_STEM_RE / _title_is_asin_placeholder (sidekick tests/test_kfx_add.py). */
class KindleAsinTest {
    @Test
    fun `asin is found in kindle archive names`() {
        assertEquals("B0GQ6VJ43X", MetadataUtils.kindleAsin("B0GQ6VJ43X_EBOK.kfx-zip"))
        assertEquals("B0GQ6VJ43X", MetadataUtils.kindleAsin("B0GQ6VJ43X_EBOK.kfx-zip.sk_synced"))
        assertEquals("B0196U2OE4", MetadataUtils.kindleAsin("B0196U2OE4.kfx"))
        assertNull(MetadataUtils.kindleAsin("Os imortais - Paulliny Tort.kfx-zip"))
    }

    @Test
    fun `sample archives are recognised`() {
        assertTrue(MetadataUtils.isKindleSample("B0GQ6VJ43X_EBSP.kfx-zip"))
        assertTrue(MetadataUtils.isKindleSample("B0GQ6VJ43X_EBSP.kfx-zip.sk_synced"))
        assertFalse(MetadataUtils.isKindleSample("B0GQ6VJ43X_EBOK.kfx-zip"))
        assertFalse(MetadataUtils.isKindleSample("My_EBSP.epub"))
    }

    @Test
    fun `parsed file name title is a placeholder, a typed one is not`() {
        val (parsed, _) = MetadataUtils.extractMetadata("B0GQ6VJ43X_EBOK.kfx-zip")
        assertTrue(MetadataUtils.titleIsAsinPlaceholder(parsed, "B0GQ6VJ43X"))
        assertTrue(MetadataUtils.titleIsAsinPlaceholder(MetadataUtils.fixTitleCapitalization(parsed), "B0GQ6VJ43X"))
        assertFalse(MetadataUtils.titleIsAsinPlaceholder("Os imortais", "B0GQ6VJ43X"))
    }
}
