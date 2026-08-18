package com.xayah.core.datastore

import android.util.Log
import androidx.datastore.preferences.core.intPreferencesKey
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
val KeyResticCompressionLevel = intPreferencesKey("restic_compression_level_int")

// S3 Restic 配置键
val KeyS3ResticRepoPath = stringPreferencesKey("s3_restic_repo_path")
val KeyS3ResticPassword = stringPreferencesKey("s3_restic_password")
val KeyS3ResticInitialized = booleanPreferencesKey("s3_restic_initialized")

// FTP Restic 配置键
val KeyFtpResticRepoPath = stringPreferencesKey("ftp_restic_repo_path")
val KeyFtpResticPassword = stringPreferencesKey("ftp_restic_password")
val KeyFtpResticInitialized = booleanPreferencesKey("ftp_restic_initialized")

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
// 数值语义：-1 = auto（不设 set_compression → rustic v2 默认压缩）；0 = 关闭压缩；1..22 = 指定 zstd 级别
fun Context.readResticCompressionLevel(): Flow<Int> {
    return dataStore.data.map { preferences ->
        val level = preferences[KeyResticCompressionLevel] ?: -1
        Log.i("ResticCompression", "read compression level=$level")
        level
    }
}

suspend fun Context.saveResticCompressionLevel(level: Int) {
    Log.i("ResticCompression", "save compression level=$level")
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

// FTP Restic 配置扩展函数
suspend fun Context.saveFtpResticPassword(password: String) {
    dataStore.edit { settings ->
        settings[KeyFtpResticPassword] = password
    }
}

suspend fun Context.readFtpResticPassword(): String? {
    return dataStore.data.map { preferences ->
        preferences[KeyFtpResticPassword]
    }.first()
}

suspend fun Context.saveFtpResticInitialized(initialized: Boolean) {
    dataStore.edit { settings ->
        settings[KeyFtpResticInitialized] = initialized
    }
}

suspend fun Context.readFtpResticInitialized(): Boolean {
    return dataStore.data.map { preferences ->
        preferences[KeyFtpResticInitialized] ?: false
    }.first()
}

suspend fun Context.saveFtpResticRepoPath(path: String) {
    dataStore.edit { settings ->
        settings[KeyFtpResticRepoPath] = path
    }
}

suspend fun Context.readFtpResticRepoPath(): String? {
    return dataStore.data.map { preferences ->
        preferences[KeyFtpResticRepoPath]
    }.first()
}