package com.golfcart.gcd.ui.config

import android.bluetooth.BluetoothAdapter
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.golfcart.gcd.data.bluetooth.ConnectionState
import com.golfcart.gcd.data.persistence.UserPreferences
import java.util.Locale

/**
 * Configuration screen for the Golf Cart Computer.
 *
 * Provides controls for all user-configurable settings, device information,
 * and system actions (pairing, reboot, reset).
 *
 * Requirements: 13.4, 13.5, 13.7, 13.8, 13.9, 12.1, 12.2, 12.7
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    onNavigateBack: () -> Unit,
    viewModel: ConfigViewModel = hiltViewModel()
) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val meshtasticState by viewModel.meshtasticConnectionState.collectAsStateWithLifecycle()
    val gciState by viewModel.gciConnectionState.collectAsStateWithLifecycle()
    val meshtasticNodeId by viewModel.meshtasticNodeId.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val pairingInProgress by viewModel.pairingInProgress.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // Show status messages via snackbar
    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuration") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        ConfigScreenContent(
            modifier = Modifier.padding(paddingValues),
            preferences = preferences,
            meshtasticConnectionState = meshtasticState,
            gciConnectionState = gciState,
            meshtasticNodeId = meshtasticNodeId,
            pairingInProgress = pairingInProgress,
            appVersion = viewModel.appVersion,
            onDayBrightnessChange = viewModel::setDayBrightness,
            onNightBrightnessChange = viewModel::setNightBrightness,
            onSpeakerVolumeChange = viewModel::setSpeakerVolume,
            onFlipScreenChange = viewModel::setFlipScreen,
            onBacklightTimeoutChange = viewModel::setBacklightTimeout,
            onTemperatureOffsetChange = viewModel::setTemperatureOffset,
            onServiceIntervalChange = viewModel::setServiceInterval,
            onMeshtasticEnabledChange = viewModel::setMeshtasticEnabled,
            onSetHomeLocation = viewModel::setHomeLocation,
            onClearHomeLocation = viewModel::clearHomeLocation,
            onStartGciPairing = viewModel::startGciPairing,
            onRebootRadio = viewModel::rebootMeshtasticRadio,
            onResetPreferences = viewModel::resetAllPreferences
        )
    }
}

/**
 * Stateless content composable for the configuration screen.
 * Separated from [ConfigScreen] for testability and preview support.
 */
@Composable
fun ConfigScreenContent(
    modifier: Modifier = Modifier,
    preferences: UserPreferences,
    meshtasticConnectionState: ConnectionState,
    gciConnectionState: ConnectionState,
    meshtasticNodeId: String,
    pairingInProgress: Boolean,
    appVersion: String,
    onDayBrightnessChange: (Int) -> Unit,
    onNightBrightnessChange: (Int) -> Unit,
    onSpeakerVolumeChange: (Int) -> Unit,
    onFlipScreenChange: (Boolean) -> Unit,
    onBacklightTimeoutChange: (Int) -> Unit,
    onTemperatureOffsetChange: (Float) -> Unit,
    onServiceIntervalChange: (Int) -> Unit,
    onMeshtasticEnabledChange: (Boolean) -> Unit,
    onSetHomeLocation: () -> Unit,
    onClearHomeLocation: () -> Unit,
    onStartGciPairing: () -> Unit,
    onRebootRadio: () -> Unit,
    onResetPreferences: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // --- Display Settings ---
        SectionHeader("Display Settings")

        SliderSetting(
            label = "Day Brightness",
            value = preferences.dayBrightness,
            range = 0..10,
            onValueChange = onDayBrightnessChange
        )

        SliderSetting(
            label = "Night Brightness",
            value = preferences.nightBrightness,
            range = 0..10,
            onValueChange = onNightBrightnessChange
        )

        SpinnerSetting(
            label = "Backlight Timeout",
            value = preferences.backlightTimeoutMinutes,
            suffix = "min",
            range = 0..60,
            step = 1,
            onValueChange = onBacklightTimeoutChange
        )

        ToggleSetting(
            label = "Flip Screen",
            checked = preferences.flipScreen,
            onCheckedChange = onFlipScreenChange
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // --- Audio Settings ---
        SectionHeader("Audio Settings")

        SliderSetting(
            label = "Speaker Volume",
            value = preferences.speakerVolume,
            range = 0..20,
            onValueChange = onSpeakerVolumeChange
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // --- Sensor Settings ---
        SectionHeader("Sensor Settings")

        FloatSpinnerSetting(
            label = "Temperature Offset",
            value = preferences.temperatureOffset,
            suffix = "°F",
            range = -20f..20f,
            step = 0.5f,
            onValueChange = onTemperatureOffsetChange
        )

        SpinnerSetting(
            label = "Service Interval",
            value = preferences.serviceIntervalHours,
            suffix = "hrs",
            range = 10..500,
            step = 10,
            onValueChange = onServiceIntervalChange
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // --- Home Location ---
        SectionHeader("Home Location")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSetHomeLocation,
                modifier = Modifier.weight(1f)
            ) {
                Text("Set Home")
            }
            Button(
                onClick = onClearHomeLocation,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Text("Clear Home")
            }
        }

        if (preferences.homeLatitude != null && preferences.homeLongitude != null) {
            Text(
                text = String.format(
                    Locale.US,
                    "Home: %.5f, %.5f",
                    preferences.homeLatitude,
                    preferences.homeLongitude
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // --- GCI Connection ---
        SectionHeader("GCI Telemetry")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Status: ${connectionStateLabel(gciConnectionState)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onStartGciPairing,
                enabled = !pairingInProgress
            ) {
                Text(if (pairingInProgress) "Pairing..." else "Pair GCI")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // --- Meshtastic Settings ---
        SectionHeader("Meshtastic Radio")

        ToggleSetting(
            label = "Meshtastic Enabled",
            checked = preferences.meshtasticEnabled,
            onCheckedChange = onMeshtasticEnabledChange
        )

        Text(
            text = "Status: ${connectionStateLabel(meshtasticConnectionState)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Display connected Meshtastic radio node ID in hex format
        if (meshtasticNodeId.isNotEmpty()) {
            Text(
                text = "Node ID: $meshtasticNodeId",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Button(
            onClick = onRebootRadio,
            enabled = meshtasticConnectionState == ConnectionState.READY
        ) {
            Text("Reboot Radio")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // --- System ---
        SectionHeader("System")

        Button(
            onClick = onResetPreferences,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reset All Preferences")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Manual reboot option
        Button(
            onClick = { Runtime.getRuntime().exit(0) },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Reboot App")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // --- Device Info ---
        SectionHeader("Device Info")

        Text(
            text = "App Version: $appVersion",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "Device: ${Build.MODEL} (${Build.MANUFACTURER})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "MAC: ${getDeviceMacAddress()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// --- Reusable Setting Components ---

/**
 * Section header text for grouping related settings.
 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

/**
 * Slider setting with label and current value display.
 */
@Composable
private fun SliderSetting(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "$value",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Integer spinner setting with increment/decrement buttons.
 */
@Composable
private fun SpinnerSetting(
    label: String,
    value: Int,
    suffix: String,
    range: IntRange,
    step: Int,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = { onValueChange((value - step).coerceIn(range)) },
                enabled = value > range.first
            ) {
                Text("-")
            }
            Text(
                text = "$value $suffix",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(64.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Button(
                onClick = { onValueChange((value + step).coerceIn(range)) },
                enabled = value < range.last
            ) {
                Text("+")
            }
        }
    }
}

/**
 * Float spinner setting with increment/decrement buttons.
 */
@Composable
private fun FloatSpinnerSetting(
    label: String,
    value: Float,
    suffix: String,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = { onValueChange((value - step).coerceIn(range)) },
                enabled = value > range.start
            ) {
                Text("-")
            }
            Text(
                text = String.format(Locale.US, "%.1f %s", value, suffix),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(80.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Button(
                onClick = { onValueChange((value + step).coerceIn(range)) },
                enabled = value < range.endInclusive
            ) {
                Text("+")
            }
        }
    }
}

/**
 * Toggle (switch) setting with label.
 */
@Composable
private fun ToggleSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * Maps a [ConnectionState] to a human-readable label.
 */
private fun connectionStateLabel(state: ConnectionState): String {
    return when (state) {
        ConnectionState.DISCONNECTED -> "Disconnected"
        ConnectionState.SCANNING -> "Scanning..."
        ConnectionState.CONNECTING -> "Connecting..."
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.HANDSHAKING -> "Handshaking..."
        ConnectionState.READY -> "Ready"
    }
}

/**
 * Attempts to retrieve the device Bluetooth MAC address.
 * Returns a placeholder if unavailable (Android 6.0+ restricts access).
 *
 * Requirement 13.8: Display device MAC/identifier.
 */
private fun getDeviceMacAddress(): String {
    return try {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        adapter?.address ?: Build.SERIAL.takeIf { it != Build.UNKNOWN } ?: Build.ID
    } catch (_: SecurityException) {
        Build.SERIAL.takeIf { it != Build.UNKNOWN } ?: Build.ID
    }
}
