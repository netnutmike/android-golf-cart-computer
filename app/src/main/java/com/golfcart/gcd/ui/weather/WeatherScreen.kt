package com.golfcart.gcd.ui.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.golfcart.gcd.domain.parser.HourForecast
import com.golfcart.gcd.domain.parser.WeatherData

/**
 * Weather forecast screen for the Golf Cart Computer.
 *
 * Displays current temperature prominently, a 4-hour forecast with hour label,
 * weather glyph icon, temperature, and precipitation for each hour, and the
 * timestamp of last weather data receipt.
 *
 * Requirements: 3.4, 3.5, 3.6, 3.7, 3.8
 */
@Composable
fun WeatherScreen(
    viewModel: WeatherViewModel = hiltViewModel()
) {
    val weatherData by viewModel.weatherData.collectAsStateWithLifecycle()

    WeatherScreenContent(weatherData = weatherData)
}

/**
 * Stateless content composable for the weather screen.
 * Separated from [WeatherScreen] for testability and preview support.
 */
@Composable
fun WeatherScreenContent(
    weatherData: WeatherData?
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (weatherData != null) {
            WeatherDataDisplay(weatherData = weatherData)
        } else {
            NoWeatherDataDisplay()
        }
    }
}

/**
 * Displays the weather data: current temperature, 4-hour forecast, and timestamp.
 */
@Composable
private fun WeatherDataDisplay(weatherData: WeatherData) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Current temperature displayed prominently
        CurrentTemperatureWidget(
            temperature = weatherData.currentTemp,
            isStored = weatherData.isStored
        )

        Spacer(modifier = Modifier.height(16.dp))

        HorizontalDivider()

        Spacer(modifier = Modifier.height(16.dp))

        // 4-hour forecast row
        ForecastRow(forecasts = weatherData.forecasts)

        Spacer(modifier = Modifier.height(16.dp))

        HorizontalDivider()

        Spacer(modifier = Modifier.height(8.dp))

        // Timestamp of last weather data receipt
        TimestampDisplay(
            timestamp = weatherData.receivedTimestamp,
            isStored = weatherData.isStored
        )
    }
}

/**
 * Displays the current temperature prominently.
 *
 * Requirement 3.4: Display current temperature.
 */
@Composable
private fun CurrentTemperatureWidget(
    temperature: Int,
    isStored: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "CURRENT",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "$temperature°",
            fontSize = 72.sp,
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
 * Displays the 4-hour forecast in a horizontal row.
 *
 * Requirement 3.4: Display 4-hour forecast with hour label, weather glyph icon,
 * temperature, and precipitation for each hour.
 */
@Composable
private fun ForecastRow(forecasts: List<HourForecast>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top
    ) {
        forecasts.forEach { forecast ->
            ForecastHourColumn(forecast = forecast)
        }
    }
}

/**
 * Displays a single hour's forecast data in a vertical column.
 *
 * Shows: hour label, weather glyph icon, temperature, and precipitation.
 */
@Composable
private fun ForecastHourColumn(forecast: HourForecast) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp)
    ) {
        // Hour label (e.g., "10am", "2pm")
        Text(
            text = forecast.hourLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Weather glyph icon (displayed as text representation of glyph code)
        Text(
            text = weatherGlyphIcon(forecast.glyphCode),
            fontSize = 28.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.size(36.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Temperature
        Text(
            text = "${forecast.temperature}°",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(2.dp))

        // Precipitation (empty string if 0.0 was cleared)
        Text(
            text = if (forecast.precipitation.isNotEmpty()) "${forecast.precipitation}%" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Displays the timestamp of last weather data receipt.
 *
 * Requirement 3.5: Display the timestamp when weather data was last received.
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
 * Displays a placeholder when no weather data is available.
 */
@Composable
private fun NoWeatherDataDisplay() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "☁️",
            fontSize = 48.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "No Weather Data",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Waiting for weather data from mesh network...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Maps a weather glyph code to a Unicode weather icon character.
 *
 * Glyph codes correspond to weather conditions from the HoT packet.
 * Uses Unicode emoji as a portable representation.
 */
private fun weatherGlyphIcon(glyphCode: Int): String {
    return when (glyphCode) {
        0 -> "☀️"   // Clear/Sunny
        1 -> "🌤️"  // Mostly sunny
        2 -> "⛅"   // Partly cloudy
        3 -> "☁️"   // Cloudy
        4 -> "🌧️"  // Rain
        5 -> "⛈️"   // Thunderstorm
        6 -> "🌨️"  // Snow
        7 -> "🌫️"  // Fog
        8 -> "💨"   // Windy
        9 -> "🌦️"  // Rain showers
        10 -> "❄️"  // Freezing
        else -> "🌡️" // Default/unknown
    }
}
