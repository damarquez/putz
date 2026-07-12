package com.damarquez.putz.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PendingConvertFormat(
    val uuid: String,
    val sourceFormat: String,
    val targetFormat: String,
    val title: String,
    val author: String,
)

@Singleton
class PendingConvertFormatRepository @Inject constructor() {
    private val _flow = MutableStateFlow<PendingConvertFormat?>(null)
    val flow: StateFlow<PendingConvertFormat?> = _flow.asStateFlow()

    fun set(uuid: String, sourceFormat: String, targetFormat: String, title: String, author: String) {
        _flow.value = PendingConvertFormat(uuid, sourceFormat, targetFormat, title, author)
    }

    fun clear() {
        _flow.value = null
    }
}
