package com.example.gsmeter

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LocationDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "location_cache.db"
        private const val DATABASE_VERSION = 1
        const val TABLE_CITIES = "cities"
        const val COLUMN_ID = "_id"
        const val COLUMN_NAME = "name"
        const val COLUMN_STATE = "state"
        const val COLUMN_COUNTRY = "country"
        const val COLUMN_LAT = "latitude"
        const val COLUMN_LON = "longitude"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = ("CREATE TABLE " + TABLE_CITIES + "("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_NAME + " TEXT,"
                + COLUMN_STATE + " TEXT,"
                + COLUMN_COUNTRY + " TEXT,"
                + COLUMN_LAT + " REAL,"
                + COLUMN_LON + " REAL" + ")")
        db.execSQL(createTable)
        // Index for faster coordinate searches
        db.execSQL("CREATE INDEX idx_coords ON $TABLE_CITIES($COLUMN_LAT, $COLUMN_LON)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CITIES")
        onCreate(db)
    }
}
