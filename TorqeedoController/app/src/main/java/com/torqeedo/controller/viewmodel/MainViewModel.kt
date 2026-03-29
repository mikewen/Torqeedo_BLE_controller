package com.torqeedo.controller.viewmodel

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.torqeedo.controller.ble.BleScanner
import com.torqeedo.controller.ble.DiscoveredDevice
import com.torqeedo.controller.ble.TorqeedoBleManager
import com.torqeedo.controller.protocol.TorqeedoProtocol
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    enum class Direction { FORWARD, REVERSE }

    companion object {
        const val SPEED_MAX = 1000
        const val SPEED_MIN = 0
        
        private const val DEFAULT_SPEED_STEP = 20        // 2% steps
        private const val DEFAULT_AUTO_DELAY = 200L      // 5 steps per second (10% / sec)
    }

    // Configurable parameters as StateFlows
    private val _speedStep = MutableStateFlow(DEFAULT_SPEED_STEP)
    val speedStep: StateFlow<Int> = _speedStep.asStateFlow()

    private val _autoIncrementDelay = MutableStateFlow(DEFAULT_AUTO_DELAY)
    val autoIncrementDelay: StateFlow<Long> = _autoIncrementDelay.asStateFlow()

    private val _scanAllNames = MutableStateFlow(false)
    val scanAllNames: StateFlow<Boolean> = _scanAllNames.asStateFlow()

    private val bluetoothAdapter: BluetoothAdapter =
        (application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    val bleManager = TorqeedoBleManager(application)
    val scanner    = BleScanner(bluetoothAdapter)

    val connectionState: StateFlow<TorqeedoBleManager.ConnectionState> = bleManager.connectionState
    val scanResults:     StateFlow<List<DiscoveredDevice>>             = scanner.devices
    val isScanning:      StateFlow<Boolean>                            = scanner.isScanning
    val motorStatus:     StateFlow<TorqeedoProtocol.MotorStatus?>      =
        bleManager.statusFlow.stateIn(viewModelScope, SharingStarted.Lazily, null)
    
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

    private var throttleJob: Job? = null
    private var autoAdjustmentJob: Job? = null

    // ── Scan ──────────────────────────────────────────────────────────────
    fun setScanAllNames(scanAll: Boolean) {
        _scanAllNames.value = scanAll
    }

    fun startScan() = scanner.startScan(_scanAllNames.value)
    fun stopScan()  = scanner.stopScan()

    // ── Connect / disconnect ──────────────────────────────────────────────
    fun connect(device: DiscoveredDevice) {
        scanner.stopScan()
        viewModelScope.launch {
            try {
                bleManager.connectToDevice(device.device)
                startThrottleLoop()
            } catch (_: Exception) {}
        }
    }

    fun disconnect() {
        stopThrottleLoop()
        bleManager.disconnectDevice()
    }

    // ── Configuration ─────────────────────────────────────────────────────
    fun updateSpeedStep(pct: Int) {
        _speedStep.value = (pct * 10).coerceIn(10, 100) // 1% to 10% steps
    }

    fun updateAutoDelay(ms: Long) {
        _autoIncrementDelay.value = ms.coerceIn(50L, 1000L)
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

    // ── 10 Hz throttle loop ───────────────────────────────────────────────
    private fun startThrottleLoop() {
        throttleJob?.cancel()
        throttleJob = viewModelScope.launch {
            while (true) {
                if (connectionState.value == TorqeedoBleManager.ConnectionState.CONNECTED) {
                    bleManager.sendDrive(currentSpeed.value)
                }
                delay(100L)
            }
        }
    }

    private fun stopThrottleLoop() {
        throttleJob?.cancel()
        throttleJob = null
        bleManager.sendDrive(0)
    }

    override fun onCleared() {
        super.onCleared()
        stopThrottleLoop()
        bleManager.disconnectDevice()
    }
}
