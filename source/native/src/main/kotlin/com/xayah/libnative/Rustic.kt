package com.xayah.libnative

object Rustic {
    fun initPlatformVerifier(context: android.content.Context) = nativeInitPlatformVerifier(context)

    fun initLogger() = nativeInitLogger()

    fun getVersion(): String = nativeGetVersion()

    fun initRepository(
        repositoryPath: String,
        password: String,
        options: Map<String, String> = emptyMap(),
    ) {
        val (optionKeys, optionValues) = options.toKeyValueArrays()
        nativeInitRepository(repositoryPath, password, optionKeys, optionValues)
    }

    fun repositoryExists(
        repositoryPath: String,
        options: Map<String, String> = emptyMap(),
    ): Boolean {
        val (optionKeys, optionValues) = options.toKeyValueArrays()
        return nativeRepositoryExists(repositoryPath, optionKeys, optionValues)
    }

    fun validateRepository(
        repositoryPath: String,
        password: String,
        options: Map<String, String> = emptyMap(),
    ) {
        val (optionKeys, optionValues) = options.toKeyValueArrays()
        nativeValidateRepository(repositoryPath, password, optionKeys, optionValues)
    }

    fun createSnapshot(
        repositoryPath: String,
        password: String,
        sourcePaths: List<String>,
        tags: List<String> = emptyList(),
        options: Map<String, String> = emptyMap(),
        callback: Any? = null,
        cancelId: Long = 0L,
    ): String {
        val (optionKeys, optionValues) = options.toKeyValueArrays()
        return nativeCreateSnapshot(
            repositoryPath,
            password,
            optionKeys,
            optionValues,
            sourcePaths.toTypedArray(),
            tags.toTypedArray(),
            callback,
            cancelId,
        )
    }

    fun cancelBackup(cancelId: Long) {
        android.util.Log.i("RusticCancel", "kt Rustic.cancelBackup id=$cancelId (about to call native)")
        nativeCancelBackup(cancelId)
    }

    fun restoreSnapshot(
        repositoryPath: String,
        password: String,
        snapshotId: String,
        destinationPath: String,
        includeGlob: String = "",
        options: Map<String, String> = emptyMap(),
        callback: Any? = null,
    ) {
        val (optionKeys, optionValues) = options.toKeyValueArrays()
        nativeRestoreSnapshot(
            repositoryPath,
            password,
            optionKeys,
            optionValues,
            snapshotId,
            destinationPath,
            includeGlob,
            callback,
        )
    }

    fun checkRepository(
        repositoryPath: String,
        password: String,
        options: Map<String, String> = emptyMap(),
    ) {
        val (optionKeys, optionValues) = options.toKeyValueArrays()
        nativeCheckRepository(repositoryPath, password, optionKeys, optionValues)
    }

    fun forgetSnapshot(
        repositoryPath: String,
        password: String,
        snapshotId: String,
        options: Map<String, String> = emptyMap(),
    ) {
        val (optionKeys, optionValues) = options.toKeyValueArrays()
        nativeForgetSnapshot(repositoryPath, password, optionKeys, optionValues, snapshotId)
    }

    fun pruneRepository(
        repositoryPath: String,
        password: String,
        maxUnused: String,
        options: Map<String, String> = emptyMap(),
        instantDelete: Boolean = false,
    ) {
        val (optionKeys, optionValues) = options.toKeyValueArrays()
        nativePruneRepository(repositoryPath, password, optionKeys, optionValues, maxUnused, instantDelete)
    }

    fun listSnapshotsDb(
        repositoryPath: String,
        password: String,
        dbPath: String,
        options: Map<String, String> = emptyMap(),
    ) {
        val (optionKeys, optionValues) = options.toKeyValueArrays()
        nativeListSnapshotsDb(repositoryPath, password, optionKeys, optionValues, dbPath)
    }

    private external fun nativeInitPlatformVerifier(context: android.content.Context)

    // keys 与 values 来自同一个 entry 迭代，保证按位一一对应，
    // 与 jni_bridge.rs 的 string_arrays_to_map(zip) 配对方式一致
    private fun Map<String, String>.toKeyValueArrays(): Pair<Array<String>, Array<String>> {
        val entries = entries.toList()
        val keys = Array(entries.size) { entries[it].key }
        val values = Array(entries.size) { entries[it].value }
        return keys to values
    }

    private external fun nativeInitLogger()

    private external fun nativeGetVersion(): String

    private external fun nativeInitRepository(
        repositoryPath: String,
        password: String,
        optionKeys: Array<String>,
        optionValues: Array<String>,
    )

    private external fun nativeRepositoryExists(
        repositoryPath: String,
        optionKeys: Array<String>,
        optionValues: Array<String>,
    ): Boolean

    private external fun nativeValidateRepository(
        repositoryPath: String,
        password: String,
        optionKeys: Array<String>,
        optionValues: Array<String>,
    )

    private external fun nativeCreateSnapshot(
        repositoryPath: String,
        password: String,
        optionKeys: Array<String>,
        optionValues: Array<String>,
        sourcePaths: Array<String>,
        tags: Array<String>,
        callback: Any?,
        cancelId: Long,
    ): String

    private external fun nativeCancelBackup(cancelId: Long)

    private external fun nativeRestoreSnapshot(
        repositoryPath: String,
        password: String,
        optionKeys: Array<String>,
        optionValues: Array<String>,
        snapshotId: String,
        destinationPath: String,
        includeGlob: String,
        callback: Any?,
    )

    private external fun nativeCheckRepository(
        repositoryPath: String,
        password: String,
        optionKeys: Array<String>,
        optionValues: Array<String>,
    )

    private external fun nativeForgetSnapshot(
        repositoryPath: String,
        password: String,
        optionKeys: Array<String>,
        optionValues: Array<String>,
        snapshotId: String,
    )

    private external fun nativePruneRepository(
        repositoryPath: String,
        password: String,
        optionKeys: Array<String>,
        optionValues: Array<String>,
        maxUnused: String,
        instantDelete: Boolean,
    )

    private external fun nativeListSnapshotsDb(
        repositoryPath: String,
        password: String,
        optionKeys: Array<String>,
        optionValues: Array<String>,
        dbPath: String,
    )
}