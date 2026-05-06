package com.damarquez.putz.oauth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Bridges magnet: intents received by MainActivity into the TransfersViewModel. */
@Singleton
class PendingMagnetRepository @Inject constructor() {
    private val _pending = MutableStateFlow<String?>(null)
    val pending = _pending.asStateFlow()

    fun set(magnetLink: String) { _pending.value = magnetLink }
    fun clear() { _pending.value = null }
}
