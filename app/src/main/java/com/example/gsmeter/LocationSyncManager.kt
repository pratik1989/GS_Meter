package com.example.gsmeter

import android.content.Context
import android.util.JsonReader
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LocationSyncManager(private val context: Context) {
    private val offlineGeocoder = OfflineGeocoder(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // Using streaming-friendly sources.
    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/lutam/cities.json/master/cities.json",
        "https://raw.githubusercontent.com/dr5hn/countries-states-cities-database/master/cities.json"
    )

    interface SyncCallback {
        fun onSyncStarted(countryCode: String)
        fun onSyncProgress(progress: Int)
        fun onSyncFinished()
        fun onSyncError(message: String)
    }

    private var callback: SyncCallback? = null
    
    @Volatile
    private var isSyncing = false
    private var hasFailedThisSession = false

    fun setSyncCallback(callback: SyncCallback) {
        this.callback = callback
    }

    fun syncCurrentCountry(countryCode: String) {
        synchronized(this) {
            if (isSyncing || hasFailedThisSession || offlineGeocoder.hasData()) return
            isSyncing = true
        }

        Log.d("LocationSync", "Starting efficient streaming sync for: $countryCode")
        callback?.onSyncStarted(countryCode)
        
        thread {
            var success = false
            var lastError = "No connection"

            for (url in SOURCES) {
                try {
                    Log.d("LocationSync", "Downloading from: $url")
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code}"
                        response.close()
                        continue
                    }

                    // Use JsonReader for memory-efficient streaming parsing
                    val reader = JsonReader(response.body?.charStream())
                    success = parseAndStore(reader, countryCode)
                    response.close()
                    
                    if (success) break
                } catch (e: Exception) {
                    Log.e("LocationSync", "Source $url failed", e)
                    lastError = e.message ?: "Network error"
                }
            }

            isSyncing = false
            if (success) {
                callback?.onSyncFinished()
            } else {
                hasFailedThisSession = true
                callback?.onSyncError("Location database sync failed ($lastError). Using GPS only.")
            }
        }
    }

    private fun parseAndStore(reader: JsonReader, targetCountry: String): Boolean {
        return try {
            reader.beginArray()
            val batch = mutableListOf<Map<String, Any>>()
            var processed = 0
            
            while (reader.hasNext()) {
                val cityMap = mutableMapOf<String, Any>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    when (key) {
                        "name" -> cityMap["name"] = reader.nextString()
                        "country", "country_code" -> cityMap["country"] = reader.nextString()
                        "state_name", "admin1" -> cityMap["state"] = reader.nextString()
                        "latitude", "lat" -> cityMap["lat"] = try { reader.nextString().toDouble() } catch (e: Exception) { try { reader.nextDouble() } catch (e2: Exception) { 0.0 } }
                        "longitude", "lng" -> cityMap["lon"] = try { reader.nextString().toDouble() } catch (e: Exception) { try { reader.nextDouble() } catch (e2: Exception) { 0.0 } }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                
                val country = cityMap["country"] as? String
                if (country.equals(targetCountry, ignoreCase = true)) {
                    // Fill defaults for missing fields to avoid DB errors
                    cityMap.putIfAbsent("state", "")
                    cityMap.putIfAbsent("name", "Unknown")
                    batch.add(cityMap)
                }
                
                processed++
                if (batch.size >= 500) {
                    offlineGeocoder.insertCities(batch)
                    batch.clear()
                    // Update progress (just as a heart-beat)
                    callback?.onSyncProgress((processed / 1000) % 100)
                }
            }
            reader.endArray()
            
            if (batch.isNotEmpty()) {
                offlineGeocoder.insertCities(batch)
            }
            true
        } catch (e: Exception) {
            Log.e("LocationSync", "Streaming parse error", e)
            false
        } finally {
            try { reader.close() } catch (e: Exception) {}
        }
    }
}
