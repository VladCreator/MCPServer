package com.vlad.mcpserver

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Icon
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath

/**
 * Renders the Compose [toolIcon] of a tool into a white alpha bitmap so the
 * notification (and the Live Update status bar chip) shows the same icon as
 * the dashboard.
 */
object ToolIconBitmap {
    private const val SIZE_PX = 96
    private val cache = HashMap<String, Icon>()

    @Synchronized
    fun icon(toolName: String): Icon = cache.getOrPut(toolName) {
        Icon.createWithBitmap(render(toolIcon(toolName)))
    }

    private fun render(vector: ImageVector): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        val matrix = Matrix().apply {
            setScale(SIZE_PX / vector.viewportWidth, SIZE_PX / vector.viewportHeight)
        }
        drawGroup(canvas, vector.root, matrix, paint)
        return bitmap
    }

    private fun drawGroup(canvas: Canvas, group: VectorGroup, parent: Matrix, paint: Paint) {
        val matrix = Matrix(parent).apply {
            preTranslate(group.translationX + group.pivotX, group.translationY + group.pivotY)
            preRotate(group.rotation)
            preScale(group.scaleX, group.scaleY)
            preTranslate(-group.pivotX, -group.pivotY)
        }
        for (node in group) {
            when (node) {
                is VectorGroup -> drawGroup(canvas, node, matrix, paint)
                is VectorPath -> {
                    val path = Path(node.pathData.toPath().asAndroidPath()).apply {
                        fillType = if (node.pathFillType == PathFillType.EvenOdd) {
                            Path.FillType.EVEN_ODD
                        } else {
                            Path.FillType.WINDING
                        }
                        transform(matrix)
                    }
                    paint.alpha = (node.fillAlpha * 255).toInt().coerceIn(0, 255)
                    canvas.drawPath(path, paint)
                }
            }
        }
    }
}
