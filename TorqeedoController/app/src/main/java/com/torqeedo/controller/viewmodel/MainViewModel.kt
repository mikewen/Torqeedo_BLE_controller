package com.torqeedo.controller.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.*
import com.torqeedo.controller.ble.BleScanner
import com.torqeedo.controller.ble.DiscoveredDevice
import com.torqeedo.controller.ble.TorqeedoBleManager
import com.torqeedo.controller.protocol.TorqeedoProtocol
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    enum class Direction { FORWARD, REVERSE }

    companion object {
        const val SPEED_MAX = 1000
        const val SPEED_MIN = 0
        
        private const val DEFAULT_SPEED_STEP = 20        // 2% steps
        private const val DEFAULT_AUTO_DELAY = 200L      // 5 steps per second (10% / sec)
        private const val DEFAULT_THROTTLE_DELAY = 200L  // 5 Hz throttle loop
        private const val STATUS_QUERY_DELAY = 500L      // 2 Hz status query
        private const val SENSOR_READ_DELAY = 200L      // 5 Hz current sensor read
    }

    // Configurable parameters as StateFlows
    private val _speedStep = MutableStateFlow(DEFAULT_SPEED_STEP)
    val speedStep: StateFlow<Int> = _speedStep.asStateFlow()

    private val _autoIncrementDelay = MutableStateFlow(DEFAULT_AUTO_DELAY)
    val autoIncrementDelay: StateFlow<Long> = _autoIncrementDelay.asStateFlow()

    private val _throttleDelay = MutableStateFlow(DEFAULT_THROTTLE_DELAY)
    val throttleDelay: StateFlow<Long> = _throttleDelay.asStateFlow()

    private val _scanAllNames = MutableStateFlow(false)
    val scanAllNames: StateFlow<Boolean> = _scanAllNames.asStateFlow()

    // Debug settings
    private val _showRawData = MutableStateFlow(true)
    val showRawData: StateFlow<Boolean> = _showRawData.asStateFlow()

    private val _enableLogging = MutableStateFlow(true)
    val enableLogging: StateFlow<Boolean> = _enableLogging.asStateFlow()

    private val bluetoothAdapter: BluetoothAdapter =
        (application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    val bleManager = TorqeedoBleManager(application)
    val scanner    = BleScanner(bluetoothAdapter)

    val connectionState: StateFlow<TorqeedoBleManager.ConnectionState> = bleManager.connectionState
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

    // ── GPS ───────────────────────────────────────────────────────────────
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            _gpsFix.value = true
            
            // Speed in m/s to knots (1 m/s ≈ 1.94384 knots)
            _gpsSpeedKnots.value = location.speed * 1.94384f
            
            if (location.hasBearing()) {
                _gpsCourse.value = location.bearing.toInt()
            }
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
        _gpsFix.value = false
    }

    // ── Scan ──────────────────────────────────────────────────────────────
    fun setScanAllNames(scanAll: Boolean) {
        _scanAllNames.value = scanAll
    }

    fun startScan() = scanner.startScan(_scanAllNames.value)
    fun stopScan()  = scanner.stopScan()

    // ── Debug ─────────────────────────────────────────────────────────────
    fun setShowRawData(show: Boolean) {
        _showRawData.value = show
        bleManager.setRawDataEnabled(show)
    }

    fun setEnableLogging(enabled: Boolean) {
        _enableLogging.value = enabled
        bleManager.setLoggingEnabled(enabled)
    }

    // ── Connect / disconnect ──────────────────────────────────────────────
    fun connect(device: DiscoveredDevice) {
        scanner.stopScan()
        viewModelScope.launch {
            try {
                bleManager.connectToDevice(device.device)
                startLoops()
                startGpsUpdates()
            } catch (_: Exception) {}
        }
    }

    fun disconnect() {
        stopLoops()
        stopGpsUpdates()
        bleManager.disconnectDevice()
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
    }
}
