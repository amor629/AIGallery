package com.example.aigallery.ui.edit

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * AI 编辑完成后的保存方式选择弹窗
 *
 * 展示处理后的预览图，并让用户在"另存为新图片"和"覆盖原图"之间选择，
 * 而不是自动落盘——避免用户在不知情的情况下丢失原图。
 */
@Composable
fun AiEditResultDialog(
    bitmap: Bitmap,
    onSaveAsNew: () -> Unit,
    onOverwrite: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E))
        ) {
            Column {
                Text(
                    "AI 编辑完成",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.padding(top = 20.dp, start = 20.dp, end = 20.dp)
                )
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .padding(16.dp)
                )
                Text(
                    "请选择保存方式",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    TextButton(
                        onClick = onSaveAsNew,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("另存为新图片", modifier = Modifier.padding(vertical = 4.dp))
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    TextButton(
                        onClick = onOverwrite,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("覆盖原图", modifier = Modifier.padding(vertical = 4.dp))
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "取消",
                            color = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
