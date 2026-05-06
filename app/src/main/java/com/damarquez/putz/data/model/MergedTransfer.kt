package com.damarquez.putz.data.model

/**
 * Combines a live put.io transfer with our local management layer.
 * This is what the UI works with — never raw [PutioTransfer] in screens.
 */
data class MergedTransfer(
    /** Live status, progress, speed from the put.io API. */
    val transfer: PutioTransfer,

    /** The name we show in the app. Starts as the magnet dn= value; promoted
     *  to put.io's resolved torrent name once the download begins. */
    val appDisplayName: String,

    /** Original magnet URI stored when the user added this transfer, or null. */
    val magnetLink: String?,

    /** True if the user added this through our app (vs. added externally). */
    val addedByApp: Boolean,
)
