package com.xayah.feature.main.settings.restic

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xayah.core.datastore.readResticRepoPath
import com.xayah.core.datastore.readResticPassword
import com.xayah.core.datastore.saveResticRepoPath
import com.xayah.core.datastore.saveResticPassword
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

@HiltViewModel
class ResticViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResticUiState())
    val uiState: StateFlow<ResticUiState> = _uiState.asStateFlow()

    suspend fun getRepoPath(): String {
        // 从 DataStore 读取，如果为空则返回默认值
        return context.readResticRepoPath() ?: "/data/data/com.databackup/restic_repo"
    }

    suspend fun getPassword(): String {
        // 从 DataStore 读取，如果为空则返回默认值
        return context.readResticPassword() ?: ""
    }

    fun saveRepoPath(context: Context, path: String) {
        viewModelScope.launch {
            // 保存到 DataStore
            context.saveResticRepoPath(path)
        }
    }

    fun savePassword(context: Context, password: String) {
        viewModelScope.launch {
            // 保存到 DataStore
            context.saveResticPassword(password)
        }
    }
}

data class ResticUiState(
    val isLoading: Boolean = false
)