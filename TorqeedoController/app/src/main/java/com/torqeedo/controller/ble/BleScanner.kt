package com.torqeedo.controller.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DiscoveredDevice(
    val device: BluetoothDevice,
    val name: String,
    val rssi: Int,
    val address: String
)

class BleScanner(private val adapter: BluetoothAdapter) {

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val scanner = adapter.bluetoothLeScanner

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "Unknown Device"
            
            val discovered = DiscoveredDevice(
                device = result.device,
                name = name,
                rssi = result.rssi,
                address = result.device.address
            )
            _devices.update { current ->
                val existing = current.indexOfFirst { it.address == discovered.address }
                if (existing >= 0) {
                    current.toMutableList().also { it[existing] = discovered }
                } else {
                    current + discovered
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BleScanner", "Scan failed: $errorCode")
            _isScanning.value = false
        }
    }

    fun startScan(scanAllNames: Boolean) {
        if (_isScanning.value) return
        _devices.value = emptyList()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = if (scanAllNames) {
            emptyList<ScanFilter>()
        } else {
            listOf(
                ScanFilter.Builder().setDeviceName("AC6328_UART").build(),
                ScanFilter.Builder().setDeviceName("LOOKBON").build()
            )
        }
        
        internalStartScan(filters, settings)
    }

    fun startRemoteScan() {
        if (_isScanning.value) return
        _devices.value = emptyList()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = listOf(
            ScanFilter.Builder().setDeviceName("LOOKBON").build()
        )
        
        internalStartScan(filters, settings)
    }

    private fun internalStartScan(filters: List<ScanFilter>, settings: ScanSettings) {
        try {
            scanner?.startScan(filters, settings, scanCallback)
            _isScanning.value = true
        } catch (e: Exception) {
            Log.e("BleScanner", "Could not start scan: ${e.message}")
        }
    }

    fun stopScan() {
        if (!_isScanning.value) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e("BleScanner", "Could not stop scan: ${e.message}")
        }
        _isScanning.value = false
    }
}
