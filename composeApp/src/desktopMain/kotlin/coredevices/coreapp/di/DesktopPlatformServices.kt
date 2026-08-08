package coredevices.coreapp.di

import PlatformUiContext
import coredevices.analytics.AnalyticsBackend
import coredevices.util.AppResumed
import coredevices.util.CompanionDevice
import coredevices.util.Permission
import coredevices.util.PermissionRequester
import coredevices.util.PermissionResult
import coredevices.util.Platform
import coredevices.util.RequiredPermissions
import coredevices.util.integrations.OAuthCancelledException
import coredevices.util.integrations.OAuthLauncher
import coredevices.libindex.device.IndexIdentifier
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import java.awt.Desktop
import java.net.URI

/**
 * Honest desktop stand-ins for the Android-specific platform services
 * `androidDefaultModule` wires up. Most of these have no real desktop
 * equivalent yet (system permission model, companion device pairing,
 * analytics, OAuth redirect capture) — see docs/ubuntu-touch-poc-plan.md.
 */

class DesktopPlatform : Platform {
    override val name: String = "Linux Desktop"
    override val deviceModelName: String =
        System.getProperty("os.name") + " " + System.getProperty("os.arch")

    override suspend fun openUrl(url: String) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }

    override suspend fun runWithBgTask(name: String, task: suspend () -> Unit) {
        // No background task assertion API to hold on desktop - just run it.
        task()
    }
}

class DesktopPermissionRequester(
    requiredPermissions: RequiredPermissions,
    appResumed: AppResumed,
) : PermissionRequester(requiredPermissions, appResumed) {
    // No OS permission model on desktop yet - everything reports granted so the app doesn't
    // permanently nag for something it can never obtain here.
    override suspend fun requestPlatformPermission(permission: Permission, uiContext: PlatformUiContext): PermissionResult =
        PermissionResult.Granted

    override suspend fun hasPermission(permission: Permission): Boolean = true

    override fun openPermissionsScreen(uiContext: PlatformUiContext) {}
}

class DesktopCompanionDevice : CompanionDevice {
    override suspend fun registerDevice(identifier: IndexIdentifier, uiContext: PlatformUiContext) {}
    override suspend fun registerDevice(identifier: PebbleIdentifier, uiContext: PlatformUiContext) {}
    override fun hasApprovedDevice(identifier: PebbleIdentifier): Boolean = true
    override fun hasApprovedDevice(identifier: IndexIdentifier): Boolean = true
    override fun cdmPreviouslyCrashed(): Boolean = false
}

object DesktopAnalytics : AnalyticsBackend {
    override fun logEvent(name: String, parameters: Map<String, Any>?) {}
    override fun addGlobalProperty(name: String, value: String?) {}
    override fun setEnabled(enabled: Boolean) {}
}

object DesktopOAuthLauncher : OAuthLauncher {
    // No embeddable web-auth view or redirect-scheme capture on this platform yet.
    override suspend fun authenticate(authUrl: String, callbackScheme: String, expectedPathSegment: String) =
        throw OAuthCancelledException()
}
