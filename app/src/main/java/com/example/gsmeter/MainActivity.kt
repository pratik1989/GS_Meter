package com.example.gsmeter

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var tvSpeed: TextView
    private lateinit var tvUnit: TextView
    private lateinit var tvAltitude: TextView
    private lateinit var tvHeading: TextView
    private lateinit var tvSats: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvLocation: TextView
    private lateinit var tvCountry: TextView
    private lateinit var tvWeather: TextView
    private lateinit var tvOfflineStatus: TextView
    private lateinit var tvDistance: TextView
    private lateinit var forecastItemsHolder: LinearLayout
    private lateinit var btnPermissions: Button
    private lateinit var btnToggleUi: ImageButton
    private lateinit var btnResetDistance: ImageButton
    private lateinit var gsMeterView: GSSpeedometerView
    
    private lateinit var btnMediaPrev: ImageButton
    private lateinit var btnMediaPlayPause: ImageButton
    private lateinit var btnMediaNext: ImageButton
    private lateinit var btnMusicApp: ImageButton
    private lateinit var btnInfo: ImageButton
    private lateinit var btnResetGauges: ImageButton
    private lateinit var cardInfo: CardView
    private lateinit var switchAutoLock: SwitchCompat
    private lateinit var switchHideStats: SwitchCompat
    private lateinit var cbHideGauges: CheckBox
    private lateinit var cbHideStats: CheckBox
    
    private lateinit var tvAvgSpeed: TextView
    private lateinit var tvMaxSpeed: TextView
    private lateinit var tvTime: TextView
    
    private lateinit var tvBattery: TextView
    private lateinit var ivBattery: ImageView

    private lateinit var gaugeLean: LeanAngleView
    private lateinit var gaugePitch: LeanAngleView

    private lateinit var locationContainer: LinearLayout
    private lateinit var mapOverlayContainer: FrameLayout
    private lateinit var btnCloseMap: ImageButton
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnZoomOut: ImageButton
    private lateinit var mapView: MapView

    private lateinit var forecastContainer: View
    private lateinit var statsWeatherRow: View
    private lateinit var gaugeContainer: View

    private lateinit var locationManager: LocationManager
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private var lastLocation: Location? = null
    private var gpsService: GPSForegroundService? = null
    private var isBound = false
    private var isStatusBarHidden = false
    private var hasFetchedInitialWeather = false
    private var isAutoLockEnabled = false
    
    private var maxSpeed: Float = 0f
    private var speedSum: Double = 0.0
    private var speedCount: Int = 0
    private var totalDistance: Float = 0f

    private var useMilesPerHour = false
    private var useMetersAltitude = false
    private var useMilesDistance = false

    private var currentMarker: Marker? = null
    private var currentZoom = 15.0

    // Smoothing variables
    private var smoothedSpeed: Float = 0f
    private val smoothingFactor = 0.4f

    private var initialLean: Float = 0f
    private var initialPitch: Float = 0f
    private var rotationFactor: Float = 1f
    private var isCalibrated = false
    private var lastSyncTime: String? = null
    private var isCurrentlyConnected = true

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = level * 100 / scale.toFloat()
                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                 status == BatteryManager.BATTERY_STATUS_FULL

                runOnUiThread {
                    tvBattery.text = "${batteryPct.toInt()}%"
                    updateBatteryIcon(batteryPct.toInt(), isCharging)
                }
            }
        }
    }

    private fun updateBatteryIcon(percentage: Int, isCharging: Boolean) {
        val iconRes = when {
            isCharging -> R.drawable.ic_battery_charging
            percentage >= 75 -> R.drawable.ic_battery_full
            percentage >= 50 -> R.drawable.ic_battery_4_bar
            percentage >= 25 -> R.drawable.ic_battery_2_bar
            else -> R.drawable.ic_battery_std
        }
        ivBattery.setImageResource(iconRes)
        
        // Color coding
        val color = when {
            isCharging -> Color.parseColor("#4CAF50") // Green
            percentage <= 15 -> Color.parseColor("#F44336") // Red
            percentage <= 30 -> Color.parseColor("#FF9800") // Orange
            else -> Color.WHITE
        }
        ivBattery.setColorFilter(color)
        tvBattery.setTextColor(color)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GPSForegroundService.LocalBinder
            gpsService = binder.getService()
            isBound = true
            gpsService?.setLocationCallback { location ->
                runOnUiThread { onLocationChanged(location) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var usedInFix = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) usedInFix++
            }
            handler.post {
                tvSats.text = "Sats: $usedInFix"
            }
        }
    }

    private val weatherRunnable = object : Runnable {
        override fun run() {
            fetchWeather()
            handler.postDelayed(this, 900000L) // 15 mins
        }
    }

    private val connectionCheckRunnable = object : Runnable {
        override fun run() {
            val connected = isNetworkAvailable()
            
            // UI Update for network status
            runOnUiThread {
                if (!connected) {
                    val now = Calendar.getInstance()
                    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                    tvOfflineStatus.text = "No internet. Last sync: ${lastSyncTime ?: timeFormat.format(now.time)}"
                    tvOfflineStatus.visibility = View.VISIBLE
                } else {
                    tvOfflineStatus.visibility = View.GONE
                }
            }

            if (connected != isCurrentlyConnected) {
                isCurrentlyConnected = connected
                if (connected) {
                    fetchWeather()
                    lastLocation?.let { updateLocationName(it) }
                }
            }
            handler.postDelayed(this, 30000)
        }
    }

    private val timeRunnable = object : Runnable {
        override fun run() {
            val now = Calendar.getInstance()
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
            tvTime.text = timeFormat.format(now.time)
            handler.postDelayed(this, 1000)
        }
    }

    private val mediaStatusRunnable = object : Runnable {
        override fun run() {
            updatePlayPauseIcon()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Osmdroid configuration
        Configuration.getInstance().load(this, getPreferences(Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        
        // Clear map cache on launch
        val osmPath = Configuration.getInstance().osmdroidTileCache
        if (osmPath.exists()) {
            osmPath.deleteRecursively()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        tvSpeed = findViewById(R.id.tv_speed)
        tvUnit = findViewById(R.id.tv_unit)
        tvAltitude = findViewById(R.id.tv_altitude)
        tvHeading = findViewById(R.id.tv_heading)
        tvSats = findViewById(R.id.tv_sats)
        tvTemp = findViewById(R.id.tv_temp)
        tvLocation = findViewById(R.id.tv_location)
        tvCountry = findViewById(R.id.tv_country)
        tvWeather = findViewById(R.id.tv_weather_condition)
        tvOfflineStatus = findViewById(R.id.tv_offline_status)
        tvDistance = findViewById(R.id.tv_distance)
        forecastItemsHolder = findViewById(R.id.forecast_items_holder)
        btnPermissions = findViewById(R.id.btn_permissions)
        btnToggleUi = findViewById(R.id.btn_toggle_ui)
        btnResetDistance = findViewById(R.id.btn_reset_distance)
        gsMeterView = findViewById(R.id.gs_meter_view)
        
        btnMediaPrev = findViewById(R.id.btn_media_prev)
        btnMediaPlayPause = findViewById(R.id.btn_media_play_pause)
        btnMediaNext = findViewById(R.id.btn_media_next)
        btnMusicApp = findViewById(R.id.btn_music_app)
        btnInfo = findViewById(R.id.btn_info)
        btnResetGauges = findViewById(R.id.btn_reset_gauges)
        cardInfo = findViewById(R.id.card_info)
        switchAutoLock = findViewById(R.id.switch_auto_lock)
        switchHideStats = findViewById(R.id.switch_hide_stats)
        cbHideGauges = findViewById(R.id.cb_hide_gauges)
        cbHideStats = findViewById(R.id.cb_hide_stats)
        
        tvAvgSpeed = findViewById(R.id.tv_avg_speed)
        tvMaxSpeed = findViewById(R.id.tv_max_speed)
        tvTime = findViewById(R.id.tv_time)
        
        tvBattery = findViewById(R.id.tv_battery)
        ivBattery = findViewById(R.id.iv_battery)

        gaugeLean = findViewById(R.id.gauge_lean)
        gaugePitch = findViewById(R.id.gauge_pitch)

        locationContainer = findViewById(R.id.location_container)
        mapOverlayContainer = findViewById(R.id.map_overlay_container)
        btnCloseMap = findViewById(R.id.btn_close_map)
        btnZoomIn = findViewById(R.id.btn_zoom_in)
        btnZoomOut = findViewById(R.id.btn_zoom_out)
        mapView = findViewById(R.id.map_view)

        forecastContainer = findViewById(R.id.forecast_container)
        statsWeatherRow = findViewById(R.id.stats_weather_row)
        gaugeContainer = findViewById(R.id.gauge_container)

        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(currentZoom)

        gaugeLean.setConfiguration("PITCH", true, R.drawable.ic_motorcycle_side)
        gaugePitch.setConfiguration("LEAN", false, R.drawable.ic_motorcycle_rear)

        tvSpeed.text = "---"

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, AdminReceiver::class.java)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val prefs = getPreferences(Context.MODE_PRIVATE)
        isAutoLockEnabled = prefs.getBoolean("auto_lock", false)
        switchAutoLock.isChecked = isAutoLockEnabled
        
        val isMasterHideEnabled = prefs.getBoolean("hide_stats", false)
        switchHideStats.isChecked = isMasterHideEnabled
        cbHideGauges.isChecked = prefs.getBoolean("pref_hide_gauges", true)
        cbHideStats.isChecked = prefs.getBoolean("pref_hide_stats", true)
        
        updateStatsGaugesVisibility()

        totalDistance = prefs.getFloat("total_distance", 0f)
        useMilesPerHour = prefs.getBoolean("use_mph", false)
        useMetersAltitude = prefs.getBoolean("use_meters", false)
        useMilesDistance = prefs.getBoolean("use_miles_dist", false)
        updateDistanceDisplay()
        
        switchAutoLock.setOnCheckedChangeListener { _, isChecked ->
            isAutoLockEnabled = isChecked
            prefs.edit().putBoolean("auto_lock", isChecked).apply()
            if (isChecked) requestDeviceAdmin()
        }

        switchHideStats.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("hide_stats", isChecked).apply()
            updateStatsGaugesVisibility()
        }

        cbHideGauges.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_hide_gauges", isChecked).apply()
            updateStatsGaugesVisibility()
        }

        cbHideStats.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_hide_stats", isChecked).apply()
            updateStatsGaugesVisibility()
        }

        tvSpeed.setOnClickListener {
            useMilesPerHour = !useMilesPerHour
            prefs.edit().putBoolean("use_mph", useMilesPerHour).apply()
            lastLocation?.let { onLocationChanged(it) }
        }

        tvAltitude.setOnClickListener {
            useMetersAltitude = !useMetersAltitude
            prefs.edit().putBoolean("use_meters", useMetersAltitude).apply()
            lastLocation?.let { onLocationChanged(it) }
        }

        tvDistance.setOnClickListener {
            useMilesDistance = !useMilesDistance
            prefs.edit().putBoolean("use_miles_dist", useMilesDistance).apply()
            updateDistanceDisplay()
        }

        locationContainer.setOnClickListener {
            if (mapOverlayContainer.visibility == View.GONE) {
                mapOverlayContainer.visibility = View.VISIBLE
                updateMapLocation()
            }
        }

        btnCloseMap.setOnClickListener { mapOverlayContainer.visibility = View.GONE }
        
        btnZoomIn.setOnClickListener {
            currentZoom += 1.0
            mapView.controller.setZoom(currentZoom)
        }

        btnZoomOut.setOnClickListener {
            currentZoom -= 1.0
            mapView.controller.setZoom(currentZoom)
        }

        btnPermissions.setOnClickListener { checkAndRequestPermissions() }
        btnToggleUi.setOnClickListener { toggleStatusBar() }

        btnMediaPrev.setOnClickListener { sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        btnMediaPlayPause.setOnClickListener { 
            sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            handler.postDelayed({ updatePlayPauseIcon() }, 100)
        }
        btnMediaNext.setOnClickListener { sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_NEXT) }
        btnMusicApp.setOnClickListener { openYouTubeMusic() }
        btnInfo.setOnClickListener {
            cardInfo.visibility = if (cardInfo.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        btnResetGauges.setOnClickListener {
            isCalibrated = false
            Toast.makeText(this, "Gauges Reset", Toast.LENGTH_SHORT).show()
        }
        btnResetDistance.setOnClickListener {
            showResetDistanceDialog()
        }

        findViewById<TextView>(R.id.tv_info_youtube).setOnClickListener { openUrl("https://www.youtube.com/@sleepyvoyager") }
        findViewById<TextView>(R.id.tv_info_instagram).setOnClickListener { openUrl("https://www.instagram.com/sleepyvoyager") }

        hideStatusBar()

        if (hasLocationPermission()) {
            btnPermissions.visibility = View.GONE
            startGpsService()
            handler.post(weatherRunnable)
            handler.post(connectionCheckRunnable)
        } else {
            btnPermissions.visibility = View.VISIBLE
        }
        
        handler.post(mediaStatusRunnable)
        handler.post(timeRunnable)
        if (isAutoLockEnabled) requestDeviceAdmin()
        
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun updateStatsGaugesVisibility() {
        val masterHide = switchHideStats.isChecked
        
        if (!masterHide) {
            forecastContainer.visibility = View.VISIBLE
            statsWeatherRow.visibility = View.VISIBLE
            gaugeContainer.visibility = View.VISIBLE
        } else {
            gaugeContainer.visibility = if (cbHideGauges.isChecked) View.GONE else View.VISIBLE
            
            val statsVisibility = if (cbHideStats.isChecked) View.GONE else View.VISIBLE
            forecastContainer.visibility = statsVisibility
            statsWeatherRow.visibility = statsVisibility
        }
    }

    private fun updateMapLocation() {
        val loc = lastLocation ?: return
        val startPoint = GeoPoint(loc.latitude, loc.longitude)
        
        if (currentMarker == null) {
            currentMarker = Marker(mapView)
            currentMarker?.title = "Current Location"
            mapView.overlays.add(currentMarker)
        }
        currentMarker?.position = startPoint
        mapView.controller.animateTo(startPoint)
        mapView.invalidate()
    }

    private fun showResetDistanceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset Distance")
            .setMessage("Do you want to reset the total distance covered?")
            .setPositiveButton("Reset") { _, _ ->
                totalDistance = 0f
                updateDistanceDisplay()
                saveDistance()
                Toast.makeText(this, "Distance Reset", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateDistanceDisplay() {
        if (useMilesDistance) {
            val distanceMiles = totalDistance * 0.621371f
            tvDistance.text = String.format("Dist: %.2f mi", distanceMiles)
        } else {
            tvDistance.text = String.format("Dist: %.2f km", totalDistance)
        }
    }

    private fun saveDistance() {
        getPreferences(Context.MODE_PRIVATE).edit().putFloat("total_distance", totalDistance).apply()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (hasLocationPermission()) {
            if (!isBound) startGpsService()
            checkLocationSettings()
        }
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onStop() {
        super.onStop()
        if (isAutoLockEnabled && !isChangingConfigurations && devicePolicyManager.isAdminActive(adminComponent)) {
            try {
                devicePolicyManager.lockNow()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to lock device: ${e.message}")
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)

            val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
            val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()

            if (!isCalibrated) {
                initialPitch = pitch
                initialLean = roll
                val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display?.rotation ?: Surface.ROTATION_0
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.rotation
                }
                rotationFactor = if (rotation == Surface.ROTATION_270) -1f else 1f
                isCalibrated = true
            }

            val currentPitch = -(pitch - initialPitch) * rotationFactor
            val currentLean = -(roll - initialLean) * rotationFactor
            gaugeLean.setAngle(currentLean)
            gaugePitch.setAngle(currentPitch)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @SuppressLint("MissingPermission")
    private fun startGpsService() {
        val serviceIntent = Intent(this, GPSForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        try {
            locationManager.registerGnssStatusCallback(gnssStatusCallback, handler)
        } catch (e: Exception) {}
    }

    private fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun checkAndRequestPermissions() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            btnPermissions.visibility = View.GONE
            startGpsService()
        }
    }

    override fun onLocationChanged(location: Location) {
        if (lastLocation != null) {
            val distance = lastLocation!!.distanceTo(location)
            if (location.accuracy < 25 && distance > 1.0) {
                totalDistance += distance / 1000f
                updateDistanceDisplay()
                saveDistance()
            }
        }
        lastLocation = location
        
        val rawSpeedKmh = location.speed * 3.6f
        
        smoothedSpeed = (rawSpeedKmh * smoothingFactor) + (smoothedSpeed * (1f - smoothingFactor))
        val displaySpeedKmh = if (smoothedSpeed < 1.0f) 0f else smoothedSpeed
        
        if (useMilesPerHour) {
            val speedMph = displaySpeedKmh * 0.621371f
            tvSpeed.text = String.format("%.0f", speedMph)
            tvUnit.text = "mi/h"
        } else {
            tvSpeed.text = String.format("%.0f", displaySpeedKmh)
            tvUnit.text = "km/h"
        }
        gsMeterView.setSpeed(displaySpeedKmh)
        
        if (useMetersAltitude) {
            tvAltitude.text = String.format("%.0f m", location.altitude)
        } else {
            val altitudeFeet = location.altitude * 3.28084
            tvAltitude.text = String.format("%.0f ft", altitudeFeet)
        }

        if (displaySpeedKmh > 5.0f) tvHeading.text = getCompassDirection(location.bearing)
        
        if (displaySpeedKmh > 1.0) {
            if (displaySpeedKmh > maxSpeed) {
                maxSpeed = displaySpeedKmh
                tvMaxSpeed.text = String.format("Max: %.0f km/h", maxSpeed)
            }
            speedSum += displaySpeedKmh
            speedCount++
            tvAvgSpeed.text = String.format("Avg: %.0f km/h", speedSum / speedCount)
        }

        if (!hasFetchedInitialWeather) {
            fetchWeather()
            hasFetchedInitialWeather = true
        }
        updateLocationName(location)
        if (mapOverlayContainer.visibility == View.VISIBLE) updateMapLocation()
    }

    private fun updateLocationName(location: Location) {
        thread {
            try {
                val geocoder = Geocoder(this, Locale.getDefault())
                val addresses: List<Address>? = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val city = address.locality ?: address.subAdminArea ?: "Unknown"
                    val state = address.adminArea ?: ""
                    val country = address.countryName ?: ""
                    runOnUiThread {
                        tvLocation.text = if (state.isNotEmpty()) "$city, $state" else city
                        tvCountry.text = country
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = connectivityManager.activeNetwork ?: return false
        val actNw = connectivityManager.getNetworkCapabilities(nw) ?: return false
        return actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun fetchWeather() {
        val loc = lastLocation ?: return
        if (!isNetworkAvailable()) return
        thread {
            try {
                val urlString = "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}&current=temperature_2m,weather_code&hourly=temperature_2m,weather_code&forecast_days=2&timezone=auto"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)
                    
                    val current = json.getJSONObject("current")
                    val currentTemp = current.getDouble("temperature_2m")
                    val currentWeatherCode = current.getInt("weather_code")
                    
                    val hourly = json.getJSONObject("hourly")
                    val hourlyTemps = hourly.getJSONArray("temperature_2m")
                    val hourlyCodes = hourly.getJSONArray("weather_code")
                    val hourlyTimes = hourly.getJSONArray("time")
                    
                    val nowMs = System.currentTimeMillis()
                    val timeParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
                    
                    var startIndex = -1
                    for (i in 0 until hourlyTimes.length()) {
                        val timeStr = hourlyTimes.getString(i)
                        val timeMs = timeParser.parse(timeStr)?.time ?: 0L
                        if (timeMs > nowMs) {
                            startIndex = i
                            break
                        }
                    }
                    if (startIndex == -1) startIndex = 0

                    val forecastData = mutableListOf<ForecastItem>()
                    val outputFormat = SimpleDateFormat("h a", Locale.getDefault())

                    for (i in startIndex until (startIndex + 5)) {
                        if (i < hourlyTemps.length()) {
                            val timeStr = hourlyTimes.getString(i)
                            val date = timeParser.parse(timeStr)
                            forecastData.add(ForecastItem(
                                time = outputFormat.format(date!!),
                                temp = hourlyTemps.getDouble(i).toInt(),
                                code = hourlyCodes.getInt(i)
                            ))
                        }
                    }

                    runOnUiThread {
                        tvTemp.text = String.format("%.0f°C", currentTemp)
                        tvWeather.text = getWeatherDescription(currentWeatherCode)
                        tvOfflineStatus.visibility = View.GONE
                        val now = Calendar.getInstance()
                        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                        lastSyncTime = timeFormat.format(now.time)
                        updateForecastUI(forecastData)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun updateForecastUI(forecast: List<ForecastItem>) {
        forecastItemsHolder.removeAllViews()
        for ((index, item) in forecast.withIndex()) {
            if (index > 0) {
                val separator = View(this).apply {
                    val params = LinearLayout.LayoutParams(dpToPx(1), LinearLayout.LayoutParams.MATCH_PARENT)
                    params.setMargins(0, dpToPx(8), 0, dpToPx(8))
                    layoutParams = params
                    setBackgroundColor(Color.parseColor("#40FFFFFF"))
                }
                forecastItemsHolder.addView(separator)
            }

            val itemView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dpToPx(6), 0, dpToPx(6), 0)
            }

            val tvTimeItem = TextView(this).apply {
                text = item.time
                setTextColor(Color.WHITE)
                setTextSize(10f)
                gravity = Gravity.CENTER
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.bmw_font_bold)
            }

            val tvTempItem = TextView(this).apply {
                text = "${item.temp}°"
                setTextColor(Color.WHITE)
                setTextSize(12f)
                gravity = Gravity.CENTER
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.bmw_font_bold)
            }
            
            val tvDesc = TextView(this).apply {
                text = getWeatherShortDescription(item.code)
                setTextColor(Color.parseColor("#3DBCDB"))
                setTextSize(8f)
                gravity = Gravity.CENTER
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.bmw_font_bold)
            }

            itemView.addView(tvTimeItem)
            itemView.addView(tvTempItem)
            itemView.addView(tvDesc)
            forecastItemsHolder.addView(itemView)
        }
    }

    private data class ForecastItem(val time: String, val temp: Int, val code: Int)
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun getWeatherDescription(code: Int): String {
        return when (code) {
            0 -> "Clear sky"
            1, 2, 3 -> "Partly cloudy"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            61, 63, 65 -> "Rain"
            71, 73, 75 -> "Snow fall"
            80, 81, 82 -> "Rain showers"
            95, 96, 99 -> "Thunderstorm"
            else -> "Cloudy"
        }
    }
    
    private fun getWeatherShortDescription(code: Int): String {
        return when (code) {
            0 -> "Clear"
            1, 2, 3 -> "Cloudy"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            61, 63, 65 -> "Rain"
            71, 73, 75 -> "Snow"
            80, 81, 82 -> "Showers"
            95, 96, 99 -> "Storm"
            else -> "Cloud"
        }
    }

    private fun getCompassDirection(bearing: Float): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return directions[((bearing + 22.5) / 45).toInt() % 8]
    }

    private fun toggleStatusBar() { if (isStatusBarHidden) showStatusBar() else hideStatusBar() }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            window.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        isStatusBarHidden = true
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        }
        isStatusBarHidden = false
    }

    private fun sendMediaButtonEvent(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val eventTime = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun updatePlayPauseIcon() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        btnMediaPlayPause.setImageResource(if (audioManager.isMusicActive) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
    }

    private fun openYouTubeMusic() {
        val intent = packageManager.getLaunchIntentForPackage("com.google.android.apps.youtube.music")
        if (intent != null) startActivity(intent)
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
    }

    private fun requestDeviceAdmin() {
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            startActivity(intent)
        }
    }

    private fun checkLocationSettings() {
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Enable GPS for accuracy", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) unbindService(serviceConnection)
        unregisterReceiver(batteryReceiver)
        handler.removeCallbacksAndMessages(null)
    }
}
