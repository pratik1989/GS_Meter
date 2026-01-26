package com.example.gsmeter

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class GSMeterSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return SpeedometerScreen(carContext)
    }
}
