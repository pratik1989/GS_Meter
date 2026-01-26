### **🏍️ Motorcycle GPS Speedometer – BMW GS Style Dashboard (Android)**

A premium Android motorcycle dashboard application inspired by the iconic BMW GS instrument cluster.
This app transforms your Android device into a full-featured riding companion with real-time GPS telemetry, lean and pitch visualization, weather intelligence, and media controls — all optimized for landscape motorcycle mounting.

### **✨ Key Features**
**🎛️ BMW GS Style Dashboard (UI/UX)**

1. **Curved Segmented Speedometer**
    - BMW GS–inspired premium speedometer with high-contrast segmented arcs and large digital speed readout (km/h).

2. **Dynamic Telemetry Display**
    - Real-time monitoring of:
    - Speed (GPS based)
    - Altitude (meters)
    - Compass heading (N, NE, E, etc.)
    - GPS satellite count and fix status

3. **Lean & Pitch Gauges**
    - Lean Angle: Track cornering performance
    - Pitch Angle: Monitor acceleration and braking attitude
    - One-tap calibration button to zero both gauges based on current mounting position

4. **Session Statistics**
    - Max Speed
    - Average Speed
    - Live ride metrics updated continuously

5. **Smart Weather & Forecast**
    - Current temperature and conditions
    - 5-hour horizontal mini-forecast
    - Automatic location-based weather updates

6. **Integrated Media Controller**
    - Universal playback controls (Play/Pause, Next, Previous)
    - Dedicated quick-launch button for YouTube Music

7. **Contextual Location Display**
    - Automatically shows current City, State, and Country using reverse geocoding

8. **Immersive Interface**
    - Fullscreen riding mode
    - Clean BMW-themed typography
    - Optimized day and night visibility
    - Landscape-only layout for motorcycle mounts

### **⚙️ Smart Backend Features**

1. **Persistent Foreground Service**
    - High-priority GPS tracking remains active even when switching apps
    - Prevents ride interruptions and telemetry loss

2. **Sensor Fusion Logic**
    - Uses Rotation Vector sensor for smooth, lag-free lean and pitch readings

3. **Adaptive Orientation Handling**
    - Automatically detects Landscape-Left vs Landscape-Right
    - Adjusts sensor inversion logic dynamically for accurate lean and pitch values

4. **Open-Meteo Weather Integration**
    - Fetches real-time weather using live GPS coordinates

5. **Intelligent Sync Engine**
    - Weather refresh every 15 minutes
    - Automatic resync on network recovery
    - Offline mode with “Last Sync” timestamp and connection status

6. **Device Admin Security**
    - Optional “Auto Lock on Exit” feature
    - Locks device screen when app is closed (requires user permission)

7. **Efficient Reverse Geocoding**
    - Converts GPS coordinates to human-readable locations in background threads
    - Prevents UI stutters

8. **Low-Latency Update Loop**
    - 1000 ms refresh cycle for clock and media state
    - Hardware-accelerated real-time gauge rendering

### **🧠 Technical Highlights**

1. **Language: Kotlin**
2. **Architecture: Native Android application**
3. **Custom Views**
    Hand-coded Canvas-based rendering for:
    - Speedometer
    - Lean Angle Gauge
    - Pitch Gauge

4. **API Integrations**
    - Open-Meteo API (Weather)
    - Android Location API (GPS)
    - Android Sensor API (IMU / Rotation Vector)

1. **Power Management**
    - Foreground Services for GPS reliability
    - Optimized sensor listeners to balance performance and battery life

### **🚧 Disclaimer**
This application is designed for personal and informational use only.
It is not a certified speed measurement device and should not be relied upon as a legal or safety-critical instrument.

### _Always ride safely and follow local traffic laws._
