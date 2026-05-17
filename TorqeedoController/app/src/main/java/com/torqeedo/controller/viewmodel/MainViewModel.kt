package com.torqeedo.controller.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.hardware.GeomagneticField
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.*
import com.torqeedo.controller.ble.*
import com.torqeedo.controller.protocol.MagEllipseCalibrator
import com.torqeedo.controller.protocol.SteerSensorProcessor
import com.torqeedo.controller.protocol.TorqeedoProtocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.util.Locale
import kotlin.math.*

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val PREFS_NAME = "torqeedo_prefs"
        private const val KEY_SHOW_RAW = "show_raw"
        private const val KEY_LOGGING = "logging"
        private const val KEY_VOICE = "voice"
        private const val KEY_SHOW_MOTOR_STATUS = "show_motor_status"
        private const val KEY_REMOTE_MAC = "remote_mac"
        private const val KEY_STEER_SCALE = "steer_scale"
        
        private const val KEY_CALIB_ZERO = "calib_zero"
        private const val KEY_CALIB_PORT = "calib_port"
        private const val KEY_CALIB_STBD = "calib_stbd"

        private const val KEY_DECLINATION = "declination"
        private const val KEY_HEADING_OFFSET = "heading_offset"

        private const val KEY_BIAS1 = "steer_bias1"
        private const val KEY_BIAS2 = "steer_bias2"
        
        private const val KEY_MAG_ELLIPSE_CX = "mag_ellipse_cx"
        private const val KEY_MAG_ELLIPSE_CY = "mag_ellipse_cy"
        private const val KEY_MAG_ELLIPSE_A = "mag_ellipse_a"
        private const val KEY_MAG_ELLIPSE_B = "mag_ellipse_b"
        private const val KEY_MAG_ELLIPSE_ANGLE = "mag_ellipse_angle"
        private const val KEY_MAG_ELLIPSE_VALID = "mag_ellipse_valid"
        private const val KEY_MAG_CALIB_ZERO_DEG = "mag_calib_zero_deg"
        private const val KEY_MAG_CALIB_PORT_DEG = "mag_calib_port_deg"
        private const val KEY_MAG_CALIB_STBD_DEG = "mag_calib_stbd_deg"

        private const val KEY_VEC_A_CENTER = "vec_a_center"
        private const val KEY_VEC_B_CENTER = "vec_b_center"
        private const val KEY_VEC_A_PORT22 = "vec_a_port22"
        private const val KEY_VEC_B_PORT22 = "vec_b_port22"
        private const val KEY_VEC_A_PORT35 = "vec_a_port35"
        private const val KEY_VEC_B_PORT35 = "vec_b_port35"
        private const val KEY_VEC_A_STBD22 = "vec_a_stbd22"
        private const val KEY_VEC_B_STBD22 = "vec_b_stbd22"
        private const val KEY_VEC_A_STBD35 = "vec_a_stbd35"
        private const val KEY_VEC_B_STBD35 = "vec_b_stbd35"

        private const val KEY_STEER_LUT_A = "steer_lut_a"
        private const val KEY_STEER_LUT_B = "steer_lut_b"

        private const val KEY_AP_KP = "ap_kp"
        private const val KEY_AP_KI = "ap_ki"
        private const val KEY_AP_KD = "ap_kd"
        private const val KEY_USE_RUDDER_SENSOR = "use_rudder_sensor"
        private const val KEY_QMC_LPF = "qmc_lpf"

        private const val DEFAULT_AP_KP = 2.5f
        private const val DEFAULT_AP_KI = 0.1f
        private const val DEFAULT_AP_KD = 1.0f

        private const val KEY_WAYPOINTS = "waypoints_v3"
    }

    private val prefs: SharedPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Global State Proxies ---
    val direction: StateFlow<Direction> = BleRepository.direction
    val speedMagnitude: StateFlow<Int> = BleRepository.speedMagnitude
    val currentSpeed: StateFlow<Int> = BleRepository.currentSpeed
    val steerValue: StateFlow<Int> = BleRepository.steerValue
    val autoPilotActive: StateFlow<Boolean> = BleRepository.autoPilotActive
    val targetHeading: StateFlow<Float> = BleRepository.targetHeading

    // --- Persisted Config Flows ---
    private val _showRawData = MutableStateFlow(prefs.getBoolean(KEY_SHOW_RAW, true))
    val showRawData: StateFlow<Boolean> = _showRawData.asStateFlow()

    private val _enableLogging = MutableStateFlow(prefs.getBoolean(KEY_LOGGING, true))
    val enableLogging: StateFlow<Boolean> = _enableLogging.asStateFlow()

    private val _enableVoicePrompts = MutableStateFlow(prefs.getBoolean(KEY_VOICE, true))
    val enableVoicePrompts: StateFlow<Boolean> = _enableVoicePrompts.asStateFlow()

    private val _showMotorStatus = MutableStateFlow(prefs.getBoolean(KEY_SHOW_MOTOR_STATUS, false))
    val showMotorStatus: StateFlow<Boolean> = _showMotorStatus.asStateFlow()

    private val _steerScale = MutableStateFlow(prefs.getInt(KEY_STEER_SCALE, 10))
    val steerScale: StateFlow<Int> = _steerScale.asStateFlow()

    private val _qmcLpfEnabled = MutableStateFlow(prefs.getBoolean(KEY_QMC_LPF, false))
    val qmcLpfEnabled: StateFlow<Boolean> = _qmcLpfEnabled.asStateFlow()

    private val bluetoothAdapter: BluetoothAdapter =
        (application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    val motorManager = BleRepository.getMotorManager(application)
    val imuManager   = BleRepository.getImuManager(application)
    val gpsManager   = BleRepository.getGpsManager(application)
    val remote       = BleRepository.getRemote(application)
    val scanner      = BleScanner(bluetoothAdapter)

    val motorConnectionState: StateFlow<TorqeedoBleManager.ConnectionState> = motorManager.connectionState
    val imuConnectionState:   StateFlow<TorqeedoBleManager.ConnectionState> = imuManager.connectionState
    val gpsConnectionState:   StateFlow<TorqeedoBleManager.ConnectionState> = gpsManager.connectionState

    private val _remoteConnected = MutableStateFlow(false)
    val remoteConnected: StateFlow<Boolean> = _remoteConnected.asStateFlow()

    val scanResults: StateFlow<List<DiscoveredDevice>> = scanner.devices
    val isScanning:  StateFlow<Boolean> = scanner.isScanning
    
    val motorStatus: StateFlow<TorqeedoProtocol.MotorStatus?> =
        motorManager.statusFlow.stateIn(viewModelScope, SharingStarted.Lazily, null)
    
    val sensorCurrent: StateFlow<Float> = motorManager.sensorCurrent
    val estimatedPowerW: StateFlow<Float> = sensorCurrent.map { it * 47.0f }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    val rawStatus: StateFlow<ByteArray?> = 
        motorManager.rawStatusFlow.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // --- Sensors (Calculated locally, synced to Repository) ---
    private val _magX = MutableStateFlow(0)
    val magX: StateFlow<Int> = _magX.asStateFlow()
    private val _magY = MutableStateFlow(0)
    val magY: StateFlow<Int> = _magY.asStateFlow()
    private val _magZ = MutableStateFlow(0)
    val magZ: StateFlow<Int> = _magZ.asStateFlow()

    private val magCalibrator = MagEllipseCalibrator()
    private val _magEllipseResult = MutableStateFlow<MagEllipseCalibrator.Result?>(loadMagEllipse())
    val magEllipseResult = _magEllipseResult.asStateFlow()
    private val _isMagCalibrating = MutableStateFlow(false)
    val isMagCalibrating = _isMagCalibrating.asStateFlow()

    private val _magCalibZeroDeg = MutableStateFlow(prefs.getFloat(KEY_MAG_CALIB_ZERO_DEG, 0f))
    private val _magCalibPortDeg = MutableStateFlow(prefs.getFloat(KEY_MAG_CALIB_PORT_DEG, 0f))
    private val _magCalibStbdDeg = MutableStateFlow(prefs.getFloat(KEY_MAG_CALIB_STBD_DEG, 0f))

    private val steerProcessor = SteerSensorProcessor()
    private val _steerSensorA = MutableStateFlow(0)
    val steerSensorA: StateFlow<Int> = _steerSensorA.asStateFlow()
    private val _steerSensorB = MutableStateFlow(0)
    val steerSensorB: StateFlow<Int> = _steerSensorB.asStateFlow()
    private val _steerSensorAngle = MutableStateFlow(0f)
    val steerSensorAngle: StateFlow<Float> = _steerSensorAngle.asStateFlow()

    val steerSensorRatio: StateFlow<Float> = combine(_steerSensorA, _steerSensorB) { a, b ->
        steerProcessor.getRatio(a, b)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    private val _witRoll = MutableStateFlow(0f)
    val witRoll: StateFlow<Float> = _witRoll.asStateFlow()
    private val _witPitch = MutableStateFlow(0f)
    val witPitch: StateFlow<Float> = _witPitch.asStateFlow()
    private val _witYaw = MutableStateFlow(0f)
    val witYaw: StateFlow<Float> = _witYaw.asStateFlow()

    val seaState: StateFlow<SeaState> = combine(witRoll, witPitch) { roll, pitch ->
        val maxDev = max(abs(roll), abs(pitch))
        when {
            maxDev < 3.0f -> SeaState.CALM
            maxDev < 8.0f -> SeaState.MODERATE
            else -> SeaState.ROUGH
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SeaState.CALM)

    private val _declination = MutableStateFlow(prefs.getFloat(KEY_DECLINATION, 0f))
    val declination: StateFlow<Float> = _declination.asStateFlow()

    private val _headingOffset = MutableStateFlow(prefs.getFloat(KEY_HEADING_OFFSET, 0f))
    val headingOffset: StateFlow<Float> = _headingOffset.asStateFlow()

    val trueHeading: StateFlow<Float> = combine(witYaw, declination, headingOffset) { yaw, decl, offset ->
        var heading = yaw + decl + offset
        while (heading < 0) heading += 360f
        while (heading >= 360) heading -= 360f
        heading
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    private val _calibZero = MutableStateFlow(prefs.getInt(KEY_CALIB_ZERO, 0))
    private val _calibPort = MutableStateFlow(prefs.getInt(KEY_CALIB_PORT, 0))
    private val _calibStbd = MutableStateFlow(prefs.getInt(KEY_CALIB_STBD, 0))

    val rudderPosition: StateFlow<Float> = combine(
        combine(_magX, _magY, _magEllipseResult) { x, y, res -> Triple(x, y, res) },
        combine(_calibZero, _calibPort, _calibStbd) { zero, port, stbd -> Triple(zero, port, stbd) },
        combine(_magCalibZeroDeg, _magCalibPortDeg, _magCalibStbdDeg) { z, p, s -> Triple(z, p, s) }
    ) { mag, legacy, ellipseCal ->
        val (x, y, res) = mag
        val (zero, port, stbd) = legacy
        val (zeroDeg, portDeg, stbdDeg) = ellipseCal

        if (res != null) {
            val norm = magCalibrator.normalize(x.toFloat(), y.toFloat(), res)
            val angle = Math.toDegrees(atan2(norm.second.toDouble(), norm.first.toDouble())).toFloat()
            fun diffAngle(a: Float, b: Float): Float {
                var d = a - b
                while (d > 180) d -= 360f
                while (d < -180) d += 360f
                return d
            }
            val diff = diffAngle(angle, zeroDeg)
            val portRange = diffAngle(portDeg, zeroDeg)
            val stbdRange = diffAngle(stbdDeg, zeroDeg)
            val pos = when {
                abs(portRange) > 1 && (diff / portRange) > 0 -> (diff / portRange) * -100f
                abs(stbdRange) > 1 && (diff / stbdRange) > 0 -> (diff / stbdRange) * 100f
                else -> 0f
            }
            pos.coerceIn(-100f, 100f)
        } else {
            val diff = (y - zero).toFloat()
            if (abs(diff) < 1f) return@combine 0f
            val portRange = (port - zero).toFloat()
            val stbdRange = (stbd - zero).toFloat()
            val pos = when {
                abs(portRange) > 10 && (diff / portRange) > 0 -> (diff / portRange) * -100f
                abs(stbdRange) > 10 && (diff / stbdRange) > 0 -> (diff / stbdRange) * 100f
                else -> 0f
            }
            pos.coerceIn(-100f, 100f)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    // --- GPS State ---
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    private val _gpsFix = MutableStateFlow(false)
    val gpsFix: StateFlow<Boolean> = _gpsFix.asStateFlow()
    private val _gpsSpeedKnots = MutableStateFlow(0.0f)
    val gpsSpeedKnots: StateFlow<Float> = _gpsSpeedKnots.asStateFlow()
    private val _gpsCourse = MutableStateFlow<Int?>(null)
    val gpsCourse: StateFlow<Int?> = _gpsCourse.asStateFlow()

    val currentLocation: StateFlow<GeoPoint?> = BleRepository.currentLocation
    val waypoints: StateFlow<List<Waypoint>> = BleRepository.waypoints
    val targetLocation: StateFlow<GeoPoint?> = BleRepository.targetLocation
    val targetName: StateFlow<String?> = BleRepository.targetName

    // --- PID Config ---
    private val _apKp = MutableStateFlow(prefs.getFloat(KEY_AP_KP, DEFAULT_AP_KP))
    val apKp: StateFlow<Float> = _apKp.asStateFlow()
    private val _apKi = MutableStateFlow(prefs.getFloat(KEY_AP_KI, DEFAULT_AP_KI))
    val apKi: StateFlow<Float> = _apKi.asStateFlow()
    private val _apKd = MutableStateFlow(prefs.getFloat(KEY_AP_KD, DEFAULT_AP_KD))
    val apKd: StateFlow<Float> = _apKd.asStateFlow()
    private val _useRudderSensor = MutableStateFlow(prefs.getBoolean(KEY_USE_RUDDER_SENSOR, false))
    val useRudderSensor: StateFlow<Boolean> = _useRudderSensor.asStateFlow()

    private var lastBleGpsUpdate = 0L
    private val _scanAllNames = MutableStateFlow(false)
    val scanAllNames: StateFlow<Boolean> = _scanAllNames.asStateFlow()
    private val _imuCalibStatus = MutableStateFlow("Idle")
    val imuCalibStatus: StateFlow<String> = _imuCalibStatus.asStateFlow()

    init {
        setupSync()
        setupMagnetometer()
        setupQmc6308()
        setupSteerSensor()
        setupWitMotion()
        setupBleGps()
        setupAutoCalibration()

        // Sync Managers with Prefs
        motorManager.setRawDataEnabled(_showRawData.value)
        motorManager.setLoggingEnabled(_enableLogging.value)
        imuManager.setRawDataEnabled(_showRawData.value)
        imuManager.setLoggingEnabled(_enableLogging.value)
        gpsManager.setRawDataEnabled(_showRawData.value)
        gpsManager.setLoggingEnabled(_enableLogging.value)

        // Steer LUT Init
        steerProcessor.bias1 = prefs.getInt(KEY_BIAS1, SteerSensorProcessor.DEFAULT_BIAS)
        steerProcessor.bias2 = prefs.getInt(KEY_BIAS2, SteerSensorProcessor.DEFAULT_BIAS)
        loadSteerLutData()

        if (BleRepository.waypoints.value.isEmpty()) {
            BleRepository.setWaypoints(loadWaypoints())
        }

        // Auto-reconnect remote
        if (!remote.isConnected) {
            prefs.getString(KEY_REMOTE_MAC, null)?.let { mac ->
                try {
                    remote.connectToDevice(bluetoothAdapter.getRemoteDevice(mac), autoReconnect = true)
                } catch (e: Exception) { Log.e(TAG, "Remote reconnect failed", e) }
            }
        } else {
            _remoteConnected.value = true
        }
    }

    private fun setupSync() {
        // Feed local UI state into Singleton Repository
        BleRepository.speedStep = 20
        BleRepository.autoIncrementDelay = 200L
        BleRepository.throttleDelay = 200L
        BleRepository.steerScale = _steerScale.value
        BleRepository.apKp = _apKp.value
        BleRepository.apKi = _apKi.value
        BleRepository.apKd = _apKd.value
        BleRepository.useRudderSensor = _useRudderSensor.value
        BleRepository.showMotorStatus = _showMotorStatus.value
        BleRepository.enableVoicePrompts = _enableVoicePrompts.value

        // Feed Derived Sensor values back to Repository for PID loop
        viewModelScope.launch { trueHeading.collect { BleRepository.trueHeading.value = it } }
        viewModelScope.launch { rudderPosition.collect { BleRepository.rudderPosition.value = it } }
        viewModelScope.launch { sensorCurrent.collect { BleRepository.sensorCurrent.value = it } }

        // Local remote connection handling
        remote.onConnected = {
            _remoteConnected.value = true
            speak("Remote connected")
            remote.bluetoothDevice?.address?.let { mac ->
                prefs.edit().putString(KEY_REMOTE_MAC, mac).apply()
            }
        }
        remote.onDisconnected = {
            _remoteConnected.value = false
            speak("Remote disconnected")
        }
    }

    private fun speak(text: String) {
        BleRepository.speak(text)
    }

    private fun setupMagnetometer() {
        viewModelScope.launch {
            motorManager.magnetometerData.collect { bytes ->
                if (bytes.size >= 9) {
                    val xU = ((bytes[0].toInt() and 0xFF) shl 12) or ((bytes[1].toInt() and 0xFF) shl 4) or (bytes[6].toInt() and 0x0F)
                    val yU = ((bytes[2].toInt() and 0xFF) shl 12) or ((bytes[3].toInt() and 0xFF) shl 4) or (bytes[7].toInt() and 0x0F)
                    val zU = ((bytes[4].toInt() and 0xFF) shl 12) or ((bytes[5].toInt() and 0xFF) shl 4) or (bytes[8].toInt() and 0x0F)
                    _magX.value = xU - 524288
                    _magY.value = yU - 524288
                    _magZ.value = zU - 524288
                    if (_isMagCalibrating.value) magCalibrator.addSample(_magX.value.toFloat(), _magY.value.toFloat())
                }
            }
        }
    }

    private fun setupQmc6308() {
        var lx = 0f; var ly = 0f; var lz = 0f; val alpha = 0.2f
        viewModelScope.launch {
            motorManager.qmc6308Data.collect { data ->
                if (_qmcLpfEnabled.value) {
                    lx = (data.x * alpha) + (lx * (1f - alpha))
                    ly = (data.y * alpha) + (ly * (1f - alpha))
                    lz = (data.z * alpha) + (lz * (1f - alpha))
                    _magX.value = lx.toInt(); _magY.value = ly.toInt(); _magZ.value = lz.toInt()
                } else {
                    _magX.value = data.x; _magY.value = data.y; _magZ.value = data.z
                    lx = data.x.toFloat(); ly = data.y.toFloat(); lz = data.z.toFloat()
                }
                if (_isMagCalibrating.value) magCalibrator.addSample(_magX.value.toFloat(), _magY.value.toFloat())
            }
        }
    }

    private fun setupSteerSensor() {
        viewModelScope.launch {
            motorManager.steerSensorData.collect { data ->
                _steerSensorA.value = data.sensorA; _steerSensorB.value = data.sensorB
                _steerSensorAngle.value = steerProcessor.calculateAngle(data.sensorA, data.sensorB)
            }
        }
    }

    private fun setupWitMotion() {
        viewModelScope.launch {
            imuManager.witMotionData.collect { frame ->
                if (frame.size < 2) return@collect
                val type = frame[1].toInt() and 0xFF
                when (type) {
                    0x53 -> {
                        if (frame.size < 11) return@collect
                        _witRoll.value = ((frame[3].toInt() shl 8) or (frame[2].toInt() and 0xFF)).toShort() / 32768f * 180f
                        _witPitch.value = ((frame[5].toInt() shl 8) or (frame[4].toInt() and 0xFF)).toShort() / 32768f * 180f
                        var yaw = -(((frame[7].toInt() shl 8) or (frame[6].toInt() and 0xFF)).toShort() / 32768f * 180f)
                        while (yaw < 0) yaw += 360f; while (yaw >= 360) yaw -= 360f; _witYaw.value = yaw
                    }
                    0x61 -> {
                        if (frame.size < 20) return@collect
                        _witRoll.value = ((frame[15].toInt() shl 8) or (frame[14].toInt() and 0xFF)).toShort() / 32768f * 180f
                        _witPitch.value = ((frame[17].toInt() shl 8) or (frame[16].toInt() and 0xFF)).toShort() / 32768f * 180f
                        var yaw = -(((frame[19].toInt() shl 8) or (frame[18].toInt() and 0xFF)).toShort() / 32768f * 180f)
                        while (yaw < 0) yaw += 360f; while (yaw >= 360) yaw -= 360f; _witYaw.value = yaw
                    }
                }
            }
        }
    }

    private fun setupBleGps() {
        val process: (ByteArray) -> Unit = { frame ->
            if (frame.size >= 17) {
                lastBleGpsUpdate = System.currentTimeMillis()
                val lat = readS32LE(frame, 5) / 1_000_000.0; val lon = readS32LE(frame, 9) / 1_000_000.0
                val speed = readU16LE(frame, 13) / 100.0f; val course = (readU16LE(frame, 15) / 100.0f).toInt()
                _gpsFix.value = true; _gpsSpeedKnots.value = speed; _gpsCourse.value = course
                BleRepository.setCurrentLocation(GeoPoint(lat, lon))
                motorManager.updateGpsInfo(lat, lon, speed, course); gpsManager.updateGpsInfo(lat, lon, speed, course)
                val decl = GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, System.currentTimeMillis()).declination
                if (abs(_declination.value - decl) > 0.1f) { _declination.value = decl; prefs.edit().putFloat(KEY_DECLINATION, decl).apply() }
            }
        }
        viewModelScope.launch { motorManager.bleGpsData.collect { process(it) } }
        viewModelScope.launch { gpsManager.bleGpsData.collect { process(it) } }
    }

    private fun setupAutoCalibration() {
        viewModelScope.launch {
            val gpsStraightFlow = combine(gpsSpeedKnots, gpsCourse, rudderPosition) { s, c, r -> if (s > 3.5f && c != null && abs(r) < 2.0f) c.toFloat() else null }
            combine(seaState, gpsStraightFlow, witYaw, declination) { s, tc, y, d ->
                if (s == SeaState.CALM && tc != null) {
                    var cur = y + d; while (cur < 0) cur += 360f; while (cur >= 360) cur -= 360f
                    var diff = tc - cur; while (diff > 180f) diff -= 360f; while (diff < -180f) diff += 360f; diff
                } else null
            }.collectLatest { to ->
                if (to != null) {
                    val co = _headingOffset.value
                    var diff = to - co
                    while (diff > 180f) diff -= 360f
                    while (diff < -180f) diff += 360f
                    val no = co + (diff * 0.001f)
                    if (abs(no - co) > 0.0001f) { _headingOffset.value = no; prefs.edit().putFloat(KEY_HEADING_OFFSET, no).apply() }
                }
            }
        }
    }

    // --- Control Methods (Delegated to Repository) ---
    fun setDirection(dir: Direction) = BleRepository.setDirection(dir)
    fun increaseSpeed() {
        BleRepository.increaseSpeed()
        BleRepository.speakThrottle()
    }
    fun decreaseSpeed() {
        BleRepository.decreaseSpeed()
        BleRepository.speakThrottle()
    }
    fun stopMotor() = BleRepository.stopMotor()
    fun startAutoIncrease(multiplier: Int = 1) = BleRepository.startAutoIncrease(multiplier)
    fun startAutoDecrease(multiplier: Int = 1) = BleRepository.startAutoDecrease(multiplier)
    fun stopAutoAdjustment() {
        BleRepository.stopAutoAdjustment()
        BleRepository.speakThrottle()
    }
    fun adjustSteer(delta: Int) {
        if (delta < 0) speak("Left")
        else if (delta > 0) speak("Right")
        BleRepository.adjustSteer(delta)
    }
    fun startSteerRepeat(delta: Int) {
        if (delta < 0) speak("Left")
        else if (delta > 0) speak("Right")
        BleRepository.startSteerRepeat(delta)
    }
    fun stopSteerRepeat() = BleRepository.stopSteerRepeat()
    fun setAutoPilotActive(active: Boolean) = BleRepository.setAutoPilotActive(active)
    fun adjustTargetHeading(delta: Float) = BleRepository.adjustTargetHeading(delta)

    fun resetSteer() {
        BleRepository.stopSteerRepeat()
        viewModelScope.launch {
            while (BleRepository.steerValue.value != 0) {
                val cur = BleRepository.steerValue.value
                BleRepository.adjustSteer(if (cur > 0) -1 else 1)
                delay(80L)
            }
        }
        speak("Straight")
    }

    @SuppressLint("MissingPermission")
    fun startGpsUpdates() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).setMinUpdateIntervalMillis(500L).build()
        fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(res: LocationResult) {
            if (System.currentTimeMillis() - lastBleGpsUpdate < 2000) return
            val loc = res.lastLocation ?: return
            _gpsFix.value = true; _gpsSpeedKnots.value = loc.speed * 1.94384f
            _gpsCourse.value = if (loc.hasBearing()) loc.bearing.toInt() else null
            BleRepository.setCurrentLocation(GeoPoint(loc.latitude, loc.longitude))
            motorManager.updateGpsInfo(loc.latitude, loc.longitude, _gpsSpeedKnots.value, _gpsCourse.value)
            val d = GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), System.currentTimeMillis()).declination
            if (abs(_declination.value - d) > 0.1f) { _declination.value = d; prefs.edit().putFloat(KEY_DECLINATION, d).apply() }
        }
    }

    fun stopGpsUpdates() { fusedLocationClient.removeLocationUpdates(locationCallback); _gpsFix.value = false }
    fun startScan() = scanner.startScan(_scanAllNames.value)
    fun startRemoteScan() = scanner.startRemoteScan(); fun startImuScan() = scanner.startImuScan(); fun startGpsScan() = scanner.startGpsScan(); fun stopScan() = scanner.stopScan(); fun setScanAllNames(all: Boolean) { _scanAllNames.value = all }

    fun connect(disc: DiscoveredDevice) {
        viewModelScope.launch {
            try {
                when {
                    disc.name.contains("LOOKBON", true) -> remote.connectToDevice(disc.device)
                    disc.name.contains("WitMotion", true) -> imuManager.connectToDevice(disc.device)
                    disc.name.contains("GPS", true) -> gpsManager.connectToDevice(disc.device)
                    else -> motorManager.connectToDevice(disc.device)
                }
                stopScan()
            } catch (e: Exception) { Log.e(TAG, "Connect failed", e) }
        }
    }

    fun disconnect() { motorManager.disconnectDevice(); imuManager.disconnectDevice(); gpsManager.disconnectDevice(); remote.disconnect().enqueue() }
    
    fun setShowRawData(s: Boolean) { _showRawData.value = s; motorManager.setRawDataEnabled(s); imuManager.setRawDataEnabled(s); gpsManager.setRawDataEnabled(s); prefs.edit().putBoolean(KEY_SHOW_RAW, s).apply() }
    fun setEnableLogging(e: Boolean) { _enableLogging.value = e; motorManager.setLoggingEnabled(e); imuManager.setLoggingEnabled(e); gpsManager.setLoggingEnabled(e); prefs.edit().putBoolean(KEY_LOGGING, e).apply() }
    fun setEnableVoicePrompts(e: Boolean) = prefs.edit().putBoolean(KEY_VOICE, e).apply().also { _enableVoicePrompts.value = e; BleRepository.enableVoicePrompts = e }
    fun setShowMotorStatus(s: Boolean) = prefs.edit().putBoolean(KEY_SHOW_MOTOR_STATUS, s).apply().also { _showMotorStatus.value = s; BleRepository.showMotorStatus = s }
    fun setSteerScale(s: Int) = prefs.edit().putInt(KEY_STEER_SCALE, s).apply().also { _steerScale.value = s; BleRepository.steerScale = s }
    fun setUseRudderSensor(u: Boolean) = prefs.edit().putBoolean(KEY_USE_RUDDER_SENSOR, u).apply().also { _useRudderSensor.value = u; BleRepository.useRudderSensor = u }
    fun setQmcLpfEnabled(e: Boolean) = prefs.edit().putBoolean(KEY_QMC_LPF, e).apply().also { _qmcLpfEnabled.value = e }

    fun setApKp(v: Float) { _apKp.value = v; prefs.edit().putFloat(KEY_AP_KP, v).apply(); BleRepository.apKp = v }
    fun setApKi(v: Float) { _apKi.value = v; prefs.edit().putFloat(KEY_AP_KI, v).apply(); BleRepository.apKi = v }
    fun setApKd(v: Float) { _apKd.value = v; prefs.edit().putFloat(KEY_AP_KD, v).apply(); BleRepository.apKd = v }

    // Calibration Logic
    private fun loadMagEllipse(): MagEllipseCalibrator.Result? {
        if (!prefs.getBoolean(KEY_MAG_ELLIPSE_VALID, false)) return null
        return MagEllipseCalibrator.Result(prefs.getFloat(KEY_MAG_ELLIPSE_CX, 0f), prefs.getFloat(KEY_MAG_ELLIPSE_CY, 0f), prefs.getFloat(KEY_MAG_ELLIPSE_A, 1f), prefs.getFloat(KEY_MAG_ELLIPSE_B, 1f), prefs.getFloat(KEY_MAG_ELLIPSE_ANGLE, 0f))
    }
    fun startMagEllipseCalib() { magCalibrator.clear(); _isMagCalibrating.value = true; speak("Calibration started") }
    fun stopMagEllipseCalib() { _isMagCalibrating.value = false; magCalibrator.fit()?.let { _magEllipseResult.value = it; speak("Fit success") } ?: speak("Fit failed") }
    fun saveMagEllipseCalib() = _magEllipseResult.value?.let { res -> prefs.edit().putFloat(KEY_MAG_ELLIPSE_CX, res.centerX).putFloat(KEY_MAG_ELLIPSE_CY, res.centerY).putFloat(KEY_MAG_ELLIPSE_A, res.axisA).putFloat(KEY_MAG_ELLIPSE_B, res.axisB).putFloat(KEY_MAG_ELLIPSE_ANGLE, res.angle).putBoolean(KEY_MAG_ELLIPSE_VALID, true).apply(); speak("Saved") }
    fun clearMagEllipseCalib() { _magEllipseResult.value = null; prefs.edit().remove(KEY_MAG_ELLIPSE_VALID).apply(); speak("Cleared") }
    
    fun calibrateZero() = getEllipseAngle()?.let { _magCalibZeroDeg.value = it; prefs.edit().putFloat(KEY_MAG_CALIB_ZERO_DEG, it).apply(); speak("Zero set") } ?: _magY.value.let { _calibZero.value = it; prefs.edit().putInt(KEY_CALIB_ZERO, it).apply(); speak("Zero set") }
    fun calibratePort() = getEllipseAngle()?.let { _magCalibPortDeg.value = it; prefs.edit().putFloat(KEY_MAG_CALIB_PORT_DEG, it).apply(); speak("Port set") } ?: _magY.value.let { _calibPort.value = it; prefs.edit().putInt(KEY_CALIB_PORT, it).apply(); speak("Port set") }
    fun calibrateStbd() = getEllipseAngle()?.let { _magCalibPortDeg.value = it; prefs.edit().putFloat(KEY_MAG_CALIB_STBD_DEG, it).apply(); speak("Starboard set") } ?: _magY.value.let { _calibStbd.value = it; prefs.edit().putInt(KEY_CALIB_STBD, it).apply(); speak("Starboard set") }
    private fun getEllipseAngle() = _magEllipseResult.value?.let { res -> val norm = magCalibrator.normalize(_magX.value.toFloat(), _magY.value.toFloat(), res); Math.toDegrees(atan2(norm.second.toDouble(), norm.first.toDouble())).toFloat() }
    
    fun calibrateSteerBias() { steerProcessor.bias1 = _steerSensorA.value; steerProcessor.bias2 = _steerSensorB.value; prefs.edit().putInt(KEY_BIAS1, steerProcessor.bias1).putInt(KEY_BIAS2, steerProcessor.bias2).apply(); speak("Steer bias calibrated") }
    
    fun setSteerCalibCenter() { steerProcessor.getVectorA(_steerSensorA.value).let { a -> steerProcessor.getVectorB(_steerSensorB.value).let { b -> prefs.edit().putFloat(KEY_VEC_A_CENTER, a).putFloat(KEY_VEC_B_CENTER, b).apply() } }; recalculateAndSaveLut(); speak("Center set") }
    fun setSteerCalibPort22() { steerProcessor.getVectorA(_steerSensorA.value).let { a -> steerProcessor.getVectorB(_steerSensorB.value).let { b -> prefs.edit().putFloat(KEY_VEC_A_PORT22, a).putFloat(KEY_VEC_B_PORT22, b).apply() } }; recalculateAndSaveLut(); speak("Port 22 set") }
    fun setSteerCalibPort35() { steerProcessor.getVectorA(_steerSensorA.value).let { a -> steerProcessor.getVectorB(_steerSensorB.value).let { b -> prefs.edit().putFloat(KEY_VEC_A_PORT35, a).putFloat(KEY_VEC_B_PORT35, b).apply() } }; recalculateAndSaveLut(); speak("Port 35 set") }
    fun setSteerCalibStbd22() { steerProcessor.getVectorA(_steerSensorA.value).let { a -> steerProcessor.getVectorB(_steerSensorB.value).let { b -> prefs.edit().putFloat(KEY_VEC_A_STBD22, a).putFloat(KEY_VEC_B_STBD22, b).apply() } }; recalculateAndSaveLut(); speak("Stbd 22 set") }
    fun setSteerCalibStbd35() { steerProcessor.getVectorA(_steerSensorA.value).let { a -> steerProcessor.getVectorB(_steerSensorB.value).let { b -> prefs.edit().putFloat(KEY_VEC_A_STBD35, a).putFloat(KEY_VEC_B_STBD35, b).apply() } }; recalculateAndSaveLut(); speak("Stbd 35 set") }

    private fun recalculateAndSaveLut() = getManualPoints().takeIf { it.size >= 2 }?.let { steerProcessor.fillTableFromPoints(it); saveSteerLutData() }
    private fun loadSteerLutData() = prefs.getString(KEY_STEER_LUT_A, null)?.let { lutA -> prefs.getString(KEY_STEER_LUT_B, null)?.let { lutB -> try { steerProcessor.updateTable(lutA.split(",").map { it.toFloat() }.toFloatArray(), lutB.split(",").map { it.toFloat() }.toFloatArray()) } catch (e: Exception) {} } }
    private fun saveSteerLutData() = prefs.edit().putString(KEY_STEER_LUT_A, steerProcessor.getPathA().joinToString(",") { "%.3f".format(it) }).putString(KEY_STEER_LUT_B, steerProcessor.getPathB().joinToString(",") { "%.3f".format(it) }).apply()

    fun autoCalibPort() = autoCalib(KEY_VEC_A_PORT22, KEY_VEC_B_PORT22, -1, -22.5f)
    fun autoCalibStbd() = autoCalib(KEY_VEC_A_STBD22, KEY_VEC_B_STBD22, 1, 22.5f)
    private fun autoCalib(keyA: String, keyB: String, dir: Int, angle: Float) {
        viewModelScope.launch {
            val tA = prefs.getFloat(keyA, -9999f); val tB = prefs.getFloat(keyB, -9999f)
            if (tA == -9999f) { speak("Error: target not set"); return@launch }
            moveToAngle(0f); delay(1000L); val start = System.currentTimeMillis(); val samples = mutableListOf<Triple<Float, Float, Long>>()
            val drive = launch { while(true) { BleRepository.adjustSteer(dir); delay(50) } }
            while (System.currentTimeMillis() - start < 15000L) {
                val cA = steerProcessor.getVectorA(_steerSensorA.value); val cB = steerProcessor.getVectorB(_steerSensorB.value)
                samples.add(Triple(cA, cB, System.currentTimeMillis() - start))
                if (sqrt(((cA - tA).pow(2) + (cB - tB).pow(2)).toDouble()) < 15.0) break; delay(50)
            }
            drive.cancel(); samples.lastOrNull()?.let { last ->
                val totalTime = last.third; val timedPoints = samples.map { Triple(it.first, it.second, (it.third.toFloat() / totalTime) * angle) }
                steerProcessor.fillTableFromPoints(getManualPoints() + timedPoints); saveSteerLutData(); speak("Auto calibration success")
            } ?: speak("Timed out")
        }
    }
    private fun getManualPoints() = mutableListOf<Triple<Float, Float, Float>>().apply {
        if (prefs.contains(KEY_VEC_A_CENTER)) add(Triple(prefs.getFloat(KEY_VEC_A_CENTER, 0f), prefs.getFloat(KEY_VEC_B_CENTER, 0f), 0f))
        if (prefs.contains(KEY_VEC_A_PORT22)) add(Triple(prefs.getFloat(KEY_VEC_A_PORT22, 0f), prefs.getFloat(KEY_VEC_B_PORT22, 0f), -22.5f))
        if (prefs.contains(KEY_VEC_A_PORT35)) add(Triple(prefs.getFloat(KEY_VEC_A_PORT35, 0f), prefs.getFloat(KEY_VEC_B_PORT35, 0f), -35f))
        if (prefs.contains(KEY_VEC_A_STBD22)) add(Triple(prefs.getFloat(KEY_VEC_A_STBD22, 0f), prefs.getFloat(KEY_VEC_B_STBD22, 0f), 22.5f))
        if (prefs.contains(KEY_VEC_A_STBD35)) add(Triple(prefs.getFloat(KEY_VEC_A_STBD35, 0f), prefs.getFloat(KEY_VEC_B_STBD35, 0f), 35f))
    }
    private suspend fun moveToAngle(target: Float) {
        val start = System.currentTimeMillis()
        while (abs(_steerSensorAngle.value - target) > 2.0f && System.currentTimeMillis() - start < 5000L) { BleRepository.adjustSteer(if (target > _steerSensorAngle.value) 1 else -1); delay(100) }
    }

    fun startImuGyroCalibration() = imuManager.sendWitCalibration(0x01).also { _imuCalibStatus.value = "Gyro..." }
    fun startImuMagCalibration() = imuManager.sendWitCalibration(0x02).also { _imuCalibStatus.value = "Mag..." }
    fun saveImuCalibration() = imuManager.sendWitCalibration(0x00).also { _imuCalibStatus.value = "Idle" }
    fun resetHeadingOffset() = _headingOffset.value.let { _headingOffset.value = 0f; prefs.edit().remove(KEY_HEADING_OFFSET).apply() }
    fun disconnectRemote() = prefs.edit().remove(KEY_REMOTE_MAC).apply().also { remote.disconnect().enqueue() }

    fun saveLocation(name: String) = (targetLocation.value ?: currentLocation.value)?.let { loc ->
        val updated = waypoints.value.toMutableList().apply { add(Waypoint(name, loc)) }
        BleRepository.setWaypoints(updated); saveWaypoints(updated); speak("Location $name saved")
    }

    fun removeWaypoint(waypoint: Waypoint) = waypoints.value.toMutableList().apply { remove(waypoint) }.let { updated ->
        BleRepository.setWaypoints(updated); saveWaypoints(updated); if (targetLocation.value?.latitude == waypoint.point.latitude) BleRepository.setTarget(null, null)
    }

    fun clearWaypoints() = BleRepository.setWaypoints(emptyList()).also { saveWaypoints(emptyList()); BleRepository.setTarget(null, null); speak("Cleared") }
    fun setTargetLocation(loc: GeoPoint?, name: String? = null) = BleRepository.setTarget(loc, name).also { if (loc != null) speak("Target set") }

    private fun loadWaypoints() = prefs.getString(KEY_WAYPOINTS, null)?.split(";")?.filter { it.isNotBlank() }?.map {
        it.split(",").let { pts -> Waypoint(pts[0], GeoPoint(pts[1].toDouble(), pts[2].toDouble())) }
    } ?: emptyList()
    private fun saveWaypoints(points: List<Waypoint>) = prefs.edit().putString(KEY_WAYPOINTS, points.joinToString(";") { "${it.name},${it.point.latitude},${it.point.longitude}" }).apply()

    override fun onCleared() {
        super.onCleared()
        stopGpsUpdates()
    }

    private fun readS32LE(d: ByteArray, o: Int) = if (o + 3 >= d.size) 0 else (d[o].toInt() and 0xFF) or ((d[o+1].toInt() and 0xFF) shl 8) or ((d[o+2].toInt() and 0xFF) shl 16) or ((d[o+3].toInt() and 0xFF) shl 24)
    private fun readU16LE(d: ByteArray, o: Int) = if (o + 1 >= d.size) 0 else (d[o].toInt() and 0xFF) or ((d[o+1].toInt() and 0xFF) shl 8)
}
