package com.example.gsmeter

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class GPSForegroundService : Service(), LocationListener {

    private val binder = LocalBinder()
    private lateinit var locationManager: LocationManager
    private var locationCallback: ((Location) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): GPSForegroundService = this@GPSForegroundService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("GS Meter is tracking location")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }
        
        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        try {
            val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (hasFine || hasCoarse) {
                // Get all providers (even disabled ones, we'll try to request from them)
                val allProviders = locationManager.allProviders
                Log.d("GPSForegroundService", "Available providers: $allProviders")

                for (provider in allProviders) {
                    if (provider == LocationManager.PASSIVE_PROVIDER) continue
                    
                    try {
                        locationManager.requestLocationUpdates(
                            provider,
                            1000L,
                            0f,
                            this
                        )
                        Log.d("GPSForegroundService", "Successfully requested updates from $provider")
                    } catch (e: Exception) {
                        Log.e("GPSForegroundService", "Failed to request updates from $provider: ${e.message}")
                    }
                }
                
                // Immediate attempt to get a location
                sendLastBestLocation()
            } else {
                Log.e("GPSForegroundService", "Location permissions not granted")
            }
        } catch (e: SecurityException) {
            Log.e("GPSForegroundService", "SecurityException in startLocationUpdates", e)
        }
    }

    private fun sendLastBestLocation() {
        var bestLocation: Location? = null
        for (provider in locationManager.allProviders) {
            try {
                val l = locationManager.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                    bestLocation = l
                }
            } catch (e: SecurityException) {}
        }
        bestLocation?.let { 
            Log.d("GPSForegroundService", "Initial fix found from last known location: ${it.provider}")
            onLocationChanged(it) 
        }
    }

    fun setLocationCallback(callback: (Location) -> Unit) {
        this.locationCallback = callback
        Log.d("GPSForegroundService", "UI Callback attached")
        // Try to send an initial fix if available
        sendLastBestLocation()
    }

    override fun onLocationChanged(location: Location) {
        Log.d("GPSForegroundService", "Location update received from ${location.provider}: Lat ${location.latitude}, Lon ${location.longitude}")
        locationCallback?.invoke(location)
    }

    @Suppress("DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {
        Log.d("GPSForegroundService", "Provider enabled: $provider")
    }
    override fun onProviderDisabled(provider: String) {
        Log.d("GPSForegroundService", "Provider disabled: $provider")
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(this, "gps_channel")
            .setContentTitle("GS Meter Active")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_speedometer)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "gps_channel",
                "GPS Tracking Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows that the speedometer is active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(this)
    }
}
