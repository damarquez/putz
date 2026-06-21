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

    /** True if this transfer was stopped (removed from put.io but kept in app). */
    val isStopped: Boolean = false,

    /** Matching history entry from the daemon, if available for this transfer's hash. */
    val historyEntry: HistoryFileEntry? = null,

    /** True if this is a locally-queued entry that hasn't been accepted by put.io yet
     *  (active-transfer limit reached). [transfer.id] is a negative sentinel in this case. */
    val isPendingLocal: Boolean = false,
)
