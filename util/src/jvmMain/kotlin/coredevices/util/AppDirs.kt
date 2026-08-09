package coredevices.util

import java.io.File

/**
 * XDG base-dir paths for the desktop app's own state, under a single app directory name.
 *
 * The name defaults to "coreapp" (the plain desktop dev run), but is overridable via
 * `$COREAPP_DIR_NAME` - packaged as a Click, the app's writable directories are
 * `~/.cache/@{APP_PKGNAME}/`, `~/.local/share/@{APP_PKGNAME}/` etc. (see
 * docs/ubuntu-touch-poc-plan.md), not `~/.cache/coreapp/`, so the launcher sets this env var to
 * the click's real package name.
 */
object AppDirs {
    private val appDirName = System.getenv("COREAPP_DIR_NAME")?.takeIf { it.isNotBlank() } ?: "coreapp"

    fun cacheDir(vararg subpath: String): File = xdgDir("XDG_CACHE_HOME", ".cache", subpath)
    fun dataDir(vararg subpath: String): File = xdgDir("XDG_DATA_HOME", ".local/share", subpath)
    fun configDir(vararg subpath: String): File = xdgDir("XDG_CONFIG_HOME", ".config", subpath)

    private fun xdgDir(envVar: String, homeFallback: String, subpath: Array<out String>): File {
        val base = System.getenv(envVar)?.takeIf { it.isNotBlank() }
            ?: (System.getProperty("user.home") + "/" + homeFallback)
        return File(base, (listOf(appDirName) + subpath).joinToString("/"))
    }
}
