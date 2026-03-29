package com.torqeedo.controller.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.torqeedo.controller.R
import com.torqeedo.controller.ble.TorqeedoBleManager
import com.torqeedo.controller.databinding.ActivityMainBinding
import com.torqeedo.controller.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private lateinit var deviceAdapter: DeviceListAdapter

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) {}

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            ensureBluetoothEnabled()
        } else {
            showSnack("Permissions (Bluetooth & Location) are required for GPS and BLE.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDeviceList()
        setupControls()
        observeState()
        requestPermissionsIfNeeded()
    }

    override fun onStop() {
        super.onStop()
        vm.stopScan()
    }

    private fun setupDeviceList() {
        deviceAdapter = DeviceListAdapter { device -> vm.connect(device) }
        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        // Debug Settings
        binding.switchShowRaw.setOnCheckedChangeListener { _, isChecked ->
            vm.setShowRawData(isChecked)
        }
        binding.switchLogging.setOnCheckedChangeListener { _, isChecked ->
            vm.setEnableLogging(isChecked)
        }

        // Scan Settings
        binding.switchScanAll.setOnCheckedChangeListener { _, isChecked ->
            vm.setScanAllNames(isChecked)
        }

        // Direction Switch
        binding.switchDirection.setOnCheckedChangeListener { _, isChecked ->
            vm.setDirection(if (isChecked) MainViewModel.Direction.REVERSE else MainViewModel.Direction.FORWARD)
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

        // Stop Button
        binding.btnStop.setOnClickListener {
            vm.stopMotor()
        }

        // Disconnect Button
        binding.btnDisconnect.setOnClickListener {
            vm.disconnect()
        }

        binding.btnScan.setOnClickListener {
            if (vm.isScanning.value) vm.stopScan() else vm.startScan()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.connectionState.collectLatest { state ->
                        when (state) {
                            TorqeedoBleManager.ConnectionState.DISCONNECTED -> {
                                binding.tvConnectionStatus.text = "Disconnected"
                                binding.tvConnectionStatus.setTextColor(
                                    ContextCompat.getColor(this@MainActivity, R.color.status_disconnected))
                                binding.controlPanel.visibility = View.GONE
                                binding.scanPanel.visibility    = View.VISIBLE
                            }
                            TorqeedoBleManager.ConnectionState.CONNECTING -> {
                                binding.tvConnectionStatus.text = "Connecting…"
                                binding.tvConnectionStatus.setTextColor(
                                    ContextCompat.getColor(this@MainActivity, R.color.status_connecting))
                            }
                            TorqeedoBleManager.ConnectionState.CONNECTED -> {
                                binding.tvConnectionStatus.text = "Connected"
                                binding.tvConnectionStatus.setTextColor(
                                    ContextCompat.getColor(this@MainActivity, R.color.status_connected))
                                binding.scanPanel.visibility    = View.GONE
                                binding.controlPanel.visibility = View.VISIBLE
                            }
                        }
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
                        binding.btnScan.text = if (scanning) "Stop scan" else "Scan for devices"
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

                // GPS Data Observation
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
                        binding.switchDirection.isChecked = (dir == MainViewModel.Direction.REVERSE)
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
        } else if (vm.direction.value == MainViewModel.Direction.FORWARD) {
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
