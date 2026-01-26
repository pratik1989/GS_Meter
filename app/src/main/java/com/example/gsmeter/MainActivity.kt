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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {

    private lateinit var tvSpeed: TextView
    private lateinit var tvAltitude: TextView
    private lateinit var tvHeading: TextView
    private lateinit var tvSats: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvLocation: TextView
    private lateinit var tvCountry: TextView
    private lateinit var tvWeather: TextView
    private lateinit var tvOfflineStatus: TextView
    private lateinit var forecastItemsHolder: LinearLayout
    private lateinit var btnPermissions: Button
    private lateinit var btnToggleUi: ImageButton
    private lateinit var gsMeterView: GSSpeedometerView
    
    private lateinit var btnMediaPrev: ImageButton
    private lateinit var btnMediaPlayPause: ImageButton
    private lateinit var btnMediaNext: ImageButton
    private lateinit var btnMusicApp: ImageButton
    private lateinit var btnInfo: ImageButton
    private lateinit var btnResetGauges: ImageButton
    private lateinit var cardInfo: CardView
    private lateinit var switchAutoLock: SwitchCompat
    
    private lateinit var tvAvgSpeed: TextView
    private lateinit var tvMaxSpeed: TextView
    private lateinit var tvTime: TextView

    private lateinit var gaugeLean: LeanAngleView
    private lateinit var gaugePitch: LeanAngleView

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

    private var initialLean: Float = 0f
    private var initialPitch: Float = 0f
    private var rotationFactor: Float = 1f
    private var isCalibrated = false
    private var lastSyncTime: String? = null
    private var isCurrentlyConnected = true

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GPSForegroundService.LocalBinder
            gpsService = binder.getService()
            isBound = true
            Log.d("MainActivity", "GPS Service Connected")
            gpsService?.setLocationCallback { location ->
                runOnUiThread { onLocationChanged(location) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            Log.d("MainActivity", "GPS Service Disconnected")
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
            
            val now = Calendar.getInstance()
            val minutes = now.get(Calendar.MINUTE)
            val next15 = ((minutes / 15) + 1) * 15
            
            val nextRun = (now.clone() as Calendar).apply {
                if (next15 >= 60) {
                    add(Calendar.HOUR_OF_DAY, 1)
                    set(Calendar.MINUTE, 0)
                } else {
                    set(Calendar.MINUTE, next15)
                }
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            
            val delay = nextRun.timeInMillis - now.timeInMillis
            handler.postDelayed(this, if (delay > 1000) delay else 900000L)
        }
    }

    private val connectionCheckRunnable = object : Runnable {
        override fun run() {
            val connected = isNetworkAvailable()
            if (connected != isCurrentlyConnected) {
                isCurrentlyConnected = connected
                if (connected) {
                    // Back online, trigger syncs
                    fetchWeather()
                    lastLocation?.let { updateLocationName(it) }
                } else {
                    showOfflineStatus()
                }
            } else if (!connected) {
                showOfflineStatus()
            }
            handler.postDelayed(this, 30000) // 30 seconds
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

    private val searchTimeoutRunnable = Runnable {
        if (lastLocation == null) {
            tvSpeed.text = "Searching..."
            Toast.makeText(this, "Searching for location signal. Ensure GPS is enabled.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        tvSpeed = findViewById(R.id.tv_speed)
        tvAltitude = findViewById(R.id.tv_altitude)
        tvHeading = findViewById(R.id.tv_heading)
        tvSats = findViewById(R.id.tv_sats)
        tvTemp = findViewById(R.id.tv_temp)
        tvLocation = findViewById(R.id.tv_location)
        tvCountry = findViewById(R.id.tv_country)
        tvWeather = findViewById(R.id.tv_weather_condition)
        tvOfflineStatus = findViewById(R.id.tv_offline_status)
        forecastItemsHolder = findViewById(R.id.forecast_items_holder)
        btnPermissions = findViewById(R.id.btn_permissions)
        btnToggleUi = findViewById(R.id.btn_toggle_ui)
        gsMeterView = findViewById(R.id.gs_meter_view)
        
        btnMediaPrev = findViewById(R.id.btn_media_prev)
        btnMediaPlayPause = findViewById(R.id.btn_media_play_pause)
        btnMediaNext = findViewById(R.id.btn_media_next)
        btnMusicApp = findViewById(R.id.btn_music_app)
        btnInfo = findViewById(R.id.btn_info)
        btnResetGauges = findViewById(R.id.btn_reset_gauges)
        cardInfo = findViewById(R.id.card_info)
        switchAutoLock = findViewById(R.id.switch_auto_lock)
        
        tvAvgSpeed = findViewById(R.id.tv_avg_speed)
        tvMaxSpeed = findViewById(R.id.tv_max_speed)
        tvTime = findViewById(R.id.tv_time)

        gaugeLean = findViewById(R.id.gauge_lean)
        gaugePitch = findViewById(R.id.gauge_pitch)

        gaugeLean.setConfiguration("PITCH", true, R.drawable.ic_adventure_left)
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
        
        switchAutoLock.setOnCheckedChangeListener { _, isChecked ->
            isAutoLockEnabled = isChecked
            prefs.edit().putBoolean("auto_lock", isChecked).apply()
            if (isChecked) {
                requestDeviceAdmin()
                Toast.makeText(this, "Auto lock enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Auto lock disabled", Toast.LENGTH_SHORT).show()
            }
        }

        btnPermissions.setOnClickListener {
            checkAndRequestPermissions()
        }

        btnToggleUi.setOnClickListener {
            toggleStatusBar()
        }

        btnMediaPrev.setOnClickListener { sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        btnMediaPlayPause.setOnClickListener { 
            sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            handler.postDelayed({ updatePlayPauseIcon() }, 100)
        }
        btnMediaNext.setOnClickListener { sendMediaButtonEvent(KeyEvent.KEYCODE_MEDIA_NEXT) }
        
        btnMusicApp.setOnClickListener {
            openYouTubeMusic()
        }

        btnInfo.setOnClickListener {
            cardInfo.visibility = if (cardInfo.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        btnResetGauges.setOnClickListener {
            isCalibrated = false
            Toast.makeText(this, "Gauges Reset / Calibrated", Toast.LENGTH_SHORT).show()
        }

        val tvYoutube = findViewById<TextView>(R.id.tv_info_youtube)
        tvYoutube.paintFlags = tvYoutube.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        tvYoutube.setOnClickListener {
            openUrl("https://www.youtube.com/@sleepyvoyager")
        }

        val tvInstagram = findViewById<TextView>(R.id.tv_info_instagram)
        tvInstagram.paintFlags = tvInstagram.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        tvInstagram.setOnClickListener {
            openUrl("https://www.instagram.com/sleepyvoyager?igsh=YXZudXN0ODZ3cTdm&utm_source=qr")
        }

        hideStatusBar()

        if (hasLocationPermission()) {
            btnPermissions.visibility = View.GONE
            startGpsService()
            handler.post(weatherRunnable)
            handler.post(connectionCheckRunnable)
            handler.postDelayed(searchTimeoutRunnable, 5000)
        } else {
            btnPermissions.visibility = View.VISIBLE
        }
        
        handler.post(mediaStatusRunnable)
        handler.post(timeRunnable)
        
        if (isAutoLockEnabled) requestDeviceAdmin()
    }

    private fun openYouTubeMusic() {
        val packageName = "com.google.android.apps.youtube.music"
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            } else {
                openYouTubeMusicInPlayStore(packageName)
            }
        } catch (e: Exception) {
            openYouTubeMusicInPlayStore(packageName)
        }
    }

    private fun openYouTubeMusicInPlayStore(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            if (!isBound) {
                startGpsService()
            }
            checkLocationSettings()
        }
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onStop() {
        super.onStop()
        if (isAutoLockEnabled && devicePolicyManager.isAdminActive(adminComponent)) {
            devicePolicyManager.lockNow()
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
                
                // rotationFactor handles the flip when device is in Landscape Right (270)
                rotationFactor = if (rotation == Surface.ROTATION_270) -1f else 1f
                isCalibrated = true
            }

            // user requested inversed readings: -(current - initial)
            // and we multiply by rotationFactor to handle the 180 flip
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

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
            handler.post(weatherRunnable)
            handler.post(connectionCheckRunnable)
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        
        val speedKmh = location.speed * 3.6f
        tvSpeed.text = String.format("%.0f", speedKmh)
        gsMeterView.setSpeed(speedKmh)
        
        tvAltitude.text = String.format("%.0f m", location.altitude)
        
        if (speedKmh > 5.0f) {
            tvHeading.text = getCompassDirection(location.bearing)
        }
        
        if (speedKmh > 1.0) {
            if (speedKmh > maxSpeed) {
                maxSpeed = speedKmh
                tvMaxSpeed.text = String.format("Max: %.0f km/h", maxSpeed)
            }
            speedSum += speedKmh
            speedCount++
            val avgSpeed = speedSum / speedCount
            tvAvgSpeed.text = String.format("Avg: %.0f km/h", avgSpeed)
        }

        if (!hasFetchedInitialWeather) {
            fetchWeather()
            hasFetchedInitialWeather = true
        }
        
        updateLocationName(location)
    }

    private fun updateLocationName(location: Location) {
        thread {
            try {
                val geocoder = Geocoder(this, Locale.getDefault())
                val addresses: List<Address>? = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                
                if (!addresses.isNullOrEmpty()) {
                    val address = addresses[0]
                    val city = address.locality ?: address.subAdminArea ?: address.adminArea ?: "Unknown"
                    val state = address.adminArea ?: ""
                    val country = address.countryName ?: ""
                    
                    val locationText = if (state.isNotEmpty() && state != city) "$city, $state" else city

                    runOnUiThread {
                        tvLocation.text = locationText
                        tvCountry.text = country
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = connectivityManager.activeNetwork ?: return false
        val actNw = connectivityManager.getNetworkCapabilities(nw) ?: return false
        return when {
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> true
            else -> false
        }
    }

    private fun fetchWeather() {
        val loc = lastLocation ?: return
        
        if (!isNetworkAvailable()) {
            runOnUiThread { showOfflineStatus() }
            return
        }

        thread {
            try {
                val urlString = "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}&current=temperature_2m,weather_code&hourly=temperature_2m,weather_code&forecast_days=2&timezone=auto"
                
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)
                    
                    val current = json.getJSONObject("current")
                    val currentTemp = current.getDouble("temperature_2m")
                    val currentWeatherCode = current.getInt("weather_code")
                    val currentDesc = getWeatherDescription(currentWeatherCode)
                    
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
                            val formattedTime = outputFormat.format(date!!)

                            forecastData.add(ForecastItem(
                                time = formattedTime,
                                temp = hourlyTemps.getDouble(i).toInt(),
                                code = hourlyCodes.getInt(i)
                            ))
                        }
                    }

                    runOnUiThread {
                        lastSyncTime = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Calendar.getInstance().time)
                        tvOfflineStatus.visibility = View.GONE
                        isCurrentlyConnected = true
                        
                        tvTemp.text = String.format("%.0f°C", currentTemp)
                        tvWeather.text = currentDesc
                        updateForecastUI(forecastData)
                    }
                } else {
                    runOnUiThread { showOfflineStatus() }
                }
            } catch (e: Exception) {
                Log.e("Weather", "Exception: ${e.message}", e)
                runOnUiThread { showOfflineStatus() }
            }
        }
    }

    private fun showOfflineStatus() {
        tvOfflineStatus.visibility = View.VISIBLE
        val syncText = if (lastSyncTime != null) "Last sync: $lastSyncTime" else "Never synced"
        tvOfflineStatus.text = "No internet. $syncText"
    }

    private data class ForecastItem(val time: String, val temp: Int, val code: Int)

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
                setPadding(dpToPx(8), 0, dpToPx(8), 0)
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

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun getWeatherDescription(code: Int): String {
        return when (code) {
            0 -> "Clear sky"
            1, 2, 3 -> "Mainly clear"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            61, 63, 65 -> "Rain"
            71, 73, 75 -> "Snow fall"
            80, 81, 82 -> "Rain showers"
            95, 96, 99 -> "Thunderstorm"
            else -> "Unknown"
        }
    }
    
    private fun getWeatherShortDescription(code: Int): String {
        return when (code) {
            0 -> "Clear"
            1, 2, 3 -> "Clear"
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
        val index = ((bearing + 22.5) / 45).toInt() % 8
        return directions[index]
    }

    private fun toggleStatusBar() {
        if (isStatusBarHidden) showStatusBar() else hideStatusBar()
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
        isStatusBarHidden = true
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
        isStatusBarHidden = false
    }

    private fun sendMediaButtonEvent(keyCode: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val eventTime = SystemClock.uptimeMillis()
        
        val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        audioManager.dispatchMediaKeyEvent(downEvent)

        val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    private fun updatePlayPauseIcon() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val iconRes = if (audioManager.isMusicActive) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        btnMediaPlayPause.setImageResource(iconRes)
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestDeviceAdmin() {
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required to automatically lock the screen when you exit the app.")
            startActivity(intent)
        }
    }

    private fun checkLocationSettings() {
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Please enable GPS for better accuracy", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        handler.removeCallbacksAndMessages(null)
    }
}
