package com.torqeedo.controller.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.torqeedo.controller.R
import com.torqeedo.controller.ble.Direction
import com.torqeedo.controller.ble.TorqeedoBleManager
import com.torqeedo.controller.databinding.ActivityMainBinding
import com.torqeedo.controller.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private lateinit var deviceAdapter: DeviceListAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            ensureBluetoothEnabled()
        } else {
            showSnack("Permissions required for BLE")
        }
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeState()
        requestPermissionsIfNeeded()
    }

    private fun setupUI() {
        deviceAdapter = DeviceListAdapter { device ->
            vm.connect(device)
        }
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }

        binding.switchShowRaw.setOnCheckedChangeListener { _, isChecked ->
            vm.setShowRawData(isChecked)
        }
        binding.switchLogging.setOnCheckedChangeListener { _, isChecked ->
            vm.setEnableLogging(isChecked)
        }
        binding.switchVoice.setOnCheckedChangeListener { _, isChecked ->
            vm.setEnableVoicePrompts(isChecked)
        }
        binding.switchShowMotorStatus.setOnCheckedChangeListener { _, isChecked ->
            vm.setShowMotorStatus(isChecked)
        }
        binding.switchQmcLpf.setOnCheckedChangeListener { _, isChecked ->
            vm.setQmcLpfEnabled(isChecked)
        }

        // Scan Settings
        binding.switchScanAll.setOnCheckedChangeListener { _, isChecked ->
            vm.setScanAllNames(isChecked)
        }

        // Direction Switch
        binding.switchDirection.setOnCheckedChangeListener { _, isChecked ->
            vm.setDirection(if (isChecked) Direction.REVERSE else Direction.FORWARD)
        }

        // Speed Increase Button
        binding.btnSpeedUp.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    vm.increaseSpeed()
                    v.postDelayed({
                        if (v.isPressed) vm.startAutoIncrease()
                    }, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vm.stopAutoAdjustment()
                    v.isPressed = false
                }
            }
            true
        }

        // Speed Decrease Button
        binding.btnSpeedDown.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    vm.decreaseSpeed()
                    v.postDelayed({
                        if (v.isPressed) vm.startAutoDecrease()
                    }, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vm.stopAutoAdjustment()
                    v.isPressed = false
                }
            }
            true
        }

        // Steering Buttons with repeat support
        listOf(
            binding.btnSteerL5 to -5,
            binding.btnSteerL1 to -1,
            binding.btnSteerR1 to 1,
            binding.btnSteerR5 to 5
        ).forEach { (btn, delta) ->
            btn.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        vm.adjustSteer(delta)
                        v.postDelayed({
                            if (v.isPressed) vm.startSteerRepeat(delta)
                        }, 400)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        vm.stopSteerRepeat()
                        v.isPressed = false
                    }
                }
                true
            }
        }

        // Steer Scale SeekBar
        binding.seekBarSteerScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) vm.setSteerScale(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Steer Scale EditText
        binding.etSteerScale.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val value = v.text.toString().toIntOrNull() ?: 0
                vm.setSteerScale(value.coerceIn(0, 10000)) // Allowing up to 10s if typed
                v.clearFocus()
                true
            } else {
                false
            }
        }
        
        binding.etSteerScale.doAfterTextChanged { s ->
            if (binding.etSteerScale.hasFocus()) {
                val value = s.toString().toIntOrNull() ?: 0
                vm.setSteerScale(value.coerceIn(0, 10000))
            }
        }

        // Stop Button
        binding.btnStop.setOnClickListener {
            vm.stopMotor()
        }

        // Reset Steer Button
        binding.btnResetSteer.setOnClickListener {
            vm.resetSteer()
        }

        // Auto-Pilot Button
        binding.btnAutoPilot.setOnClickListener {
            startActivity(Intent(this, AutoPilotActivity::class.java))
        }

        // Calibrate Sensors Button
        binding.btnCalibrate.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        // Disconnect Button
        binding.btnDisconnect.setOnClickListener {
            vm.disconnect()
        }

        binding.btnScan.setOnClickListener {
            if (vm.isScanning.value) vm.stopScan() else vm.startScan()
        }

        binding.btnScanRemote.setOnClickListener {
            if (vm.isScanning.value) vm.stopScan() else vm.startRemoteScan()
        }

        binding.btnScanImu.setOnClickListener {
            if (vm.isScanning.value) vm.stopScan() else vm.startImuScan()
        }

        binding.btnScanGps.setOnClickListener {
            if (vm.isScanning.value) vm.stopScan() else vm.startGpsScan()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Control Panel Visibility
                launch {
                    combine(vm.motorConnectionState, vm.imuConnectionState, vm.gpsConnectionState, vm.remoteConnected) { motor, imu, gps, remote ->
                        val motorConnected = motor == TorqeedoBleManager.ConnectionState.CONNECTED
                        val anyConnected = motorConnected || imu == TorqeedoBleManager.ConnectionState.CONNECTED || gps == TorqeedoBleManager.ConnectionState.CONNECTED || remote
                        Pair(anyConnected, motorConnected)
                    }.collectLatest { (anyConnected, motorConnected) ->
                        binding.controlPanel.visibility = if (motorConnected) View.VISIBLE else View.GONE
                        binding.scanPanel.visibility    = if (motorConnected) View.GONE else View.VISIBLE
                    }
                }

                // Motor-dependent Cards Visibility
                launch {
                    combine(vm.motorConnectionState, vm.showMotorStatus) { state, show ->
                        state == TorqeedoBleManager.ConnectionState.CONNECTED && show
                    }.collectLatest { show ->
                        binding.motorDataCard.visibility = if (show) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    vm.motorConnectionState.collectLatest { state ->
                        val connected = state == TorqeedoBleManager.ConnectionState.CONNECTED
                        binding.telemetryCard.visibility = if (connected) View.VISIBLE else View.GONE
                        binding.motorControlCard.visibility = if (connected) View.VISIBLE else View.GONE
                        binding.steeringCard.visibility = if (connected) View.VISIBLE else View.GONE
                    }
                }

                // IMU-dependent Card Visibility
                launch {
                    vm.imuConnectionState.collectLatest { state ->
                        val connected = state == TorqeedoBleManager.ConnectionState.CONNECTED
                        binding.witMotionCard.visibility = if (connected) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    vm.motorConnectionState.collectLatest { state ->
                        when (state) {
                            TorqeedoBleManager.ConnectionState.DISCONNECTED -> {
                                binding.tvConnectionStatus.text = "Mot: Off"
                                binding.tvConnectionStatus.setTextColor(
                                    ContextCompat.getColor(this@MainActivity, R.color.status_disconnected))
                            }
                            TorqeedoBleManager.ConnectionState.CONNECTING -> {
                                binding.tvConnectionStatus.text = "Mot: …"
                                binding.tvConnectionStatus.setTextColor(
                                    ContextCompat.getColor(this@MainActivity, R.color.status_connecting))
                            }
                            TorqeedoBleManager.ConnectionState.CONNECTED -> {
                                binding.tvConnectionStatus.text = "Mot: On"
                                binding.tvConnectionStatus.setTextColor(
                                    ContextCompat.getColor(this@MainActivity, R.color.status_connected))
                            }
                        }
                    }
                }

                launch {
                    vm.imuConnectionState.collectLatest { state ->
                        binding.tvImuStatus.text = when(state) {
                            TorqeedoBleManager.ConnectionState.CONNECTED -> "Hdg: On"
                            TorqeedoBleManager.ConnectionState.CONNECTING -> "Hdg: …"
                            else -> "Hdg: Off"
                        }
                        binding.tvImuStatus.setTextColor(ContextCompat.getColor(this@MainActivity,
                            if (state == TorqeedoBleManager.ConnectionState.CONNECTED) R.color.status_connected else R.color.text_secondary))
                    }
                }

                launch {
                    vm.gpsConnectionState.collectLatest { state ->
                        binding.tvGpsStatusTop.text = when(state) {
                            TorqeedoBleManager.ConnectionState.CONNECTED -> "GPS: On"
                            TorqeedoBleManager.ConnectionState.CONNECTING -> "GPS: …"
                            else -> "GPS: Off"
                        }
                        binding.tvGpsStatusTop.setTextColor(ContextCompat.getColor(this@MainActivity,
                            if (state == TorqeedoBleManager.ConnectionState.CONNECTED) R.color.status_connected else R.color.text_secondary))
                    }
                }

                launch {
                    vm.remoteConnected.collectLatest { connected ->
                        binding.tvRemoteStatus.text = if (connected) "Rem: On" else "Rem: Off"
                        binding.tvRemoteStatus.setTextColor(ContextCompat.getColor(this@MainActivity,
                            if (connected) R.color.status_connected else R.color.text_secondary))
                    }
                }

                launch {
                    vm.scanResults.collectLatest { devices ->
                        deviceAdapter.submitList(devices)
                        binding.tvNoDevices.visibility =
                            if (devices.isEmpty()) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    vm.isScanning.collectLatest { scanning ->
                        binding.btnScan.text = if (scanning) "Stop Scan" else "Scan for Motor"
                        binding.btnScanRemote.text = if (scanning) "Stop Scan" else "Scan for Remote"
                        binding.btnScanImu.text = if (scanning) "Stop Scan" else "Scan for Heading"
                        binding.btnScanGps.text = if (scanning) "Stop Scan" else "Scan for GPS"
                        binding.scanProgress.visibility = if (scanning) View.VISIBLE else View.GONE
                        binding.switchScanAll.isEnabled = !scanning
                    }
                }

                launch {
                    vm.scanAllNames.collectLatest { all ->
                        binding.switchScanAll.isChecked = all
                    }
                }

                launch {
                    vm.showRawData.collectLatest { show ->
                        binding.switchShowRaw.isChecked = show
                        binding.tvRawData.visibility = if (show) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    vm.enableLogging.collectLatest { enabled ->
                        binding.switchLogging.isChecked = enabled
                    }
                }

                launch {
                    vm.enableVoicePrompts.collectLatest { enabled ->
                        binding.switchVoice.isChecked = enabled
                    }
                }

                launch {
                    vm.qmcLpfEnabled.collectLatest { enabled ->
                        binding.switchQmcLpf.isChecked = enabled
                    }
                }

                launch {
                    vm.steerScale.collectLatest { scale ->
                        if (!binding.etSteerScale.hasFocus()) {
                            binding.etSteerScale.setText(scale.toString())
                        }
                        binding.seekBarSteerScale.progress = scale.coerceAtMost(binding.seekBarSteerScale.max)
                    }
                }

                // Motor Data Observation
                launch {
                    vm.motorStatus.collectLatest { status ->
                        if (status != null) {
                            val soc = if (status.voltage > 42f) {
                                ((status.voltage - 42f) / (52.5f - 42f) * 100f).coerceIn(0f, 100f)
                            } else {
                                0f
                            }
                            binding.tvMotorSoc.text = "%.0f%%".format(soc)
                            binding.tvMotorWatts.text = status.powerW.toString()
                            binding.tvMotorVolts.text = "%.1fV".format(status.voltage)
                            binding.tvMotorRpm.text = status.rpm.toString()
                        }
                    }
                }

                // Telemetry Data Observation
                launch {
                    vm.gpsSpeedKnots.collectLatest { knots ->
                        binding.tvGpsSpeed.text = "%.1f".format(knots)
                    }
                }

                launch {
                    vm.gpsCourse.collectLatest { course ->
                        binding.tvGpsCourse.text = course?.let { "$it°" } ?: "—"
                    }
                }

                launch {
                    vm.sensorCurrent.collectLatest { current ->
                        binding.tvCurrent.text = "%.1f".format(current)
                    }
                }

                launch {
                    vm.estimatedPowerW.collectLatest { power ->
                        binding.tvPower.text = "%.0f".format(power)
                    }
                }

                launch {
                    vm.steerValue.collectLatest { steer ->
                        binding.tvSteer.text = when {
                            steer > 0 -> "R"
                            steer < 0 -> "L"
                            else -> "0"
                        }
                        binding.tvSteerValue.text = steer.toString()
                    }
                }

                launch {
                    vm.trueHeading.collectLatest { heading ->
                        binding.tvTrueHeading.text = "%.1f°".format(heading)
                    }
                }

                launch {
                    vm.witYaw.collectLatest { yaw ->
                        binding.tvYaw.text = "%.1f°".format(yaw)
                    }
                }

                launch {
                    vm.witPitch.collectLatest { pitch ->
                        binding.tvPitch.text = "%.1f°".format(pitch)
                    }
                }

                launch {
                    vm.witRoll.collectLatest { roll ->
                        binding.tvRoll.text = "%.1f°".format(roll)
                    }
                }

                launch {
                    vm.rudderPosition.collectLatest { pos ->
                        binding.tvSteerAngle.text = "Steer Angle: %.0f%%".format(pos)
                    }
                }

                launch {
                    vm.gpsFix.collectLatest { hasFix ->
                        binding.tvGpsStatus.text = if (hasFix) "GPS Fixed" else "Waiting for GPS fix…"
                        binding.tvGpsStatus.setTextColor(ContextCompat.getColor(this@MainActivity,
                            if (hasFix) R.color.status_connected else R.color.text_secondary))
                    }
                }

                launch {
                    vm.rawStatus.collectLatest { bytes ->
                        binding.tvRawData.text = if (bytes != null) "Raw: ${bytes.joinToString(" ") { "%02X".format(it) }}" else "Raw: —"
                    }
                }

                launch {
                    vm.direction.collectLatest { dir ->
                        binding.tvDirectionLabel.text = dir.name
                        binding.switchDirection.isChecked = (dir == Direction.REVERSE)
                        updateSpeedColor(vm.speedMagnitude.value)
                    }
                }

                launch {
                    vm.speedMagnitude.collectLatest { magnitude ->
                        binding.tvSpeed.text = "${magnitude / 10}%"
                        updateSpeedColor(magnitude)
                    }
                }
            }
        }
    }

    private fun updateSpeedColor(magnitude: Int) {
        val color = if (magnitude == 0) {
            R.color.text_secondary
        } else if (vm.direction.value == Direction.FORWARD) {
            R.color.status_connected
        } else {
            R.color.status_connecting
        }
        binding.tvSpeed.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun ensureBluetoothEnabled() {
        val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bt?.isEnabled == false)
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    private fun showSnack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
}
