package com.example.gsmeter

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.Log
import kotlin.math.*

data class CityInfo(val name: String, val state: String, val country: String)

class OfflineGeocoder(context: Context) {
    private val dbHelper = LocationDatabaseHelper(context)

    fun hasData(): Boolean {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT count(*) FROM ${LocationDatabaseHelper.TABLE_CITIES}", null)
        cursor.moveToFirst()
        val count = cursor.getInt(0)
        cursor.close()
        return count > 0
    }

    fun insertCities(cities: List<Map<String, Any>>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (city in cities) {
                val values = ContentValues().apply {
                    put(LocationDatabaseHelper.COLUMN_NAME, city["name"] as String)
                    put(LocationDatabaseHelper.COLUMN_STATE, city["state"] as String)
                    put(LocationDatabaseHelper.COLUMN_COUNTRY, city["country"] as String)
                    put(LocationDatabaseHelper.COLUMN_LAT, city["lat"] as Double)
                    put(LocationDatabaseHelper.COLUMN_LON, city["lon"] as Double)
                }
                db.insert(LocationDatabaseHelper.TABLE_CITIES, null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getNearestCity(lat: Double, lon: Double): CityInfo? {
        val db = dbHelper.readableDatabase
        // Optimization: search within a small bounding box first (~50km)
        val delta = 0.5 
        val query = "SELECT * FROM ${LocationDatabaseHelper.TABLE_CITIES} " +
                    "WHERE ${LocationDatabaseHelper.COLUMN_LAT} BETWEEN ? AND ? " +
                    "AND ${LocationDatabaseHelper.COLUMN_LON} BETWEEN ? AND ?"
        
        var cursor = db.rawQuery(query, arrayOf(
            (lat - delta).toString(), (lat + delta).toString(),
            (lon - delta).toString(), (lon + delta).toString()
        ))

        if (cursor.count == 0) {
            cursor.close()
            // Fallback to searching everything if box is empty
            cursor = db.rawQuery("SELECT * FROM ${LocationDatabaseHelper.TABLE_CITIES}", null)
        }

        var nearestCity: CityInfo? = null
        var minDistance = Double.MAX_VALUE

        while (cursor.moveToNext()) {
            val cLat = cursor.getDouble(cursor.getColumnIndexOrThrow(LocationDatabaseHelper.COLUMN_LAT))
            val cLon = cursor.getDouble(cursor.getColumnIndexOrThrow(LocationDatabaseHelper.COLUMN_LON))
            
            val distance = calculateDistance(lat, lon, cLat, cLon)
            if (distance < minDistance) {
                minDistance = distance
                nearestCity = CityInfo(
                    cursor.getString(cursor.getColumnIndexOrThrow(LocationDatabaseHelper.COLUMN_NAME)),
                    cursor.getString(cursor.getColumnIndexOrThrow(LocationDatabaseHelper.COLUMN_STATE)),
                    cursor.getString(cursor.getColumnIndexOrThrow(LocationDatabaseHelper.COLUMN_COUNTRY))
                )
            }
        }
        cursor.close()
        return nearestCity
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371 // Earth radius in km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
