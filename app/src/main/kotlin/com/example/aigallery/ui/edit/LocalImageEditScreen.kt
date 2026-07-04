package com.example.aigallery.ui.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aigallery.domain.model.LocalEditMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 本地图片编辑页：马赛克 / 裁剪 / 涂鸦（纯离线，无需联网）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalImageEditScreen(
    sourceUri: Uri,
    mode: LocalEditMode,
    onNavigateBack: () -> Unit,
    viewModel: ImageEditViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(sourceUri) { viewModel.loadImage(sourceUri) }

    LaunchedEffect(uiState) {
        when (val s = uiState) {
            is ImageEditUiState.Saved -> {
                Toast.makeText(context, s.message, Toast.LENGTH_SHORT).show()
                onNavigateBack()
            }
            is ImageEditUiState.Error -> {
                Toast.makeText(context, s.message, Toast.LENGTH_SHORT).show()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(mode.label) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.85f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                ),
                modifier = Modifier.statusBarsPadding()
            )
        },
        containerColor = Color.Black
    ) { padding ->
        when (val s = uiState) {
            is ImageEditUiState.Loading, is ImageEditUiState.Idle -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            is ImageEditUiState.Ready -> {
                when (mode) {
                    LocalEditMode.CROP -> CropEditor(
                        bitmap = s.bitmap,
                        modifier = Modifier.fillMaxSize().padding(padding),
                        onSave = { l, t, w, h -> viewModel.saveCrop(l, t, w, h) }
                    )
                    LocalEditMode.DOODLE -> DoodleEditor(
                        bitmap = s.bitmap,
                        modifier = Modifier.fillMaxSize().padding(padding),
                        onSave = { layer -> viewModel.saveDoodle(layer) }
                    )
                    LocalEditMode.MOSAIC -> BrushMaskEditor(
                        bitmap = s.bitmap,
                        hint = "涂抹需要打马赛克的区域",
                        modifier = Modifier.fillMaxSize().padding(padding),
                        onSave = { mask -> viewModel.saveMosaic(mask) }
                    )
                }
            }
            else -> {}
        }
    }
}

@Composable
private fun DoodleEditor(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    onSave: (Bitmap) -> Unit
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val paths = remember { mutableStateListOf<Pair<List<Offset>, Color>>() }
    var currentPath by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var strokeColor by remember { mutableStateOf(Color.Red) }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset -> currentPath = listOf(offset) },
                        onDrag = { change, _ ->
                            currentPath = currentPath + change.position
                            change.consume()
                        },
                        onDragEnd = {
                            if (currentPath.size > 1) {
                                paths.add(currentPath to strokeColor)
                            }
                            currentPath = emptyList()
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
            Canvas(Modifier.fillMaxSize()) {
                paths.forEach { (pts, color) ->
                    for (i in 1 until pts.size) {
                        drawLine(color, pts[i - 1], pts[i], strokeWidth = 6f)
                    }
                }
                if (currentPath.size > 1) {
                    for (i in 1 until currentPath.size) {
                        drawLine(strokeColor, currentPath[i - 1], currentPath[i], strokeWidth = 6f)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Color.Red, Color.Yellow, Color.White, Color.Cyan).forEach { c ->
                    Box(
                        modifier = Modifier
                            .background(c, shape = MaterialTheme.shapes.small)
                            .clickable { strokeColor = c }
                            .padding(12.dp)
                    )
                }
            }
            IconButton(onClick = {
                val layer = buildDoodleLayer(bitmap.width, bitmap.height, viewSize, paths)
                onSave(layer)
            }) {
                Icon(Icons.Default.Check, contentDescription = "保存", tint = Color.White)
            }
        }
    }
}

/** 裁剪框拖拽命中目标 */
private enum class CropHandle { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

@Composable
private fun CropEditor(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    onSave: (Int, Int, Int, Int) -> Unit
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var cropLeft by remember { mutableFloatStateOf(0.1f) }
    var cropTop by remember { mutableFloatStateOf(0.1f) }
    var cropRight by remember { mutableFloatStateOf(0.9f) }
    var cropBottom by remember { mutableFloatStateOf(0.9f) }
    var activeHandle by remember { mutableStateOf(CropHandle.NONE) }

    Column(modifier = modifier) {
        Text(
            "拖动边框或四角调整裁剪区域",
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
                        onDragStart = { offset ->
                            val w = viewSize.width.toFloat().coerceAtLeast(1f)
                            val h = viewSize.height.toFloat().coerceAtLeast(1f)
                            activeHandle = nearestHandle(
                                offset,
                                cropLeft * w, cropTop * h, cropRight * w, cropBottom * h,
                                slop = 56f
                            )
                        },
                        onDrag = { change, drag ->
                            val w = viewSize.width.toFloat().coerceAtLeast(1f)
                            val h = viewSize.height.toFloat().coerceAtLeast(1f)
                            when (activeHandle) {
                                CropHandle.TOP_LEFT -> {
                                    cropLeft = (cropLeft + drag.x / w).coerceIn(0f, cropRight - 0.1f)
                                    cropTop = (cropTop + drag.y / h).coerceIn(0f, cropBottom - 0.1f)
                                }
                                CropHandle.TOP_RIGHT -> {
                                    cropRight = (cropRight + drag.x / w).coerceIn(cropLeft + 0.1f, 1f)
                                    cropTop = (cropTop + drag.y / h).coerceIn(0f, cropBottom - 0.1f)
                                }
                                CropHandle.BOTTOM_LEFT -> {
                                    cropLeft = (cropLeft + drag.x / w).coerceIn(0f, cropRight - 0.1f)
                                    cropBottom = (cropBottom + drag.y / h).coerceIn(cropTop + 0.1f, 1f)
                                }
                                CropHandle.BOTTOM_RIGHT -> {
                                    cropRight = (cropRight + drag.x / w).coerceIn(cropLeft + 0.1f, 1f)
                                    cropBottom = (cropBottom + drag.y / h).coerceIn(cropTop + 0.1f, 1f)
                                }
                                CropHandle.MOVE -> {
                                    val dx = drag.x / w
                                    val dy = drag.y / h
                                    val rectW = cropRight - cropLeft
                                    val rectH = cropBottom - cropTop
                                    val newLeft = (cropLeft + dx).coerceIn(0f, 1f - rectW)
                                    val newTop = (cropTop + dy).coerceIn(0f, 1f - rectH)
                                    cropLeft = newLeft
                                    cropRight = newLeft + rectW
                                    cropTop = newTop
                                    cropBottom = newTop + rectH
                                }
                                CropHandle.NONE -> {}
                            }
                            change.consume()
                        },
                        onDragEnd = { activeHandle = CropHandle.NONE }
                    )
                }
        ) {
            // 底层：完整显示原图，不被任何遮罩清除，确保裁剪区域始终清晰可见
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            if (viewSize.width > 0) {
                Canvas(Modifier.fillMaxSize()) {
                    val left = cropLeft * size.width
                    val top = cropTop * size.height
                    val right = cropRight * size.width
                    val bottom = cropBottom * size.height
                    val overlayColor = Color.Black.copy(alpha = 0.55f)

                    // 仅暗化裁剪框外的四条边带，裁剪框内区域完全不绘制遮罩，图片始终清晰
                    drawRect(overlayColor, topLeft = Offset(0f, 0f), size = Size(size.width, top))
                    drawRect(overlayColor, topLeft = Offset(0f, bottom), size = Size(size.width, size.height - bottom))
                    drawRect(overlayColor, topLeft = Offset(0f, top), size = Size(left, bottom - top))
                    drawRect(overlayColor, topLeft = Offset(right, top), size = Size(size.width - right, bottom - top))

                    // 裁剪框边框
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // 三等分构图辅助线
                    val thirdW = (right - left) / 3f
                    val thirdH = (bottom - top) / 3f
                    val gridColor = Color.White.copy(alpha = 0.5f)
                    for (i in 1..2) {
                        drawLine(gridColor, Offset(left + thirdW * i, top), Offset(left + thirdW * i, bottom), strokeWidth = 1.dp.toPx())
                        drawLine(gridColor, Offset(left, top + thirdH * i), Offset(right, top + thirdH * i), strokeWidth = 1.dp.toPx())
                    }

                    // 四角手柄，提示可拖拽调整
                    val handleLen = 16.dp.toPx()
                    val handleWidth = 3.dp.toPx()
                    listOf(
                        Offset(left, top) to Pair(1f, 1f),
                        Offset(right, top) to Pair(-1f, 1f),
                        Offset(left, bottom) to Pair(1f, -1f),
                        Offset(right, bottom) to Pair(-1f, -1f)
                    ).forEach { (corner, dir) ->
                        val (dx, dy) = dir
                        drawLine(Color.White, corner, corner + Offset(handleLen * dx, 0f), strokeWidth = handleWidth)
                        drawLine(Color.White, corner, corner + Offset(0f, handleLen * dy), strokeWidth = handleWidth)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = {
                val bw = bitmap.width; val bh = bitmap.height
                val l = (cropLeft * bw).toInt()
                val t = (cropTop * bh).toInt()
                val w = ((cropRight - cropLeft) * bw).toInt()
                val h = ((cropBottom - cropTop) * bh).toInt()
                onSave(l, t, w, h)
            }) {
                Icon(Icons.Default.Check, contentDescription = "保存", tint = Color.White)
            }
        }
    }
}

private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = ax - bx; val dy = ay - by
    return sqrt(dx * dx + dy * dy)
}

private fun nearestHandle(
    offset: Offset, left: Float, top: Float, right: Float, bottom: Float, slop: Float
): CropHandle {
    val corners = listOf(
        CropHandle.TOP_LEFT to Offset(left, top),
        CropHandle.TOP_RIGHT to Offset(right, top),
        CropHandle.BOTTOM_LEFT to Offset(left, bottom),
        CropHandle.BOTTOM_RIGHT to Offset(right, bottom)
    )
    val nearest = corners.minByOrNull { distance(offset.x, offset.y, it.second.x, it.second.y) }
    if (nearest != null && distance(offset.x, offset.y, nearest.second.x, nearest.second.y) <= slop) {
        return nearest.first
    }
    return if (offset.x in left..right && offset.y in top..bottom) CropHandle.MOVE else CropHandle.NONE
}

private fun buildDoodleLayer(
    bmpW: Int, bmpH: Int, viewSize: IntSize,
    paths: List<Pair<List<Offset>, Color>>
): Bitmap {
    val layer = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(layer)
    val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 6f * min(
            bmpW.toFloat() / max(viewSize.width, 1),
            bmpH.toFloat() / max(viewSize.height, 1)
        )
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val scaleX = bmpW.toFloat() / max(viewSize.width, 1)
    val scaleY = bmpH.toFloat() / max(viewSize.height, 1)
    paths.forEach { (pts, color) ->
        paint.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
        val path = Path()
        pts.forEachIndexed { i, pt ->
            val x = pt.x * scaleX; val y = pt.y * scaleY
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
    return layer
}
