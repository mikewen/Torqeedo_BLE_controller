package com.torqeedo.controller.ble

import android.content.Context
import android.util.Log

/**
 * Singleton repository to maintain BLE connections across multiple Activities/ViewModels.
 */
object BleRepository {
    private var motorManager: TorqeedoBleManager? = null
    private var imuManager: TorqeedoBleManager? = null
    private var remote: LookbonRemote? = null

    fun getMotorManager(context: Context): TorqeedoBleManager {
        return motorManager ?: TorqeedoBleManager(context.applicationContext).also { motorManager = it }
    }

    fun getImuManager(context: Context): TorqeedoBleManager {
        return imuManager ?: TorqeedoBleManager(context.applicationContext).also { imuManager = it }
    }

    fun getRemote(context: Context): LookbonRemote {
        return remote ?: LookbonRemote(context.applicationContext).also { remote = it }
    }
}
