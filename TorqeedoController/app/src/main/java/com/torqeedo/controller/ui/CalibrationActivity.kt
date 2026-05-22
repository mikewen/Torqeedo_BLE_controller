package com.torqeedo.controller.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.torqeedo.controller.databinding.ActivityCalibrationBinding
import com.torqeedo.controller.viewmodel.MainViewModel
import com.torqeedo.controller.viewmodel.SteerSensorType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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

        binding.swUseKalman.setOnCheckedChangeListener { _, isChecked ->
            vm.setUseKalmanFilter(isChecked)
        }

        // Steer Sensor Type Toggle
        binding.toggleSteerSensorType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    binding.btnTypeQmc.id -> vm.setSteerSensorType(SteerSensorType.QMC6308)
                    binding.btnTypeVl53.id -> vm.setSteerSensorType(SteerSensorType.VL53L0X)
                }
            }
        }

        // Mag Ellipse Calibration (Steering - QMC Only)
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

        // Steer Calibration (Generic - reuses QMC or VL53 based on ViewModel logic)
        binding.btnCalibZero.setOnClickListener {
            vm.calibrateZero()
            showSnack("Zero position (0%) calibrated")
        }
        binding.btnCalibPort.setOnClickListener {
            vm.calibratePort()
            showSnack("Port position (-100%) calibrated")
        }
        binding.btnCalibStbd.setOnClickListener {
            vm.calibrateStbd()
            showSnack("Starboard position (100%) calibrated")
        }

        // Multi-point Calibration
        binding.btnAddCustomCalib.setOnClickListener {
            val text = binding.etCustomCalib.text.toString()
            val percent = text.toFloatOrNull()
            if (percent != null && percent in -100f..100f) {
                vm.addSteerCalibPoint(percent)
                showSnack("Calibrated point at $percent%")
                binding.etCustomCalib.setText("")
            } else {
                showSnack("Enter valid percentage (-100 to 100)")
            }
        }

        binding.btnClearSteerCalib.setOnClickListener {
            vm.clearSteerCalib()
            showSnack("All steering calibration points cleared")
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

                launch {
                    vm.useKalmanFilter.collectLatest { use ->
                        if (binding.swUseKalman.isChecked != use) {
                            binding.swUseKalman.isChecked = use
                        }
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

                // Steering Sensor Data
                launch {
                    vm.steerSensorType.collectLatest { type ->
                        val isQmc = type == SteerSensorType.QMC6308
                        binding.toggleSteerSensorType.check(if (isQmc) binding.btnTypeQmc.id else binding.btnTypeVl53.id)
                        binding.layoutQmcRaw.visibility = if (isQmc) View.VISIBLE else View.GONE
                        //binding.layoutVl53Raw.visibility = if (isQmc) View.GONE else View.VISIBLE
                        
                        // Disable ellipse buttons for VL53
                        binding.btnStartMagEllipse.isEnabled = isQmc
                        binding.btnStopMagEllipse.isEnabled = isQmc
                        binding.btnSaveMagEllipse.isEnabled = isQmc
                        binding.btnClearMagEllipse.isEnabled = isQmc
                    }
                }
                launch {
                    vm.magX.collectLatest { x -> binding.tvMagX.text = "X: $x" }
                }
                launch {
                    vm.magY.collectLatest { y -> binding.tvMagY.text = "Y: $y" }
                }
                launch {
                    vm.magZ.collectLatest { z -> binding.tvMagZ.text = "Z: $z" }
                }
//                launch {
//                    vm.vl53l0xDistance.collectLatest { dist ->
//                        binding.tvVl53Distance.text = "Distance: $dist mm"
//                    }
//                }
//                launch {
//                    vm.rawVl53Frame.collectLatest { frame ->
//                        binding.tvRawVl53.text = "Raw: " + (frame?.joinToString(" ") { "%02X".format(it) } ?: "--")
//                    }
//                }
                launch {
                    combine(vm.rawMagAngle, vm.magRudderPercentage, vm.vl53l0xDistance, vm.steerSensorType) { angle, percent, dist, type ->
                        if (type == SteerSensorType.QMC6308) {
                            "Angle: %.1f° (%.1f%%)".format(angle, percent)
                        } else {
                            "Dist: $dist mm (%.1f%%)".format(percent)
                        }
                    }.collectLatest { text ->
                        binding.tvRudderPos.text = text
                    }
                }
                launch {
                    vm.isMagCalibrating.collectLatest { isCalibrating ->
                        if (vm.steerSensorType.value == SteerSensorType.QMC6308) {
                            binding.btnStartMagEllipse.isEnabled = !isCalibrating
                            binding.btnStopMagEllipse.isEnabled = isCalibrating
                        }
                    }
                }
            }
        }
    }

    private fun showSnack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
}
