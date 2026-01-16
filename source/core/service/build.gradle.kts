plugins {
    alias(libs.plugins.library.common)
    alias(libs.plugins.library.hilt)
    alias(libs.plugins.library.protobuf)
    alias(libs.plugins.refine)
    alias(libs.plugins.kotlin.serialization)  // 添加这行
}

android {
    namespace = "com.xayah.core.service"
}

dependencies {
    // Core
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:util"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:rootservice"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":core:restic"))
    compileOnly(project(":core:hiddenapi"))

    // JSON Serialization - 添加这个依赖
    implementation(libs.kotlinx.serialization.json)

    // Gson
    implementation(libs.gson)

    // Preferences DataStore
    implementation(libs.androidx.datastore.preferences)
}