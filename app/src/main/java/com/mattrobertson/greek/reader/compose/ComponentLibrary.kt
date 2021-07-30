package com.mattrobertson.greek.reader.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mattrobertson.greek.reader.compose.previews.FakeChipRowProvider

@Composable
fun DragHandle() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .width(24.dp)
                .height(4.dp),
            shape = RoundedCornerShape(2.dp),
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.2f)
        ) {}
    }
}

@Composable
fun Chip(
    title: String,
    selected: Boolean,
    onSelected: () -> Unit,
    outlineColor: Color,
    backgroundColor: Color,
    textColor: Color
) {
    Surface(
        color = if (selected) backgroundColor else Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .clickable {
                onSelected()
            }
            .border(
                width = 1.dp,
                color = outlineColor,
                shape = RoundedCornerShape(16.dp)
            )
            .defaultMinSize(
                minHeight = 32.dp,
                minWidth = 60.dp
            )
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                fontFamily = FontFamily.SansSerif,
                color = textColor,
                textAlign = TextAlign.Center,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
fun ScrollableChipRow(
    items: List<String>,
    onItemSelected: (index: Int) -> Unit = {},
    outlineColor: Color,
    backgroundColor: Color,
    textColor: Color
) {
    var selectedIndex by remember { mutableStateOf(0) }

    LazyRow(
        modifier = Modifier.fillMaxWidth()
    ) {
        itemsIndexed(items) { index, item ->
            Chip(
                title = item,
                selected = (index == selectedIndex),
                onSelected = {
                    selectedIndex = index
                    onItemSelected(index)
                },
                outlineColor = outlineColor,
                backgroundColor = backgroundColor,
                textColor = textColor
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}
