package com.xayah.core.util.command

import android.util.Log
import com.xayah.core.common.util.toSpaceString
import com.xayah.core.common.util.trim
import com.xayah.core.util.SymbolUtil
import com.xayah.core.util.model.ShellResult

object Tar {
    private suspend fun execute(vararg args: String): ShellResult = BaseUtil.execute("tar", *args)

    suspend fun compressInCur(cur: String, src: String, dst: String, extra: String): ShellResult {
        Log.d("Tar-Wrapper", "Starting compressInCur: cur=$cur, src=$src, dst=$dst, extra=$extra")

        // Move to $cur path.
        BaseUtil.execute("cd", cur)
        Log.d("Tar-Wrapper", "Changed to directory: $cur")

        // Compress
        val result = if (extra.isEmpty()) {
            Log.d("Tar-Wrapper", "Executing tar without compression")
            execute("--totals", "-cpf", "- $src", ">", "${SymbolUtil.QUOTE}$dst${SymbolUtil.QUOTE}")
        } else {
            Log.d("Tar-Wrapper", "Executing tar with compression: $extra")
            execute("--totals", "-cpf", "- $src", "|", extra, ">", "${SymbolUtil.QUOTE}$dst${SymbolUtil.QUOTE}")
        }

        Log.d("Tar-Wrapper", "Tar command completed with code: ${result.code}")

        // Move back
        BaseUtil.execute("cd", "/")
        Log.d("Tar-Wrapper", "Returned to root directory")

        return result
    }

    suspend fun compress(exclusionList: List<String>, h: String, srcDir: String, src: String, dst: String, extra: String): ShellResult =
        run {
            val exclusion = exclusionList.trim().map { "--exclude=$it" }.toSpaceString()
            if (extra.isEmpty()) {
                // tar --totals "$exclusion" $h -cpf - -C "$srcDir" -- "$src" > "$dst"
                execute(
                    "--totals",
                    exclusion,
                    h,
                    "-cpf",
                    "-",
                    "-C",
                    "${SymbolUtil.QUOTE}$srcDir${SymbolUtil.QUOTE}",
                    "--",
                    "${SymbolUtil.QUOTE}$src${SymbolUtil.QUOTE}",
                    ">",
                    "${SymbolUtil.QUOTE}$dst${SymbolUtil.QUOTE}",
                )
            } else {
                // tar --totals "$exclusion" $h -cpf - -C "$srcDir" -- "$src" | $extra > "$dst"
                execute(
                    "--totals",
                    exclusion,
                    h,
                    "-cpf",
                    "-",
                    "-C",
                    "${SymbolUtil.QUOTE}$srcDir${SymbolUtil.QUOTE}",
                    "--",
                    "${SymbolUtil.QUOTE}$src${SymbolUtil.QUOTE}",
                    "|",
                    extra,
                    ">",
                    "${SymbolUtil.QUOTE}$dst${SymbolUtil.QUOTE}",
                )
            }
        }

    suspend fun test(src: String, extra: String): ShellResult = if (extra.isEmpty()) {
        // tar -tf "$src" > /dev/null 2>&1
        execute(
            "-tf",
            "${SymbolUtil.QUOTE}$src${SymbolUtil.QUOTE}",
            ">",
            "/dev/null",
            "2>&1",
        )
    } else {
        // zstd -d -c "$src" | tar -tf - > /dev/null 2>&1
        BaseUtil.execute(
            "zstd",
            "-d",
            "-c",
            "${SymbolUtil.QUOTE}$src${SymbolUtil.QUOTE}",
            "|",
            "tar",
            "-tf",
            "-",
            ">",
            "/dev/null",
            "2>&1",
        )
    }

    suspend fun decompress(src: String, dst: String, extra: String): ShellResult = run {
        if (extra.isEmpty()) {
            // tar --totals -xmpf "$src" -C "$dst"
            execute(
                "--totals",
                "-xmpf",
                "${SymbolUtil.QUOTE}$src${SymbolUtil.QUOTE}",
                "-C",
                "${SymbolUtil.QUOTE}$dst${SymbolUtil.QUOTE}",
            )
        } else {
            // zstd -d -c "$src" | tar --totals -xmpf - -C "$dst"
            BaseUtil.execute(
                "zstd",
                "-d",
                "-c",
                "${SymbolUtil.QUOTE}$src${SymbolUtil.QUOTE}",
                "|",
                "tar",
                "--totals",
                "-xmpf",
                "-",
                "-C",
                "${SymbolUtil.QUOTE}$dst${SymbolUtil.QUOTE}",
            )
        }
    }

    suspend fun decompress(exclusionList: List<String>, clear: String, m: Boolean, src: String, dst: String, extra: String): ShellResult = run {
        val exclusion = exclusionList.trim().map { "--exclude=$it" }.toSpaceString()
        if (extra.isEmpty()) {
            // tar --totals "$exclusion" $clear -xmpf "$src" -C "$dst"
            execute(
                "--totals",
                exclusion,
                clear,
                if (m) "-xmpf" else "-xpf",
                "${SymbolUtil.QUOTE}$src${SymbolUtil.QUOTE}",
                "-C",
                "${SymbolUtil.QUOTE}$dst${SymbolUtil.QUOTE}",
            )
        } else {
            // zstd -d -c "$src" | tar --totals "$exclusion" $clear -xmpf - -C "$dst"
            BaseUtil.execute(
                "zstd",
                "-d",
                "-c",
                "${SymbolUtil.QUOTE}$src${SymbolUtil.QUOTE}",
                "|",
                "tar",
                "--totals",
                exclusion,
                clear,
                "-xmpf",
                "-",
                "-C",
                "${SymbolUtil.QUOTE}$dst${SymbolUtil.QUOTE}",
            )
        }
    }
}
