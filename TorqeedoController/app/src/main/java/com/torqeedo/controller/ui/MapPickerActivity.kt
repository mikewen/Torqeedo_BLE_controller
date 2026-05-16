package com.torqeedo.controller.ui

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.torqeedo.controller.R
import com.torqeedo.controller.databinding.ActivityMapPickerBinding
import com.torqeedo.controller.viewmodel.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

class MapPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapPickerBinding
    private val vm: MainViewModel by viewModels()

    private var currentPosMarker: Marker? = null
    private var targetMarker: Marker? = null
    private val waypointMarkers = mutableListOf<Marker>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap()
        setupControls()
        observeState()
        
        vm.startGpsUpdates()
    }

    private fun setupMap() {
        binding.mapView.setTileSource(TileSourceFactory.MAPNIK)
        binding.mapView.setMultiTouchControls(true)
        binding.mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        
        val controller = binding.mapView.controller
        controller.setZoom(15.0)
        
        // Map touch events
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                vm.setTargetLocation(p)
                return true
            }
            override fun longPressHelper(p: GeoPoint?): Boolean {
                return false
            }
        }
        binding.mapView.overlays.add(MapEventsOverlay(receiver))
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnCenterMap.setOnClickListener {
            vm.currentLocation.value?.let {
                binding.mapView.controller.animateTo(it)
            }
        }

        binding.btnSaveLocation.setOnClickListener {
            vm.saveCurrentLocation()
        }

        binding.btnClearWaypoints.setOnClickListener {
            vm.clearWaypoints()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.currentLocation.collectLatest { loc ->
                        if (loc != null) {
                            if (currentPosMarker == null) {
                                currentPosMarker = Marker(binding.mapView)
                                currentPosMarker?.icon = ContextCompat.getDrawable(this@MapPickerActivity, android.R.drawable.ic_menu_compass)
                                currentPosMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                binding.mapView.overlays.add(currentPosMarker)
                            }
                            currentPosMarker?.position = loc
                            binding.mapView.invalidate()
                        }
                    }
                }

                launch {
                    vm.trueHeading.collectLatest { heading ->
                        currentPosMarker?.rotation = -heading
                        binding.mapView.invalidate()
                    }
                }

                launch {
                    vm.targetLocation.collectLatest { loc ->
                        if (loc != null) {
                            if (targetMarker == null) {
                                targetMarker = Marker(binding.mapView)
                                targetMarker?.icon = ContextCompat.getDrawable(this@MapPickerActivity, android.R.drawable.ic_menu_mylocation)
                                targetMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                binding.mapView.overlays.add(targetMarker)
                            }
                            targetMarker?.position = loc
                            binding.tvWaypointInfo.text = "Target: %.5f, %.5f".format(loc.latitude, loc.longitude)
                        } else {
                            targetMarker?.let { binding.mapView.overlays.remove(it) }
                            targetMarker = null
                            binding.tvWaypointInfo.text = "Tap map to set target"
                        }
                        binding.mapView.invalidate()
                    }
                }

                launch {
                    vm.waypoints.collectLatest { points ->
                        waypointMarkers.forEach { binding.mapView.overlays.remove(it) }
                        waypointMarkers.clear()
                        points.forEach { p ->
                            val m = Marker(binding.mapView)
                            m.position = p
                            m.icon = ContextCompat.getDrawable(this@MapPickerActivity, android.R.drawable.ic_input_add)
                            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            m.setOnMarkerClickListener { marker, _ ->
                                vm.setTargetLocation(marker.position)
                                true
                            }
                            binding.mapView.overlays.add(m)
                            waypointMarkers.add(m)
                        }
                        binding.mapView.invalidate()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        vm.stopGpsUpdates()
    }
}
