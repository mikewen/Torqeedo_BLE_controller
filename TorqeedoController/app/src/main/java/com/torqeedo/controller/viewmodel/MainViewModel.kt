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

class MainViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    enum class Direction { FORWARD, REVERSE }

    companion object {
        private const val TAG = "MainViewModel"
        private const val PREFS_NAME = "torqeedo_prefs"
        private const val KEY_SHOW_RAW = "show_raw"
        private const val KEY_LOGGING = "logging"
        private const val KEY_VOICE = "voice"
        private const val KEY_REMOTE_MAC = "remote_mac"

        const val SPEED_MAX = 1000
        const val SPEED_MIN = 0
        
        private const val DEFAULT_SPEED_STEP = 20        // 2% steps
        private const val DEFAULT_AUTO_DELAY = 200L      // 5 steps per second (10% / sec)
        private const val DEFAULT_THROTTLE_DELAY = 200L  // 5 Hz throttle loop
        private const val STATUS_QUERY_DELAY = 500L      // 2 Hz status query
        private const val SENSOR_READ_DELAY = 200L      // 5 Hz current sensor read
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

    private val bluetoothAdapter: BluetoothAdapter =
        (application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    val bleManager = TorqeedoBleManager(application)
    val remote     = LookbonRemote(application)
    val scanner    = BleScanner(bluetoothAdapter)

    val connectionState: StateFlow<TorqeedoBleManager.ConnectionState> = bleManager.connectionState
    
    private val _remoteConnected = MutableStateFlow(false)
    val remoteConnected: StateFlow<Boolean> = _remoteConnected.asStateFlow()

    val scanResults:     StateFlow<List<DiscoveredDevice>>             = scanner.devices
    val isScanning:      StateFlow<Boolean>                            = scanner.isScanning
    val motorStatus:     StateFlow<TorqeedoProtocol.MotorStatus?>      =
        bleManager.statusFlow.stateIn(viewModelScope, SharingStarted.Lazily, null)
    
    val sensorCurrent: StateFlow<Float> = bleManager.sensorCurrent
    
    // Estimated Power at 47V
    val estimatedPowerW: StateFlow<Float> = sensorCurrent.map { it * 47.0f }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    val rawStatus: StateFlow<ByteArray?> = 
        bleManager.rawStatusFlow.stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _direction = MutableStateFlow(Direction.FORWARD)
    val direction: StateFlow<Direction> = _direction.asStateFlow()

    private val _speedMagnitude = MutableStateFlow(0)
    val speedMagnitude: StateFlow<Int> = _speedMagnitude.asStateFlow()

    val currentSpeed: StateFlow<Int> =
        combine(_direction, _speedMagnitude) { dir, mag ->
            if (dir == Direction.FORWARD) mag else -mag
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // GPS State
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    
    private val _gpsSpeedKnots = MutableStateFlow(0.0f)
    val gpsSpeedKnots: StateFlow<Float> = _gpsSpeedKnots.asStateFlow()

    private val _gpsCourse = MutableStateFlow<Int?>(null)
    val gpsCourse: StateFlow<Int?> = _gpsCourse.asStateFlow()

    private val _gpsFix = MutableStateFlow(false)
    val gpsFix: StateFlow<Boolean> = _gpsFix.asStateFlow()

    private var throttleJob: Job? = null
    private var statusQueryJob: Job? = null
    private var sensorReadJob: Job? = null
    private var autoAdjustmentJob: Job? = null

    private var tts: TextToSpeech? = TextToSpeech(application, this)

    init {
        setupRemote()
        setupConnectionVoice()
        
        // Initial setup for managers from persisted values
        bleManager.setRawDataEnabled(_showRawData.value)
        bleManager.setLoggingEnabled(_enableLogging.value)

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
            }
        }
    }

    private fun setupConnectionVoice() {
        viewModelScope.launch {
            connectionState.drop(1).collect { state ->
                when (state) {
                    TorqeedoBleManager.ConnectionState.CONNECTED -> speak("Motor connected")
                    TorqeedoBleManager.ConnectionState.DISCONNECTED -> speak("Motor disconnected")
                    else -> {}
                }
            }
        }
    }

    // ── GPS ───────────────────────────────────────────────────────────────
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            _gpsFix.value = true
            
            // Speed in m/s to knots (1 m/s ≈ 1.94384 knots)
            val speedKnots = location.speed * 1.94384f
            _gpsSpeedKnots.value = speedKnots
            
            val course = if (location.hasBearing()) {
                location.bearing.toInt()
            } else {
                null
            }
            _gpsCourse.value = course

            // Update BLE manager with GPS info for logging
            bleManager.updateGpsInfo(
                location.latitude,
                location.longitude,
                speedKnots,
                course
            )
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
        bleManager.updateGpsInfo(null, null, null, null)
        _gpsFix.value = false
    }

    // ── Scan ──────────────────────────────────────────────────────────────
    fun setScanAllNames(scanAll: Boolean) {
        _scanAllNames.value = scanAll
    }

    fun startScan() = scanner.startScan(_scanAllNames.value)
    fun startRemoteScan() = scanner.startRemoteScan()
    fun stopScan()  = scanner.stopScan()

    // ── Debug ─────────────────────────────────────────────────────────────
    fun setShowRawData(show: Boolean) {
        _showRawData.value = show
        bleManager.setRawDataEnabled(show)
        prefs.edit().putBoolean(KEY_SHOW_RAW, show).apply()
    }

    fun setEnableLogging(enabled: Boolean) {
        _enableLogging.value = enabled
        bleManager.setLoggingEnabled(enabled)
        prefs.edit().putBoolean(KEY_LOGGING, enabled).apply()
    }

    fun setEnableVoicePrompts(enabled: Boolean) {
        _enableVoicePrompts.value = enabled
        prefs.edit().putBoolean(KEY_VOICE, enabled).apply()
    }

    // ── Connect / disconnect ──────────────────────────────────────────────
    fun connect(device: DiscoveredDevice) {
        scanner.stopScan()
        viewModelScope.launch {
            try {
                if (device.name.contains("LOOKBON", ignoreCase = true)) {
                    remote.connectToDevice(device.device, autoReconnect = true)
                } else {
                    bleManager.connectToDevice(device.device)
                    startLoops()
                    startGpsUpdates()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to ${device.name}", e)
            }
        }
    }

    fun disconnect() {
        stopLoops()
        stopGpsUpdates()
        bleManager.disconnectDevice()
    }
    
    fun disconnectRemote() {
        prefs.edit().remove(KEY_REMOTE_MAC).apply() // Forget remote if explicitly disconnected
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
        _speedMagnitude.value = (_speedMagnitude.value + _speedStep.value).coerceAtMost(SPEED_MAX)
    }

    fun decreaseSpeed() {
        _speedMagnitude.value = (_speedMagnitude.value - _speedStep.value).coerceAtLeast(SPEED_MIN)
    }

    fun stopMotor() {
        _speedMagnitude.value = 0
    }

    fun startAutoIncrease() {
        autoAdjustmentJob?.cancel()
        autoAdjustmentJob = viewModelScope.launch {
            while (true) {
                increaseSpeed()
                delay(_autoIncrementDelay.value)
            }
        }
    }

    fun startAutoDecrease() {
        autoAdjustmentJob?.cancel()
        autoAdjustmentJob = viewModelScope.launch {
            while (true) {
                decreaseSpeed()
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
    }

    private fun startThrottleLoop() {
        throttleJob?.cancel()
        throttleJob = viewModelScope.launch {
            while (true) {
                if (connectionState.value == TorqeedoBleManager.ConnectionState.CONNECTED) {
                    bleManager.sendDrive(currentSpeed.value)
                }
                delay(_throttleDelay.value)
            }
        }
    }

    private fun stopThrottleLoop() {
        throttleJob?.cancel()
        throttleJob = null
        bleManager.sendDrive(0)
    }

    private fun startStatusQueryLoop() {
        statusQueryJob?.cancel()
        statusQueryJob = viewModelScope.launch {
            while (true) {
                if (connectionState.value == TorqeedoBleManager.ConnectionState.CONNECTED) {
                    bleManager.sendStatusQuery()
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
                if (connectionState.value == TorqeedoBleManager.ConnectionState.CONNECTED) {
                    bleManager.readCurrentSensor()
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
        bleManager.disconnectDevice()
        remote.disconnect().enqueue()
        tts?.shutdown()
        tts = null
    }
}
