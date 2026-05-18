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
import com.torqeedo.controller.protocol.SensorFusion
import com.torqeedo.controller.protocol.SteerSensorProcessor
import com.torqeedo.controller.protocol.TorqeedoProtocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.util.Locale
import kotlin.math.*

class MainViewModel(application: Application) : AndroidViewModel(application) {

    data class RawImuData(
        val ax: Short = 0, val ay: Short = 0, val az: Short = 0,
        val gx: Short = 0, val gy: Short = 0, val gz: Short = 0,
        val mx: Short = 0, val my: Short = 0, val mz: Short = 0
    )

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
        private const val KEY_AP_DEADBAND = "ap_deadband"
        private const val KEY_AP_MAX_RATE = "ap_max_rate"
        private const val KEY_USE_RUDDER_SENSOR = "use_rudder_sensor"
        private const val KEY_QMC_LPF = "qmc_lpf"

        private const val KEY_SF_MAG_BIAS_X = "sf_mag_bias_x"
        private const val KEY_SF_MAG_BIAS_Y = "sf_mag_bias_y"
        private const val KEY_SF_GYRO_BIAS_X = "sf_gyro_bias_x"
        private const val KEY_SF_GYRO_BIAS_Y = "sf_gyro_bias_y"
        private const val KEY_SF_GYRO_BIAS_Z = "sf_gyro_bias_z"

        private const val DEFAULT_AP_KP = 2.5f
        private const val DEFAULT_AP_KI = 0.1f
        private const val DEFAULT_AP_KD = 1.0f
        private const val DEFAULT_AP_DEADBAND = 3.0f
        private const val DEFAULT_AP_MAX_RATE = 25f

        private const val KEY_WAYPOINTS = "waypoints_v3"
        private const val KEY_SCAN_ALL = "scan_all_names"
    }

    private val prefs: SharedPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Global State Proxies ---
    val direction: StateFlow<Direction> = BleRepository.direction
    val speedMagnitude: StateFlow<Int> = BleRepository.speedMagnitude
    //val currentSpeed: StateFlow<Int> = BleRepository.currentSpeed
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

    private val _steerScale = MutableStateFlow(prefs.getInt(KEY_STEER_SCALE, 200))
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

    val remoteConnected: StateFlow<Boolean> = BleRepository.remoteConnected.asStateFlow()

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

    private val _IMURoll = MutableStateFlow(0f)
    val IMURoll: StateFlow<Float> = _IMURoll.asStateFlow()
    private val _IMUPitch = MutableStateFlow(0f)
    val IMUPitch: StateFlow<Float> = _IMUPitch.asStateFlow()
    private val _IMUYaw = MutableStateFlow(0f)
    val IMUYaw: StateFlow<Float> = _IMUYaw.asStateFlow()

    // --- Sensor Fusion ---
    private val sensorFusion = SensorFusion()
    private val _fusedState = MutableStateFlow(SensorFusion.FusedState())
    val fusedState: StateFlow<SensorFusion.FusedState> = _fusedState.asStateFlow()

    val seaState: StateFlow<SeaState> = fusedState.map { state ->
        when {
            state.seaState < 0.2f -> SeaState.CALM
            state.seaState < 0.6f -> SeaState.MODERATE
            else -> SeaState.ROUGH
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SeaState.CALM)

    private val _declination = MutableStateFlow(prefs.getFloat(KEY_DECLINATION, 0f))
    val declination: StateFlow<Float> = _declination.asStateFlow()

    private val _headingOffset = MutableStateFlow(prefs.getFloat(KEY_HEADING_OFFSET, 0f))
    val headingOffset: StateFlow<Float> = _headingOffset.asStateFlow()

    val trueHeading: StateFlow<Float> = combine(IMUYaw, declination, headingOffset) { yaw, decl, offset ->
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

    private val _rawBleGpsFrame = MutableStateFlow<ByteArray?>(null)
    val rawBleGpsFrame: StateFlow<ByteArray?> = _rawBleGpsFrame.asStateFlow()

    private val _parsedBleGps = MutableStateFlow<String>("No Data")
    val parsedBleGps: StateFlow<String> = _parsedBleGps.asStateFlow()

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
    private val _apDeadband = MutableStateFlow(prefs.getFloat(KEY_AP_DEADBAND, DEFAULT_AP_DEADBAND))
    val apDeadband: StateFlow<Float> = _apDeadband.asStateFlow()
    private val _apMaxRate = MutableStateFlow(prefs.getFloat(KEY_AP_MAX_RATE, DEFAULT_AP_MAX_RATE))
    val apMaxRate: StateFlow<Float> = _apMaxRate.asStateFlow()
    private val _useRudderSensor = MutableStateFlow(prefs.getBoolean(KEY_USE_RUDDER_SENSOR, false))
    val useRudderSensor: StateFlow<Boolean> = _useRudderSensor.asStateFlow()

    private var lastBleGpsUpdate = 0L
    private val _scanAllNames = MutableStateFlow(prefs.getBoolean(KEY_SCAN_ALL, false))
    val scanAllNames: StateFlow<Boolean> = _scanAllNames.asStateFlow()
    private val _imuCalibStatus = MutableStateFlow("Idle")
    val imuCalibStatus: StateFlow<String> = _imuCalibStatus.asStateFlow()

    private val _rawImuData = MutableStateFlow(RawImuData())
    val rawImuData: StateFlow<RawImuData> = _rawImuData.asStateFlow()

    private val _calibDegreesTurned = MutableStateFlow(0f)
    val calibDegreesTurned: StateFlow<Float> = _calibDegreesTurned.asStateFlow()

    private val _isSFusionMagCalibrating = MutableStateFlow(false)
    val isSFusionMagCalibrating = _isSFusionMagCalibrating.asStateFlow()

    private val _isSFusionGyroCalibrating = MutableStateFlow(false)
    val isSFusionGyroCalibrating = _isSFusionGyroCalibrating.asStateFlow()

    private val _sfMagCalStatus = MutableStateFlow("Ready")
    val sfMagCalStatus: StateFlow<String> = _sfMagCalStatus.asStateFlow()

    private val _sfGyroCalStatus = MutableStateFlow("Ready")
    val sfGyroCalStatus: StateFlow<String> = _sfGyroCalStatus.asStateFlow()

    private var lastA1Time = 0L

    init {
        loadSensorFusionOffsets()
        setupSync()
        setupMagnetometer()
        setupQmc6308()
        setupSteerSensor()
        setupIMU()
        setupBleGps()
        setupAutoCalibration()
        setupSensorFusion()
    }

    private fun loadSensorFusionOffsets() {
        sensorFusion.manualCalHardIronX = prefs.getFloat(KEY_SF_MAG_BIAS_X, 0f)
        sensorFusion.manualCalHardIronY = prefs.getFloat(KEY_SF_MAG_BIAS_Y, 0f)
        sensorFusion.gyroBiasX = prefs.getFloat(KEY_SF_GYRO_BIAS_X, 0f)
        sensorFusion.gyroBiasY = prefs.getFloat(KEY_SF_GYRO_BIAS_Y, 0f)
        sensorFusion.gyroBiasZ = prefs.getFloat(KEY_SF_GYRO_BIAS_Z, 0f)
    }

    private fun setupSync() {
        viewModelScope.launch { BleRepository.targetHeading.collect { sensorFusion.resetFilter() } }
        // Feed initial values to Repository
        BleRepository.apKp = _apKp.value
        BleRepository.apKi = _apKi.value
        BleRepository.apKd = _apKd.value
        BleRepository.apDeadband = _apDeadband.value
        BleRepository.maxTurnRate = _apMaxRate.value
        BleRepository.useRudderSensor = _useRudderSensor.value
        BleRepository.steerScale = _steerScale.value
        BleRepository.enableVoicePrompts = _enableVoicePrompts.value
        BleRepository.showMotorStatus = _showMotorStatus.value
        
        viewModelScope.launch { trueHeading.collect { BleRepository.trueHeading.value = it } }
        viewModelScope.launch { rudderPosition.collect { BleRepository.rudderPosition.value = it } }
    }

    private fun setupMagnetometer() {
        viewModelScope.launch {
            motorManager.magnetometerData.collect { data ->
                if (data.size >= 6) {
                    val x = readS16LE(data, 0); val y = readS16LE(data, 2); val z = readS16LE(data, 4)
                    val xU = (x.toInt() and 0xFFFF); val yU = (y.toInt() and 0xFFFF); val zU = (z.toInt() and 0xFFFF)
                    _magX.value = xU - 524288; _magY.value = yU - 524288; _magZ.value = zU - 524288
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

    private fun setupIMU() {
        viewModelScope.launch {
            imuManager.IMUData.collect { frame ->
                if (frame.size < 2) return@collect
                val type = frame[1].toInt() and 0xFF
                when (type) {
                    0x53 -> {
                        if (frame.size < 11) return@collect
                        _IMURoll.value = ((frame[3].toInt() shl 8) or (frame[2].toInt() and 0xFF)).toShort() / 32768f * 180f
                        _IMUPitch.value = ((frame[5].toInt() shl 8) or (frame[4].toInt() and 0xFF)).toShort() / 32768f * 180f
                        var yaw = -(((frame[7].toInt() shl 8) or (frame[6].toInt() and 0xFF)).toShort() / 32768f * 180f)
                        while (yaw < 0) yaw += 360f; while (yaw >= 360) yaw -= 360f; _IMUYaw.value = yaw
                    }
                    0x61 -> {
                        if (frame.size < 20) return@collect
                        _IMURoll.value = ((frame[15].toInt() shl 8) or (frame[14].toInt() and 0xFF)).toShort() / 32768f * 180f
                        _IMUPitch.value = ((frame[17].toInt() shl 8) or (frame[16].toInt() and 0xFF)).toShort() / 32768f * 180f
                        var yaw = -(((frame[19].toInt() shl 8) or (frame[18].toInt() and 0xFF)).toShort() / 32768f * 180f)
                        while (yaw < 0) yaw += 360f; while (yaw >= 360) yaw -= 360f; _IMUYaw.value = yaw
                    }
                }
            }
        }
    }

    /**
     * Convert NMEA coordinate format (DDMM.MMMM) to decimal degrees.
     * Input is the NMEA value already divided by 10000 (i.e. rawLat = DDMM.MMMM as float).
     */
    private fun convertNmeaToDecimal(nmea: Float): Double {
        val degrees = (nmea / 100).toInt()
        val minutes = nmea - degrees * 100f
        return degrees + minutes / 60.0
    }

    private fun setupBleGps() {
        val process: (ByteArray) -> Unit = { frame ->
            _rawBleGpsFrame.value = frame
            //Log.d(TAG, "RECV A3 GPS: ${frame.joinToString(" ") { "%02X".format(it) }}")
            if (frame.size >= 17) {
                lastBleGpsUpdate = System.currentTimeMillis()
                //val lat = readS32LE(frame, 5) / 1_000_000.0; val lon = readS32LE(frame, 9) / 1_000_000.0
                //val rawLat = readS32LE(frame, 5) / 1_000_000.0; val rawLon = readS32LE(frame, 9) / 1_000_000.0
                val rawLat = readS32LE(frame, 5) / 10000.0f; val rawLon = readS32LE(frame, 9) / 10000.0f
                val lat      = convertNmeaToDecimal(rawLat)
                val lon      = convertNmeaToDecimal(rawLon)
                val speed = readU16LE(frame, 13) / 100.0f; val course = (readU16LE(frame, 15) / 100.0f).toInt()
                _gpsFix.value = true; _gpsSpeedKnots.value = speed; _gpsCourse.value = course
                _parsedBleGps.value = "Lat: %.6f, Lon: %.6f, Spd: %.1f, Cog: %d".format(lat, lon, speed, course)
                //Log.d(TAG, "RECV A3 GPS: Lat: %.6f, Lon: %.6f, Spd: %.1f, Cog: %d".format(lat, lon, speed, course))
                //_tvParsedGps.value = "Lat: %.6f, Lon: %.6f, Spd: %.1f, Cog: %d".format(lat, lon, speed, course)
                BleRepository.setCurrentLocation(GeoPoint(lat, lon))
                motorManager.updateGpsInfo(lat, lon, speed, course); gpsManager.updateGpsInfo(lat, lon, speed, course)
                val decl = GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, System.currentTimeMillis()).declination
                if (abs(_declination.value - decl) > 0.1f) {
                    _declination.value = decl
                    prefs.edit().putFloat(KEY_DECLINATION, decl).apply()
                    sensorFusion.setDeclination(decl)
                }

                // Also feed SensorFusion A3
                sensorFusion.processA3(lat, lon, speed, course.toFloat(), true, System.currentTimeMillis())
            } else {
                Log.w(TAG, "A3 GPS frame too short: ${frame.size}")
                _parsedBleGps.value = "Frame too short: ${frame.size}"
            }
        }
        //viewModelScope.launch { motorManager.bleGpsData.collect { process(it) } }
        viewModelScope.launch { gpsManager.bleGpsData.collect { process(it) } }
    }

    private fun setupSensorFusion() {
        sensorFusion.onFusedHeading = { state ->
            _fusedState.value = state
            // Sync gyro rate to repository for autopilot damping/limiting
            BleRepository.gyroZDegS.value = state.gyroZDegS

            // If SensorFusion has a valid heading, use it as the primary heading
            if (state.hasHeading) {
                _IMUYaw.value = state.headingDeg
                // We clear declination and offset because SensorFusion already applies them
                _declination.value = 0f
                _headingOffset.value = 0f
            }
            _IMUPitch.value = state.pitchDeg
            _IMURoll.value = state.rollDeg

            if (state.hasFix) {
                BleRepository.setCurrentLocation(GeoPoint(state.latDeg, state.lonDeg))
                _gpsSpeedKnots.value = state.speedKnots
            }

            // Update calibration status strings
            if (sensorFusion.isManualCalActive) {
                _sfMagCalStatus.value = "Recording: ${sensorFusion.manualMagCalSampleCount} samples (${sensorFusion.manualMagCalProgress}%)"
            }
            if (sensorFusion.isGyroBiasCalActive) {
                _sfGyroCalStatus.value = "Recording: ${sensorFusion.gyroBiasCalSampleCount} samples (${sensorFusion.gyroBiasCalProgress}%)"
            }
        }

        // Initialize declination from prefs
        sensorFusion.setDeclination(prefs.getFloat(KEY_DECLINATION, 0f))

        val processA1: (ByteArray) -> Unit = { frame ->
            if (frame.size >= 20) {
                val now = System.currentTimeMillis()
                val dt = if (lastA1Time > 0L) (now - lastA1Time) / 1000f else 0.02f
                lastA1Time = now

                val ax = readS16LE(frame, 2).toShort()
                val ay = readS16LE(frame, 4).toShort()
                val az = readS16LE(frame, 6).toShort()
                val gx = readS16LE(frame, 8).toShort()
                val gy = readS16LE(frame, 10).toShort()
                val gz = readS16LE(frame, 12).toShort()
                val mx = readS16LE(frame, 14).toShort()
                val my = readS16LE(frame, 16).toShort()
                val mz = readS16LE(frame, 18).toShort()

                _rawImuData.value = RawImuData(ax, ay, az, gx, gy, gz, mx, my, mz)

                if (sensorFusion.isManualCalActive) {
                    sensorFusion.feedManualMagSample(mx, my)
                }
                if (sensorFusion.isGyroBiasCalActive) {
                    sensorFusion.feedGyroBiasSample(gx, gy, gz)
                }

                // Apply SensorFusion processing
                sensorFusion.processA1(ax, ay, az, gx, gy, gz, mx, my, mz, now, accelRotated180 = false)

                // Continuous integration for verification in UI
                _calibDegreesTurned.value += sensorFusion.getState().gyroZDegS * dt
            }
        }

        val processA2: (ByteArray) -> Unit = { frame ->
            if (frame.size >= 17) {
                val qual = frame[5].toInt() and 0xFF
                val base = readU16LE(frame, 6) / 1000f
                val pitch = readS16LE(frame, 8) / 100f
                val roll = readS16LE(frame, 10) / 100f
                val hdg = readU16LE(frame, 12) / 100f
                val acc = readU16LE(frame, 14) / 1000f
                val sats = frame[16].toInt() and 0xFF

                sensorFusion.processA2(hdg, pitch, roll, acc, base, qual, sats, System.currentTimeMillis())
            }
        }

        viewModelScope.launch { imuManager.imuA1Data.collect { processA1(it) } }
        //viewModelScope.launch { motorManager.imuA1Data.collect { processA1(it) } }
        viewModelScope.launch { gpsManager.imuA1Data.collect { processA1(it) } }
        viewModelScope.launch { imuManager.gnssA2Data.collect { processA2(it) } }
        //viewModelScope.launch { motorManager.gnssA2Data.collect { processA2(it) } }
        viewModelScope.launch { gpsManager.gnssA2Data.collect { processA2(it) } }
    }

    private fun setupAutoCalibration() {
        viewModelScope.launch {
            val gpsStraightFlow = combine(gpsSpeedKnots, gpsCourse, rudderPosition) { s, c, r -> if (s > 3.5f && c != null && abs(r) < 2.0f) c.toFloat() else null }
            combine(seaState, gpsStraightFlow, IMUYaw, declination) { s, tc, y, d ->
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
                val Councilor = BleRepository.steerValue.value
                BleRepository.adjustSteer(if (Councilor > 0) -1 else 1)
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
            // Prefer BLE GPS (0xA3) if it was updated in the last 10 seconds
            if (System.currentTimeMillis() - lastBleGpsUpdate < 10000) return
            
            val loc = res.lastLocation ?: return
            _gpsFix.value = true
            _gpsSpeedKnots.value = loc.speed * 1.94384f
            _gpsCourse.value = if (loc.hasBearing()) loc.bearing.toInt() else null
            BleRepository.setCurrentLocation(GeoPoint(loc.latitude, loc.longitude))
            motorManager.updateGpsInfo(loc.latitude, loc.longitude, _gpsSpeedKnots.value.toFloat(), _gpsCourse.value)
            val d = GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), System.currentTimeMillis()).declination
            if (abs(_declination.value - d) > 0.1f) {
                _declination.value = d
                prefs.edit().putFloat(KEY_DECLINATION, d).apply() 
                sensorFusion.setDeclination(d)
            }
        }
    }

    fun stopGpsUpdates() { fusedLocationClient.removeLocationUpdates(locationCallback); _gpsFix.value = false }
    fun startScan() = scanner.startScan(_scanAllNames.value)
    fun startRemoteScan() = scanner.startRemoteScan(); fun startImuScan() = scanner.startImuScan(); fun startGpsScan() = scanner.startGpsScan();
    fun stopScan() = scanner.stopScan(); fun setScanAllNames(all: Boolean) { _scanAllNames.value = all }


// In MainViewModel.kt

    fun connect(disc: DiscoveredDevice) {
        viewModelScope.launch {
            try {
                when {
                    disc.name.contains("LOOKBON", true) -> remote.connectToDevice(disc.device)
                    // More specific matching for motor/steering control
                    disc.name.contains("UART", true) || disc.name.contains("Steer", true) -> motorManager.connectToDevice(disc.device)
                    disc.name.contains("GPS", true) || disc.name.contains("IMU", true) -> {
                        // Decide if it should be gpsManager or imuManager
                        if (disc.name.contains("GPS", true)) gpsManager.connectToDevice(disc.device)
                        else imuManager.connectToDevice(disc.device)
                    }
                    else -> motorManager.connectToDevice(disc.device)
                }
                stopScan()
            } catch (e: Exception) { Log.e(TAG, "Connect failed", e) }
        }
    }

    fun setShowRawData(s: Boolean) { _showRawData.value = s; motorManager.setRawDataEnabled(s); imuManager.setRawDataEnabled(s); gpsManager.setRawDataEnabled(s); prefs.edit().putBoolean(KEY_SHOW_RAW, s).apply() }
    fun setEnableLogging(e: Boolean) { _enableLogging.value = e; motorManager.setLoggingEnabled(e); imuManager.setLoggingEnabled(e); gpsManager.setLoggingEnabled(e); prefs.edit().putBoolean(KEY_LOGGING, e).apply() }
    fun setEnableVoicePrompts(e: Boolean) = prefs.edit().putBoolean(KEY_VOICE, e).apply().also { _enableVoicePrompts.value = e; BleRepository.enableVoicePrompts = e }
    fun setShowMotorStatus(s: Boolean) = prefs.edit().putBoolean(KEY_SHOW_MOTOR_STATUS, s).apply().also { _showMotorStatus.value = s; BleRepository.showMotorStatus = s }
    fun setSteerScale(s: Int) = prefs.edit().putInt(KEY_STEER_SCALE, s).apply().also { _steerScale.value = s; BleRepository.steerScale = s }
    fun setQmcLpfEnabled(e: Boolean) = prefs.edit().putBoolean(KEY_QMC_LPF, e).apply().also { _qmcLpfEnabled.value = e }

    fun connectToDevice(address: String) {
        val device = bluetoothAdapter.getRemoteDevice(address)
        viewModelScope.launch {
            try {
                motorManager.connectToDevice(device)
                imuManager.connectToDevice(device)
                gpsManager.connectToDevice(device)
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
            }
        }
        prefs.edit().putString(KEY_REMOTE_MAC, address).apply()
    }

    fun disconnect() { motorManager.disconnectDevice(); imuManager.disconnectDevice(); gpsManager.disconnectDevice() }

    fun speak(text: String) = BleRepository.speak(text)

    fun calibrateZero() { steerProcessor.calibrateZero(_magX.value, _magY.value); saveSteerCalib() }
    fun calibratePort() { steerProcessor.calibratePort(_magX.value, _magY.value); saveSteerCalib() }
    fun calibrateStbd() { steerProcessor.calibrateStbd(_magX.value, _magY.value); saveSteerCalib() }

    private fun saveSteerCalib() {
        prefs.edit().putInt(KEY_CALIB_ZERO, steerProcessor.zeroX).putInt(KEY_CALIB_PORT, steerProcessor.portX).putInt(KEY_CALIB_STBD, steerProcessor.stbdX).apply()
    }

    fun startMagEllipseCalib() { magCalibrator.clear(); _isMagCalibrating.value = true }
    fun stopMagEllipseCalib() {
        _isMagCalibrating.value = false
        val res = magCalibrator.fit()
        if (res != null) { _magEllipseResult.value = res; steerProcessor.setEllipse(res.centerX, res.centerY, res.axisA, res.axisB, res.angle) }
    }
    fun saveMagEllipseCalib() {
        val res = _magEllipseResult.value ?: return
        prefs.edit().putFloat(KEY_MAG_ELLIPSE_CX, res.centerX).putFloat(KEY_MAG_ELLIPSE_CY, res.centerY).putFloat(KEY_MAG_ELLIPSE_A, res.axisA).putFloat(KEY_MAG_ELLIPSE_B, res.axisB).putFloat(KEY_MAG_ELLIPSE_ANGLE, res.angle).putBoolean(KEY_MAG_ELLIPSE_VALID, true).apply()
    }
    fun clearMagEllipseCalib() { _magEllipseResult.value = null; prefs.edit().remove(KEY_MAG_ELLIPSE_CX).apply() }

    private fun loadMagEllipse(): MagEllipseCalibrator.Result? {
        if (!prefs.contains(KEY_MAG_ELLIPSE_CX)) return null
        return MagEllipseCalibrator.Result(prefs.getFloat(KEY_MAG_ELLIPSE_CX, 0f), prefs.getFloat(KEY_MAG_ELLIPSE_CY, 0f), prefs.getFloat(KEY_MAG_ELLIPSE_A, 1f), prefs.getFloat(KEY_MAG_ELLIPSE_B, 1f), prefs.getFloat(KEY_MAG_ELLIPSE_ANGLE, 0f))
    }

    fun resetSFDegrees() {
        _calibDegreesTurned.value = 0f
    }

    fun startSFusionMagCal() {
        sensorFusion.startManualMagCal()
        _calibDegreesTurned.value = 0f
        _isSFusionMagCalibrating.value = true
        _sfMagCalStatus.value = "Starting..."
        speak("Heading calibration started. Please rotate the boat 360 degrees.")
    }

    fun stopSFusionMagCal() {
        if (sensorFusion.finishManualMagCal()) {
            _isSFusionMagCalibrating.value = false
            _sfMagCalStatus.value = "Calibrated: X=${"%.0f".format(sensorFusion.manualCalHardIronX)} Y=${"%.0f".format(sensorFusion.manualCalHardIronY)}"
            prefs.edit().putFloat(KEY_SF_MAG_BIAS_X, sensorFusion.manualCalHardIronX)
                .putFloat(KEY_SF_MAG_BIAS_Y, sensorFusion.manualCalHardIronY).apply()
            speak("Heading calibration complete")
        } else {
            _isSFusionMagCalibrating.value = false
            _sfMagCalStatus.value = "Failed: Not enough data"
            speak("Calibration failed, not enough data")
        }
    }

    fun startSFusionGyroCal() {
        sensorFusion.startGyroBiasCal()
        _isSFusionGyroCalibrating.value = true
        _sfGyroCalStatus.value = "Starting..."
        speak("Gyro calibration started. Keep the boat steady.")
    }

    fun stopSFusionGyroCal() {
        if (sensorFusion.finishGyroBiasCal()) {
            _isSFusionGyroCalibrating.value = false
            _sfGyroCalStatus.value = "Calibrated: Z=${"%.3f".format(sensorFusion.gyroBiasZ)}"
            prefs.edit().putFloat(KEY_SF_GYRO_BIAS_X, sensorFusion.gyroBiasX)
                .putFloat(KEY_SF_GYRO_BIAS_Y, sensorFusion.gyroBiasY)
                .putFloat(KEY_SF_GYRO_BIAS_Z, sensorFusion.gyroBiasZ).apply()
            speak("Gyro calibration complete")
        } else {
            _isSFusionGyroCalibrating.value = false
            _sfGyroCalStatus.value = "Failed: Not enough data"
            speak("Gyro calibration failed")
        }
    }

    private fun loadWaypoints() = prefs.getString(KEY_WAYPOINTS, null)?.split(";")?.filter { it.isNotBlank() }?.map {
        it.split(",").let { pts -> Waypoint(pts[0], GeoPoint(pts[1].toDouble(), pts[2].toDouble())) }
    } ?: emptyList()
    private fun saveWaypoints(points: List<Waypoint>) = prefs.edit().putString(KEY_WAYPOINTS, points.joinToString(";") { "${it.name},${it.point.latitude},${it.point.longitude}" }).apply()

    fun saveLocation(name: String) = (targetLocation.value ?: currentLocation.value)?.let { loc ->
        val updated = waypoints.value.toMutableList().apply { add(Waypoint(name, loc)) }
        BleRepository.setWaypoints(updated); saveWaypoints(updated); speak("Location $name saved")
    }

    fun removeWaypoint(waypoint: Waypoint) = waypoints.value.toMutableList().apply { remove(waypoint) }.let { updated ->
        BleRepository.setWaypoints(updated); saveWaypoints(updated); if (targetLocation.value?.latitude == waypoint.point.latitude) BleRepository.setTarget(null, null)
    }

    override fun onCleared() {
        super.onCleared()
        stopGpsUpdates()
    }
    fun clearWaypoints() = BleRepository.setWaypoints(emptyList()).also { saveWaypoints(emptyList()); BleRepository.setTarget(null, null); speak("Cleared") }
    fun setTargetLocation(loc: GeoPoint?, name: String? = null) = BleRepository.setTarget(loc, name).also { if (loc != null) speak("Target set") }

    fun setApKp(v: Float) { _apKp.value = v; prefs.edit().putFloat(KEY_AP_KP, v).apply(); BleRepository.apKp = v }
    fun setApKi(v: Float) { _apKi.value = v; prefs.edit().putFloat(KEY_AP_KI, v).apply(); BleRepository.apKi = v }
    fun setApKd(v: Float) { _apKd.value = v; prefs.edit().putFloat(KEY_AP_KD, v).apply(); BleRepository.apKd = v }
    fun setApDeadband(v: Float) { _apDeadband.value = v; prefs.edit().putFloat(KEY_AP_DEADBAND, v).apply(); BleRepository.apDeadband = v }
    fun setApMaxRate(v: Float) { _apMaxRate.value = v; prefs.edit().putFloat(KEY_AP_MAX_RATE, v).apply(); BleRepository.maxTurnRate = v }
    fun setUseRudderSensor(v: Boolean) { _useRudderSensor.value = v; prefs.edit().putBoolean(KEY_USE_RUDDER_SENSOR, v).apply(); BleRepository.useRudderSensor = v }

    // Helper methods for reading BLE data
    private fun readS16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or (data[offset + 1].toInt() shl 8)
    }
    private fun readU16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }
    private fun readS32LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8) or ((data[offset + 2].toInt() and 0xFF) shl 16) or (data[offset + 3].toInt() shl 24)
    }
}
