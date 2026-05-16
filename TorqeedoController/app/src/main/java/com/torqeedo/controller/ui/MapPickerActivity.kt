package com.torqeedo.controller.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
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
    private var hasCenteredInitially = false

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
        controller.setZoom(17.0)
        
        // Initial center if location is already known
        vm.currentLocation.value?.let {
            controller.setCenter(it)
            hasCenteredInitially = true
        }
        
        // Map touch events
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                p?.let {
                    vm.setTargetLocation(it, "Custom Target")
                }
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
            } ?: run {
                Toast.makeText(this, "Location unknown", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSaveLocation.setOnClickListener {
            if (vm.currentLocation.value == null) {
                Toast.makeText(this, "Cannot save: GPS not fixed", Toast.LENGTH_SHORT).show()
            } else {
                showSaveLocationDialog()
            }
        }

        binding.btnViewList.setOnClickListener {
            showWaypointListDialog()
        }

        binding.btnClearWaypoints.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Waypoints")
                .setMessage("Are you sure you want to delete all saved locations?")
                .setPositiveButton("Clear") { _, _ -> vm.clearWaypoints() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showSaveLocationDialog() {
        val input = EditText(this)
        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        params.setMargins(48, 24, 48, 24)
        input.layoutParams = params
        input.hint = "e.g. Fishing Spot 1"
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Save Location")
            .setMessage("Enter a name for this location:")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().ifBlank { "Waypoint ${waypointMarkers.size + 1}" }
                vm.saveLocation(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showWaypointListDialog() {
        val waypoints = vm.waypoints.value
        if (waypoints.isEmpty()) {
            Toast.makeText(this, "No saved locations", Toast.LENGTH_SHORT).show()
            return
        }

        val names = waypoints.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Saved Locations")
            .setItems(names) { _, which ->
                val wp = waypoints[which]
                vm.setTargetLocation(wp.point, wp.name)
                binding.mapView.controller.animateTo(wp.point)
            }
            .setNegativeButton("Close", null)
            .show()
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
                                currentPosMarker?.title = "My Location"
                                binding.mapView.overlays.add(currentPosMarker)
                            }
                            currentPosMarker?.position = loc
                            
                            if (!hasCenteredInitially) {
                                binding.mapView.controller.setCenter(loc)
                                hasCenteredInitially = true
                            }
                            
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
                            val name = vm.targetName.value ?: "Target"
                            targetMarker?.title = name
                            binding.tvWaypointInfo.text = "$name: %.5f, %.5f".format(loc.latitude, loc.longitude)
                        } else {
                            targetMarker?.let { binding.mapView.overlays.remove(it) }
                            targetMarker = null
                            binding.tvWaypointInfo.text = "Tap map to set target"
                        }
                        binding.mapView.invalidate()
                    }
                }

                launch {
                    vm.waypoints.collectLatest { waypoints ->
                        waypointMarkers.forEach { binding.mapView.overlays.remove(it) }
                        waypointMarkers.clear()
                        waypoints.forEach { wp ->
                            val m = Marker(binding.mapView)
                            m.position = wp.point
                            m.icon = ContextCompat.getDrawable(this@MapPickerActivity, android.R.drawable.ic_input_add)
                            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            m.title = wp.name
                            m.snippet = "Tap to navigate here"
                            m.setOnMarkerClickListener { marker, _ ->
                                vm.setTargetLocation(marker.position, wp.name)
                                marker.showInfoWindow()
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
