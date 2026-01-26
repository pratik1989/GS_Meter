package com.example.gsmeter

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import java.util.Locale

class SpeedometerScreen(carContext: CarContext) : Screen(carContext), LocationListener {

    private var currentSpeed: Float = 0f
    private var altitude: Double = 0.0
    private var bearing: Float = 0f
    private var satellites: Int = 0
    private var temperature: String = "--"
    private var weatherCondition: String = "Unknown"

    private val locationManager = carContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    init {
        startLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                1f,
                this
            )
        } catch (e: Exception) {
            // Handle permission or provider issues
        }
    }

    override fun onLocationChanged(location: Location) {
        currentSpeed = location.speed * 3.6f // Convert m/s to km/h
        altitude = location.altitude
        bearing = location.bearing
        satellites = location.extras?.getInt("satellites") ?: 0
        invalidate()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        satellites = extras?.getInt("satellites") ?: 0
        invalidate()
    }

    private fun getHeading(bearing: Float): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = ((bearing + 22.5) % 360 / 45).toInt()
        return directions[index]
    }

    override fun onGetTemplate(): Template {
        val speedText = String.format(Locale.getDefault(), "%.0f", currentSpeed)
        
        val paneBuilder = Pane.Builder()
            // Moved Temperature and Weather to the top
            .addRow(
                Row.Builder()
                    .setTitle("Weather")
                    .addText("Temp: $temperature | Condition: $weatherCondition")
                    .build()
            )
            // Telemetry rows below
            .addRow(
                Row.Builder()
                    .setTitle("Satellites")
                    .addText("$satellites connected")
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Heading")
                    .addText(getHeading(bearing))
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Altitude")
                    .addText(String.format(Locale.getDefault(), "%.0f m", altitude))
                    .build()
            )

        return PaneTemplate.Builder(paneBuilder.build())
            .setHeader(
                Header.Builder()
                    .setTitle("$speedText km/h")
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .build()
    }
}
