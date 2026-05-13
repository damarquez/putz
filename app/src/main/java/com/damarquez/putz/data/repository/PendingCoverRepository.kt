package com.damarquez.putz.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PendingCoverRepository @Inject constructor() {
    private val _uuidFlow = MutableStateFlow<String?>(null)
    val uuidFlow: StateFlow<String?> = _uuidFlow.asStateFlow()

    fun set(uuid: String) {
        _uuidFlow.value = uuid
    }
}
