package com.example.gsmeter

import android.app.admin.DevicePolicyManager
import android.content.*
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class MainActivity : AppCompatActivity(), SensorEventListener {

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
    private var locationMarker: Marker? = null

    private lateinit var forecastContainer: View
    private lateinit var statsWeatherRow: View
    private lateinit var gaugeContainer: View

    private var locationManager: LocationManager? = null
    private var gpsService: GPSForegroundService? = null
    private var isBound = false
    private val handler = Handler(Looper.getMainLooper())
    
    private var totalDistance = 0f
    private var lastLocation: Location? = null
    private var lastDataUpdateLocation: Location? = null
    private var isCalibrated = false
    private var basePitch = 0f
    private var baseRoll = 0f
    private var lastSyncTime: String? = null
    private var isCurrentlyConnected = true

    private var maxSpeedKph = 0f
    private var avgSpeedKph = 0f
    private var speedSamples = 0
    private var totalSpeedSum = 0f

    private var useMilesPerHour = false
    private var useMetersAltitude = false
    private var useMilesDistance = false
    
    private var currentZoom = 15.0

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private var isAutoLockEnabled = false
    private var isWeatherLoading = false
    private var lastWeatherFetchTime = 0L

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
            handler.postDelayed(this, 30000L) // 30 seconds
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
        
        locationMarker = Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Current Location"
        }
        mapView.overlays.add(locationMarker)

        // Lean uses rear view (banking), Pitch uses side view (incline)
        gaugeLean.setConfiguration("LEAN", false, R.drawable.ic_motorcycle_rear)
        gaugePitch.setConfiguration("PITCH", true, R.drawable.ic_motorcycle_side)

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
        lastLocation?.let {
            val point = GeoPoint(it.latitude, it.longitude)
            mapView.controller.setCenter(point)
            locationMarker?.position = point
            mapView.invalidate()
        }
    }

    private fun showResetDistanceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset Total Distance")
            .setMessage("Are you sure you want to reset the total distance traveled?")
            .setPositiveButton("Reset") { _, _ ->
                totalDistance = 0f
                getPreferences(Context.MODE_PRIVATE).edit().putFloat("total_distance", 0f).apply()
                updateDistanceDisplay()
                Toast.makeText(this, "Distance Reset", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateDistanceDisplay() {
        val displayDistance: Float
        val unit: String
        
        if (useMilesDistance) {
            displayDistance = totalDistance * 0.000621371f
            unit = "mi"
        } else {
            displayDistance = totalDistance / 1000f
            unit = "km"
        }
        
        tvDistance.text = String.format("%.2f %s", displayDistance, unit)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun startGpsService() {
        val intent = Intent(this, GPSForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        
        if (hasLocationPermission()) {
            try {
                locationManager?.registerGnssStatusCallback(gnssStatusCallback, null)
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        try {
            locationManager?.unregisterGnssStatusCallback(gnssStatusCallback)
        } catch (e: Exception) {}
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(batteryReceiver)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)

            var pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
            var roll = Math.toDegrees(orientation[2].toDouble()).toFloat()

            if (!isCalibrated) {
                basePitch = pitch
                baseRoll = roll
                isCalibrated = true
            }

            val calPitch = pitch - basePitch
            val calRoll = roll - baseRoll

            // Lean (Roll) - how much the bike is leaning sideways
            gaugeLean.setAngle(calRoll)
            // Pitch - how much the bike is tilting forward/backward
            gaugePitch.setAngle(calPitch)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onLocationChanged(location: Location) {
        val isFirstLocation = lastLocation == null
        
        lastLocation?.let {
            val distance = it.distanceTo(location)
            if (location.accuracy < 25) {
                totalDistance += distance
                getPreferences(Context.MODE_PRIVATE).edit().putFloat("total_distance", totalDistance).apply()
                updateDistanceDisplay()
            }
        }
        
        lastLocation = location
        
        // Force update location and weather if moved significantly (e.g., > 500m) or first time
        val distanceSinceLastUpdate = lastDataUpdateLocation?.distanceTo(location) ?: Float.MAX_VALUE
        if (isFirstLocation || tvTemp.text == "--°C" || distanceSinceLastUpdate > 500f) {
            updateLocationName(location)
            fetchWeather()
            lastDataUpdateLocation = location
        }

        val speedKph = location.speed * 3.6f
        
        // Update stats
        if (speedKph > maxSpeedKph) maxSpeedKph = speedKph
        totalSpeedSum += speedKph
        speedSamples++
        avgSpeedKph = totalSpeedSum / speedSamples

        val displaySpeed: Float
        val unit: String
        val lowerUnit: String
        if (useMilesPerHour) {
            displaySpeed = speedKph * 0.621371f
            unit = "MPH"
            lowerUnit = "mph"
        } else {
            displaySpeed = speedKph
            unit = "KM/H"
            lowerUnit = "km/h"
        }

        tvSpeed.text = if (speedKph < 1) "0" else speedKph.roundToInt().toString()
        tvUnit.text = unit
        
        val displayAvg: Float
        val displayMax: Float
        if (useMilesPerHour) {
            displayAvg = avgSpeedKph * 0.621371f
            displayMax = maxSpeedKph * 0.621371f
        } else {
            displayAvg = avgSpeedKph
            displayMax = maxSpeedKph
        }
        
        tvAvgSpeed.text = String.format("Avg: %.1f %s", displayAvg, lowerUnit)
        tvMaxSpeed.text = String.format("Max: %.1f %s", displayMax, lowerUnit)

        val displayAlt: Double
        val altUnit: String
        if (useMetersAltitude) {
            displayAlt = location.altitude
            altUnit = "m"
        } else {
            displayAlt = location.altitude * 3.28084
            altUnit = "ft"
        }
        tvAltitude.text = String.format("%.0f %s", displayAlt, altUnit)

        tvHeading.text = getCardinalDirection(location.bearing)
        gsMeterView.setSpeed(speedKph)
        
        if (mapOverlayContainer.visibility == View.VISIBLE) {
            updateMapLocation()
        }

        if (isNetworkAvailable() && (tvLocation.text == "Locating..." || tvLocation.text == "Unknown Location")) {
            updateLocationName(location)
        } else if (!isNetworkAvailable()) {
            val offlineName = OfflineGeocoder(this).getNearestCity(location.latitude, location.longitude)
            if (offlineName != null) {
                tvLocation.text = if (offlineName.state.isNotEmpty()) "${offlineName.name}, ${offlineName.state}" else offlineName.name
                tvCountry.text = offlineName.country
            }
        }
    }

    private fun getCardinalDirection(bearing: Float): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW", "N")
        return directions[((bearing % 360) / 45).roundToInt()]
    }

    private fun updateLocationName(location: Location) {
        Thread {
            try {
                val geocoder = Geocoder(this, Locale.getDefault())
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                
                if (addresses != null && addresses.isNotEmpty()) {
                    val address = addresses[0]
                    val city = address.locality ?: address.subLocality ?: address.subAdminArea ?: ""
                    val state = address.adminArea ?: ""
                    val country = address.countryName ?: ""

                    runOnUiThread {
                        if (city.isNotEmpty()) {
                            tvLocation.text = if (state.isNotEmpty()) "$city, $state" else city
                        } else if (state.isNotEmpty()) {
                            tvLocation.text = state
                        } else {
                            tvLocation.text = "Unknown Location"
                        }
                        tvCountry.text = country
                    }
                } else {
                    val url = URL("https://nominatim.openstreetmap.org/reverse?format=json&lat=${location.latitude}&lon=${location.longitude}&zoom=10&addressdetails=1")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "GSMeterApp")
                    val text = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(text)
                    val addressJson = json.getJSONObject("address")
                    
                    val name = addressJson.optString("city", 
                               addressJson.optString("town", 
                               addressJson.optString("village", 
                               addressJson.optString("hamlet", 
                               addressJson.optString("suburb", "")))))
                    
                    val state = addressJson.optString("state", "")
                    val country = addressJson.optString("country", "")

                    runOnUiThread {
                        if (name.isNotEmpty()) {
                            tvLocation.text = if (state.isNotEmpty()) "$name, $state" else name
                        } else if (state.isNotEmpty()) {
                            tvLocation.text = state
                        } else {
                            tvLocation.text = "Unknown Location"
                        }
                        tvCountry.text = country
                    }
                }
            } catch (e: Exception) {
                Log.e("GSMeter", "Error updating location name", e)
            }
        }.start()
    }

    private fun fetchWeather() {
        val loc = lastLocation ?: return
        val currentTime = System.currentTimeMillis()
        if (isWeatherLoading || (currentTime - lastWeatherFetchTime < 25000)) return
        
        Log.d("GSMeter", "Fetching weather for: ${loc.latitude}, ${loc.longitude}")
        
        isWeatherLoading = true
        Thread {
            try {
                // Primary: Open-Meteo (No key required, highly reliable)
                if (fetchWeatherOpenMeteo(loc.latitude, loc.longitude)) {
                    lastWeatherFetchTime = System.currentTimeMillis()
                } else {
                    // Fallback: WeatherAPI (Using your key)
                    val apiKey = "b67e8832a7684617a80112652251401"
                    if (fetchWeatherFromAPI(apiKey, "${loc.latitude},${loc.longitude}")) {
                        lastWeatherFetchTime = System.currentTimeMillis()
                    }
                }
            } finally {
                isWeatherLoading = false
            }
        }.start()
    }

    private fun fetchWeatherOpenMeteo(lat: Double, lon: Double): Boolean {
        try {
            val urlString = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true&hourly=temperature_2m,weathercode&forecast_days=1"
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                val current = json.getJSONObject("current_weather")
                val tempC = current.getDouble("temperature")
                val condition = getWeatherConditionFromCode(current.getInt("weathercode"))
                
                val hourly = json.getJSONObject("hourly")
                val times = hourly.getJSONArray("time")
                val temps = hourly.getJSONArray("temperature_2m")
                val codes = hourly.getJSONArray("weathercode")
                
                val now = Calendar.getInstance()
                val currentHourStr = SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US).format(now.time)
                
                var startIndex = 0
                for (i in 0 until times.length()) {
                    if (times.getString(i) == currentHourStr) {
                        startIndex = i + 1
                        break
                    }
                }
                
                val forecastData = mutableListOf<ForecastItem>()
                for (i in startIndex until Math.min(startIndex + 5, times.length())) {
                    val timeStr = times.getString(i)
                    val hour = timeStr.substring(11, 13).toInt()
                    val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0) }
                    forecastData.add(ForecastItem(SimpleDateFormat("h a", Locale.getDefault()).format(cal.time), temps.getDouble(i).toInt(), getWeatherConditionFromCode(codes.getInt(i))))
                }

                runOnUiThread {
                    tvTemp.text = "${tempC.toInt()}°C"
                    tvWeather.text = condition
                    updateForecastUi(forecastData)
                    lastSyncTime = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                }
                return true
            }
        } catch (e: Exception) { Log.e("GSMeter", "Open-Meteo Error", e) }
        return false
    }

    private fun fetchWeatherFromAPI(apiKey: String, query: String): Boolean {
        try {
            val urlString = "https://api.weatherapi.com/v1/forecast.json?key=$apiKey&q=${Uri.encode(query)}&days=1&aqi=no&alerts=no"
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                val current = json.getJSONObject("current")
                val tempC = current.getDouble("temp_c")
                val condition = getWeatherConditionFromCodeSimplified(current.getJSONObject("condition").getString("text"))
                
                val forecast = json.getJSONObject("forecast").getJSONArray("forecastday").getJSONObject(0)
                val hourArray = forecast.getJSONArray("hour")
                val now = Calendar.getInstance()
                val currentHour = now.get(Calendar.HOUR_OF_DAY)
                
                val forecastData = mutableListOf<ForecastItem>()
                for (i in 1..5) {
                    val nextHour = (currentHour + i) % 24
                    val hourObj = hourArray.getJSONObject(nextHour)
                    val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, nextHour); set(Calendar.MINUTE, 0) }
                    forecastData.add(ForecastItem(SimpleDateFormat("h a", Locale.getDefault()).format(cal.time), hourObj.getDouble("temp_c").toInt(), getWeatherConditionFromCodeSimplified(hourObj.getJSONObject("condition").getString("text"))))
                }

                runOnUiThread {
                    tvTemp.text = "${tempC.toInt()}°C"
                    tvWeather.text = condition
                    updateForecastUi(forecastData)
                    lastSyncTime = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                }
                return true
            }
        } catch (e: Exception) { Log.e("GSMeter", "WeatherAPI Error", e) }
        return false
    }

    private fun getWeatherConditionFromCode(code: Int): String {
        return when (code) {
            0 -> "Clear"
            1, 2, 3 -> "Clear"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            61, 63, 65 -> "Rain"
            71, 73, 75 -> "Snow"
            80, 81, 82 -> "Showers"
            95, 96, 99 -> "Storm"
            else -> "Cloudy"
        }
    }

    private fun getWeatherConditionFromCodeSimplified(conditionText: String): String {
        val lowerText = conditionText.lowercase()
        return when {
            lowerText.contains("clear") || lowerText.contains("sunny") -> "Clear"
            lowerText.contains("fog") || lowerText.contains("mist") -> "Fog"
            lowerText.contains("rain") || lowerText.contains("drizzle") -> "Rain"
            lowerText.contains("shower") -> "Showers"
            lowerText.contains("storm") || lowerText.contains("thunder") -> "Storm"
            lowerText.contains("snow") || lowerText.contains("sleet") || lowerText.contains("ice") -> "Snow"
            lowerText.contains("cloud") || lowerText.contains("overcast") -> "Cloudy"
            else -> "Cloudy"
        }
    }

    private fun updateForecastUi(items: List<ForecastItem>) {
        forecastItemsHolder.removeAllViews()
        for (index in items.indices) {
            val item = items[index]
            val view = layoutInflater.inflate(R.layout.item_forecast, forecastItemsHolder, false)
            view.findViewById<TextView>(R.id.tv_forecast_time).text = item.time
            view.findViewById<TextView>(R.id.tv_forecast_temp).text = "${item.temp}°"
            view.findViewById<TextView>(R.id.tv_forecast_cond).text = item.condition
            forecastItemsHolder.addView(view)
            
            // Add separator if it's not the last item
            if (index < items.size - 1) {
                val separator = View(this)
                val params = LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT)
                params.setMargins(4, 8, 4, 8)
                separator.layoutParams = params
                separator.setBackgroundColor(Color.parseColor("#40FFFFFF"))
                forecastItemsHolder.addView(separator)
            }
        }
    }

    data class ForecastItem(val time: String, val temp: Int, val condition: String)

    private fun hasLocationPermission() = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun checkAndRequestPermissions() {
        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 1001)
        }
    }

    private fun hideStatusBar() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN 
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION 
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    private fun toggleStatusBar() {
        val isHidden = (window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN) != 0
        if (isHidden) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        } else {
            hideStatusBar()
        }
    }

    private fun sendMediaButtonEvent(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val eventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(eventDown)
        audioManager.dispatchMediaKeyEvent(eventUp)
    }

    private fun updatePlayPauseIcon() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioManager.isMusicActive) {
            btnMediaPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            btnMediaPlayPause.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun openYouTubeMusic() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "YouTube Music not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun requestDeviceAdmin() {
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Auto-lock feature requires Device Admin permission to turn off the screen.")
            startActivity(intent)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            
            val batteryPct = level * 100 / scale.toFloat()
            tvBattery.text = "${batteryPct.toInt()}%"
            
            val iconRes = when {
                isCharging -> R.drawable.ic_battery_charging
                batteryPct > 90 -> R.drawable.ic_battery_full
                batteryPct > 50 -> R.drawable.ic_battery_full // Changed from ic_battery_half which was missing
                batteryPct > 20 -> R.drawable.ic_battery_full // Changed from ic_battery_low which was missing
                else -> R.drawable.ic_battery_full // Changed from ic_battery_low which was missing
            }
            ivBattery.setImageResource(iconRes)
        }
    }
}
