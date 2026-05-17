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

        binding.btnOpenMap.setOnClickListener {
            startActivity(Intent(this, MapPickerActivity::class.java))
        }

        // PID Tuning - Kp
        binding.sbKp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) vm.setApKp(progress / 10f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.etKp.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val value = v.text.toString().toFloatOrNull() ?: 0f
                vm.setApKp(value.coerceIn(0f, 100f))
                v.clearFocus()
                true
            } else false
        }
        binding.etKp.doAfterTextChanged { s ->
            if (binding.etKp.hasFocus()) {
                val value = s.toString().toFloatOrNull() ?: 0f
                vm.setApKp(value)
            }
        }

        // PID Tuning - Ki
        binding.sbKi.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) vm.setApKi(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.etKi.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val value = v.text.toString().toFloatOrNull() ?: 0f
                vm.setApKi(value.coerceIn(0f, 10f))
                v.clearFocus()
                true
            } else false
        }
        binding.etKi.doAfterTextChanged { s ->
            if (binding.etKi.hasFocus()) {
                val value = s.toString().toFloatOrNull() ?: 0f
                vm.setApKi(value)
            }
        }

        // PID Tuning - Kd
        binding.sbKd.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) vm.setApKd(progress / 10f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.etKd.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val value = v.text.toString().toFloatOrNull() ?: 0f
                vm.setApKd(value.coerceIn(0f, 100f))
                v.clearFocus()
                true
            } else false
        }
        binding.etKd.doAfterTextChanged { s ->
            if (binding.etKd.hasFocus()) {
                val value = s.toString().toFloatOrNull() ?: 0f
                vm.setApKd(value)
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
                    combine(vm.witPitch, vm.witRoll) { pitch, roll ->
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
