import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    alias(libs.plugins.application.common)
    alias(libs.plugins.application.hilt)
    alias(libs.plugins.application.hilt.work)
    alias(libs.plugins.application.compose)
    alias(libs.plugins.refine)
}

// 在 android 块之前添加下载任务
tasks.register("downloadResticBinaries") {
    doLast {
        if (System.getProperty("os.name").lowercase().contains("windows")) {
            // Windows环境使用PowerShell下载
            exec {
                commandLine = listOf("powershell", "-File", "../build/download_restic.ps1")
                workingDir = projectDir
            }
        } else {
            // Unix环境使用shell脚本
            exec {
                commandLine = listOf("bash", "../build/download_restic.sh")
                workingDir = projectDir
            }
        }
    }
}

android {
    // 动态获取 Actions 传入的版本信息
    val cmdVersionCode = if (project.hasProperty("versionCode")) {
        project.property("versionCode").toString().toInt()
    } else {
        libs.versions.versionCode.get().toInt()
    }

    val cmdVersionName = if (project.hasProperty("versionName")) {
        project.property("versionName").toString()
    } else {
        libs.versions.versionName.get()
    }

    namespace = "com.xayah.databackup"  // 修改:改回原来的包名
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.xayah.databackup.revived"  // 保持:新的应用包名
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()

        // 使用动态版本号
        versionCode = cmdVersionCode
        versionName = cmdVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String[]", "SUPPORTED_LOCALES", generateSupportedLocales())
    }

    lint {
        disable += "MissingTranslation"
    }

    flavorDimensions += listOf("abi", "feature")
    productFlavors {
        create("arm64-v8a") {
            dimension = "abi"
            // 移除偏移逻辑，统一使用基础版本号
            ndk.abiFilters.add("arm64-v8a")
        }
        create("armeabi-v7a") {
            dimension = "abi"
            ndk.abiFilters.add("armeabi-v7a")
        }
        create("x86_64") {
            dimension = "abi"
            ndk.abiFilters.add("x86_64")
        }
        create("x86") {
            dimension = "abi"
            ndk.abiFilters.add("x86")
        }
        create("foss") {
            dimension = "feature"
            applicationIdSuffix = ".foss"
        }
        create("premium") {
            dimension = "feature"
            applicationIdSuffix = ".premium"
        }
        create("alpha") {
            dimension = "feature"
            applicationIdSuffix = ".alpha"
            // 确保 Alpha 变体也遵循动态版本号
            versionCode = cmdVersionCode
            versionName = cmdVersionName
        }
    }

    applicationVariants.all {
        outputs.forEach { output ->
            (output as BaseVariantOutputImpl).outputFileName =
                "DataBackup-Revived-${versionName}-${productFlavors[0].name}-${productFlavors[1].name}-${buildType.name}.apk"
        }
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
}

fun generateSupportedLocales(): String {
    val foundLocales = StringBuilder()
    foundLocales.append("new String[]{")

    val languages = mutableListOf<String>()
    fileTree("src/main/res").visit {
        if(file.path.endsWith("strings.xml")){
            var languageCode = file.parent.replace("\\", "/").split('/').last()
                .replace("values-", "").replace("-r", "-")
            if (languageCode == "values") {
                languageCode = "en"
            }
            languages.add(languageCode)
        }
    }
    languages.sorted().forEach {
        foundLocales.append("\"").append(it).append("\"").append(",")
    }

    foundLocales.append("}")
    return foundLocales.toString().replace(",}","}")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Core
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))
    implementation(project(":core:util"))
    implementation(project(":core:work"))
    compileOnly(project(":core:hiddenapi"))
    implementation(project(":core:rootservice"))
    implementation(project(":core:network"))
    implementation(project(":core:restic"))

    // Feature
    implementation(project(":feature:crash"))
    implementation(project(":feature:setup"))
    "fossImplementation"(project(":feature:flavor:foss"))
    "premiumImplementation"(project(":feature:flavor:premium"))
    "alphaImplementation"(project(":feature:flavor:alpha"))
    "alphaImplementation"(project(":feature:flavor:foss"))
    implementation(project(":feature:main:dashboard"))
    implementation(project(":feature:main:restore"))
    implementation(project(":feature:main:cloud"))
    implementation(project(":feature:main:settings"))
    implementation(project(":feature:main:configurations"))
    implementation(project(":feature:main:processing"))
    implementation(project(":feature:main:list"))
    implementation(project(":feature:main:details"))
    implementation(project(":feature:main:history"))
    implementation(project(":feature:main:directory"))

    // Splash Screen
    implementation(libs.androidx.core.splashscreen)

    // Compose Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // libsu
    implementation(libs.libsu.core)

    // BountyCastle
    implementation(libs.bountycastle)

    implementation(libs.kotlinx.serialization.json)
}