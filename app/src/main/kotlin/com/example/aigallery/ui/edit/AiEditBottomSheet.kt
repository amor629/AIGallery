package com.example.aigallery.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.aigallery.domain.model.AiEditType

private data class AiEditItem(val type: AiEditType, val icon: ImageVector, val desc: String)

private val AI_EDIT_ITEMS = listOf(
    AiEditItem(AiEditType.CAPTION, Icons.Default.Edit, "生成适合朋友圈的文案"),
    AiEditItem(AiEditType.RECOGNIZE, Icons.Default.ImageSearch, "识别图片内容与文字"),
    AiEditItem(AiEditType.RESTORE, Icons.Default.HistoryEdu, "修复泛黄、模糊的老照片"),
    AiEditItem(AiEditType.BEAUTIFY, Icons.Default.Face, "极致美颜，颜值全面提升")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiEditBottomSheet(
    onDismiss: () -> Unit,
    onSelect: (AiEditType) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Text("AI 编辑", style = MaterialTheme.typography.titleMedium)
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(AI_EDIT_ITEMS, key = { it.type.name }) { item ->
                    ListItem(
                        headlineContent = { Text(item.type.label, style = MaterialTheme.typography.bodyMedium) },
                        supportingContent = { Text(item.desc, style = MaterialTheme.typography.bodySmall) },
                        leadingContent = {
                            Icon(item.icon, contentDescription = null, modifier = Modifier.padding(4.dp))
                        },
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .clickable { onSelect(item.type) }
                    )
                }
            }
        }
    }
}
