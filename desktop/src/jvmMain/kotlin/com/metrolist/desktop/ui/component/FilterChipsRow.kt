/**
 * Metrolist Desktop — FilterChipsRow component
 * Material 3 Expressive filter chips row for quick search and library categorization
 */

package com.metrolist.desktop.ui.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun <T> FilterChipsRow(
    chips: List<T>,
    selectedChip: T?,
    onChipSelected: (T?) -> Unit,
    chipLabel: (T) -> String,
    modifier: Modifier = Modifier,
    allLabel: String = "All",
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "All" chip
        FilterChip(
            selected = selectedChip == null,
            onClick = { onChipSelected(null) },
            label = { Text(allLabel, style = MaterialTheme.typography.labelMedium) },
            shape = RoundedCornerShape(12.dp),
            leadingIcon = if (selectedChip == null) {
                {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else null,
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        )

        chips.forEach { chip ->
            val isSelected = selectedChip == chip
            FilterChip(
                selected = isSelected,
                onClick = {
                    if (isSelected) onChipSelected(null) else onChipSelected(chip)
                },
                label = { Text(chipLabel(chip), style = MaterialTheme.typography.labelMedium) },
                shape = RoundedCornerShape(12.dp),
                leadingIcon = if (isSelected) {
                    {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}
