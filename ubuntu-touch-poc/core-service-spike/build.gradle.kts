plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    linuxX64 {
        binaries {
            executable {
                entryPoint = "main"
            }
        }
    }
    // Real UT devices are arm64.
    linuxArm64 {
        binaries {
            executable {
                entryPoint = "main"
            }
        }
    }
}
