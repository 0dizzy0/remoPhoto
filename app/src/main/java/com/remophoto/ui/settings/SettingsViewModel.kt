package com.remophoto.ui.settings

import androidx.lifecycle.ViewModel
import com.remophoto.domain.model.SortOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置 ViewModel（骨架 — Phase 2 完善）
 */
class SettingsViewModel : ViewModel() {

    private val _defaultSortOrder = MutableStateFlow(SortOrder.DEFAULT)
    val defaultSortOrder: StateFlow<SortOrder> = _defaultSortOrder.asStateFlow()

    private val _albumsPerPage = MutableStateFlow(20)
    val albumsPerPage: StateFlow<Int> = _albumsPerPage.asStateFlow()

    private val _themeMode = MutableStateFlow("system")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()
}
