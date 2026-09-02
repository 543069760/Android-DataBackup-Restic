import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class ApplicationHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")

            extensions.getByType<ApplicationExtension>().apply {
                dependencies {
                    add("implementation", catalogLibs.findLibrary("hilt.android").get())
                    add("ksp", catalogLibs.findLibrary("hilt.android.compiler").get())
                }
            }
        }
    }
}