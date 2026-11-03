package com.vlad.tester.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.io.IOException
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

internal fun droppedBuild(files: List<File>, simulator: Boolean): File {
    if (files.size != 1) throw DesktopFailure("Drop exactly one ${if (simulator) ".app directory" else ".apk file"} in this area.")
    val file = files.single()
    val valid = if (simulator) file.isDirectory && file.extension.equals("app", true)
        else file.isFile && file.extension.equals("apk", true)
    if (!valid) throw DesktopFailure("Only ${if (simulator) "simulator .app directories" else "Android .apk files"} are accepted here.")
    return file
}

@Composable
internal fun BuildDropZone(
    simulator: Boolean, enabled: Boolean, onDrop: (File) -> Unit, onError: (String) -> Unit, modifier: Modifier
) {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentDrop by rememberUpdatedState(onDrop)
    val currentError by rememberUpdatedState(onError)
    SwingPanel(modifier = modifier, factory = {
        val label = JLabel(
            if (simulator) "Drop simulator .app here" else "Drop Android APK here", SwingConstants.CENTER
        ).apply { foreground = Color(0x94, 0xA3, 0xB8) }
        val panel = JPanel(BorderLayout()).apply {
            background = Color(0x16, 0x1E, 0x2C)
            border = BorderFactory.createDashedBorder(Color(0x4A, 0xDE, 0x80))
            add(label, BorderLayout.CENTER)
        }
        val listener = object : DropTargetAdapter() {
            override fun dragEnter(event: DropTargetDragEvent) {
                if (currentEnabled && event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    event.acceptDrag(DnDConstants.ACTION_COPY)
                } else event.rejectDrag()
            }
            override fun drop(event: DropTargetDropEvent) {
                if (!currentEnabled || !event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    event.rejectDrop()
                    return
                }
                event.acceptDrop(DnDConstants.ACTION_COPY)
                try {
                    val value = event.transferable.getTransferData(DataFlavor.javaFileListFlavor)
                    val list = value as? List<*> ?: throw DesktopFailure("The dropped content is not a file list.")
                    val files = list.map { it as? File ?: throw DesktopFailure("Drop local files only.") }
                    currentDrop(droppedBuild(files, simulator))
                    event.dropComplete(true)
                } catch (error: DesktopFailure) {
                    event.dropComplete(false)
                    currentError(error.message ?: "Invalid dropped build.")
                } catch (error: IOException) {
                    event.dropComplete(false)
                    currentError("Cannot read dropped file: ${error.message}")
                } catch (error: UnsupportedFlavorException) {
                    event.dropComplete(false)
                    currentError("Drop local APK files or simulator .app directories.")
                }
            }
        }
        DropTarget(panel, DnDConstants.ACTION_COPY, listener, true)
        DropTarget(label, DnDConstants.ACTION_COPY, listener, true)
        panel
    }, update = { panel ->
        panel.isEnabled = enabled
    })
}
