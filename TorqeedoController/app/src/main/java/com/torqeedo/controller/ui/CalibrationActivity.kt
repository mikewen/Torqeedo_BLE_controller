package com.torqeedo.controller.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.torqeedo.controller.databinding.ActivityCalibrationBinding
import com.torqeedo.controller.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupControls()
        observeState()
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnCalibZero.setOnClickListener {
            vm.calibrateZero()
            showSnack("Zero position calibrated")
        }
        binding.btnCalibPort.setOnClickListener {
            vm.calibratePort()
            showSnack("Port max position calibrated")
        }
        binding.btnCalibStbd.setOnClickListener {
            vm.calibrateStbd()
            showSnack("Starboard max position calibrated")
        }
        
        binding.btnCalibImu.setOnClickListener {
            // Placeholder for IMU calibration command if available in VM
            showSnack("IMU Calibration started")
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.magX.collectLatest { x -> binding.tvMagX.text = "X: $x" }
                }
                launch {
                    vm.magY.collectLatest { y -> binding.tvMagY.text = "Y: $y" }
                }
                launch {
                    vm.magZ.collectLatest { z -> binding.tvMagZ.text = "Z: $z" }
                }
                launch {
                    vm.rudderPosition.collectLatest { pos ->
                        binding.tvRudderPos.text = "Rudder: %.1f%%".format(pos)
                    }
                }
                launch {
                    vm.witYaw.collectLatest { yaw -> binding.tvYaw.text = "%.1f°".format(yaw) }
                }
                launch {
                    vm.witPitch.collectLatest { pitch -> binding.tvPitch.text = "%.1f°".format(pitch) }
                }
                launch {
                    vm.witRoll.collectLatest { roll -> binding.tvRoll.text = "%.1f°".format(roll) }
                }
            }
        }
    }

    private fun showSnack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
}
