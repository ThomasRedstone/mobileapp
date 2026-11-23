plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.nativeCocoaPods) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.buildKonfig) apply false
    alias(libs.plugins.kotlinx.atomicfu) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.androidLint) apply false
}

project.gradle.taskGraph.whenReady {
    allTasks.filter { it::class.simpleName?.contains("EmbedAndSign") == true }.forEach {
        logger.warn("Disabling embedding and signing task in project ${it.project.name}")
        it.enabled = false
    }
}
// compose-webview-multiplatform pulls an embedded Chromium (dev.datlag:kcef) into every jvm()
// classpath, and kcef needs org.jogamp's OpenGL bindings, which are not on Maven Central.
// composeApp already excludes it from its desktop classpaths; do the same for every module's
// jvmTest runtime classpath, or `jvmTest` fails to resolve (seen in :libindex). The tests
// never open a webview.
subprojects {
    configurations.matching { it.name == "jvmTestRuntimeClasspath" }.configureEach {
        exclude(group = "dev.datlag", module = "kcef")
    }
}
