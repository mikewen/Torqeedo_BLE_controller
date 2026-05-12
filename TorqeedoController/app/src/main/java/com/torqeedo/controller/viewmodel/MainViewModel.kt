package com.torqeedo.controller.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.*
import com.torqeedo.controller.ble.BleScanner
import com.torqeedo.controller.ble.DiscoveredDevice
import com.torqeedo.controller.ble.LookbonRemote
import com.torqeedo.controller.ble.TorqeedoBleManager
import com.torqeedo.controller.protocol.TorqeedoProtocol
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class MainViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    enum class Direction { FORWARD, REVERSE }

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

    private val _showMotorStatus = MutableStateFlow(prefs.getBoolean(KEY_SHOW_MOTOR_STATUS, true))
    val showMotorStatus: StateFlow<Boolean> = _showMotorStatus.asStateFlow()

    private val _steerScale = MutableStateFlow(prefs.getInt(KEY_STEER_SCALE, DEFAULT_STEER_SCALE))
    val steerScale: StateFlow<Int> = _steerScale.asStateFlow()

    private val bluetoothAdapter: BluetoothAdapter =
        (application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    val motorManager = TorqeedoBleManager(application)
    val imuManager   = TorqeedoBleManager(application)
    val remote       = LookbonRemote(application)
    val scanner      = BleScanner(bluetoothAdapter)

    val motorConnectionState: StateFlow<TorqeedoBleManager.ConnectionState> = motorManager.connectionState
    val imuConnectionState:   StateFlow<TorqeedoBleManager.ConnectionState> = imuManager.connectionState
    
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

    // Magnetometer / Rudder Position
    private val _magX = MutableStateFlow(0)
    val magX: StateFlow<Int> = _magX.asStateFlow()
    private val _magY = MutableStateFlow(0)
    val magY: StateFlow<Int> = _magY.asStateFlow()
    private val _magZ = MutableStateFlow(0)
    val magZ: StateFlow<Int> = _magZ.asStateFlow()

    // WitMotion IMU Data
    private val _witRoll = MutableStateFlow(0f)
    val witRoll: StateFlow<Float> = _witRoll.asStateFlow()
    private val _witPitch = MutableStateFlow(0f)
    val witPitch: StateFlow<Float> = _witPitch.asStateFlow()
    private val _witYaw = MutableStateFlow(0f)
    val witYaw: StateFlow<Float> = _witYaw.asStateFlow()

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

    // GPS State
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    
    private val _gpsFix = MutableStateFlow(false)
    val gpsFix: StateFlow<Boolean> = _gpsFix.asStateFlow()

    private val _gpsSpeedKnots = MutableStateFlow(0.0f)
    val gpsSpeedKnots: StateFlow<Float> = _gpsSpeedKnots.asStateFlow()

    private val _gpsCourse = MutableStateFlow<Int?>(null)
    val gpsCourse: StateFlow<Int?> = _gpsCourse.asStateFlow()

    private var throttleJob: Job? = null
    private var statusQueryJob: Job? = null
    private var sensorReadJob: Job? = null
    private var autoAdjustmentJob: Job? = null
    private var steerRepeatJob: Job? = null
    private var resetSteerJob: Job? = null

    private var tts: TextToSpeech? = TextToSpeech(application, this)

    init {
        setupRemote()
        setupConnectionVoice()
        setupMagnetometer()
        setupWitMotion()
        
        // Initial setup for managers from persisted values
        motorManager.setRawDataEnabled(_showRawData.value)
        motorManager.setLoggingEnabled(_enableLogging.value)
        imuManager.setRawDataEnabled(_showRawData.value)
        imuManager.setLoggingEnabled(_enableLogging.value)

        // Auto-reconnect to remote if we have a saved MAC
        prefs.getString(KEY_REMOTE_MAC, null)?.let { mac ->
            try {
                val device = bluetoothAdapter.getRemoteDevice(mac)
                remote.connectToDevice(device, autoReconnect = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-reconnect to remote: $mac", e)
            }
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

    private fun setupConnectionVoice() {
        viewModelScope.launch {
            motorConnectionState.drop(1).collect { state ->
                when (state) {
                    TorqeedoBleManager.ConnectionState.CONNECTED -> speak("Motor connected")
                    TorqeedoBleManager.ConnectionState.DISCONNECTED -> speak("Motor disconnected")
                    else -> {}
                }
            }
        }
        viewModelScope.launch {
            imuConnectionState.drop(1).collect { state ->
                when (state) {
                    TorqeedoBleManager.ConnectionState.CONNECTED -> speak("Heading sensor connected")
                    TorqeedoBleManager.ConnectionState.DISCONNECTED -> speak("Heading sensor disconnected")
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

    private fun setupWitMotion() {
        viewModelScope.launch {
            imuManager.witMotionData.collect { frame ->
                if (frame.size < 11) return@collect
                val type = frame[1].toInt() and 0xFF
                when (type) {
                    0x53 -> { // Angle: Roll, Pitch, Yaw
                        val rollRaw  = ((frame[3].toInt() shl 8) or (frame[2].toInt() and 0xFF)).toShort()
                        val pitchRaw = ((frame[5].toInt() shl 8) or (frame[4].toInt() and 0xFF)).toShort()
                        val yawRaw   = ((frame[7].toInt() shl 8) or (frame[6].toInt() and 0xFF)).toShort()
                        
                        _witRoll.value  = rollRaw  / 32768f * 180f
                        _witPitch.value = pitchRaw / 32768f * 180f
                        _witYaw.value   = yawRaw   / 32768f * 180f
                    }
                }
            }
        }
    }

    // ── GPS ───────────────────────────────────────────────────────────────
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            _gpsFix.value = true
            val speedKnots = location.speed * 1.94384f
            _gpsSpeedKnots.value = speedKnots
            val course = if (location.hasBearing()) location.bearing.toInt() else null
            _gpsCourse.value = course
            motorManager.updateGpsInfo(location.latitude, location.longitude, speedKnots, course)
        }
        override fun onLocationAvailability(availability: LocationAvailability) {
            _gpsFix.value = availability.isLocationAvailable
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
    fun stopScan()  = scanner.stopScan()

    // ── Debug ─────────────────────────────────────────────────────────────
    fun setShowRawData(show: Boolean) {
        _showRawData.value = show
        motorManager.setRawDataEnabled(show)
        imuManager.setRawDataEnabled(show)
        prefs.edit().putBoolean(KEY_SHOW_RAW, show).apply()
    }

    fun setEnableLogging(enabled: Boolean) {
        _enableLogging.value = enabled
        motorManager.setLoggingEnabled(enabled)
        imuManager.setLoggingEnabled(enabled)
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

    // ── Connect / disconnect ──────────────────────────────────────────────
    fun connect(device: DiscoveredDevice) {
        scanner.stopScan()
        viewModelScope.launch {
            try {
                when {
                    device.name.contains("LOOKBON", ignoreCase = true) -> {
                        remote.connectToDevice(device.device, autoReconnect = true)
                    }
                    device.name.contains("IMU", ignoreCase = true) -> {
                        imuManager.connectToDevice(device.device)
                    }
                    else -> {
                        motorManager.connectToDevice(device.device)
                        startLoops()
                        startGpsUpdates()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to ${device.name}", e)
            }
        }
    }

    fun disconnect() {
        stopLoops()
        stopGpsUpdates()
        motorManager.disconnectDevice()
        imuManager.disconnectDevice()
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

    // ── Controls ──────────────────────────────────────────────────────────
    fun setDirection(dir: Direction) {
        _direction.value = dir
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
        resetSteerJob?.cancel()
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

    fun startAutoIncrease(multiplier: Int = 1) {
        autoAdjustmentJob?.cancel()
        autoAdjustmentJob = viewModelScope.launch {
            while (true) {
                repeat(multiplier) { increaseSpeed() }
                delay(_autoIncrementDelay.value)
            }
        }
    }

    fun startAutoDecrease(multiplier: Int = 1) {
        autoAdjustmentJob?.cancel()
        autoAdjustmentJob = viewModelScope.launch {
            while (true) {
                repeat(multiplier) { decreaseSpeed() }
                delay(_autoIncrementDelay.value)
            }
        }
    }

    fun stopAutoAdjustment() {
        autoAdjustmentJob?.cancel()
        autoAdjustmentJob = null
    }

    // ── Loops ─────────────────────────────────────────────────────────────
    private fun startLoops() {
        startThrottleLoop()
        startStatusQueryLoop()
        startSensorReadLoop()
    }

    private fun stopLoops() {
        stopThrottleLoop()
        stopStatusQueryLoop()
        stopSensorReadLoop()
        stopAutoAdjustment()
        stopSteerRepeat()
        resetSteerJob?.cancel()
    }

    private fun startThrottleLoop() {
        throttleJob?.cancel()
        throttleJob = viewModelScope.launch {
            while (true) {
                if (motorConnectionState.value == TorqeedoBleManager.ConnectionState.CONNECTED) {
                    motorManager.sendDrive(currentSpeed.value)
                }
                delay(_throttleDelay.value)
            }
        }
    }

    private fun stopThrottleLoop() {
        throttleJob?.cancel()
        throttleJob = null
        motorManager.sendDrive(0)
    }

    private fun startStatusQueryLoop() {
        statusQueryJob?.cancel()
        statusQueryJob = viewModelScope.launch {
            while (true) {
                if (motorConnectionState.value == TorqeedoBleManager.ConnectionState.CONNECTED) {
                    motorManager.sendStatusQuery()
                }
                delay(STATUS_QUERY_DELAY)
            }
        }
    }

    private fun stopStatusQueryLoop() {
        statusQueryJob?.cancel()
        statusQueryJob = null
    }

    private fun startSensorReadLoop() {
        sensorReadJob?.cancel()
        sensorReadJob = viewModelScope.launch {
            while (true) {
                if (motorConnectionState.value == TorqeedoBleManager.ConnectionState.CONNECTED) {
                    motorManager.readCurrentSensor()
                }
                delay(SENSOR_READ_DELAY)
            }
        }
    }

    private fun stopSensorReadLoop() {
        sensorReadJob?.cancel()
        sensorReadJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopLoops()
        stopGpsUpdates()
        motorManager.disconnectDevice()
        imuManager.disconnectDevice()
        remote.disconnect().enqueue()
        tts?.shutdown()
        tts = null
    }
}
