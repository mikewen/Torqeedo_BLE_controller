package com.torqeedo.controller.ble

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osmdroid.util.GeoPoint

data class Waypoint(val name: String, val point: GeoPoint)

/**
 * Singleton repository to maintain BLE connections and global navigation state 
 * across multiple Activities/ViewModels.
 */
object BleRepository {
    private var motorManager: TorqeedoBleManager? = null
    private var imuManager: TorqeedoBleManager? = null
    private var gpsManager: TorqeedoBleManager? = null
    private var remote: LookbonRemote? = null

    // Global navigation state shared across Activities
    private val _targetLocation = MutableStateFlow<GeoPoint?>(null)
    val targetLocation = _targetLocation.asStateFlow()

    private val _targetName = MutableStateFlow<String?>(null)
    val targetName = _targetName.asStateFlow()

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints = _waypoints.asStateFlow()

    private val _currentLocation = MutableStateFlow<GeoPoint?>(null)
    val currentLocation = _currentLocation.asStateFlow()

    fun setTarget(loc: GeoPoint?, name: String? = null) {
        _targetLocation.value = loc
        _targetName.value = name
    }

    fun setWaypoints(list: List<Waypoint>) {
        _waypoints.value = list
    }
    
    fun setCurrentLocation(loc: GeoPoint?) {
        _currentLocation.value = loc
    }

    fun getMotorManager(context: Context): TorqeedoBleManager {
        return motorManager ?: TorqeedoBleManager(context.applicationContext).also { motorManager = it }
    }

    fun getImuManager(context: Context): TorqeedoBleManager {
        return imuManager ?: TorqeedoBleManager(context.applicationContext).also { imuManager = it }
    }

    fun getGpsManager(context: Context): TorqeedoBleManager {
        return gpsManager ?: TorqeedoBleManager(context.applicationContext).also { gpsManager = it }
    }

    fun getRemote(context: Context): LookbonRemote {
        return remote ?: LookbonRemote(context.applicationContext).also { remote = it }
    }
}
