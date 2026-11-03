package com.xayah.core.util

/**
 * `cmd user list -v` 输出的一行用户信息。
 *
 * 例如：`2: id=999, name=MultiApp, type=profile.CLONE, flags=...`
 */
data class UserTypeEntry(
    val id: Int,
    val name: String,
    val userType: String,
)

/**
 * 用户类型判定。
 *
 * **不能用 userId 判断分身类型**：Android 上分身用户的 id 由系统分配，
 * 999 只是 ColorOS/OPPO 系列常见的取值，并不保证。唯一可靠依据是 userType：
 * - 应用分身（MultiApp / 克隆应用）：`profile.CLONE`
 * - 系统分身：`full.SECONDARY`（ColorOS 的 system_clone 就是这一类）
 *
 * 两者的桌面行为完全不同——系统分身是独立空间、切进去自带桌面图标；
 * 应用分身的图标由系统「应用分身」服务维护，开关没开就不会出现在主桌面。
 * 因此所有判定必须按 type 走，绝不按 id 走。
 */
object UserTypeUtil {
    /** 应用分身（克隆 profile）的 userType */
    const val TYPE_PROFILE_CLONE = "profile.CLONE"

    private val ENTRY_REGEX = Regex("""id=(\d+),\s*name=(.*?),\s*type=([^,\s]+)""")

    /** 解析单行；格式不符返回 null（老系统不支持 -v 时上层静默降级） */
    fun parse(line: String): UserTypeEntry? {
        val match = ENTRY_REGEX.find(line) ?: return null
        val id = match.groupValues[1].toIntOrNull() ?: return null
        return UserTypeEntry(
            id = id,
            name = match.groupValues[2].trim(),
            userType = match.groupValues[3].trim(),
        )
    }

    /** 从 `cmd user list -v` 的整段输出里挑出应用分身用户 */
    fun appCloneUserIds(lines: List<String>): Set<Int> =
        lines.mapNotNull(::parse)
            .filter { it.userType == TYPE_PROFILE_CLONE }
            .map { it.id }
            .toSet()
}
