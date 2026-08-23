package com.example.lrmprotokoll.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lrmprotokoll.R
import java.text.SimpleDateFormat
import java.util.*

const val MARK_NOISE_EVENT_SHEET_TAG = "mark_noise_event_sheet"
const val SAVE_NOISE_EVENT_BUTTON_TAG = "save_noise_event_button"

data class NoiseCategory(
    val id: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
)

val DEFAULT_NOISE_CATEGORIES = listOf(
    NoiseCategory("hammering", R.string.category_hammering, AppIcons.Hammer),
    NoiseCategory("drilling", R.string.category_drilling, AppIcons.Drill),
    NoiseCategory("footsteps", R.string.category_footsteps, AppIcons.Footsteps),
    NoiseCategory("voices", R.string.category_voices, AppIcons.Voices),
    NoiseCategory("music", R.string.category_music, AppIcons.Music),
    NoiseCategory("traffic", R.string.category_traffic, AppIcons.Traffic),
    NoiseCategory("dogs", R.string.category_dogs, AppIcons.Dogs),
    NoiseCategory("alarms", R.string.category_alarms, AppIcons.Alarms),
    NoiseCategory("other", R.string.category_other, AppIcons.Other),
)

/**
 * Modernes "Mark noise event" Bottom Sheet Modal nach Screen 3 des neuen Designs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkNoiseEventBottomSheet(
    currentDb: Double?,
    currentWeighting: String = "dB(A)",
    onSaveEvent: (category: String, note: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var selectedCategoryId by remember { mutableStateOf("hammering") }
    var noteText by remember { mutableStateOf("") }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val currentTimeStr = remember { timeFormat.format(Date()) }
    val dbText = remember(currentDb) {
        if (currentDb != null && currentDb > 0) String.format(Locale.US, "%.1f", currentDb) else "--.-"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = modifier.testTag(MARK_NOISE_EVENT_SHEET_TAG)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.cockpit_mark_noise_event),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "🕒 $currentTimeStr · $dbText $currentWeighting",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 3x3 Kategorie Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
            ) {
                items(DEFAULT_NOISE_CATEGORIES, key = { it.id }) { cat ->
                    val isSelected = cat.id == selectedCategoryId
                    val bg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    val label = stringResource(cat.labelRes)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(bg)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedCategoryId = cat.id }
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = cat.icon,
                            contentDescription = label,
                            tint = contentColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = contentColor,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Notiz-Eingabe
            Text(
                text = stringResource(R.string.mark_event_add_note),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                placeholder = { Text(stringResource(R.string.mark_event_note_placeholder)) },
                minLines = 2,
                maxLines = 3,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Speichern CTA Button
            Button(
                onClick = {
                    val catObj = DEFAULT_NOISE_CATEGORIES.find { it.id == selectedCategoryId }
                    val catLabel = catObj?.let { context.getString(it.labelRes) } ?: selectedCategoryId
                    onSaveEvent(catLabel, noteText.trim())
                    onDismiss()
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag(SAVE_NOISE_EVENT_BUTTON_TAG)
            ) {
                Text(
                    text = stringResource(R.string.mark_event_save_button),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}
