package com.xayah.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import com.xayah.core.model.CompressionType
import com.xayah.core.model.KillAppOption
import com.xayah.core.model.SelectionType
import com.xayah.core.model.ThemeType
import com.xayah.core.model.util.of
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first // <-- 修复 2：导入 first()

// -----------------------------------------Keys-----------------------------------------
val KeyBackupSavePath = stringPreferencesKey("backup_save_path")
val KeyAppVersionName = stringPreferencesKey("app_version_name")
val KeyCloudActivatedAccountName = stringPreferencesKey("cloud_activated_account_name")
val KeyLoadedIconMD5 = stringPreferencesKey("loaded_icon_md5")
val KeySelectionType = stringPreferencesKey("selection_type")
val KeyThemeType = stringPreferencesKey("theme_type")
val KeyCustomSUFile = stringPreferencesKey("custom_su_file")
val KeyKillAppOption = stringPreferencesKey("kill_app_option")
val KeyLanguage = stringPreferencesKey("language")
val KeyResticRepoPath = stringPreferencesKey("restic_repo_path")
val KeyResticPassword = stringPreferencesKey("restic_password")


// -----------------------------------------Read-----------------------------------------
fun Context.readCompressionType() = readStoreString(key = KeyCompressionType, defValue = "").map { CompressionType.of(it) }
fun Context.readAppVersionName() = readStoreString(key = KeyAppVersionName, defValue = "")
fun Context.readCloudActivatedAccountName() = readStoreString(key = KeyCloudActivatedAccountName, defValue = "")
fun Context.readLoadedIconMD5() = readStoreString(key = KeyLoadedIconMD5, defValue = "")
fun Context.readSelectionType() = readStoreString(key = KeySelectionType, defValue = "").map { SelectionType.of(it) }
fun Context.readThemeType() = readStoreString(key = KeyThemeType, defValue = "").map { ThemeType.of(it) }
fun Context.readKillAppOption() = readStoreString(key = KeyKillAppOption, defValue = "").map { KillAppOption.of(it) }
fun Context.readLanguage() = readStoreString(key = KeyLanguage, defValue = ConstantUtil.LANGUAGE_SYSTEM)

// 修复 1：将 defValue = null 改为 defValue = ""。
// .first() 后的 .takeIf { it.isNotEmpty() } 将 "" 转换回 null，从而实现 String? 的返回。
suspend fun Context.readResticRepoPath(): String? = readStoreString(key = KeyResticRepoPath, defValue = "").first().takeIf { it.isNotEmpty() }
suspend fun Context.readResticPassword(): String? = readStoreString(key = KeyResticPassword, defValue = "").first().takeIf { it.isNotEmpty() }

/**
 * The final path for saving the backup.
 */
fun Context.readBackupSavePathSaved() = readStoreString(key = KeyBackupSavePath, defValue = "").map { it.isNotEmpty() }
fun Context.readBackupSavePath() = readStoreString(key = KeyBackupSavePath, defValue = ConstantUtil.DEFAULT_PATH)
fun Context.readCustomSUFile() = readStoreString(key = KeyCustomSUFile, defValue = "su")


// -----------------------------------------Write-----------------------------------------
suspend fun Context.saveCompressionType(value: CompressionType) = saveStoreString(key = KeyCompressionType, value = value.type.trim())
suspend fun Context.saveAppVersionName() = saveStoreString(key = KeyAppVersionName, value = getCurrentAppVersionName())
suspend fun Context.saveCloudActivatedAccountName(value: String) = saveStoreString(key = KeyCloudActivatedAccountName, value = value.trim())
suspend fun Context.saveLoadedIconMD5(value: String) = saveStoreString(key = KeyLoadedIconMD5, value = value.trim())
suspend fun Context.saveSelectionType(value: SelectionType) = saveStoreString(key = KeySelectionType, value = value.name.trim())
suspend fun Context.saveThemeType(value: ThemeType) = saveStoreString(key = KeyThemeType, value = value.name.trim())
suspend fun Context.saveBackupSavePath(value: String) = saveStoreString(key = KeyBackupSavePath, value = value.trim())
suspend fun Context.saveCustomSUFile(value: String) = saveStoreString(key = KeyCustomSUFile, value = value.trim())
suspend fun Context.saveKillAppOption(value: KillAppOption) = saveStoreString(key = KeyKillAppOption, value = value.name.trim())
suspend fun Context.saveLanguage(value: String) = saveStoreString(key = KeyLanguage, value = value.trim())
suspend fun Context.saveResticRepoPath(value: String) = saveStoreString(key = KeyResticRepoPath, value = value)
suspend fun Context.saveResticPassword(value: String) = saveStoreString(key = KeyResticPassword, value = value)