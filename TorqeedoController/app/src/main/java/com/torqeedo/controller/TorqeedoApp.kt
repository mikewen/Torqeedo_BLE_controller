package com.torqeedo.controller

import android.app.Application
import no.nordicsemi.android.ble.BleManager
import org.osmdroid.config.Configuration
import java.io.File

class TorqeedoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Nordic BLE Library initialises via BleManager — no global init needed
        
        // osmdroid configuration
        Configuration.getInstance().userAgentValue = packageName
        val osmConfig = File(getExternalFilesDir(null), "osmdroid")
        osmConfig.mkdirs()
        Configuration.getInstance().osmdroidTileCache = osmConfig
    }
}
