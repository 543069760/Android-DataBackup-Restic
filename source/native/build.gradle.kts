plugins {
    alias(libs.plugins.library.common)
    alias(libs.plugins.library.androidTest)
}

// ── rclone (gomobile) bind + 解压 ──────────────────────────────────
// 双层 submodule 根（含 go.mod，模块名 github.com/rclone/rclone）
// 注意：file(...) 相对 :native 模块目录 source/native/，不带 source/native 前缀
val rcloneRepoDir = file("src/main/jni/external/rclone/rclone")

// bind 产物与解压产物落到 build 目录，避免污染源码树
val rcloneAar = layout.buildDirectory.file("rclone/rclone.aar")
val rcloneExtractDir = layout.buildDirectory.dir("rclone/extracted")

val isWindows = System.getProperty("os.name").lowercase().contains("windows")

// 1) 调 gomobile bind 出 AAR（一次编全 4 个 ABI，靠 :app 的 abiFilters 打包时筛）
val gomobileBindRclone = tasks.register<Exec>("gomobileBindRclone") {
    workingDir = rcloneRepoDir
    // 增量：Go 源与 go.mod 不变则 up-to-date 跳过（bind 很慢）
    inputs.dir(File(rcloneRepoDir, "librclone"))
    inputs.file(File(rcloneRepoDir, "go.mod"))
    val aarFile = rcloneAar.get().asFile
    outputs.file(aarFile)

    doFirst { aarFile.parentFile.mkdirs() }

    val gomobileExe = if (isWindows) "gomobile.exe" else "gomobile"
    commandLine(
        gomobileExe, "bind", "-v",
        "-androidapi", "26",
        "-target=android/arm64,android/arm,android/amd64,android/386",
        "-javapkg=org.rclone",
        "-o", rcloneAar.get().asFile.absolutePath,
        "github.com/rclone/rclone/librclone/gomobile"
    )
}

// 2) 就地解压 AAR：classes.jar + jni/<abi>/libgojni.so
val extractRclone = tasks.register<Copy>("extractRclone") {
    dependsOn(gomobileBindRclone)
    from(zipTree(rcloneAar)) {
        include("classes.jar")
        include("jni/**")
    }
    into(rcloneExtractDir)
}

android {
    namespace = "com.xayah.libnative"
    ndkVersion = "25.2.9519653"

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // gomobile 解压出的 jni/<abi>/libgojni.so 作为额外 jniLibs 源集，AGP 自动打进 APK
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(rcloneExtractDir.map { it.dir("jni") })
        }
    }
}

// 让所有编译/打包在解压 task 之后
tasks.named("preBuild") {
    dependsOn(extractRclone)
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)

    // gobind 生成的 org.rclone.gomobile.* 绑定（classes.jar），直接上 classpath
    api(files(rcloneExtractDir.map { it.file("classes.jar") }).builtBy(extractRclone))
}