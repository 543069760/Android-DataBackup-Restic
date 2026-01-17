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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import android.content.Context
import javax.inject.Inject

val KeyCompressionType = stringPreferencesKey("compression_type")
val KeyAppsUpdateTime = longPreferencesKey("apps_update_time")
val KeyResticCompressionLevel = stringPreferencesKey("restic_compression_level")

// S3 Restic 配置键
val KeyS3ResticRepoPath = stringPreferencesKey("s3_restic_repo_path")
val KeyS3ResticPassword = stringPreferencesKey("s3_restic_password")
val KeyS3ResticInitialized = booleanPreferencesKey("s3_restic_initialized")

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

// S3 Restic 配置扩展函数
suspend fun Context.saveS3ResticPassword(password: String) {
    dataStore.edit { settings ->
        settings[KeyS3ResticPassword] = password
    }
}

suspend fun Context.readS3ResticPassword(): String? {
    return dataStore.data.map { preferences ->
        preferences[KeyS3ResticPassword]
    }.first()
}

suspend fun Context.saveS3ResticInitialized(initialized: Boolean) {
    dataStore.edit { settings ->
        settings[KeyS3ResticInitialized] = initialized
    }
}

suspend fun Context.readS3ResticInitialized(): Boolean {
    return dataStore.data.map { preferences ->
        preferences[KeyS3ResticInitialized] ?: false
    }.first()
}

suspend fun Context.saveS3ResticRepoPath(path: String) {
    dataStore.edit { settings ->
        settings[KeyS3ResticRepoPath] = path
    }
}

suspend fun Context.readS3ResticRepoPath(): String? {
    return dataStore.data.map { preferences ->
        preferences[KeyS3ResticRepoPath]
    }.first()
}