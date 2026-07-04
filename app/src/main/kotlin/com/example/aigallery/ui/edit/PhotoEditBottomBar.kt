package com.example.aigallery.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.aigallery.domain.model.LocalEditMode
import com.example.aigallery.domain.model.MediaType

@Composable
fun PhotoEditBottomBar(
    mediaType: MediaType,
    onAiEditClick: () -> Unit,
    onLocalEditClick: (LocalEditMode) -> Unit,
    onVideoTrimClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                )
            )
            .navigationBarsPadding()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (mediaType == MediaType.IMAGE) {
            FilledTonalButton(
                onClick = onAiEditClick,
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("AI 编辑", modifier = Modifier.padding(start = 4.dp))
            }
            LocalEditButton(Icons.Default.GridOn, "马赛克") { onLocalEditClick(LocalEditMode.MOSAIC) }
            LocalEditButton(Icons.Default.Crop, "裁剪") { onLocalEditClick(LocalEditMode.CROP) }
            LocalEditButton(Icons.Default.Brush, "涂鸦") { onLocalEditClick(LocalEditMode.DOODLE) }
        } else {
            FilledTonalButton(
                onClick = onVideoTrimClick,
                shape = RoundedCornerShape(20.dp)
            ) {
                Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("截取", modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun LocalEditButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(20.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(label, modifier = Modifier.padding(start = 2.dp))
    }
}
