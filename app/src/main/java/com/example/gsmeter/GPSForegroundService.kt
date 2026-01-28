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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                // We prioritize GPS_PROVIDER for speedometer accuracy
                // Minimum time: 500ms for high responsiveness, 0 distance change
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0f,
                    this
                )
                
                // Also request from fused/network as a fallback, but we'll prioritize GPS in the callback
                if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        2000L,
                        0f,
                        this
                    )
                }
                
                Log.d("GPSForegroundService", "Location updates requested")
                sendLastBestLocation()
            }
        } catch (e: SecurityException) {
            Log.e("GPSForegroundService", "SecurityException in startLocationUpdates", e)
        }
    }

    private fun sendLastBestLocation() {
        try {
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            
            val best = if (lastGps != null && (lastNetwork == null || lastGps.accuracy < lastNetwork.accuracy)) {
                lastGps
            } else {
                lastNetwork
            }
            
            best?.let { onLocationChanged(it) }
        } catch (e: SecurityException) {}
    }

    fun setLocationCallback(callback: (Location) -> Unit) {
        this.locationCallback = callback
        sendLastBestLocation()
    }

    override fun onLocationChanged(location: Location) {
        // Basic filtering: Ignore locations with extremely poor accuracy (> 60m)
        // for speedometer purposes, unless we have nothing else.
        if (location.accuracy > 60f && location.provider != LocationManager.GPS_PROVIDER) return
        
        locationCallback?.invoke(location)
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Suppress("DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

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
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(this)
    }
}
