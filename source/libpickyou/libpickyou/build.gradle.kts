plugins {
    alias(libs.plugins.library.common)
    alias(libs.plugins.library.compose)
}

android {
    namespace = "com.xayah.libpickyou"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // convention 里只开了 compose，aidl 需在这里补上
    buildFeatures {
        aidl = true
    }
}

dependencies {
    // 说明：core-ktx / appcompat / activity-compose / lifecycle-runtime(-ktx/-compose) /
    //       compose-bom / compose-ui(-graphics) / material3 / material-icons-extended
    //       都由 library.compose convention 统一注入，这里不再重复声明。

    // library.compose 未注入的 compose 扩展
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Accompanist
    implementation(libs.accompanist.permissions)

    // libsu
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)

    // document
    implementation(libs.androidx.documentfile)
}