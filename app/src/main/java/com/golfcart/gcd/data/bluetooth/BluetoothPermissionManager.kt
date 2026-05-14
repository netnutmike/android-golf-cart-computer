package com.golfcart.gcd.data.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Centralized manager for Android Bluetooth runtime permissions.
 *
 * Handles permission checking and provides the list of required permissions
 * based on the current Android API level. On Android 12+ (API 31), the new
 * BLUETOOTH_CONNECT and BLUETOOTH_SCAN permissions are required. On older
 * versions, the legacy BLUETOOTH and BLUETOOTH_ADMIN permissions are used.
 *
 * This manager is shared between [MeshtasticService] and [TelemetryService]
 * to provide a single source of truth for Bluetooth permission state.
 *
 * Validates: Requirements 1.15, 17.6
 */
class BluetoothPermissionManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothPermMgr"

        /**
         * User-facing rationale for Bluetooth permissions.
         *
         * Explains why the app needs Bluetooth access — connecting to the
         * Meshtastic radio for mesh messaging and the GCI for vehicle telemetry.
         */
        const val PERMISSION_RATIONALE =
            "This app needs Bluetooth permissions to connect to your Meshtastic radio " +
            "for mesh messaging and GPS, and to the GCI computer for vehicle telemetry " +
            "(battery voltage, fuel level, temperature). Without these permissions, " +
            "the app cannot communicate with your golf cart's devices."

        /**
         * Rationale specifically for the BLUETOOTH_SCAN permission.
         */
        const val SCAN_RATIONALE =
            "Bluetooth scanning is needed to discover your Meshtastic radio and GCI " +
            "computer nearby. The app does not use Bluetooth scanning to derive your location."

        /**
         * Rationale specifically for the BLUETOOTH_CONNECT permission.
         */
        const val CONNECT_RATIONALE =
            "Bluetooth connection permission is needed to communicate with your " +
            "Meshtastic radio (BLE) and GCI telemetry computer (Bluetooth Classic)."
    }

    /**
     * Represents the current state of Bluetooth permissions.
     */
    data class PermissionState(
        /** Whether all required Bluetooth permissions are granted. */
        val allGranted: Boolean = false,
        /** Whether BLUETOOTH_CONNECT (or legacy BLUETOOTH) is granted. */
        val connectGranted: Boolean = false,
        /** Whether BLUETOOTH_SCAN (or legacy BLUETOOTH_ADMIN) is granted. */
        val scanGranted: Boolean = false,
        /** Whether the user has previously denied permissions (should show rationale). */
        val shouldShowRationale: Boolean = false
    )

    private val _permissionState = MutableStateFlow(PermissionState())

    /** Observable permission state for UI consumption. */
    val permissionState: StateFlow<PermissionState> = _permissionState.asStateFlow()

    init {
        refreshPermissionState()
    }

    /**
     * Returns the list of Bluetooth permissions required for the current API level.
     *
     * On Android 12+ (API 31):
     * - BLUETOOTH_CONNECT: Required for connecting to paired/discovered devices
     * - BLUETOOTH_SCAN: Required for discovering nearby Bluetooth devices
     *
     * On Android 11 and below:
     * - BLUETOOTH: Required for any Bluetooth communication
     * - BLUETOOTH_ADMIN: Required for device discovery and pairing
     */
    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

    /**
     * Checks whether all required Bluetooth permissions are currently granted.
     *
     * @return true if all required permissions are granted, false otherwise.
     */
    fun hasAllPermissions(): Boolean {
        val permissions = getRequiredPermissions()
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Checks whether the BLUETOOTH_CONNECT permission (or legacy equivalent) is granted.
     */
    fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Checks whether the BLUETOOTH_SCAN permission (or legacy equivalent) is granted.
     */
    fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_ADMIN
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Refreshes the internal permission state by re-checking all permissions.
     * Call this after the user responds to a permission request dialog.
     */
    fun refreshPermissionState() {
        val connectGranted = hasConnectPermission()
        val scanGranted = hasScanPermission()
        val allGranted = connectGranted && scanGranted

        _permissionState.value = PermissionState(
            allGranted = allGranted,
            connectGranted = connectGranted,
            scanGranted = scanGranted,
            shouldShowRationale = !allGranted
        )

        Log.d(TAG, "Permission state refreshed: allGranted=$allGranted, " +
            "connect=$connectGranted, scan=$scanGranted")
    }

    /**
     * Called when the user responds to a permission request.
     * Updates the internal state based on the grant results.
     *
     * @param permissions The permissions that were requested.
     * @param grantResults The grant results for each permission.
     */
    fun onPermissionResult(permissions: Array<String>, grantResults: IntArray) {
        val allGranted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }

        if (allGranted) {
            Log.i(TAG, "All Bluetooth permissions granted")
        } else {
            val denied = permissions.zip(grantResults.toTypedArray())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
                .map { it.first }
            Log.w(TAG, "Bluetooth permissions denied: $denied")
        }

        refreshPermissionState()
    }

    /**
     * Returns the appropriate rationale string for the given permission.
     *
     * @param permission The Android permission string.
     * @return A user-facing rationale explaining why the permission is needed.
     */
    fun getRationaleForPermission(permission: String): String {
        return when (permission) {
            Manifest.permission.BLUETOOTH_SCAN -> SCAN_RATIONALE
            Manifest.permission.BLUETOOTH_CONNECT -> CONNECT_RATIONALE
            else -> PERMISSION_RATIONALE
        }
    }
}
