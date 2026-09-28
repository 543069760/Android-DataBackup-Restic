package com.xayah.core.network.model

import com.google.gson.annotations.SerializedName

/**
 * GitHub Api Release Entity
 */
data class Release(
    @SerializedName("html_url") val url: String = "",
    @SerializedName("tag_name") val tagName: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("assets") val assets: List<Asset> = listOf(),
    @SerializedName("body") val body: String = "",
) {
    val content get() = body.replace(Regex("[*`]"), "")

    /**
     * body 中的 changelog/提交信息段：定位含 "Changelog"/"Commit Message"/"更新内容"
     * 的标题行，取其后的正文；取不到时回退全文。
     * 用于更新弹窗展示——Release Info 等固定段在每个 release 的 body 中重复，
     * 各版本唯一的差异就是 changelog 部分。
     */
    val changelog: String
        get() = run {
            val lines = content.lines()
            val idx = lines.indexOfFirst { line ->
                val t = line.trimStart('#', ' ', '\t')
                t.contains("Changelog", true) ||
                        t.contains("Commit Message", true) ||
                        t.contains("更新内容")
            }
            if (idx >= 0) lines.drop(idx + 1).joinToString("\n").trim().ifEmpty { content }
            else content
        }
}

/**
 * GitHub Api Asset Entity
 */
data class Asset(
    @SerializedName("browser_download_url") val url: String = "",
)