package com.damarquez.putz.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local record for every put.io transfer. This is the app's management layer:
 * put.io is the transfer engine; we are the authoritative name/metadata source.
 */
@Entity(
    tableName = "app_transfers",
    indices = [Index(value = ["infoHash"], unique = true, name = "idx_info_hash")],
)
data class AppTransferEntity(
    /** put.io transfer ID — the join key between this table and the put.io API. */
    @PrimaryKey val putioId: Long,

    /** The name we display in the app. Starts as magnet dn= value; updated to put.io's
     *  resolved torrent name once the transfer begins downloading. */
    val displayName: String,

    /** Latest name reported by put.io (may differ from displayName while resolving). */
    val putioName: String,

    /** Original full magnet URI (null for transfers not added through this app). */
    val magnetLink: String?,

    /** Info-hash extracted from the magnet URI, used for duplicate detection. */
    val infoHash: String?,

    /** True if the user added this transfer through our app. */
    val addedByApp: Boolean,

    /** Epoch millis when this record was created in our DB. */
    val addedAt: Long,

    /** True once displayName has been promoted to put.io's resolved torrent name.
     *  Prevents re-applying the update if the name later changes again on put.io. */
    val nameResolved: Boolean,
)
