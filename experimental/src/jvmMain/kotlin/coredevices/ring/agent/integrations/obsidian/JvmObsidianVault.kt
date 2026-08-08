package coredevices.ring.agent.integrations.obsidian

import PlatformUiContext
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/**
 * Desktop has direct filesystem access to any folder the user picks, so unlike Android's SAF
 * or iOS's security-scoped bookmarks, [VaultRef.handle] is just the absolute vault path.
 */
class JvmObsidianVault : ObsidianVault {
    private val logger = Logger.withTag("JvmObsidianVault")

    override suspend fun pickFolder(uiContext: PlatformUiContext): VaultRef? = withContext(Dispatchers.IO) {
        var result: VaultRef? = null
        SwingUtilities.invokeAndWait {
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "Select Obsidian vault folder"
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val dir = chooser.selectedFile
                result = VaultRef(handle = dir.absolutePath, displayName = dir.name)
            }
        }
        result
    }

    override suspend fun hasAccess(handle: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(handle)
        dir.isDirectory && dir.canWrite()
    }

    override suspend fun listMarkdownFiles(handle: String, subfolder: String): List<String> = withContext(Dispatchers.IO) {
        val dir = resolveDir(handle, subfolder) ?: return@withContext emptyList()
        dir.listFiles { file -> file.isFile && file.name.endsWith(".md", ignoreCase = true) }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    override suspend fun readFile(handle: String, name: String): String? = withContext(Dispatchers.IO) {
        val file = resolveFile(handle, name) ?: return@withContext null
        if (!file.exists()) return@withContext null
        runCatching { file.readText() }.getOrNull()
    }

    override suspend fun writeFile(handle: String, name: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val file = resolveFile(handle, name) ?: return@withContext false
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        }.getOrElse {
            logger.e(it) { "Failed to write $name" }
            false
        }
    }

    override suspend fun releaseAccess(handle: String) {
        // No persisted grant to release with direct filesystem access.
    }

    private fun resolveDir(handle: String, subfolder: String): File? {
        val root = File(handle)
        if (!root.isDirectory) return null
        val dir = if (subfolder.isBlank()) root else File(root, subfolder)
        return if (dir.isDirectory) dir else null
    }

    private fun resolveFile(handle: String, name: String): File? {
        val root = File(handle)
        if (!root.isDirectory) return null
        return File(root, name)
    }
}
