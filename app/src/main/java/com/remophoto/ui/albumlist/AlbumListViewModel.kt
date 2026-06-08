package com.remophoto.ui.albumlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remophoto.domain.model.Album
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 相册列表 ViewModel（骨架 — Phase 1 完善）
 */
class AlbumListViewModel : ViewModel() {

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
}
