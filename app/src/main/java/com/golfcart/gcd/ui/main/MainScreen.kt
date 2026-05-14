package com.golfcart.gcd.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.golfcart.gcd.data.bluetooth.ConnectionState
import com.golfcart.gcd.data.bluetooth.TelemetryData
import com.golfcart.gcd.domain.gps.NavigationData
import com.golfcart.gcd.domain.gps.ProcessedGpsData
import com.golfcart.gcd.domain.odometer.OdometerState
import java.util.Locale

/**
 * Main display screen for the Golf Cart Computer.
 *
 * Displays configurable widgets: speed, heading, time, date, temperature,
 * satellite/HDOP, connection status indicators, odometer, trip odometer,
 * battery voltage, fuel level, headlight mode, and "new data received" indicator.
 *
 * Requirements: 13.1, 13.6, 13.10, 13.11, 8.3, 8.4, 8.5, 8.6
 */
@Composable
fun MainScreen(
    onNavigateToConfig: () -> Unit = {},
    onNavigateToWeather: () -> Unit = {},
    onNavigateToEntertainment: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel()
) {
    val gpsState by viewModel.gpsState.collectAsStateWithLifecycle()
    val navigationData by viewModel.navigationData.collectAsStateWithLifecycle()
    val odometerState by viewModel.odometerState.collectAsStateWithLifecycle()
    val meshtasticState by viewModel.meshtasticConnectionState.collectAsStateWithLifecycle()
    val gciState by viewModel.gciConnectionState.collectAsStateWithLifecycle()
    val telemetryData by viewModel.telemetryData.collectAsStateWithLifecycle()
    val adjustedTemp by viewModel.adjustedTemperature.collectAsStateWithLifecycle()
    val newDataReceived by viewModel.newDataReceived.collectAsStateWithLifecycle()

    MainScreenContent(
        gpsState = gpsState,
        navigationData = navigationData,
        odometerState = odometerState,
        meshtasticConnectionState = meshtasticState,
        gciConnectionState = gciState,
        telemetryData = telemetryData,
        adjustedTemperature = adjustedTemp,
        newDataReceived = newDataReceived,
        onNavigateToConfig = onNavigateToConfig
    )
}

/**
 * Stateless content composable for the main display screen.
 * Separated from [MainScreen] for testability and preview support.
 */
@Composable
fun MainScreenContent(
    gpsState: ProcessedGpsData,
    navigationData: NavigationData,
    odometerState: OdometerState,
    meshtasticConnectionState: ConnectionState,
    gciConnectionState: ConnectionState,
    telemetryData: TelemetryData,
    adjustedTemperature: Float,
    newDataReceived: Boolean,
    onNavigateToConfig: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top row: Date, Time, Connection indicators, Settings
            TopStatusRow(
                navigationData = navigationData,
                meshtasticConnectionState = meshtasticConnectionState,
                gciConnectionState = gciConnectionState,
                newDataReceived = newDataReceived,
                onNavigateToConfig = onNavigateToConfig
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Main speed and heading display
            SpeedHeadingWidget(gpsState = gpsState)

            Spacer(modifier = Modifier.height(8.dp))

            // Satellite/HDOP and Temperature row
            SatelliteTemperatureRow(
                gpsState = gpsState,
                adjustedTemperature = adjustedTemperature
            )

            Spacer(modifier = Modifier.height(8.dp))

            HorizontalDivider()

            Spacer(modifier = Modifier.height(8.dp))

            // Odometer row
            OdometerRow(odometerState = odometerState)

            Spacer(modifier = Modifier.height(8.dp))

            HorizontalDivider()

            Spacer(modifier = Modifier.height(8.dp))

            // Telemetry row: battery, fuel, headlight
            TelemetryRow(telemetryData = telemetryData)
        }
    }
}

/**
 * Top status row displaying date, time, connection indicators, and new data indicator.
 * Includes an unobtrusive settings icon for navigation to the configuration screen.
 *
 * Requirement 13.4: Configuration screen accessible from main display with unobtrusive navigation.
 */
@Composable
private fun TopStatusRow(
    navigationData: NavigationData,
    meshtasticConnectionState: ConnectionState,
    gciConnectionState: ConnectionState,
    newDataReceived: Boolean,
    onNavigateToConfig: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Date and time
        Column {
            Text(
                text = navigationData.dateString,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = navigationData.timeString,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Connection status indicators, new data indicator, and settings icon
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // New data received indicator
            if (newDataReceived) {
                NewDataIndicator()
            }

            // Meshtastic connection indicator
            ConnectionIndicator(
                label = "Mesh",
                connectionState = meshtasticConnectionState
            )

            // GCI connection indicator
            ConnectionIndicator(
                label = "GCI",
                connectionState = gciConnectionState
            )

            // Unobtrusive settings icon for config navigation
            IconButton(
                onClick = onNavigateToConfig,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Large speed display with heading direction.
 */
@Composable
private fun SpeedHeadingWidget(gpsState: ProcessedGpsData) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Speed in large text
        Text(
            text = "${gpsState.speedMph}",
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Text(
            text = "MPH",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Heading direction
        Text(
            text = gpsState.cardinalDirection,
            fontSize = 32.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Row displaying satellite/HDOP info and outdoor temperature.
 */
@Composable
private fun SatelliteTemperatureRow(
    gpsState: ProcessedGpsData,
    adjustedTemperature: Float
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Satellite/HDOP display
        Column {
            Text(
                text = "SAT/HDOP",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = gpsState.satelliteHdopDisplay,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Temperature display with offset applied
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "TEMP",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = String.format(Locale.US, "%.0f°F", adjustedTemperature),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

/**
 * Row displaying odometer and trip odometer with 1 decimal place.
 *
 * Requirement 6.3: Display both odometer and trip odometer with 1 decimal place precision.
 */
@Composable
private fun OdometerRow(odometerState: OdometerState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Total odometer
        Column {
            Text(
                text = "ODOMETER",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = String.format(Locale.US, "%.1f mi", odometerState.totalMiles),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Trip odometer
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "TRIP",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = String.format(Locale.US, "%.1f mi", odometerState.tripMiles),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

/**
 * Row displaying GCI telemetry: battery voltage, fuel level, headlight mode.
 *
 * Requirements: 8.3 (battery voltage), 8.4 (fuel level), 8.6 (headlight mode)
 */
@Composable
private fun TelemetryRow(telemetryData: TelemetryData) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Battery voltage
        Column {
            Text(
                text = "BATTERY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = String.format(Locale.US, "%.1fV", telemetryData.batteryVoltage),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Fuel level
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "FUEL",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = String.format(Locale.US, "%.0f%%", telemetryData.fuelLevel),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Headlight mode
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "LIGHTS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = headlightModeLabel(telemetryData.headlightMode),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

/**
 * Connection status indicator showing a colored dot and label.
 *
 * Requirement 13.6: Display connection status indicators for both Meshtastic and GCI.
 * The indicators are independent of each other.
 */
@Composable
private fun ConnectionIndicator(
    label: String,
    connectionState: ConnectionState
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(connectionStateColor(connectionState))
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * "New data received" visual indicator.
 *
 * Requirement 13.10, 13.11: Visual indicator when fresh data arrives, auto-clears after 5 seconds.
 */
@Composable
private fun NewDataIndicator() {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(Color(0xFF4CAF50)) // Green indicator
    )
}

/**
 * Maps a [ConnectionState] to a display color.
 *
 * - READY: Green (connected and operational)
 * - CONNECTED, HANDSHAKING: Yellow (connected but not fully ready)
 * - SCANNING, CONNECTING: Blue (attempting connection)
 * - DISCONNECTED: Red (no connection)
 */
private fun connectionStateColor(state: ConnectionState): Color {
    return when (state) {
        ConnectionState.READY -> Color(0xFF4CAF50)          // Green
        ConnectionState.CONNECTED,
        ConnectionState.HANDSHAKING -> Color(0xFFFFC107)    // Amber/Yellow
        ConnectionState.SCANNING,
        ConnectionState.CONNECTING -> Color(0xFF2196F3)     // Blue
        ConnectionState.DISCONNECTED -> Color(0xFFF44336)   // Red
    }
}

/**
 * Maps a headlight mode integer to a human-readable label.
 *
 * Requirement 8.6: Display headlight mode status.
 */
private fun headlightModeLabel(mode: Int): String {
    return when (mode) {
        0 -> "OFF"
        1 -> "LOW"
        2 -> "HIGH"
        else -> "M$mode"
    }
}
