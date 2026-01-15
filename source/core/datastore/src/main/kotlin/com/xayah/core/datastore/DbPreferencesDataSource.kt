package com.xayah.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.xayah.core.model.CompressionType
import com.xayah.core.model.DEFAULT_APPS_UPDATE_TIME
import com.xayah.core.model.DEFAULT_COMPRESSION_TYPE
import com.xayah.core.model.SettingsData
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import android.content.Context
import javax.inject.Inject

val KeyCompressionType = stringPreferencesKey("compression_type")
val KeyAppsUpdateTime = longPreferencesKey("apps_update_time")
val KeyResticCompressionLevel = stringPreferencesKey("restic_compression_level")
val KeyRclonePort = stringPreferencesKey("rclone_port")
val KeyRcloneServerEnabled = booleanPreferencesKey("rclone_server_enabled")

class DbPreferencesDataSource @Inject constructor(
    private val preferences: DataStore<Preferences>
) {
    val settingsData = preferences.data.map {
        SettingsData(
            compressionType = it[KeyCompressionType]?.let { v -> CompressionType.valueOf(v) }
                ?: DEFAULT_COMPRESSION_TYPE,
            appsUpdateTime = it[KeyAppsUpdateTime] ?: DEFAULT_APPS_UPDATE_TIME
        )
    }

    suspend fun <T> edit(key: Preferences.Key<T>, value: T) {
        preferences.edit { settings -> settings[key] = value }
    }
}

fun Context.readRclonePort(): Flow<String> {
    return dataStore.data.map { preferences ->
        preferences[KeyRclonePort] ?: "38080"  // 默认端口
    }
}

suspend fun Context.saveRclonePort(port: String) {
    dataStore.edit { settings ->
        settings[KeyRclonePort] = port
    }
}

// Rclone 服务开关扩展函数
fun Context.readRcloneServerEnabled(): Flow<Boolean> {
    return dataStore.data.map { preferences ->
        preferences[KeyRcloneServerEnabled] ?: false  // 默认关闭
    }
}

suspend fun Context.saveRcloneServerEnabled(enabled: Boolean) {
    dataStore.edit { settings ->
        settings[KeyRcloneServerEnabled] = enabled
    }
}

// Restic 压缩级别扩展函数
fun Context.readResticCompressionLevel(): Flow<String> {
    return dataStore.data.map { preferences ->
        preferences[KeyResticCompressionLevel] ?: "auto"
    }
}

suspend fun Context.saveResticCompressionLevel(level: String) {
    dataStore.edit { settings ->
        settings[KeyResticCompressionLevel] = level
    }
}