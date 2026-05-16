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

        // Manual Drive Buttons (Hold to repeat)
        binding.btnSteerLeft.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    vm.startSteerRepeat(-1)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    vm.stopSteerRepeat()
                    v.performClick()
                }
            }
            true
        }
        binding.btnSteerRight.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    vm.startSteerRepeat(1)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    vm.stopSteerRepeat()
                    v.performClick()
                }
            }
            true
        }

        binding.btnCalibHallBias.setOnClickListener {
            vm.calibrateSteerBias()
        }

        // Manual Hall Calibration
        binding.btnSetCenter.setOnClickListener { vm.setSteerCalibCenter() }
        binding.btnSetPort22.setOnClickListener { vm.setSteerCalibPort22() }
        binding.btnSetPort35.setOnClickListener { vm.setSteerCalibPort35() }
        binding.btnSetStbd22.setOnClickListener { vm.setSteerCalibStbd22() }
        binding.btnSetStbd35.setOnClickListener { vm.setSteerCalibStbd35() }

        // Auto Hall Calibration
        binding.btnAutoCalibPort.setOnClickListener { vm.autoCalibPort() }
        binding.btnAutoCalibStbd.setOnClickListener { vm.autoCalibStbd() }

        // Mag Ellipse Calibration
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

        binding.btnCalibGyro.setOnClickListener {
            vm.startImuGyroCalibration()
            showSnack("Gyro calibration started")
        }

        binding.btnCalibMag.setOnClickListener {
            vm.startImuMagCalibration()
            showSnack("Magnetic calibration started")
        }

        binding.btnSaveImu.setOnClickListener {
            vm.saveImuCalibration()
            showSnack("Calibration saved")
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.steerSensorA.collectLatest { a -> binding.tvHallA.text = "A: $a" }
                }
                launch {
                    vm.steerSensorB.collectLatest { b -> binding.tvHallB.text = "B: $b" }
                }
                launch {
                    vm.steerSensorRatio.collectLatest { r -> binding.tvHallRatio.text = "R: %.3f".format(r) }
                }
                launch {
                    vm.steerSensorAngle.collectLatest { angle ->
                        binding.tvHallAngle.text = "Angle: %.1f°".format(angle)
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
                launch {
                    vm.rudderPosition.collectLatest { pos ->
                        binding.tvRudderPos.text = "Rudder: %.1f%%".format(pos)
                    }
                }
                launch {
                    vm.isMagCalibrating.collectLatest { isCalibrating ->
                        if (isCalibrating) {
                            binding.btnStartMagEllipse.isEnabled = false
                            binding.btnStopMagEllipse.isEnabled = true
                        } else {
                            binding.btnStartMagEllipse.isEnabled = true
                            binding.btnStopMagEllipse.isEnabled = false
                        }
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
                launch {
                    vm.imuCalibStatus.collectLatest { status ->
                        binding.tvImuStatus.text = "Status: $status"
                    }
                }
            }
        }
    }

    private fun showSnack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
}
