package com.golfcart.gcd.ui.entertainment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.golfcart.gcd.domain.parser.VenueEvent

/**
 * Entertainment screen for the Golf Cart Computer.
 *
 * Displays venue/event data in a scrollable two-column table (venue name | event name),
 * supports up to 12 entries, and shows the timestamp of last data receipt.
 *
 * Requirements: 4.3, 4.4, 4.5, 4.6, 4.7, 4.8
 */
@Composable
fun EntertainmentScreen(
    viewModel: EntertainmentViewModel = hiltViewModel()
) {
    val entertainmentData by viewModel.entertainmentData.collectAsStateWithLifecycle()

    EntertainmentScreenContent(entertainmentData = entertainmentData)
}

/**
 * Stateless content composable for the entertainment screen.
 * Separated from [EntertainmentScreen] for testability and preview support.
 */
@Composable
fun EntertainmentScreenContent(
    entertainmentData: EntertainmentData?
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (entertainmentData != null) {
            EntertainmentDataDisplay(entertainmentData = entertainmentData)
        } else {
            NoEntertainmentDataDisplay()
        }
    }
}

/**
 * Displays the venue/event data: header, scrollable table, and timestamp.
 */
@Composable
private fun EntertainmentDataDisplay(entertainmentData: EntertainmentData) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        EntertainmentHeader(isStored = entertainmentData.isStored)

        Spacer(modifier = Modifier.height(12.dp))

        HorizontalDivider()

        // Table header row
        TableHeaderRow()

        HorizontalDivider()

        // Scrollable venue/event table (up to 12 entries)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(entertainmentData.venues) { venueEvent ->
                VenueEventRow(venueEvent = venueEvent)
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        HorizontalDivider()

        Spacer(modifier = Modifier.height(8.dp))

        // Timestamp of last venue/event data receipt
        TimestampDisplay(
            timestamp = entertainmentData.receivedTimestamp,
            isStored = entertainmentData.isStored
        )
    }
}

/**
 * Displays the entertainment screen header.
 */
@Composable
private fun EntertainmentHeader(isStored: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "ENTERTAINMENT",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        if (isStored) {
            Text(
                text = "(stored)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Displays the table header row with column labels.
 *
 * Requirement 4.3: Two-column table with venue names in column 1 and event names in column 2.
 */
@Composable
private fun TableHeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Venue",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "Event",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Displays a single venue/event row in the two-column table.
 *
 * Requirement 4.3: Scrollable two-column table (venue name | event name).
 * Requirement 4.4: Support up to 12 entries.
 */
@Composable
private fun VenueEventRow(venueEvent: VenueEvent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = venueEvent.venueName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = venueEvent.eventName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Displays the timestamp of last venue/event data receipt.
 *
 * Requirement 4.5: Display the timestamp when venue/event data was last received.
 */
@Composable
private fun TimestampDisplay(
    timestamp: String,
    isStored: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Last updated: $timestamp",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isStored) {
            Text(
                text = " (stored)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Displays a placeholder when no venue/event data is available.
 */
@Composable
private fun NoEntertainmentDataDisplay() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "🎵",
            fontSize = 48.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "No Entertainment Data",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Waiting for venue/event data from mesh network...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
