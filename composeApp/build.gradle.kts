import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.nativeCocoaPods)
    alias(libs.plugins.kotlinx.atomicfu)
}

kotlin {
    val xcodeExists = providers.exec {
        isIgnoreExitValue = true
        commandLine("which", "xcode-select")
    }.result.get().exitValue == 0
    val xcodeDir = if (xcodeExists) {
        providers.exec {
            commandLine("xcode-select", "-p")
        }.standardOutput.asText.get().trim()
    } else {
        ""
    }

    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }
    android {
        namespace = "coredevices.coreapp.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        androidResources {
            enable = true
        }

        withHostTestBuilder {}
    }

    // Make xcode invoke gradle from the right place
    tasks.register("fixXcodeProject") {
        val xcodeProjectFile = project.file("../iosApp/Pods/Pods.xcodeproj/project.pbxproj")
        val rootProjectPath = rootProject.projectDir.absolutePath
        doLast {
            if (xcodeProjectFile.exists()) {
                var content = xcodeProjectFile.readText()
                content = content.replace("gradlew\\\" -p \\\"\$REPO_ROOT\\\"", "gradlew\\\" -p \\\"$rootProjectPath\\\"")
                xcodeProjectFile.writeText(content)
            } else {
                logger.warn("Xcode project file not found, skipping fix: ${xcodeProjectFile.path}")
            }
        }
    }
    tasks.named("podInstall") {
        finalizedBy("fixXcodeProject")
    }

    cocoapods {
        version = "1.0"
        summary = "Core App"
        homepage = "https://github.com/coredevices/CoreApp"
        license = "proprietary"
        ios.deploymentTarget = "15.6"
        podfile = project.file("../iosApp/Podfile")

        pod("GoogleSignIn", "8.0.0")
        pod("FirebaseCore", "11.10.0")
        pod("FirebaseAuth") {
            linkOnly = true
            extraOpts += listOf("-compiler-option", "-fmodules")
        }
        pod("FirebaseFirestore") {
            linkOnly = true
            source = git("https://github.com/invertase/firestore-ios-sdk-frameworks.git") {
                // 11.10.0 — pinned here by commit because the synthetic Podfile's lock lives
                // under build/ and is never committed
                commit = "e43715cc392c819b522c7a189bed9400e757c788"
            }
        }
        // The binary FirebaseFirestoreInternal is static, so its C deps must be linked here
        pod("nanopb") {
            version = "3.30910.0"
            linkOnly = true
        }
        pod("leveldb-library") {
            version = "1.22.6"
            moduleName = "leveldb"
            linkOnly = true
        }
        pod("FirebaseStorage") {
            linkOnly = true
        }
        pod("FirebaseCrashlytics") {
            linkOnly = true
        }
        pod("FirebaseMessaging") {
            linkOnly = true
        }

        framework {
            baseName = "ComposeApp"
        }
    }

    buildList {
        // idea.sync.active is set by the IDE during sync only. The simulator target doubles the
        // pod/cinterop work sync waits on; command-line and Xcode builds still configure it.
        val ideSync = providers.systemProperty("idea.sync.active").orNull.toBoolean()
        if (!ideSync) add(iosSimulatorArm64())
        add(iosArm64())
    }.forEach {
        it.binaries.all {
            freeCompilerArgs += listOf(
                "-Xdisable-phases=DevirtualizationAnalysis,DCEPhase"
            )
            linkerOpts.addAll(listOf("-framework", "Accelerate"))
            val osName =
                if (target.konanTarget.name.contains("simulator")) "iphonesimulator" else "iphoneos"
            if (xcodeExists) {
                linkerOpts.addAll(listOf(
                    "-weak_framework", "CoreML",
                    "-L$xcodeDir/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/$osName"
                ))
            }
            // The binary FirebaseFirestoreInternal is static, so its C deps must be linked into
            // every binary that pulls in Firestore, not just the pod framework.
            val grpcSlice = if (target.konanTarget.name.contains("simulator")) {
                "ios-arm64_x86_64-simulator"
            } else {
                "ios-arm64"
            }
            listOf(
                "FirebaseFirestoreGRPCCoreBinary/grpc.xcframework" to "grpc",
                "FirebaseFirestoreGRPCCPPBinary/grpcpp.xcframework" to "grpcpp",
                "FirebaseFirestoreGRPCBoringSSLBinary/openssl_grpc.xcframework" to "openssl_grpc",
                "FirebaseFirestoreAbseilBinary/absl.xcframework" to "absl",
            ).forEach { (path, fw) ->
                val sliceDir = layout.buildDirectory
                    .dir("cocoapods/synthetic/ios/Pods/$path/$grpcSlice")
                    .get().asFile
                linkerOpts.addAll(listOf("-F" + sliceDir.absolutePath, "-framework", fw))
            }
        }
    }

    // Ubuntu Touch / Linux desktop target, via Libertine + Xwayland (docs/ubuntu-touch-poc-plan.md).
    // Not a general-purpose desktop target: no Windows/macOS packaging is set up, and several
    // Android/iOS-only expects (deep links, theming delegates, phone-integration services) still
    // need jvmMain actuals before App() itself will compile here — real remaining work, not
    // finished by this target existing.
    jvm("desktop") {
        compilerOptions {
            // See pebble/build.gradle.kts's jvm{} target for why: Compose Multiplatform 1.11.1's
            // own artifacts ship inline functions compiled at JVM target 21.
            jvmTarget.set(JvmTarget.JVM_21)
        }
        // compose.desktop.application.mainClass (below) doesn't wire into Kotlin's own
        // `desktopRun` task - that needs its own mainRun {} config.
        mainRun {
            mainClass.set("coredevices.coreapp.MainKt")
        }
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("androidx.compose.material3.ExperimentalMaterial3Api")
                optIn("kotlinx.cinterop.BetaInteropApi")
                optIn("kotlin.time.ExperimentalTime")
            }
        }
        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.crashlytics)
            implementation(libs.firebase.crashlytics.ndk)
            implementation(compose.preview)
            implementation(compose.uiTooling)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.credentials)
            implementation(libs.gms.auth)
            implementation(libs.identity.google)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.coroutines.android)
            implementation(libs.androidx.work)
            implementation(libs.play.update)
            implementation(libs.play.update.ktx)
            implementation(libs.coil.gif)
            implementation(libs.coredevices.haversine)
            implementation(project(":experimental"))
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.crashkios)
            implementation(libs.firebase.crashlytics)
            implementation(project(":experimental"))
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.coroutines)
                // :util depends on :libindex via implementation(), which doesn't leak
                // transitively - DesktopPlatformServices.kt needs IndexIdentifier directly.
                implementation(project(":libindex"))
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        commonMain.dependencies {
            implementation(libs.kotlinx.io.okio)
            implementation(libs.kermit)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.ui)
            implementation(libs.backhandler)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.serialization)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.coil)
            implementation(libs.coil.svg)

            implementation(libs.firebase.auth)
            implementation(libs.firebase.firestore)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.client.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.coroutines)
            implementation(project(":pebble"))
            implementation(project(":util"))
            // :experimental (Ring/Index device features) is android/iOS-only: it pulls in
            // :libindex, which needs the coredevices.haversine BLE library — no jvm() target.
            // Shared UI reaches it only through ExperimentalDevicesFacade (:util), bound to
            // the real ExperimentalDevices on android/iOS and a no-op on desktop.
            implementation(libs.kmpnotifier)
            implementation(libs.kmpio)
            implementation(project(":libpebble3"))
            implementation(project(":index-ai"))
            api(project(":mcp"))
            // com.viktormykhailiv:health-kmp has no jvm() variant (see
            // docs/ubuntu-touch-poc-plan.md); composeApp doesn't reference its types
            // directly (only through :pebble's already-abstracted PlatformHealthSync), so
            // this is android/iOS-only rather than needing its own facade.
        }
    }
}

compose.desktop {
    application {
        mainClass = "coredevices.coreapp.MainKt"

        // Phase 6 (docs/ubuntu-touch-poc-plan.md): produces a self-contained app directory
        // (jars + a jlink-trimmed JRE) via jpackage, which a Click package wraps directly -
        // no target format set, since we package the Click ourselves rather than using
        // jpackage's own .deb/.rpm output.
        nativeDistributions {
            packageName = "coreapp"
        }
    }
}

// Desktop has no google-services Gradle plugin to inject Firebase config, so bundle the same
// (gitignored, see README.md) file the Android build uses as a resource for DesktopFirebase to
// read at runtime. Absent, the app still starts and logs where it looked.
rootProject.file("androidApp/src/google-services.json").takeIf { it.isFile }?.let { config ->
    tasks.named<Copy>("desktopProcessResources") { from(config) }
}

// :pebble's compose-webview-multiplatform dependency pulls in an embedded Chromium (JCEF) on
// desktop, which needs org.jogamp's native OpenGL bindings from a Maven repo we don't configure
// (see docs/ubuntu-touch-poc-plan.md). Not used by anything App() reaches unconditionally at
// startup (only specific support/help screens) - excluded from the desktop runtime classpath
// only, so android/iOS keep the real webview.
configurations.matching {
    it.name == "desktopRuntimeClasspath" || it.name == "desktopTestRuntimeClasspath"
}.configureEach {
    exclude(group = "dev.datlag", module = "kcef")
}

// desktopRun (KotlinJvmRun, an internal KGP task type we can't configure directly - see
// docs/ubuntu-touch-poc-plan.md) doesn't propagate $DISPLAY to the child JVM, and AWT needs a
// real one to open a window over Xwayland. Prints a ready-to-use classpath so `java` can be
// invoked directly from a shell instead, where environment inheritance works normally.
tasks.register("printDesktopRuntimeClasspath") {
    doLast {
        val cp = configurations.getByName("desktopRuntimeClasspath").files.joinToString(":") { it.absolutePath }
        println("DESKTOP_CLASSPATH_START")
        println(cp + ":" + layout.buildDirectory.dir("classes/kotlin/desktop/main").get().asFile.absolutePath)
        println("DESKTOP_CLASSPATH_END")
    }
}


compose.resources {
    packageOfResClass = "coreapp.composeapp.generated.resources"
}
