package com.torqeedo.controller.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.torqeedo.controller.R
import com.torqeedo.controller.databinding.ActivityAutopilotBinding
import com.torqeedo.controller.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest
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
                    vm.rudderPosition.collectLatest { pos ->
                        binding.tvRudderPos.text = "%.0f%%".format(pos)
                    }
                }

                launch {
                    vm.speedMagnitude.collectLatest { speed ->
                        binding.tvSpeed.text = "${speed / 10}%"
                    }
                }
            }
        }
    }
}
