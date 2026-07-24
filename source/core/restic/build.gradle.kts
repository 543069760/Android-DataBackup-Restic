plugins {
    alias(libs.plugins.library.common)
    alias(libs.plugins.library.hilt)
    alias(libs.plugins.serialization)
}

android {
    namespace = "com.xayah.core.restic"
}

dependencies {
    implementation(libs.libsu.core)
    implementation(project(":core:common"))
    implementation(project(":core:datastore"))
    implementation(project(":core:model"))
    implementation(project(":core:util"))
    implementation(project(":core:rootservice"))
    // 序列化依赖
    implementation(libs.gson)
    implementation(libs.kotlinx.serialization.json)

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.android.compiler)  // 修正：使用正确的引用
}