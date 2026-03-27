import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class LibraryHiltWorkConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")       // 新增：应用 KSP 插件
            extensions.getByType<LibraryExtension>().apply {
                dependencies {
                    add("implementation", catalogLibs.findLibrary("hilt.work").get())
                    add("ksp", catalogLibs.findLibrary("hilt.work.compiler").get())  // kapt → ksp
                }
            }
        }
    }
}