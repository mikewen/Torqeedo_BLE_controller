package com.torqeedo.controller.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.hardware.GeomagneticField
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.*
import com.torqeedo.controller.ble.*
import com.torqeedo.controller.protocol.SteerSensorProcessor
import com.torqeedo.controller.protocol.TorqeedoProtocol
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class MainViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    enum class Direction { FORWARD, REVERSE }
    enum class SeaState { CALM, MODERATE, ROUGH }

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
        
        // 2D Vector Calibration Points (A, B)
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

        const val SPEED_MAX = 1000
        const val SPEED_MIN = 0
        
        private const val DEFAULT_SPEED_STEP = 20        // 2% steps
        private const val DEFAULT_AUTO_DELAY = 200L      // 5 steps per second (10% / sec)
        private const val DEFAULT_THROTTLE_DELAY = 200L  // 5 Hz throttle loop
        private const val STATUS_QUERY_DELAY = 500L      // 2 Hz status query
        private const val SENSOR_READ_DELAY = 200L       // 5 Hz current sensor read
        
        private const val REMOTE_STEER_STEP = 1          // Small step for hold/repeat
        private const val REMOTE_STEER_CLICK_STEP = 1    // Larger step for single click
        private const val STEER_REPEAT_DELAY = 80L      // 12.5 Hz repeat rate for steering
        const val STEER_MAX = 50
        private const val DEFAULT_STEER_SCALE = 10

        private const val AUTOPILOT_DELAY = 200L         // 5 Hz autopilot loop
        private const val KEY_AP_KP = "ap_kp"
        private const val KEY_AP_KI = "ap_ki"
        private const val KEY_AP_KD = "ap_kd"
        
        private const val DEFAULT_AP_KP = 2.5f
        private const val DEFAULT_AP_KI = 0.1f
        private const val DEFAULT_AP_KD = 1.0f
        private const val AUTOPILOT_MAX_I = 20f          // Max integral contribution
    }

    private val prefs: SharedPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Configurable parameters as StateFlows
    private val _speedStep = MutableStateFlow(DEFAULT_SPEED_STEP)
    val speedStep: StateFlow<Int> = _speedStep.asStateFlow()

    private val _autoIncrementDelay = MutableStateFlow(DEFAULT_AUTO_DELAY)
    val autoIncrementDelay: StateFlow<Long> = _autoIncrementDelay.asStateFlow()

    private val _throttleDelay = MutableStateFlow(DEFAULT_THROTTLE_DELAY)
    val throttleDelay: StateFlow<Long> = _throttleDelay.asStateFlow()

    private val _scanAllNames = MutableStateFlow(false)
    val scanAllNames: StateFlow<Boolean> = _scanAllNames.asStateFlow()

    // Debug settings - persisted
    private val _showRawData = MutableStateFlow(prefs.getBoolean(KEY_SHOW_RAW, true))
    val showRawData: StateFlow<Boolean> = _showRawData.asStateFlow()

    private val _enableLogging = MutableStateFlow(prefs.getBoolean(KEY_LOGGING, true))
    val enableLogging: StateFlow<Boolean> = _enableLogging.asStateFlow()

    private val _enableVoicePrompts = MutableStateFlow(prefs.getBoolean(KEY_VOICE, true))
    val enableVoicePrompts: StateFlow<Boolean> = _enableVoicePrompts.asStateFlow()

    private val _showMotorStatus = MutableStateFlow(prefs.getBoolean(KEY_SHOW_MOTOR_STATUS, false))
    val showMotorStatus: StateFlow<Boolean> = _showMotorStatus.asStateFlow()

    private val _steerScale = MutableStateFlow(prefs.getInt(KEY_STEER_SCALE, DEFAULT_STEER_SCALE))
    val steerScale: StateFlow<Int> = _steerScale.asStateFlow()

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

    val scanResults:     StateFlow<List<DiscoveredDevice>>             = scanner.devices
    val isScanning:      StateFlow<Boolean>                            = scanner.isScanning
    val motorStatus:     StateFlow<TorqeedoProtocol.MotorStatus?>      =
        motorManager.statusFlow.stateIn(viewModelScope, SharingStarted.Lazily, null)
    
    val sensorCurrent: StateFlow<Float> = motorManager.sensorCurrent
    
    // Estimated Power at 47V
    val estimatedPowerW: StateFlow<Float> = sensorCurrent.map { it * 47.0f }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    val rawStatus: StateFlow<ByteArray?> = 
        motorManager.rawStatusFlow.stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _direction = MutableStateFlow(Direction.FORWARD)
    val direction: StateFlow<Direction> = _direction.asStateFlow()

    private val _speedMagnitude = MutableStateFlow(0)
    val speedMagnitude: StateFlow<Int> = _speedMagnitude.asStateFlow()

    val currentSpeed: StateFlow<Int> =
        combine(_direction, _speedMagnitude) { dir, mag ->
            if (dir == Direction.FORWARD) mag else -mag
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _steerValue = MutableStateFlow(0)
    val steerValue: StateFlow<Int> = _steerValue.asStateFlow()

    // Magnetometer / Rudder Position (MMC5603 on motor)
    private val _magX = MutableStateFlow(0)
    val magX: StateFlow<Int> = _magX.asStateFlow()
    private val _magY = MutableStateFlow(0)
    val magY: StateFlow<Int> = _magY.asStateFlow()
    private val _magZ = MutableStateFlow(0)
    val magZ: StateFlow<Int> = _magZ.asStateFlow()

    // New Steer Sensor Position
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

    // WitMotion IMU Data
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

    // WitMotion IMU Magnetometer
    private val _witMagX = MutableStateFlow(0)
    val witMagX: StateFlow<Int> = _witMagX.asStateFlow()
    private val _witMagY = MutableStateFlow(0)
    val witMagY: StateFlow<Int> = _witMagY.asStateFlow()
    private val _witMagZ = MutableStateFlow(0)
    val witMagZ: StateFlow<Int> = _witMagZ.asStateFlow()

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

    // Calibration points (using Y axis as primary for rudder position)
    private val _calibZero = MutableStateFlow(prefs.getInt(KEY_CALIB_ZERO, 0))
    private val _calibPort = MutableStateFlow(prefs.getInt(KEY_CALIB_PORT, 0))
    private val _calibStbd = MutableStateFlow(prefs.getInt(KEY_CALIB_STBD, 0))

    val rudderPosition = combine(_magY, _calibZero, _calibPort, _calibStbd) { y, zero, port, stbd ->
        val diff = (y - zero).toFloat()
        if (abs(diff) < 1f) return@combine 0f
        
        val portRange = (port - zero).toFloat()
        val stbdRange = (stbd - zero).toFloat()
        
        val pos = when {
            abs(portRange) > 10 && (diff / portRange) > 0 -> {
                (diff / portRange) * -100f
            }
            abs(stbdRange) > 10 && (diff / stbdRange) > 0 -> {
                (diff / stbdRange) * 100f
            }
            else -> 0f
        }
        pos.coerceIn(-100f, 100f)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    // IMU Calibration State
    private val _imuCalibStatus = MutableStateFlow("Idle")
    val imuCalibStatus: StateFlow<String> = _imuCalibStatus.asStateFlow()

    // GPS State
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    
    private val _gpsFix = MutableStateFlow(false)
    val gpsFix: StateFlow<Boolean> = _gpsFix.asStateFlow()

    private val _gpsSpeedKnots = MutableStateFlow(0.0f)
    val gpsSpeedKnots: StateFlow<Float> = _gpsSpeedKnots.asStateFlow()

    private val _gpsCourse = MutableStateFlow<Int?>(null)
    val gpsCourse: StateFlow<Int?> = _gpsCourse.asStateFlow()

    // Auto-pilot state
    private val _autoPilotActive = MutableStateFlow(false)
    val autoPilotActive: StateFlow<Boolean> = _autoPilotActive.asStateFlow()

    private val _targetHeading = MutableStateFlow(0f)
    val targetHeading: StateFlow<Float> = _targetHeading.asStateFlow()

    // PID Gains
    private val _apKp = MutableStateFlow(prefs.getFloat(KEY_AP_KP, DEFAULT_AP_KP))
    val apKp: StateFlow<Float> = _apKp.asStateFlow()

    private val _apKi = MutableStateFlow(prefs.getFloat(KEY_AP_KI, DEFAULT_AP_KI))
    val apKi: StateFlow<Float> = _apKi.asStateFlow()

    private val _apKd = MutableStateFlow(prefs.getFloat(KEY_AP_KD, DEFAULT_AP_KD))
    val apKd: StateFlow<Float> = _apKd.asStateFlow()

    private var autopilotLastError = 0f
    private var autopilotIntegral = 0f

    private var throttleJob: Job? = null
    private var statusQueryJob: Job? = null
    private var sensorReadJob: Job? = null
    private var autoAdjustmentJob: Job? = null
    private var steerRepeatJob: Job? = null
    private var resetSteerJob: Job? = null
    private var autoPilotJob: Job? = null
    
    private var lastBleGpsUpdate = 0L

    private var tts: TextToSpeech? = TextToSpeech(application, this)

    init {
        setupRemote()
        setupConnectionHandlers()
        setupMagnetometer()
        setupSteerSensor()
        setupWitMotion()
        setupBleGps()
        setupAutoCalibration()
        
        // Initial setup for managers from persisted values
        motorManager.setRawDataEnabled(_showRawData.value)
        motorManager.setLoggingEnabled(_enableLogging.value)
        imuManager.setRawDataEnabled(_showRawData.value)
        imuManager.setLoggingEnabled(_enableLogging.value)
        gpsManager.setRawDataEnabled(_showRawData.value)
        gpsManager.setLoggingEnabled(_enableLogging.value)

        // Load Steer Sensor Biases
        steerProcessor.bias1 = prefs.getInt(KEY_BIAS1, SteerSensorProcessor.DEFAULT_BIAS)
        steerProcessor.bias2 = prefs.getInt(KEY_BIAS2, SteerSensorProcessor.DEFAULT_BIAS)
        
        loadSteerLutData()

        // Auto-reconnect to remote if we have a saved MAC
        if (!remote.isConnected) {
            prefs.getString(KEY_REMOTE_MAC, null)?.let { mac ->
                try {
                    val device = bluetoothAdapter.getRemoteDevice(mac)
                    remote.connectToDevice(device, autoReconnect = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to auto-reconnect to remote: $mac", e)
                }
            }
        } else {
            _remoteConnected.value = true
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
        } else {
            Log.e(TAG, "TTS Initialization failed")
        }
    }

    private fun speak(text: String) {
        if (_enableVoicePrompts.value) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_prompt")
        }
    }

    private fun setupRemote() {
        remote.onConnected = { 
            _remoteConnected.value = true
            speak("Remote connected")
            // Save MAC address for auto-reconnect
            remote.bluetoothDevice?.address?.let { mac ->
                prefs.edit().putString(KEY_REMOTE_MAC, mac).apply()
            }
        }
        remote.onDisconnected = { 
            _remoteConnected.value = false
            speak("Remote disconnected")
        }
        remote.onCommand = { cmd ->
            when (cmd) {
                LookbonRemote.Command.SPEED_UP -> {
                    increaseSpeed()
                    speak("${speedMagnitude.value / 10} percent")
                }
                LookbonRemote.Command.SPEED_DOWN -> {
                    decreaseSpeed()
                    speak("${speedMagnitude.value / 10} percent")
                }
                LookbonRemote.Command.START_REPEAT_UP -> {
                    startAutoIncrease(multiplier = 1)
                }
                LookbonRemote.Command.START_REPEAT_DOWN -> {
                    startAutoDecrease(multiplier = 1)
                }
                LookbonRemote.Command.START_REPEAT_UP_FAST -> {
                    startAutoIncrease(multiplier = 2)
                }
                LookbonRemote.Command.START_REPEAT_DOWN_FAST -> {
                    startAutoDecrease(multiplier = 2)
                }
                LookbonRemote.Command.STOP_REPEAT -> {
                    stopAutoAdjustment()
                    speak("${speedMagnitude.value / 10} percent")
                }
                LookbonRemote.Command.SPEED_UP_FAST -> {
                    repeat(5) { increaseSpeed() }
                    speak("${speedMagnitude.value / 10} percent")
                }
                LookbonRemote.Command.SPEED_DOWN_FAST -> {
                    repeat(5) { decreaseSpeed() }
                    speak("${speedMagnitude.value / 10} percent")
                }
                LookbonRemote.Command.STOP -> {
                    stopMotor()
                    speak("Stop")
                }
                LookbonRemote.Command.TOGGLE_DIRECTION -> {
                    val newDir = if (direction.value == Direction.FORWARD) Direction.REVERSE else Direction.FORWARD
                    setDirection(newDir)
                    speak(newDir.name.lowercase())
                }
                LookbonRemote.Command.STEER_LEFT -> {
                    speak("Left")
                    adjustSteer(-REMOTE_STEER_CLICK_STEP)
                }
                LookbonRemote.Command.STEER_RIGHT -> {
                    speak("Right")
                    adjustSteer(REMOTE_STEER_CLICK_STEP)
                }
                LookbonRemote.Command.START_STEER_LEFT -> {
                    speak("Left")
                    startSteerRepeat(-REMOTE_STEER_STEP)
                }
                LookbonRemote.Command.START_STEER_RIGHT -> {
                    speak("Right")
                    startSteerRepeat(REMOTE_STEER_STEP)
                }
                LookbonRemote.Command.STOP_STEER -> {
                    stopSteerRepeat()
                }
            }
        }
    }

    private fun setupConnectionHandlers() {
        viewModelScope.launch {
            motorConnectionState.collect { state ->
                when (state) {
                    TorqeedoBleManager.ConnectionState.CONNECTED -> {
                        speak("Motor connected")
                        startThrottleLoop()
                        startStatusQueryLoop()
                        startSensorReadLoop()
                    }
                    TorqeedoBleManager.ConnectionState.DISCONNECTED -> {
                        speak("Motor disconnected")
                        stopThrottleLoop()
                        stopStatusQueryLoop()
                        stopSensorReadLoop()
                    }
                    else -> {}
                }
            }
        }
        viewModelScope.launch {
            imuConnectionState.collect { state ->
                when (state) {
                    TorqeedoBleManager.ConnectionState.CONNECTED -> speak("Heading sensor connected")
                    TorqeedoBleManager.ConnectionState.DISCONNECTED -> speak("Heading sensor disconnected")
                    else -> {}
                }
            }
        }
        viewModelScope.launch {
            gpsConnectionState.collect { state ->
                when (state) {
                    TorqeedoBleManager.ConnectionState.CONNECTED -> speak("G P S connected")
                    TorqeedoBleManager.ConnectionState.DISCONNECTED -> speak("G P S disconnected")
                    else -> {}
                }
            }
        }
    }

    private fun setupMagnetometer() {
        viewModelScope.launch {
            motorManager.magnetometerData.collect { bytes ->
                if (bytes.size >= 8) {
                    // MMC5603 20-bit data unpacking
                    val xUnsigned = ((bytes[0].toInt() and 0xFF) shl 12) or ((bytes[1].toInt() and 0xFF) shl 4) or (bytes[6].toInt() and 0x0F)
                    val yUnsigned = ((bytes[2].toInt() and 0xFF) shl 12) or ((bytes[3].toInt() and 0xFF) shl 4) or (bytes[7].toInt() and 0x0F)
                    val zUnsigned = ((bytes[4].toInt() and 0xFF) shl 12) or ((bytes[5].toInt() and 0xFF) shl 4) or (bytes[8].toInt() and 0x0F)
                    _magX.value = xUnsigned - 524288
                    _magY.value = yUnsigned - 524288
                    _magZ.value = zUnsigned - 524288
                }
            }
        }
    }

    private fun setupSteerSensor() {
        viewModelScope.launch {
            motorManager.steerSensorData.collect { data ->
                _steerSensorA.value = data.sensorA
                _steerSensorB.value = data.sensorB
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
                    0x53 -> { // Angle: Roll, Pitch, Yaw (11-byte frame)
                        if (frame.size < 11) return@collect
                        val rollRaw  = ((frame[3].toInt() shl 8) or (frame[2].toInt() and 0xFF)).toShort()
                        val pitchRaw = ((frame[5].toInt() shl 8) or (frame[4].toInt() and 0xFF)).toShort()
                        val yawRaw   = ((frame[7].toInt() shl 8) or (frame[6].toInt() and 0xFF)).toShort()
                        
                        _witRoll.value  = rollRaw  / 32768f * 180f
                        _witPitch.value = pitchRaw / 32768f * 180f
                        
                        // Invert yaw so CW turn increases degrees, and normalize to 0-360
                        var yaw = -(yawRaw / 32768f * 180f)
                        while (yaw < 0) yaw += 360f
                        while (yaw >= 360) yaw -= 360f
                        _witYaw.value = yaw
                    }
                    0x54 -> { // Magnetometer: Hx, Hy, Hz (11-byte frame)
                        if (frame.size < 11) return@collect
                        val hx = ((frame[3].toInt() shl 8) or (frame[2].toInt() and 0xFF)).toShort().toInt()
                        val hy = ((frame[5].toInt() shl 8) or (frame[4].toInt() and 0xFF)).toShort().toInt()
                        val hz = ((frame[7].toInt() shl 8) or (frame[6].toInt() and 0xFF)).toShort().toInt()
                        _witMagX.value = hx
                        _witMagY.value = hy
                        _witMagZ.value = hz
                    }
                    0x61 -> { // Combined BLE 5.0 Data (20-byte frame)
                        if (frame.size < 20) return@collect
                        // Roll: [14..15], Pitch: [16..17], Yaw: [18..19]
                        val rollRaw  = ((frame[15].toInt() shl 8) or (frame[14].toInt() and 0xFF)).toShort()
                        val pitchRaw = ((frame[17].toInt() shl 8) or (frame[16].toInt() and 0xFF)).toShort()
                        val yawRaw   = ((frame[19].toInt() shl 8) or (frame[18].toInt() and 0xFF)).toShort()
                        
                        _witRoll.value  = rollRaw  / 32768f * 180f
                        _witPitch.value = pitchRaw / 32768f * 180f
                        
                        // Invert yaw so CW turn increases degrees, and normalize to 0-360
                        var yaw = -(yawRaw / 32768f * 180f)
                        while (yaw < 0) yaw += 360f
                        while (yaw >= 360) yaw -= 360f
                        _witYaw.value = yaw
                    }
                }
            }
        }
    }

    private fun setupBleGps() {
        val processGpsFrame: (ByteArray) -> Unit = { frame ->
            if (frame.size >= 17) {
                lastBleGpsUpdate = System.currentTimeMillis()

                val latRaw = readS32LE(frame, 5)
                val longitudeRaw = readS32LE(frame, 9)
                val speedRaw = readU16LE(frame, 13)
                val courseRaw = readU16LE(frame, 15)

                val lat = latRaw / 1_000_000.0
                val lon = longitudeRaw / 1_000_000.0
                val speedKnots = speedRaw / 100.0f
                val course = (courseRaw / 100.0f).toInt()

                _gpsFix.value = true
                _gpsSpeedKnots.value = speedKnots
                _gpsCourse.value = course
                
                motorManager.updateGpsInfo(lat, lon, speedKnots, course)
                gpsManager.updateGpsInfo(lat, lon, speedKnots, course)

                // Update magnetic declination
                val geomag = GeomagneticField(
                    lat.toFloat(),
                    lon.toFloat(),
                    0f,
                    System.currentTimeMillis()
                )
                val decl = geomag.declination
                if (abs(_declination.value - decl) > 0.1f) {
                    _declination.value = decl
                    prefs.edit().putFloat(KEY_DECLINATION, decl).apply()
                }
            }
        }

        viewModelScope.launch {
            motorManager.bleGpsData.collect { frame ->
                processGpsFrame(frame)
            }
        }
        viewModelScope.launch {
            gpsManager.bleGpsData.collect { frame ->
                processGpsFrame(frame)
            }
        }
    }

    private fun setupAutoCalibration() {
        viewModelScope.launch {
            // Nest combines to stay within typed overloads (max 5 flows)
            val gpsStraightFlow = combine(gpsSpeedKnots, gpsCourse, rudderPosition) { speed, course, rudder ->
                if (speed > 3.5f && course != null && abs(rudder) < 2.0f) {
                    course.toFloat()
                } else {
                    null
                }
            }

            combine(seaState, gpsStraightFlow, witYaw, declination) { state, targetCog, yaw, decl ->
                if (state == SeaState.CALM && targetCog != null) {
                    // Conditions met, calculate required offset to match COG
                    var currentHdgNoOffset = yaw + decl
                    while (currentHdgNoOffset < 0) currentHdgNoOffset += 360f
                    while (currentHdgNoOffset >= 360) currentHdgNoOffset -= 360f
                    
                    var diff = targetCog - currentHdgNoOffset
                    while (diff > 180f) diff -= 360f
                    while (diff < -180f) diff += 360f
                    diff
                } else {
                    null
                }
            }.collectLatest { targetOffset ->
                if (targetOffset != null) {
                    val currentOffset = _headingOffset.value
                    var diff = targetOffset - currentOffset
                    while (diff > 180f) diff -= 360f
                    while (diff < -180f) diff += 360f
                    
                    // Very slow adjustment (0.1% per update) to pull the offset toward target
                    val newOffset = currentOffset + (diff * 0.001f) 

                    if (abs(newOffset - currentOffset) > 0.0001f) {
                        _headingOffset.value = newOffset
                        prefs.edit().putFloat(KEY_HEADING_OFFSET, newOffset).apply()
                    }
                }
            }
        }
    }

    // ── GPS ───────────────────────────────────────────────────────────────
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            // If BLE GPS has been updated recently (within last 2 seconds), ignore phone GPS
            if (System.currentTimeMillis() - lastBleGpsUpdate < 2000) return

            val location = locationResult.lastLocation ?: return
            _gpsFix.value = true
            val speedKnots = location.speed * 1.94384f
            _gpsSpeedKnots.value = speedKnots
            val course = if (location.hasBearing()) location.bearing.toInt() else null
            _gpsCourse.value = course
            motorManager.updateGpsInfo(location.latitude, location.longitude, speedKnots, course)

            // Update magnetic declination
            val geomag = GeomagneticField(
                location.latitude.toFloat(),
                location.longitude.toFloat(),
                location.altitude.toFloat(),
                System.currentTimeMillis()
            )
            val decl = geomag.declination
            if (abs(_declination.value - decl) > 0.1f) {
                _declination.value = decl
                prefs.edit().putFloat(KEY_DECLINATION, decl).apply()
            }
        }
        override fun onLocationAvailability(availability: LocationAvailability) {
            if (System.currentTimeMillis() - lastBleGpsUpdate < 2000) {
                _gpsFix.value = true
            } else {
                _gpsFix.value = availability.isLocationAvailable
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startGpsUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    fun stopGpsUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        motorManager.updateGpsInfo(null, null, null, null)
        _gpsFix.value = false
    }

    // ── Scan ──────────────────────────────────────────────────────────────
    fun setScanAllNames(scanAll: Boolean) {
        _scanAllNames.value = scanAll
    }

    fun startScan() = scanner.startScan(_scanAllNames.value)
    fun startRemoteScan() = scanner.startRemoteScan()
    fun startImuScan() = scanner.startImuScan()
    fun startGpsScan() = scanner.startGpsScan()
    fun stopScan()  = scanner.stopScan()

    fun connect(discovered: DiscoveredDevice) {
        val name = discovered.name
        val device = discovered.device
        
        viewModelScope.launch {
            try {
                when {
                    name.contains("LOOKBON", ignoreCase = true) -> {
                        remote.connectToDevice(device)
                    }
                    name.contains("WitMotion", ignoreCase = true) -> {
                        imuManager.connectToDevice(device)
                    }
                    name.contains("GPS", ignoreCase = true) -> {
                        gpsManager.connectToDevice(device)
                    }
                    else -> {
                        motorManager.connectToDevice(device)
                    }
                }
                stopScan()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to $name: ${e.message}")
            }
        }
    }

    fun disconnect() {
        motorManager.disconnectDevice()
        imuManager.disconnectDevice()
        gpsManager.disconnectDevice()
        remote.disconnect().enqueue()
    }

    // ── Debug ─────────────────────────────────────────────────────────────
    fun setShowRawData(show: Boolean) {
        _showRawData.value = show
        motorManager.setRawDataEnabled(show)
        imuManager.setRawDataEnabled(show)
        gpsManager.setRawDataEnabled(show)
        prefs.edit().putBoolean(KEY_SHOW_RAW, show).apply()
    }

    fun setEnableLogging(enabled: Boolean) {
        _enableLogging.value = enabled
        motorManager.setLoggingEnabled(enabled)
        imuManager.setLoggingEnabled(enabled)
        gpsManager.setLoggingEnabled(enabled)
        prefs.edit().putBoolean(KEY_LOGGING, enabled).apply()
    }

    fun setEnableVoicePrompts(enabled: Boolean) {
        _enableVoicePrompts.value = enabled
        prefs.edit().putBoolean(KEY_VOICE, enabled).apply()
    }

    fun setShowMotorStatus(show: Boolean) {
        _showMotorStatus.value = show
        prefs.edit().putBoolean(KEY_SHOW_MOTOR_STATUS, show).apply()
    }

    fun setSteerScale(scale: Int) {
        _steerScale.value = scale
        prefs.edit().putInt(KEY_STEER_SCALE, scale).apply()
    }

    // ── Calibration ───────────────────────────────────────────────────────
    fun calibrateZero() {
        val currentY = _magY.value
        _calibZero.value = currentY
        prefs.edit().putInt(KEY_CALIB_ZERO, currentY).apply()
        speak("Zero set")
    }

    fun calibratePort() {
        val currentY = _magY.value
        _calibPort.value = currentY
        prefs.edit().putInt(KEY_CALIB_PORT, currentY).apply()
        speak("Port max set")
    }

    fun calibrateStbd() {
        val currentY = _magY.value
        _calibStbd.value = currentY
        prefs.edit().putInt(KEY_CALIB_STBD, currentY).apply()
        speak("Starboard max set")
    }

    fun calibrateSteerBias() {
        val b1 = _steerSensorA.value
        val b2 = _steerSensorB.value
        steerProcessor.bias1 = b1
        steerProcessor.bias2 = b2
        prefs.edit()
            .putInt(KEY_BIAS1, b1)
            .putInt(KEY_BIAS2, b2)
            .apply()
        speak("Steer sensor bias calibrated")
    }

    // New Steer Calibration methods (2D Vector Path)
    fun setSteerCalibCenter() {
        val a = steerProcessor.getVectorA(_steerSensorA.value)
        val b = steerProcessor.getVectorB(_steerSensorB.value)
        prefs.edit().putFloat(KEY_VEC_A_CENTER, a).putFloat(KEY_VEC_B_CENTER, b).apply()
        recalculateAndSaveLut()
        speak("Center 0 degrees calibrated")
    }

    fun setSteerCalibPort22() {
        val a = steerProcessor.getVectorA(_steerSensorA.value)
        val b = steerProcessor.getVectorB(_steerSensorB.value)
        prefs.edit().putFloat(KEY_VEC_A_PORT22, a).putFloat(KEY_VEC_B_PORT22, b).apply()
        recalculateAndSaveLut()
        speak("Port 22.5 calibrated")
    }

    fun setSteerCalibPort35() {
        val a = steerProcessor.getVectorA(_steerSensorA.value)
        val b = steerProcessor.getVectorB(_steerSensorB.value)
        prefs.edit().putFloat(KEY_VEC_A_PORT35, a).putFloat(KEY_VEC_B_PORT35, b).apply()
        recalculateAndSaveLut()
        speak("Port 35 calibrated")
    }

    fun setSteerCalibStbd22() {
        val a = steerProcessor.getVectorA(_steerSensorA.value)
        val b = steerProcessor.getVectorB(_steerSensorB.value)
        prefs.edit().putFloat(KEY_VEC_A_STBD22, a).putFloat(KEY_VEC_B_STBD22, b).apply()
        recalculateAndSaveLut()
        speak("Starboard 22.5 calibrated")
    }

    fun setSteerCalibStbd35() {
        val a = steerProcessor.getVectorA(_steerSensorA.value)
        val b = steerProcessor.getVectorB(_steerSensorB.value)
        prefs.edit().putFloat(KEY_VEC_A_STBD35, a).putFloat(KEY_VEC_B_STBD35, b).apply()
        recalculateAndSaveLut()
        speak("Starboard 35 calibrated")
    }

    private fun recalculateAndSaveLut() {
        val points = getManualPoints()
        if (points.size >= 2) {
            steerProcessor.fillTableFromPoints(points)
            saveSteerLutData()
        }
    }

    private fun loadSteerLutData() {
        val lutA = prefs.getString(KEY_STEER_LUT_A, null)
        val lutB = prefs.getString(KEY_STEER_LUT_B, null)
        if (lutA != null && lutB != null) {
            try {
                val arrayA = lutA.split(",").map { it.toFloat() }.toFloatArray()
                val arrayB = lutB.split(",").map { it.toFloat() }.toFloatArray()
                if (arrayA.size == SteerSensorProcessor.TABLE_SIZE && arrayB.size == SteerSensorProcessor.TABLE_SIZE) {
                    steerProcessor.updateTable(arrayA, arrayB)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load LUT data", e)
            }
        } else {
            recalculateAndSaveLut()
        }
    }

    private fun saveSteerLutData() {
        val pathA = steerProcessor.getPathA()
        val pathB = steerProcessor.getPathB()
        prefs.edit()
            .putString(KEY_STEER_LUT_A, pathA.joinToString(",") { "%.3f".format(it) })
            .putString(KEY_STEER_LUT_B, pathB.joinToString(",") { "%.3f".format(it) })
            .apply()
    }

    fun autoCalibPort() {
        viewModelScope.launch {
            speak("Auto calibrate port started")
            val targetA = prefs.getFloat(KEY_VEC_A_PORT22, -9999f)
            val targetB = prefs.getFloat(KEY_VEC_B_PORT22, -9999f)
            if (targetA == -9999f) {
                speak("Error: Port 22 point not set manually")
                return@launch
            }

            moveToAngle(0f)
            delay(1000)
            
            val startTime = System.currentTimeMillis()
            // Sample list: Triple(A, B, Time)
            val samples = mutableListOf<Triple<Float, Float, Long>>()
            samples.add(Triple(steerProcessor.getVectorA(_steerSensorA.value), steerProcessor.getVectorB(_steerSensorB.value), 0L))

            var timedOut = false
            val timeout = 15000L 
            
            val driveJob = launch {
                while (true) {
                    adjustSteer(-1) 
                    delay(50)
                }
            }

            while (true) {
                val curA = steerProcessor.getVectorA(_steerSensorA.value)
                val curB = steerProcessor.getVectorB(_steerSensorB.value)
                val now = System.currentTimeMillis() - startTime
                samples.add(Triple(curA, curB, now))
                
                // Distance to target in vector space
                val distToTarget = sqrt(((curA - targetA) * (curA - targetA) + (curB - targetB) * (curB - targetB)).toDouble())
                if (distToTarget < 15.0) break // Reached target within tolerance
                
                if (now > timeout) {
                    timedOut = true
                    break
                }
                delay(50)
            }
            driveJob.cancel()
            
            if (!timedOut) {
                val totalTime = samples.last().third
                speak("Reached 22.5 degrees. Building LUT.")
                val timedPoints = samples.map { (a, b, time) ->
                    val angle = -(time.toFloat() / totalTime.toFloat()) * 22.5f
                    Triple(a, b, angle)
                }
                val manualPoints = getManualPoints()
                steerProcessor.fillTableFromPoints(manualPoints + timedPoints)
                saveSteerLutData()
            } else {
                speak("Timed out")
            }
        }
    }

    fun autoCalibStbd() {
        viewModelScope.launch {
            speak("Auto calibrate starboard started")
            val targetA = prefs.getFloat(KEY_VEC_A_STBD22, -9999f)
            val targetB = prefs.getFloat(KEY_VEC_B_STBD22, -9999f)
            if (targetA == -9999f) {
                speak("Error: Stbd 22 point not set manually")
                return@launch
            }

            moveToAngle(0f)
            delay(1000)
            
            val startTime = System.currentTimeMillis()
            val samples = mutableListOf<Triple<Float, Float, Long>>()
            samples.add(Triple(steerProcessor.getVectorA(_steerSensorA.value), steerProcessor.getVectorB(_steerSensorB.value), 0L))

            var timedOut = false
            val timeout = 15000L
            
            val driveJob = launch {
                while (true) {
                    adjustSteer(1)
                    delay(50)
                }
            }

            while (true) {
                val curA = steerProcessor.getVectorA(_steerSensorA.value)
                val curB = steerProcessor.getVectorB(_steerSensorB.value)
                val now = System.currentTimeMillis() - startTime
                samples.add(Triple(curA, curB, now))
                
                val distToTarget = sqrt(((curA - targetA) * (curA - targetA) + (curB - targetB) * (curB - targetB)).toDouble())
                if (distToTarget < 15.0) break
                
                if (now > timeout) {
                    timedOut = true
                    break
                }
                delay(50)
            }
            driveJob.cancel()
            
            if (!timedOut) {
                val totalTime = samples.last().third
                speak("Reached 22.5 degrees. Building LUT.")
                val timedPoints = samples.map { (a, b, time) ->
                    val angle = (time.toFloat() / totalTime.toFloat()) * 22.5f
                    Triple(a, b, angle)
                }
                val manualPoints = getManualPoints()
                steerProcessor.fillTableFromPoints(manualPoints + timedPoints)
                saveSteerLutData()
            } else {
                speak("Timed out")
            }
        }
    }

    private fun getManualPoints(): List<Triple<Float, Float, Float>> {
        val points = mutableListOf<Triple<Float, Float, Float>>()
        if (prefs.contains(KEY_VEC_A_CENTER)) 
            points.add(Triple(prefs.getFloat(KEY_VEC_A_CENTER, 0f), prefs.getFloat(KEY_VEC_B_CENTER, 0f), 0f))
        if (prefs.contains(KEY_VEC_A_PORT22)) 
            points.add(Triple(prefs.getFloat(KEY_VEC_A_PORT22, 0f), prefs.getFloat(KEY_VEC_B_PORT22, 0f), -22.5f))
        if (prefs.contains(KEY_VEC_A_PORT35)) 
            points.add(Triple(prefs.getFloat(KEY_VEC_A_PORT35, 0f), prefs.getFloat(KEY_VEC_B_PORT35, 0f), -35f))
        if (prefs.contains(KEY_VEC_A_STBD22)) 
            points.add(Triple(prefs.getFloat(KEY_VEC_A_STBD22, 0f), prefs.getFloat(KEY_VEC_B_STBD22, 0f), 22.5f))
        if (prefs.contains(KEY_VEC_A_STBD35)) 
            points.add(Triple(prefs.getFloat(KEY_VEC_A_STBD35, 0f), prefs.getFloat(KEY_VEC_B_STBD35, 0f), 35f))
        return points
    }

    private suspend fun moveToAngle(targetAngle: Float) {
        val timeout = 5000L
        val start = System.currentTimeMillis()
        while (abs(steerSensorAngle.value - targetAngle) > 2.0f) {
            val diff = targetAngle - steerSensorAngle.value
            val step = if (diff > 0) 1 else -1
            adjustSteer(step)
            if (System.currentTimeMillis() - start > timeout) break
            delay(100)
        }
    }

    fun startImuGyroCalibration() {
        imuManager.sendWitCalibration(0x01) // Gyro/Accel
        _imuCalibStatus.value = "Gyro Calibrating..."
        speak("Gyro calibration started. Keep sensor level and still.")
    }

    fun startImuMagCalibration() {
        imuManager.sendWitCalibration(0x02) // Magnetometer
        _imuCalibStatus.value = "Mag Calibrating..."
        speak("Magnetometer calibration started. Rotate sensor in all axes.")
    }

    fun saveImuCalibration() {
        imuManager.sendWitCalibration(0x00) // Finish/Save
        _imuCalibStatus.value = "Idle"
        speak("Calibration finished")
    }

    fun resetHeadingOffset() {
        _headingOffset.value = 0f
        prefs.edit().remove(KEY_HEADING_OFFSET).apply()
        speak("Heading offset reset")
    }

    fun disconnectRemote() {
        prefs.edit().remove(KEY_REMOTE_MAC).apply()
        remote.disconnect().enqueue()
    }

    // ── Configuration ─────────────────────────────────────────────────────
    fun updateSpeedStep(pct: Int) {
        _speedStep.value = (pct * 10).coerceIn(10, 100) // 1% to 10% steps
    }

    fun updateAutoDelay(ms: Long) {
        _autoIncrementDelay.value = ms.coerceIn(50L, 1000L)
    }

    fun updateThrottleDelay(ms: Long) {
        _throttleDelay.value = ms.coerceIn(50L, 2000L)
    }

    // ── Throttle / Control ────────────────────────────────────────────────
    fun setDirection(dir: Direction) {
        _direction.value = dir
    }

    fun setSpeedMagnitude(mag: Int) {
        _speedMagnitude.value = mag.coerceIn(SPEED_MIN, SPEED_MAX)
    }


    fun increaseSpeed() {
        if (_direction.value == Direction.FORWARD) {
            _speedMagnitude.value = (_speedMagnitude.value + _speedStep.value).coerceAtMost(SPEED_MAX)
        } else {
            val next = _speedMagnitude.value - _speedStep.value
            if (next < 0) {
                _direction.value = Direction.FORWARD
                _speedMagnitude.value = -next
            } else {
                _speedMagnitude.value = next
            }
        }
    }

    fun decreaseSpeed() {
        if (_direction.value == Direction.REVERSE) {
            _speedMagnitude.value = (_speedMagnitude.value + _speedStep.value).coerceAtMost(SPEED_MAX)
        } else {
            val next = _speedMagnitude.value - _speedStep.value
            if (next < 0) {
                _direction.value = Direction.REVERSE
                _speedMagnitude.value = -next
            } else {
                _speedMagnitude.value = next
            }
        }
    }
    fun stopMotor() {
        stopAutoAdjustment()
        _speedMagnitude.value = 0
    }

    fun startAutoIncrease(multiplier: Int = 1) {
        autoAdjustmentJob?.cancel()
        autoAdjustmentJob = viewModelScope.launch {
            while (true) {
                increaseSpeed()
                delay(_autoIncrementDelay.value / multiplier)
            }
        }
    }

    fun startAutoDecrease(multiplier: Int = 1) {
        autoAdjustmentJob?.cancel()
        autoAdjustmentJob = viewModelScope.launch {
            while (true) {
                decreaseSpeed()
                delay(_autoIncrementDelay.value / multiplier)
            }
        }
    }

    fun stopAutoAdjustment() {
        autoAdjustmentJob?.cancel()
        autoAdjustmentJob = null
    }

    fun startThrottleLoop() {
        throttleJob?.cancel()
        throttleJob = viewModelScope.launch {
            while (true) {
                motorManager.sendDrive(currentSpeed.value)
                delay(_throttleDelay.value)
            }
        }
    }

    fun stopThrottleLoop() {
        throttleJob?.cancel()
        throttleJob = null
    }

    fun startStatusQueryLoop() {
        statusQueryJob?.cancel()
        statusQueryJob = viewModelScope.launch {
            while (true) {
                motorManager.sendStatusQuery()
                delay(STATUS_QUERY_DELAY)
                if (_showMotorStatus.value) {
                    motorManager.sendSteerStatusQuery()
                    delay(STATUS_QUERY_DELAY)
                }
            }
        }
    }

    fun stopStatusQueryLoop() {
        statusQueryJob?.cancel()
        statusQueryJob = null
    }
    
    fun startSensorReadLoop() {
        sensorReadJob?.cancel()
        sensorReadJob = viewModelScope.launch {
            while(true) {
                motorManager.readCurrentSensor()
                delay(SENSOR_READ_DELAY)
            }
        }
    }
    
    fun stopSensorReadLoop() {
        sensorReadJob?.cancel()
        sensorReadJob = null
    }

    // ── Steering ──────────────────────────────────────────────────────────
    fun setSteerValue(value: Int) {
        val clamped = value.coerceIn(-STEER_MAX, STEER_MAX)
        if (_steerValue.value != clamped) {
            _steerValue.value = clamped
            motorManager.sendSteer(clamped)
        }
    }

    fun adjustSteer(delta: Int) {
        if (delta == 0) return
        val oldValue = _steerValue.value
        val newValue = (oldValue + delta).coerceIn(-STEER_MAX, STEER_MAX)
        val actualDelta = newValue - oldValue
        if (actualDelta != 0) {
            _steerValue.value = newValue
            val runtimeMs = abs(actualDelta) * _steerScale.value
            motorManager.sendSteer(actualDelta, runtimeMs)
        }
    }

    fun startSteerRepeat(delta: Int) {
        steerRepeatJob?.cancel()
        steerRepeatJob = viewModelScope.launch {
            while (true) {
                adjustSteer(delta)
                delay(STEER_REPEAT_DELAY)
            }
        }
    }

    fun stopSteerRepeat() {
        steerRepeatJob?.cancel()
        steerRepeatJob = null
    }

    fun resetSteer() {
        stopSteerRepeat()
        resetSteerJob?.cancel()
        resetSteerJob = viewModelScope.launch {
            while (_steerValue.value != 0) {
                val current = _steerValue.value
                val step = if (abs(current) >= 5) 5 else 1
                val delta = if (current > 0) -step else step
                adjustSteer(delta)
                delay(STEER_REPEAT_DELAY)
            }
        }
        speak("Straight")
    }

    // ── Autopilot ─────────────────────────────────────────────────────────
    fun setAutoPilotActive(active: Boolean) {
        if (active) {
            _targetHeading.value = trueHeading.value
            autopilotIntegral = 0f
            autopilotLastError = 0f
            speak("Auto pilot on, heading ${_targetHeading.value.toInt()} degrees")
            startAutoPilotLoop()
        } else {
            speak("Auto pilot off")
            stopAutoPilotLoop()
            resetSteer()
        }
        _autoPilotActive.value = active
    }

    fun adjustTargetHeading(delta: Float) {
        var newTarget = _targetHeading.value + delta
        while (newTarget < 0) newTarget += 360f
        while (newTarget >= 360) newTarget -= 360f
        _targetHeading.value = newTarget
        speak("${newTarget.toInt()} degrees")
    }

    private fun startAutoPilotLoop() {
        autoPilotJob?.cancel()
        autoPilotJob = viewModelScope.launch {
            while (true) {
                updateAutoPilot()
                delay(AUTOPILOT_DELAY)
            }
        }
    }

    private fun stopAutoPilotLoop() {
        autoPilotJob?.cancel()
        autoPilotJob = null
    }

    private fun updateAutoPilot() {
        val current = trueHeading.value
        val target = _targetHeading.value
        
        var error = target - current
        while (error > 180f) error -= 360f
        while (error < -180f) error += 360f
        
        // PID Calculation
        autopilotIntegral = (autopilotIntegral + error).coerceIn(-AUTOPILOT_MAX_I, AUTOPILOT_MAX_I)
        val derivative = error - autopilotLastError
        autopilotLastError = error
        
        val output = (error * _apKp.value) + (autopilotIntegral * _apKi.value) + (derivative * _apKd.value)
        
        // Convert PID output to steer value (-STEER_MAX to STEER_MAX)
        // Adjust polarity if needed: Positive output should steer Right (+) to increase heading
        setSteerValue(output.toInt())
    }

    fun setApKp(kp: Float) {
        _apKp.value = kp
        prefs.edit().putFloat(KEY_AP_KP, kp).apply()
    }

    fun setApKi(ki: Float) {
        _apKi.value = ki
        prefs.edit().putFloat(KEY_AP_KI, ki).apply()
    }

    fun setApKd(kd: Float) {
        _apKd.value = kd
        prefs.edit().putFloat(KEY_AP_KD, kd).apply()
    }

    fun setApGains(kp: Float, ki: Float, kd: Float) {
        _apKp.value = kp
        _apKi.value = ki
        _apKd.value = kd
        prefs.edit()
            .putFloat(KEY_AP_KP, kp)
            .putFloat(KEY_AP_KI, ki)
            .putFloat(KEY_AP_KD, kd)
            .apply()
    }

    override fun onCleared() {
        super.onCleared()
        stopThrottleLoop()
        statusQueryJob?.cancel()
        stopSensorReadLoop()
        stopAutoAdjustment()
        stopSteerRepeat()
        stopAutoPilotLoop()
        stopGpsUpdates()
        tts?.shutdown()
    }
    
    // Byte reading helpers for BLE GPS
    private fun readS32LE(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readU16LE(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
    }
}
