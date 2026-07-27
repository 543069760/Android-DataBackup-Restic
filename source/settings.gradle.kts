import groovy.json.JsonSlurper

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// 用 cargo metadata 定位 rustls-platform-verifier-android 包在本机 cargo registry 中的位置，
// 取其 manifest(Cargo.toml) 父目录下的 maven/ 作为本地 maven 仓库。
// 该路径含机器相关哈希(index.crates.io-xxxx)，禁止写死，必须动态解析。
fun findRustlsPlatformVerifierMavenRepo(): File {
    val manifestPath = File(
        rootDir,
        "native/src/main/jni/external/rustic/rustic/Cargo.toml"
    ).absolutePath

    val process = ProcessBuilder(
        "cargo", "metadata",
        "--format-version", "1",
        "--manifest-path", manifestPath
    )
        .redirectErrorStream(false)
        .start()

    val stdout = process.inputStream.bufferedReader().use { it.readText() }
    val exit = process.waitFor()
    require(exit == 0) {
        "cargo metadata 执行失败(exit=$exit)，请确认构建机已安装 cargo 且在 PATH 中"
    }

    @Suppress("UNCHECKED_CAST")
    val metadata = JsonSlurper().parseText(stdout) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val packages = metadata["packages"] as List<Map<String, Any?>>

    val pkg = packages.firstOrNull { it["name"] == "rustls-platform-verifier-android" }
        ?: error("cargo metadata 中未找到 rustls-platform-verifier-android 包，请先执行一次 native 构建以拉取该依赖")

    val pkgManifest = File(pkg["manifest_path"] as String)
    val mavenDir = File(pkgManifest.parentFile, "maven")
    require(mavenDir.isDirectory) {
        "未找到 rustls-platform-verifier-android 的 maven 目录: ${mavenDir.absolutePath}"
    }
    return mavenDir
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // rustls-platform-verifier 的 Android 支持组件(.aar)所在的本地 maven 仓库，
        // 因 FAIL_ON_PROJECT_REPOS 禁止在 app 模块声明仓库，故必须在此注册
        maven(url = findRustlsPlatformVerifierMavenRepo())
    }
}

rootProject.name = "DataBackup"
include(":app")
include(":core:common")
include(":core:service")
include(":core:ui")
include(":core:model")
include(":core:database")
include(":core:data")
include(":core:datastore")
include(":core:util")
include(":core:work")
include(":core:hiddenapi")
include(":core:systemapi")
include(":core:rootservice")
include(":core:network")
include(":core:provider")
include(":core:restic")
include(":feature:crash")
include(":feature:setup")
include(":feature:main:dashboard")
include(":feature:main:restore")
include(":feature:main:cloud")
include(":feature:main:settings")
include(":feature:main:configurations")
include(":feature:main:processing")
include(":feature:main:list")
include(":feature:main:details")
include(":feature:main:history")
include(":feature:main:directory")
include(":feature:flavor:foss")
include(":feature:flavor:premium")
include(":feature:flavor:alpha")
include(":native")

// libpickyou 本地源码模块(库模块在子目录 libpickyou/libpickyou)
include(":libpickyou")
project(":libpickyou").projectDir = file("libpickyou/libpickyou")