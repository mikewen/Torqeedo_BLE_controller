package com.torqeedo.controller.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.torqeedo.controller.ble.DiscoveredDevice
import com.torqeedo.controller.databinding.ItemDeviceBinding

class DeviceListAdapter(
    private val onConnect: (DiscoveredDevice) -> Unit
) : ListAdapter<DiscoveredDevice, DeviceListAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(private val b: ItemDeviceBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(device: DiscoveredDevice) {
            b.tvDeviceName.text    = device.name
            b.tvDeviceAddress.text = device.address
            b.tvRssi.text          = "${device.rssi} dBm"
            b.root.setOnClickListener { onConnect(device) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val b = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(b)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<DiscoveredDevice>() {
            override fun areItemsTheSame(a: DiscoveredDevice, b: DiscoveredDevice) =
                a.address == b.address
            override fun areContentsTheSame(a: DiscoveredDevice, b: DiscoveredDevice) =
                a == b
        }
    }
}
