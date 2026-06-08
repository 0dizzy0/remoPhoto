package com.remophoto.ui.viewer

import androidx.lifecycle.ViewModel
import com.remophoto.domain.model.ImageItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全屏浏览 ViewModel（骨架 — Phase 1 完善）
 */
class FullScreenViewModel : ViewModel() {

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _images = MutableStateFlow<List<ImageItem>>(emptyList())
    val images: StateFlow<List<ImageItem>> = _images.asStateFlow()

    private val _isUiVisible = MutableStateFlow(true)
    val isUiVisible: StateFlow<Boolean> = _isUiVisible.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    fun nextImage() {
        val list = _images.value
        if (list.isNotEmpty()) {
            _currentIndex.value = (_currentIndex.value + 1) % list.size
        }
    }

    fun previousImage() {
        val list = _images.value
        if (list.isNotEmpty()) {
            _currentIndex.value =
                (_currentIndex.value - 1 + list.size) % list.size
        }
    }

    fun toggleUiVisibility() {
        _isUiVisible.value = !_isUiVisible.value
    }
}
