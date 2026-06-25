package com.remophoto.ui.categories

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remophoto.RemoPhotoApp
import com.remophoto.data.local.entity.CategoryEntity
import com.remophoto.domain.usecase.CategoryManager
import com.remophoto.util.AppLogger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 分类管理 ViewModel
 */
class CategoryViewModel(application: Application) : AndroidViewModel(application) {

    private val categoryManager: CategoryManager =
        (application as RemoPhotoApp).dependencyContainer.categoryManager

    private val _categories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    val categories: StateFlow<List<CategoryEntity>> = _categories.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadCategories()
    }

    fun loadCategories() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                categoryManager.getAllCategories().collect { list ->
                    _categories.value = list
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "加载分类失败", e)
                _isLoading.value = false
            }
        }
    }

    fun createCategory(name: String, color: Int) {
        viewModelScope.launch {
            try {
                categoryManager.createCategory(name, color)
                AppLogger.i(TAG, "分类已创建: $name")
            } catch (e: Exception) {
                AppLogger.e(TAG, "创建分类失败", e)
            }
        }
    }

    fun deleteCategory(categoryId: Long) {
        viewModelScope.launch {
            try {
                categoryManager.deleteCategory(categoryId)
                AppLogger.i(TAG, "分类已删除: $categoryId")
            } catch (e: Exception) {
                AppLogger.e(TAG, "删除分类失败", e)
            }
        }
    }

    fun updateCategory(category: CategoryEntity) {
        viewModelScope.launch {
            try {
                categoryManager.updateCategory(category)
            } catch (e: Exception) {
                AppLogger.e(TAG, "更新分类失败", e)
            }
        }
    }

    companion object {
        private const val TAG = "CategoryVM"
    }
}
