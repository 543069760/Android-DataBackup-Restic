package com.xayah.core.rootservice.parcelables

import android.os.Parcel

/**
 * 已安装应用快照：root 守护进程一次性采集、经 ParcelFileDescriptor 回传的精简 DTO。
 *
 * 与 `getInstalledPackagesAsUser` 的区别：后者把完整
 * `android.content.pm.PackageInfo` 数组 writeTypedList 进 Parcel，字段多、体积大；
 * 这里只带初始化列表必需的字段，且一次往返覆盖所有用户。
 *
 * 该组类型不跨 AIDL 边界，仅作为 PFD 里的私有字节协议，因此不需要 Parcelable/aidl 声明。
 */
enum class InstalledAppsMode {
    /** 用 [added] 重建/对齐整张表。 */
    FULL,

    /** 只应用 [added] / [changed] / [removed]。 */
    DELTA,

    /** 与 app 侧 knownDigest 一致，Room 无需任何写入。 */
    UNCHANGED,
}

data class InstalledAppUser(
    val id: Int,
    val unlocked: Boolean,
)

data class InstalledAppEntry(
    val packageName: String,
    val userId: Int,
    val uid: Int,
    val versionName: String,
    val versionCode: Long,
    val flags: Int,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val sourceDir: String,
    val splitSourceDirs: List<String>,
    val enabled: Boolean,
)

data class InstalledAppKey(
    val packageName: String,
    val userId: Int,
)

data class InstalledAppsSnapshot(
    val mode: InstalledAppsMode,
    val serverDigest: String,
    /** FBE/门禁未通过：结果是「部分可见集合」，调用方不得据此落 digest。 */
    val partial: Boolean,
    val schema: Int,
    val users: List<InstalledAppUser>,
    val added: List<InstalledAppEntry>,
    val changed: List<InstalledAppEntry>,
    val removed: List<InstalledAppKey>,
)

/** PFD 内字节协议。schema 变更时必须同步 [SCHEMA]，root 侧缓存会自动失效走 FULL。 */
object InstalledAppsSnapshotCodec {
    const val SCHEMA = 1

    fun write(parcel: Parcel, snapshot: InstalledAppsSnapshot) {
        parcel.writeInt(snapshot.schema)
        parcel.writeInt(snapshot.mode.ordinal)
        parcel.writeString(snapshot.serverDigest)
        parcel.writeInt(if (snapshot.partial) 1 else 0)

        parcel.writeInt(snapshot.users.size)
        snapshot.users.forEach {
            parcel.writeInt(it.id)
            parcel.writeInt(if (it.unlocked) 1 else 0)
        }

        writeEntries(parcel, snapshot.added)
        writeEntries(parcel, snapshot.changed)

        parcel.writeInt(snapshot.removed.size)
        snapshot.removed.forEach {
            parcel.writeString(it.packageName)
            parcel.writeInt(it.userId)
        }
    }

    fun read(parcel: Parcel): InstalledAppsSnapshot {
        val schema = parcel.readInt()
        val mode = InstalledAppsMode.values().getOrElse(parcel.readInt()) { InstalledAppsMode.FULL }
        val serverDigest = parcel.readString() ?: ""
        val partial = parcel.readInt() == 1

        val users = List(parcel.readInt()) {
            InstalledAppUser(parcel.readInt(), parcel.readInt() == 1)
        }
        val added = readEntries(parcel)
        val changed = readEntries(parcel)
        val removed = List(parcel.readInt()) {
            InstalledAppKey(parcel.readString() ?: "", parcel.readInt())
        }
        return InstalledAppsSnapshot(mode, serverDigest, partial, schema, users, added, changed, removed)
    }

    private fun writeEntries(parcel: Parcel, entries: List<InstalledAppEntry>) {
        parcel.writeInt(entries.size)
        entries.forEach {
            parcel.writeString(it.packageName)
            parcel.writeInt(it.userId)
            parcel.writeInt(it.uid)
            parcel.writeString(it.versionName)
            parcel.writeLong(it.versionCode)
            parcel.writeInt(it.flags)
            parcel.writeLong(it.firstInstallTime)
            parcel.writeLong(it.lastUpdateTime)
            parcel.writeString(it.sourceDir)
            parcel.writeStringList(it.splitSourceDirs)
            parcel.writeInt(if (it.enabled) 1 else 0)
        }
    }

    private fun readEntries(parcel: Parcel): List<InstalledAppEntry> = List(parcel.readInt()) {
        val packageName = parcel.readString() ?: ""
        val userId = parcel.readInt()
        val uid = parcel.readInt()
        val versionName = parcel.readString() ?: ""
        val versionCode = parcel.readLong()
        val flags = parcel.readInt()
        val firstInstallTime = parcel.readLong()
        val lastUpdateTime = parcel.readLong()
        val sourceDir = parcel.readString() ?: ""
        val splitSourceDirs = parcel.createStringArrayList() ?: arrayListOf()
        val enabled = parcel.readInt() == 1
        InstalledAppEntry(
            packageName, userId, uid, versionName, versionCode, flags,
            firstInstallTime, lastUpdateTime, sourceDir, splitSourceDirs, enabled
        )
    }
}
