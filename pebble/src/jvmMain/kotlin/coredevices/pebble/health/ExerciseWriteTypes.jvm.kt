package coredevices.pebble.health

import com.viktormykhailiv.kmp.health.HealthDataType

// No Health Connect/HealthKit-equivalent platform health store on desktop Linux.
internal actual fun exerciseWriteTypes(): List<HealthDataType> = emptyList()

internal actual fun supportsSleepWriting(): Boolean = false
