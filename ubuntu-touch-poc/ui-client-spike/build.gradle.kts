plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
    }
}

tasks.register("printDesktopRuntimeClasspath") {
    doLast {
        val cp = kotlin.jvm("desktop").compilations.getByName("main").runtimeDependencyFiles
        println(cp.files.joinToString(":") { it.absolutePath })
    }
}
