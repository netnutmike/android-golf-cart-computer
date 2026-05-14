package com.golfcart.gcd.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.golfcart.gcd.ui.config.ConfigScreen
import com.golfcart.gcd.ui.entertainment.EntertainmentScreen
import com.golfcart.gcd.ui.weather.WeatherScreen

/**
 * Bridge object that sets Compose content on the Java-based [MainActivity].
 *
 * Since Jetpack Compose's `setContent` extension is a Kotlin function,
 * this object provides a callable entry point from Java.
 *
 * Handles Bluetooth runtime permission requests with rationale before
 * displaying the main application UI.
 *
 * Validates: Requirement 17.6 (request all necessary Android Bluetooth permissions
 * at runtime with appropriate user-facing rationale)
 */
object MainActivityContent {

    /** Navigation route constants. */
    object Routes {
        const val MAIN = "main"
        const val WEATHER = "weather"
        const val ENTERTAINMENT = "entertainment"
        const val CONFIG = "config"
    }

    /**
     * Checks whether all required Bluetooth permissions are granted.
     */
    private fun hasBluetoothPermissions(activity: ComponentActivity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                activity, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                activity, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                activity, Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                activity, Manifest.permission.BLUETOOTH_ADMIN
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Sets the Compose UI content on the given activity.
     *
     * First checks for Bluetooth permissions. If not granted, shows a rationale
     * dialog and requests permissions before proceeding to the main app UI.
     *
     * @param activity The [ComponentActivity] to set content on.
     */
    fun setContent(activity: ComponentActivity) {
        activity.setContent {
            MaterialTheme {
                var permissionsGranted by remember {
                    mutableStateOf(hasBluetoothPermissions(activity))
                }
                var permissionsDenied by remember { mutableStateOf(false) }

                when {
                    permissionsGranted -> {
                        // Permissions granted — show the main app UI
                        AppNavigation()
                    }
                    permissionsDenied -> {
                        // Permissions denied — show retry screen
                        BluetoothPermissionDeniedScreen(
                            onRetry = {
                                permissionsDenied = false
                            }
                        )
                    }
                    else -> {
                        // Request permissions with rationale
                        BluetoothPermissionScreen(
                            onPermissionsGranted = {
                                permissionsGranted = true
                            },
                            onPermissionsDenied = {
                                permissionsDenied = true
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * Main application navigation graph.
     * Separated for clarity and reuse.
     */
    @androidx.compose.runtime.Composable
    private fun AppNavigation() {
        val navController = rememberNavController()

        NavHost(
            navController = navController,
            startDestination = Routes.MAIN
        ) {
            composable(Routes.MAIN) {
                MainScreen(
                    onNavigateToConfig = {
                        navController.navigate(Routes.CONFIG)
                    },
                    onNavigateToWeather = {
                        navController.navigate(Routes.WEATHER)
                    },
                    onNavigateToEntertainment = {
                        navController.navigate(Routes.ENTERTAINMENT)
                    }
                )
            }
            composable(Routes.WEATHER) {
                WeatherScreen()
            }
            composable(Routes.ENTERTAINMENT) {
                EntertainmentScreen()
            }
            composable(Routes.CONFIG) {
                ConfigScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
