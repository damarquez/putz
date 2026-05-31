package com.damarquez.putz.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PendingSetPageCount(val uuid: String, val pageCount: Int)

@Singleton
class PendingSetPageCountRepository @Inject constructor() {
    private val _flow = MutableStateFlow<PendingSetPageCount?>(null)
    val flow: StateFlow<PendingSetPageCount?> = _flow.asStateFlow()

    fun set(uuid: String, pageCount: Int) {
        _flow.value = PendingSetPageCount(uuid, pageCount)
    }

    fun clear() {
        _flow.value = null
    }
}
