package com.example.aigallery.ui.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.aigallery.data.image.ImageProcessor
import kotlin.math.max
import kotlin.math.min

/**
 * 涂抹选区编辑器：手指涂抹标记区域，实时预览像素化（马赛克）效果
 */
@Composable
fun BrushMaskEditor(
    bitmap: Bitmap,
    hint: String,
    modifier: Modifier = Modifier,
    onSave: (Bitmap) -> Unit
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val brushRadius = 28f

    // 预先计算整图像素化版本，涂抹预览时直接裁切显示，所见即所得
    val pixelatedImage = remember(bitmap) {
        ImageProcessor.pixelate(bitmap, 28).asImageBitmap()
    }

    Column(modifier = modifier) {
        Text(
            hint,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset -> currentStroke = listOf(offset) },
                        onDrag = { change, _ ->
                            currentStroke = currentStroke + change.position
                            change.consume()
                        },
                        onDragEnd = {
                            if (currentStroke.isNotEmpty()) {
                                strokes.add(currentStroke)
                                currentStroke = emptyList()
                            }
                        }
                    )
                }
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            if (viewSize.width > 0) {
                ComposeCanvas(Modifier.fillMaxSize()) {
                    val allStrokes = strokes + listOf(currentStroke).filter { it.isNotEmpty() }
                    val path = Path()
                    allStrokes.forEach { stroke ->
                        stroke.forEach { pt -> path.addOval(Rect(center = pt, radius = brushRadius)) }
                    }
                    clipPath(path) {
                        drawImage(
                            image = pixelatedImage,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt())
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = {
                val mask = buildMaskBitmap(bitmap.width, bitmap.height, viewSize, strokes, brushRadius)
                onSave(mask)
            }) {
                Icon(Icons.Default.Check, contentDescription = "确定", tint = Color.White)
            }
        }
    }
}

private fun buildMaskBitmap(
    bmpW: Int, bmpH: Int, viewSize: IntSize,
    strokes: List<List<Offset>>, brushRadius: Float
): Bitmap {
    val mask = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(mask)
    val paint = Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    val scaleX = bmpW.toFloat() / max(viewSize.width, 1)
    val scaleY = bmpH.toFloat() / max(viewSize.height, 1)
    strokes.forEach { stroke ->
        stroke.forEach { pt ->
            canvas.drawCircle(pt.x * scaleX, pt.y * scaleY, brushRadius * min(scaleX, scaleY), paint)
        }
    }
    return mask
}
