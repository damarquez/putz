package com.damarquez.putz.ui.transfers

import com.damarquez.putz.data.model.PutioTransfer
import org.junit.Assert.assertEquals
import org.junit.Test

/** put.io rounds percent_done to a whole number; the displayed value must never round up to 100. */
class PreciseProgressTest {
    private fun t(percent: Int, downloaded: Long, size: Long) =
        PutioTransfer(id = 1, name = "x", percentDone = percent, downloaded = downloaded, size = size)

    @Test
    fun `last tenth of a percent missing shows 99_9, not put_io's rounded 100`() {
        // IRC bookz 2024-n093-106: 7,434,329,296 B with the two Merlin files (7,350,152 B) missing.
        assertEquals(99.9f, preciseProgressPercent(t(100, 7_434_329_296 - 7_350_152, 7_434_329_296)), 0f)
    }

    @Test
    fun `rounds down`() {
        assertEquals(42.1f, preciseProgressPercent(t(42, 421_999, 1_000_000)), 0f)
        assertEquals(99.9f, preciseProgressPercent(t(100, 999_999, 1_000_000)), 0f)
    }

    @Test
    fun `100 only when put_io and the byte count agree`() {
        assertEquals(100f, preciseProgressPercent(t(100, 1_000_000, 1_000_000)), 0f)
        assertEquals(99.9f, preciseProgressPercent(t(99, 1_000_000, 1_000_000)), 0f) // over-counted bytes
    }

    @Test
    fun `falls back to put_io's value without byte counts`() {
        assertEquals(37f, preciseProgressPercent(t(37, 0, 1_000_000)), 0f)
        assertEquals(100f, preciseProgressPercent(t(100, 5, 0)), 0f)
    }
}
