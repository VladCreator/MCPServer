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

internal fun droppedBuild(files: List<File>): File {
    if (files.size != 1) throw DesktopFailure("Drop exactly one APK or simulator .app.")
    return droppedBuild(files, files.single().extension.equals("app", true))
}

@Composable
internal fun BuildDropZone(
    enabled: Boolean, onDrop: (File) -> Unit, onError: (String) -> Unit,
    background: Color, foreground: Color, accent: Color, modifier: Modifier
) {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentDrop by rememberUpdatedState(onDrop)
    val currentError by rememberUpdatedState(onError)
    SwingPanel(modifier = modifier, factory = {
        val label = JLabel(
            "Drop Android APK or iOS simulator .app here", SwingConstants.CENTER
        ).apply { this.foreground = foreground }
        val panel = JPanel(BorderLayout()).apply {
            this.background = background
            border = BorderFactory.createDashedBorder(accent)
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
                    currentDrop(droppedBuild(files))
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
        panel.background = background
        (panel.getComponent(0) as JLabel).foreground = foreground
        panel.border = BorderFactory.createDashedBorder(accent)
    })
}
