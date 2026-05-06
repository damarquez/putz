package com.damarquez.putz.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.damarquez.putz.settings.SettingsRepository
import com.damarquez.putz.ui.theme.AppCategory
import com.damarquez.putz.ui.theme.AppMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val appCategory = settingsRepository.appCategoryFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppCategory.NORMAL)

    val appMode = settingsRepository.appModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppMode.SYSTEM)

    fun setAppCategory(category: AppCategory) {
        viewModelScope.launch { settingsRepository.saveAppCategory(category) }
    }

    fun setAppMode(mode: AppMode) {
        viewModelScope.launch { settingsRepository.saveAppMode(mode) }
    }
}
