plugins {
    alias(libs.plugins.library.common)
    alias(libs.plugins.library.hilt)
    alias(libs.plugins.library.protobuf)
    alias(libs.plugins.refine)
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


    // Gson
    implementation(libs.gson)

    // Preferences DataStore
    implementation(libs.androidx.datastore.preferences)
}