import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

// Phase 0 spike 3 proxy: proves Compose Desktop/Skiko renders over a real X11
// server (Xvfb here). It does not prove Xwayland-in-Libertine behaviour or
// touch input translation on real Ubuntu Touch hardware — see
// docs/ubuntu-touch-poc-plan.md.
fun main() {
    SwingUtilities.invokeAndWait {
        val window = ComposeWindow()
        window.size = Dimension(400, 300)
        window.setContent {
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(2000)
                val location: Point = window.locationOnScreen
                val image = Robot().createScreenCapture(Rectangle(location.x, location.y, window.width, window.height))
                ImageIO.write(image, "png", java.io.File("/tmp/compose-desktop-x11-spike.png"))
                exitProcess(0)
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText("core-service-spike: compose desktop over X11 OK")
            }
        }
        window.isVisible = true
    }
}
