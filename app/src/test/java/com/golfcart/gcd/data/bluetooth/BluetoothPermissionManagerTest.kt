package com.golfcart.gcd.data.bluetooth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [BluetoothPermissionManager].
 *
 * Tests permission state data class behavior, rationale strings, and
 * the permission list logic. Context-dependent methods (hasAllPermissions, etc.)
 * require an Android context and are tested via instrumented tests.
 *
 * Validates: Requirements 1.15, 17.6
 */
@DisplayName("BluetoothPermissionManager")
class BluetoothPermissionManagerTest {

    @Test
    @DisplayName("PERMISSION_RATIONALE is non-empty and mentions Bluetooth")
    fun permissionRationaleIsNonEmpty() {
        val rationale = BluetoothPermissionManager.PERMISSION_RATIONALE
        assertTrue(rationale.isNotBlank())
        assertTrue(rationale.contains("Bluetooth", ignoreCase = true))
        assertTrue(rationale.contains("Meshtastic", ignoreCase = true))
        assertTrue(rationale.contains("GCI", ignoreCase = true))
    }

    @Test
    @DisplayName("SCAN_RATIONALE mentions scanning")
    fun scanRationaleMentionsScanning() {
        val rationale = BluetoothPermissionManager.SCAN_RATIONALE
        assertTrue(rationale.isNotBlank())
        assertTrue(rationale.contains("scan", ignoreCase = true))
    }

    @Test
    @DisplayName("CONNECT_RATIONALE mentions connection")
    fun connectRationaleMentionsConnection() {
        val rationale = BluetoothPermissionManager.CONNECT_RATIONALE
        assertTrue(rationale.isNotBlank())
        assertTrue(rationale.contains("connect", ignoreCase = true))
    }

    @Test
    @DisplayName("PermissionState defaults to not granted")
    fun permissionStateDefaultsToNotGranted() {
        val state = BluetoothPermissionManager.PermissionState()

        assertFalse(state.allGranted)
        assertFalse(state.connectGranted)
        assertFalse(state.scanGranted)
        assertFalse(state.shouldShowRationale)
    }

    @Test
    @DisplayName("PermissionState with all granted shows allGranted true")
    fun permissionStateAllGranted() {
        val state = BluetoothPermissionManager.PermissionState(
            allGranted = true,
            connectGranted = true,
            scanGranted = true,
            shouldShowRationale = false
        )

        assertTrue(state.allGranted)
        assertTrue(state.connectGranted)
        assertTrue(state.scanGranted)
        assertFalse(state.shouldShowRationale)
    }

    @Test
    @DisplayName("PermissionState with partial grants shows not all granted")
    fun permissionStatePartialGrants() {
        val state = BluetoothPermissionManager.PermissionState(
            allGranted = false,
            connectGranted = true,
            scanGranted = false,
            shouldShowRationale = true
        )

        assertFalse(state.allGranted)
        assertTrue(state.connectGranted)
        assertFalse(state.scanGranted)
        assertTrue(state.shouldShowRationale)
    }

    @Test
    @DisplayName("PermissionState copy with updated fields works correctly")
    fun permissionStateCopyWorks() {
        val initial = BluetoothPermissionManager.PermissionState()
        val updated = initial.copy(
            allGranted = true,
            connectGranted = true,
            scanGranted = true
        )

        assertFalse(initial.allGranted)
        assertTrue(updated.allGranted)
        assertTrue(updated.connectGranted)
        assertTrue(updated.scanGranted)
    }

    @Test
    @DisplayName("rationale strings are distinct for different permissions")
    fun rationaleStringsAreDistinct() {
        val general = BluetoothPermissionManager.PERMISSION_RATIONALE
        val scan = BluetoothPermissionManager.SCAN_RATIONALE
        val connect = BluetoothPermissionManager.CONNECT_RATIONALE

        // All three should be different strings
        assertTrue(general != scan)
        assertTrue(general != connect)
        assertTrue(scan != connect)
    }
}
