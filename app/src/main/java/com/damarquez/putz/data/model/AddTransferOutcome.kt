package com.damarquez.putz.data.model

/** Result of trying to add a transfer, distinguishing a hard failure from a queued entry. */
sealed class AddTransferOutcome {
    data class Added(val merged: MergedTransfer) : AddTransferOutcome()
    /** put.io's active-transfer limit was hit; [entry] was flagged QUEUED_OUTSIDE_PUTIO in the
     *  shared transfer history so any device can later "Activate" it — nothing retries automatically. */
    data class Queued(val entry: HistoryFileEntry) : AddTransferOutcome()
    data class Failed(val message: String) : AddTransferOutcome()
}
