package com.torqeedo.controller

import android.app.Application
import no.nordicsemi.android.ble.BleManager

class TorqeedoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Nordic BLE Library initialises via BleManager — no global init needed
    }
}
