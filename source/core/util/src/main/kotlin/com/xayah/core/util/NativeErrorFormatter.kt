package com.xayah.core.util

import android.content.Context
import com.xayah.core.common.R
import org.json.JSONObject

/**
 * native 层（rustic / librclone）抛出的错误原本是一整段英文长文本：
 * - rustic：`Error: <guidance> (kind: <kind>, code: <code>) : caused by: <cause> : (source: <src>)`
 * - rclone：`rclone RPC `<method>` failed: status=<n>, output=<错误 JSON>`
 *
 * 直接写进任务日志或弹 Snackbar 会让 UI 出现大面积堆栈式英文。
 * 这里统一：
 * 1. 剥掉 `java.lang.RuntimeException: ` 之类的包装前缀；
 * 2. 只取出最有信息量的那一句（rustic 取 guidance、rclone 取 JSON 的 error 字段）；
 * 3. 按关键词归类，换成一行本地化短句。
 *
 * 完整原文仍由调用方用 Log.e 打到 Logcat，只是不再进入 UI 与任务日志。
 */
enum class NativeErrorKind {
    CANCELED,
    AUTH,
    REPO_NOT_FOUND,
    SMB_SHARE,
    NETWORK,
    NO_SPACE,
    TLS,
    PERMISSION,
    OBSCURE,
    UNKNOWN,
}

private const val MAX_RAW_LENGTH = 120

/** `java.lang.RuntimeException: X` -> `X`，repeat 用于剥掉双层包装。 */
private val JAVA_EXCEPTION_PREFIX = Regex("""^[\w.$]+(?:Exception|Error)\s*:\s*""")

/** rustic：`<guidance>` 位于 `Error: ` 与 ` (kind:` 之间。 */
private val RUSTIC_GUIDANCE = Regex("""Error:\s*(.+?)\s*\(kind:""")

/**
 * rustic 的多行 Display（非 display_log）格式：
 * `Error: `rustic_core` experienced ...` / `Message: <真正原因>` / `Some additional details ...` / `Backtrace: ...`。
 * 这种形态没有 ` (kind:` 段落，[RUSTIC_GUIDANCE] 会失配，必须单独抠出 Message 段，
 * 否则回退到「取首个非空行」只会得到 ``rustic_core` experienced ...`` 这种无信息量头部。
 */
private val RUSTIC_MULTILINE_MESSAGE = Regex("""Message:\s*(.+?)(?:\n\s*\n|Some additional details|Backtrace:|$)""", RegexOption.DOT_MATCHES_ALL)

/** rustic 的稳定错误码，C002 = 仓库密码错误。 */
private val RUSTIC_CODE_C002 = Regex("""code:\s*C002""")

/** rclone：`output=` 之后就是 rc 接口的错误 JSON，真正的文案在 error 字段里。 */
private val RCLONE_OUTPUT = Regex("""output=\s*(\{.*})\s*$""", RegexOption.DOT_MATCHES_ALL)

private fun String.containsAny(vararg keywords: String): Boolean {
    val lower = lowercase()
    return keywords.any { lower.contains(it) }
}

private fun String.truncate(max: Int): String = if (length <= max) this else "${take(max - 1)}…"

/** 异常的完整原始消息（仅剥掉 Java 异常类名前缀）。 */
private fun fullText(t: Throwable): String {
    var s = (t.message ?: t.localizedMessage ?: t.toString()).trim()
    repeat(3) { s = JAVA_EXCEPTION_PREFIX.replace(s, "") }
    return s
}

/** 从完整原始消息里抽出一句话。 */
private fun shorten(text: String): String {
    if (text.startsWith("rclone RPC")) {
        val json = RCLONE_OUTPUT.find(text)?.groupValues?.getOrNull(1)
        if (json != null) {
            val error = runCatching { JSONObject(json).optString("error") }.getOrNull()
            if (!error.isNullOrBlank()) return error.trim()
        }
        // JSON 解析失败：至少丢掉 output 里的请求/响应 dump。
        return text.substringBefore(", output=").trim()
    }

    val guidance = RUSTIC_GUIDANCE.find(text)?.groupValues?.getOrNull(1)?.trim()
    if (!guidance.isNullOrBlank()) return guidance

    // 多行 Display 格式：没有 (kind:) 段，改取 Message: 段并压平成一行。
    val multiline = RUSTIC_MULTILINE_MESSAGE.find(text)?.groupValues?.getOrNull(1)?.replace('\n', ' ')?.trim()
    if (!multiline.isNullOrBlank()) return multiline

    return text.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: text
}

private fun classify(text: String): NativeErrorKind = when {
    text.containsAny("canceled", "cancelled", "cancel", "aborted") && !RUSTIC_CODE_C002.containsMatchIn(text) ->
        NativeErrorKind.CANCELED

    RUSTIC_CODE_C002.containsMatchIn(text) ||
            text.containsAny(
                "wrong password", "incorrect password", "login incorrect", "authentication failed",
                "unauthorized", "decrypt", "crypto", "530", "421",
            ) -> NativeErrorKind.AUTH

    // 比 NETWORK 更具体的 SMB 场景优先，否则 `tree connect failed: dial tcp ...` 会被判成网络问题。
    text.containsAny("tree connect", "smb share", "share name") -> NativeErrorKind.SMB_SHARE

    text.containsAny("repository not found", "no repository", "config file") -> NativeErrorKind.REPO_NOT_FOUND

    text.containsAny(
        "timeout", "timed out", "connection refused", "no such host",
        "unreachable", "dial tcp", "network", "eof",
    ) -> NativeErrorKind.NETWORK

    text.containsAny("no space", "disk full", "not enough") -> NativeErrorKind.NO_SPACE

    text.containsAny("certificate", "x509", "tls", "ssl") -> NativeErrorKind.TLS

    text.containsAny("permission denied", "access denied", "403") -> NativeErrorKind.PERMISSION

    text.containsAny("obscure", "deobscure") -> NativeErrorKind.OBSCURE

    else -> NativeErrorKind.UNKNOWN
}

private fun stringRes(kind: NativeErrorKind): Int = when (kind) {
    NativeErrorKind.CANCELED -> R.string.native_error_canceled
    NativeErrorKind.AUTH -> R.string.native_error_auth
    NativeErrorKind.REPO_NOT_FOUND -> R.string.native_error_repo_not_found
    NativeErrorKind.SMB_SHARE -> R.string.native_error_smb_share
    NativeErrorKind.NETWORK -> R.string.native_error_network
    NativeErrorKind.NO_SPACE -> R.string.native_error_no_space
    NativeErrorKind.TLS -> R.string.native_error_tls
    NativeErrorKind.PERMISSION -> R.string.native_error_permission
    NativeErrorKind.OBSCURE -> R.string.native_error_obscure
    NativeErrorKind.UNKNOWN -> R.string.native_error_unknown
}

/** 压缩后的一句话原文，供 Logcat 或无需本地化的场景使用。 */
fun Throwable.toFriendlyRawMessage(): String = shorten(fullText(this))

/** 归类结果，供调用方按类型做分支（例如是否重试）。 */
fun Throwable.nativeErrorKind(): NativeErrorKind = classify(fullText(this))

/** 一行本地化友好文案；未命中分类时附截断后的原文。 */
fun Throwable.toFriendlyMessage(context: Context): String = fullText(this).let { full ->
    when (val kind = classify(full)) {
        NativeErrorKind.UNKNOWN -> context.getString(R.string.native_error_unknown, shorten(full).truncate(MAX_RAW_LENGTH))
        else -> context.getString(stringRes(kind))
    }
}

/** 已经在别处取到 message 时的重载。 */
fun String.toFriendlyMessage(context: Context): String = when (val kind = classify(this)) {
    NativeErrorKind.UNKNOWN -> context.getString(R.string.native_error_unknown, shorten(this).truncate(MAX_RAW_LENGTH))
    else -> context.getString(stringRes(kind))
}
