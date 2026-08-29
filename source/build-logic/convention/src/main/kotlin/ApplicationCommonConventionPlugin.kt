import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

private fun Project.configureCommon() {
    pluginManager.apply("com.android.application")
    pluginManager.apply("org.jetbrains.kotlin.android")

    extensions.getByType<ApplicationExtension>().apply {
        signingConfigs {
            create("release") {
                storeFile = file(System.getenv("STORE_FILE") ?: "placeholder")
                storePassword = System.getenv("STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }

        buildTypes {
            release {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
                buildConfigField("Boolean", "ENABLE_VERBOSE", "false")
                signingConfig = signingConfigs.getByName("release")
            }
            debug {
                isMinifyEnabled = false
                isShrinkResources = false
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
                buildConfigField("Boolean", "ENABLE_VERBOSE", "false")
                // 仅当提供了有效 keystore（如 CI 中）才复用 release 签名，否则回退到默认 debug 签名
                if (System.getenv("STORE_FILE") != null && file(System.getenv("STORE_FILE")).exists()) {
                    signingConfig = signingConfigs.getByName("release")
                }
            }
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        buildFeatures {
            buildConfig = true
        }

        packaging {
            resources {
                excludes += "/META-INF/{AL2.0,LGPL2.1}"
                excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
                excludes += "/META-INF/DEPENDENCIES"  // 新增
            }
        }

        //tasks.withType<KotlinCompile>().configureEach {
          //  compilerOptions {
            //    jvmTarget.set(JvmTarget.JVM_17)
              //  freeCompilerArgs.add("-Xcontext-receivers")
            //}
        //}
        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
}

class ApplicationCommonConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            configureCommon()
        }
    }
}