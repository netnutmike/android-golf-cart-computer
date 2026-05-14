package com.golfcart.gcd.ui.main

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.golfcart.gcd.data.bluetooth.BluetoothPermissionManager

/**
 * Composable that handles Bluetooth runtime permission requests with rationale.
 *
 * Displays a rationale dialog explaining why Bluetooth permissions are needed
 * before requesting them from the system. If permissions are already granted,
 * immediately invokes [onPermissionsGranted].
 *
 * Validates: Requirement 17.6 (request all necessary Android Bluetooth permissions
 * at runtime with appropriate user-facing rationale)
 */
@Composable
fun BluetoothPermissionScreen(
    onPermissionsGranted: () -> Unit,
    onPermissionsDenied: () -> Unit = {}
) {
    var showRationale by remember { mutableStateOf(true) }
    var permissionsRequested by remember { mutableStateOf(false) }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            onPermissionsGranted()
        } else {
            onPermissionsDenied()
        }
    }

    if (showRationale && !permissionsRequested) {
        BluetoothRationaleDialog(
            onConfirm = {
                showRationale = false
                permissionsRequested = true
                permissionLauncher.launch(permissions)
            },
            onDismiss = {
                showRationale = false
                onPermissionsDenied()
            }
        )
    }
}

/**
 * Dialog explaining why Bluetooth permissions are needed.
 *
 * Provides user-facing rationale before the system permission dialog appears.
 * This follows Android best practices for runtime permission requests.
 */
@Composable
fun BluetoothRationaleDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Bluetooth Permissions Required",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = BluetoothPermissionManager.PERMISSION_RATIONALE,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Grant Permissions")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not Now")
            }
        }
    )
}

/**
 * Full-screen permission denied state with a retry button.
 *
 * Shown when the user has denied Bluetooth permissions and the app cannot
 * function without them. Provides a button to re-request permissions.
 */
@Composable
fun BluetoothPermissionDeniedScreen(
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Bluetooth Permissions Needed",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "The Golf Cart Computer needs Bluetooth permissions to connect " +
                    "to your Meshtastic radio and GCI telemetry computer. " +
                    "Please grant Bluetooth permissions to use the app.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Text("Grant Permissions")
            }
        }
    }
}
