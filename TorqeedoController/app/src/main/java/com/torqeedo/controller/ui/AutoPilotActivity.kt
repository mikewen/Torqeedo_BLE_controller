package com.torqeedo.controller.ui

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.torqeedo.controller.R
import com.torqeedo.controller.ble.SeaState
import com.torqeedo.controller.databinding.ActivityAutopilotBinding
import com.torqeedo.controller.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AutoPilotActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAutopilotBinding
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutopilotBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupControls()
        observeState()
        
        vm.startGpsUpdates()
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnHdgMinus10.setOnClickListener { vm.adjustTargetHeading(-10f) }
        binding.btnHdgMinus1.setOnClickListener { vm.adjustTargetHeading(-1f) }
        binding.btnHdgPlus1.setOnClickListener { vm.adjustTargetHeading(1f) }
        binding.btnHdgPlus10.setOnClickListener { vm.adjustTargetHeading(10f) }

        binding.btnToggleAutoPilot.setOnClickListener {
            vm.setAutoPilotActive(!vm.autoPilotActive.value)
        }

        binding.swUseRudderSensor.setOnCheckedChangeListener { _, isChecked ->
            vm.setUseRudderSensor(isChecked)
        }

        binding.swUseKalman.setOnCheckedChangeListener { _, isChecked ->
            vm.setUseKalmanFilter(isChecked)
        }

        binding.btnOpenMap.setOnClickListener {
            startActivity(Intent(this, MapPickerActivity::class.java))
        }

        // PID Tuning - Kp (Outer Loop)
        binding.sbKp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) vm.setApKp(progress / 10f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.etKp.doAfterTextChanged { s ->
            if (binding.etKp.hasFocus()) {
                val value = s.toString().toFloatOrNull() ?: 0f
                vm.setApKp(value)
            }
        }

        // PID Tuning - Kd (Inner P)
        binding.sbKd.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) vm.setApKd(progress / 10f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.etKd.doAfterTextChanged { s ->
            if (binding.etKd.hasFocus()) {
                val value = s.toString().toFloatOrNull() ?: 0f
                vm.setApKd(value)
            }
        }

        // PID Tuning - Ki (Inner I)
        binding.sbKi.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) vm.setApKi(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.etKi.doAfterTextChanged { s ->
            if (binding.etKi.hasFocus()) {
                val value = s.toString().toFloatOrNull() ?: 0f
                vm.setApKi(value)
            }
        }

        // PID Tuning - Kf (Feed Forward)
        binding.sbKf.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) vm.setApKf(progress / 10f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.etKf.doAfterTextChanged { s ->
            if (binding.etKf.hasFocus()) {
                val value = s.toString().toFloatOrNull() ?: 0f
                vm.setApKf(value)
            }
        }

        // PID Tuning - Deadband
        binding.sbDeadband.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) vm.setApDeadband(progress / 10f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.etDeadband.doAfterTextChanged { s ->
            if (binding.etDeadband.hasFocus()) {
                val value = s.toString().toFloatOrNull() ?: 0f
                vm.setApDeadband(value)
            }
        }

        // Max Turn Rate Tuning
        binding.sbMaxRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) vm.setApMaxRate(progress.toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.etMaxRate.doAfterTextChanged { s ->
            if (binding.etMaxRate.hasFocus()) {
                val value = s.toString().toFloatOrNull() ?: 0f
                vm.setApMaxRate(value)
            }
        }

        // AP Delay Tuning
        binding.sbDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) vm.setApDelay(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.etDelay.doAfterTextChanged { s ->
            if (binding.etDelay.hasFocus()) {
                val value = s.toString().toLongOrNull() ?: 0L
                vm.setApDelay(value)
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.trueHeading.collectLatest { heading ->
                        binding.tvCurrentHeading.text = "%.1f°".format(heading)
                    }
                }

                launch {
                    vm.fusedState.collectLatest { state ->
                        binding.tvMagHeading.text = "%.1f°".format(state.magHeadingDeg)
                    }
                }

                launch {
                    vm.gpsCourse.collectLatest { course ->
                        binding.tvGpsCourse.text = course?.let { "$it.0°" } ?: "—"
                    }
                }

                launch {
                    vm.targetHeading.collectLatest { target ->
                        binding.tvTargetHeading.text = "%.1f°".format(target)
                    }
                }

                launch {
                    vm.autoPilotActive.collectLatest { active ->
                        binding.btnToggleAutoPilot.text = if (active) "DISENGAGE AUTO-PILOT" else "ENGAGE AUTO-PILOT"
                        val color = if (active) R.color.status_disconnected else R.color.status_connected
                        binding.btnToggleAutoPilot.backgroundTintList = ContextCompat.getColorStateList(this@AutoPilotActivity, color)
                    }
                }

                launch {
                    vm.useRudderSensor.collectLatest { use ->
                        if (binding.swUseRudderSensor.isChecked != use) {
                            binding.swUseRudderSensor.isChecked = use
                        }
                    }
                }

                launch {
                    vm.useKalmanFilter.collectLatest { use ->
                        if (binding.swUseKalman.isChecked != use) {
                            binding.swUseKalman.isChecked = use
                        }
                    }
                }

                launch {
                    vm.seaState.collectLatest { state ->
                        binding.tvSeaState.text = state.name
                        val color = when (state) {
                            SeaState.CALM -> R.color.status_connected
                            SeaState.MODERATE -> R.color.accent_primary
                            SeaState.ROUGH -> R.color.status_disconnected
                        }
                        binding.tvSeaState.setTextColor(ContextCompat.getColor(this@AutoPilotActivity, color))
                    }
                }

                launch {
                    combine(vm.IMUPitch, vm.IMURoll) { pitch, roll ->
                        "P: %.1f° R: %.1f°".format(pitch, roll)
                    }.collectLatest { text ->
                        binding.tvPitchRoll.text = text
                    }
                }

                launch {
                    vm.rudderPosition.collectLatest { pos ->
                        binding.tvRudderPos.text = "%.0f%%".format(pos)
                    }
                }

                launch {
                    vm.speedMagnitude.collectLatest { speed ->
                        binding.tvSpeed.text = "${speed / 10}%"
                    }
                }

                launch {
                    vm.apKp.collectLatest { kp ->
                        if (!binding.etKp.hasFocus()) binding.etKp.setText("%.1f".format(kp))
                        binding.sbKp.progress = (kp * 10).toInt()
                    }
                }

                launch {
                    vm.apKi.collectLatest { ki ->
                        if (!binding.etKi.hasFocus()) binding.etKi.setText("%.2f".format(ki))
                        binding.sbKi.progress = (ki * 100).toInt()
                    }
                }

                launch {
                    vm.apKd.collectLatest { kd ->
                        if (!binding.etKd.hasFocus()) binding.etKd.setText("%.1f".format(kd))
                        binding.sbKd.progress = (kd * 10).toInt()
                    }
                }

                launch {
                    vm.apKf.collectLatest { kf ->
                        if (!binding.etKf.hasFocus()) binding.etKf.setText("%.1f".format(kf))
                        binding.sbKf.progress = (kf * 10).toInt()
                    }
                }

                launch {
                    vm.apDeadband.collectLatest { db ->
                        if (!binding.etDeadband.hasFocus()) binding.etDeadband.setText("%.1f".format(db))
                        binding.sbDeadband.progress = (db * 10).toInt()
                    }
                }

                launch {
                    vm.apMaxRate.collectLatest { rate ->
                        if (!binding.etMaxRate.hasFocus()) binding.etMaxRate.setText("%.0f".format(rate))
                        binding.sbMaxRate.progress = rate.toInt()
                    }
                }

                launch {
                    vm.apDelay.collectLatest { delay ->
                        if (!binding.etDelay.hasFocus()) binding.etDelay.setText("%d".format(delay))
                        binding.sbDelay.progress = delay.toInt()
                    }
                }

                launch {
                    vm.targetLocation.collectLatest { loc ->
                        if (loc != null) {
                            binding.tvWaypointInfo.text = "Target: %.5f, %.5f".format(loc.latitude, loc.longitude)
                        } else {
                            binding.tvWaypointInfo.text = "No target set"
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vm.stopGpsUpdates()
    }
}
