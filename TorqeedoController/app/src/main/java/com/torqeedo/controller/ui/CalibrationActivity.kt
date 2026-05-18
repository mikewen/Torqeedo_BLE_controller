package com.torqeedo.controller.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        // SensorFusion 0xA1 Calibration
        binding.btnStartSFMag.setOnClickListener { vm.startSFusionMagCal() }
        binding.btnStopSFMag.setOnClickListener { vm.stopSFusionMagCal() }
        binding.btnStartSFGyro.setOnClickListener { vm.startSFusionGyroCal() }
        binding.btnStopSFGyro.setOnClickListener { vm.stopSFusionGyroCal() }
        binding.btnResetSFDegrees.setOnClickListener { vm.resetSFDegrees() }

        // Mag Ellipse Calibration (Steering)
        binding.btnStartMagEllipse.setOnClickListener {
            vm.startMagEllipseCalib()
            showSnack("Recording magnetic samples...")
        }
        binding.btnStopMagEllipse.setOnClickListener {
            vm.stopMagEllipseCalib()
        }
        binding.btnSaveMagEllipse.setOnClickListener {
            vm.saveMagEllipseCalib()
            showSnack("Ellipse calibration saved")
        }
        binding.btnClearMagEllipse.setOnClickListener {
            vm.clearMagEllipseCalib()
            showSnack("Ellipse calibration cleared")
        }

        // Legacy/Mag Rudder Calibration
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
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 0xA1 Raw Data & Integration
                launch {
                    vm.rawImuData.collectLatest { data ->
                        binding.tvRawMx.text = "MX: ${data.mx}"
                        binding.tvRawMy.text = "MY: ${data.my}"
                        binding.tvRawMz.text = "MZ: ${data.mz}"
                        binding.tvRawGx.text = "GX: ${data.gx}"
                        binding.tvRawGy.text = "GY: ${data.gy}"
                        binding.tvRawGz.text = "GZ: ${data.gz}"
                    }
                }
                launch {
                    vm.calibDegreesTurned.collectLatest { deg ->
                        binding.tvCalibDegrees.text = "Turned: %.1f°".format(deg)
                    }
                }
                launch {
                    vm.isSFusionMagCalibrating.collectLatest { active ->
                        binding.btnStartSFMag.isEnabled = !active
                        binding.btnStopSFMag.isEnabled = active
                    }
                }
                launch {
                    vm.isSFusionGyroCalibrating.collectLatest { active ->
                        binding.btnStartSFGyro.isEnabled = !active
                        binding.btnStopSFGyro.isEnabled = active
                    }
                }
                
                // SensorFusion Fused Data
                launch {
                    vm.fusedState.collectLatest { state ->
                        binding.tvFusedHeading.text = "%.1f°".format(state.headingDeg)
                        binding.tvMagHeading.text = "%.1f°".format(state.magHeadingDeg)
                    }
                }
                
                // Calibration Status
                launch {
                    vm.sfMagCalStatus.collectLatest { status ->
                        binding.tvMagCalStatus.text = "Status: $status"
                    }
                }
                launch {
                    vm.sfGyroCalStatus.collectLatest { status ->
                        binding.tvGyroCalStatus.text = "Status: $status"
                    }
                }

                // 0xA3 GPS Data
                launch {
                    vm.parsedBleGps.collectLatest { parsed ->
                        binding.tvParsedGps.text = parsed
                    }
                }
                launch {
                    vm.rawBleGpsFrame.collectLatest { frame ->
                        binding.tvRawGps.text = frame?.joinToString(" ") { "%02X".format(it) } ?: "--"
                    }
                }

                // Rudder/Ellipse Data
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
                    vm.isMagCalibrating.collectLatest { isCalibrating ->
                        binding.btnStartMagEllipse.isEnabled = !isCalibrating
                        binding.btnStopMagEllipse.isEnabled = isCalibrating
                    }
                }
            }
        }
    }

    private fun showSnack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
}
