package coredevices.pebble

import coredevices.util.Permission

class PebbleJvmDelegate {
    fun requiredPermissions(): Set<Permission> = setOf(
        Permission.Bluetooth,
        Permission.PostNotifications,
    )
}
