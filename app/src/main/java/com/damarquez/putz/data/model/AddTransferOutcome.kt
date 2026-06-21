package com.damarquez.putz.data.model

/** Result of trying to add a transfer, distinguishing a hard failure from a local queue. */
sealed class AddTransferOutcome {
    data class Added(val merged: MergedTransfer) : AddTransferOutcome()
    /** put.io's active-transfer limit was hit; the request is stored locally for automatic retry. */
    data object Queued : AddTransferOutcome()
    data class Failed(val message: String) : AddTransferOutcome()
}
