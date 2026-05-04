package com.torqeedo.controller.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.util.UUID

/**
 * LOOKBON BLE Remote — Adapted for Torqeedo Controller.
 * Maps remote buttons to motor control actions.
 */
class LookbonRemote(context: Context) : BleManager(context) {

    enum class Command {
        SPEED_UP,
        SPEED_DOWN,
        SPEED_UP_FAST,
        SPEED_DOWN_FAST,
        STOP,
        TOGGLE_DIRECTION
    }

    companion object {
        private const val TAG = "LookbonRemote"

        // ae30 service / ae02 characteristic — same as motor controller
        val SERVICE_UUID: UUID = UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb")
        val CHAR_UUID: UUID    = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")

        val REMOTE_NAME_FILTERS = listOf("LOOKBON", "lookbon")
        const val JOY_REPEAT_MS = 200L
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────
    var onCommand:      ((Command) -> Unit)? = null
    var onConnected:    (() -> Unit)?        = null
    var onDisconnected: (() -> Unit)?        = null

    // ── State ─────────────────────────────────────────────────────────────────
    private var rHeld = false
    private var lHeld = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var joyRepeatRunnable: Runnable? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    override fun getGattCallback(): BleManagerGattCallback = GattCb()

    private inner class GattCb : BleManagerGattCallback() {
        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val svc = gatt.getService(SERVICE_UUID) ?: return false
            notifyChar = svc.getCharacteristic(CHAR_UUID)
            return notifyChar != null
        }

        override fun initialize() {
            notifyChar?.let { c ->
                setNotificationCallback(c).with { _, data ->
                    val raw = data.value ?: return@with
                    val hex = raw.joinToString("") { "%02X".format(it) }
                    handleHex(hex)
                }
                enableNotifications(c).enqueue()
            }
        }

        override fun onServicesInvalidated() {
            notifyChar = null
        }
    }

    private fun handleHex(hex: String) {
        when {
            // 2-char = single byte key event or joystick
            hex.length == 2 -> {
                val eventByte = hex[0]   // A/B/C or D
                val btnChar   = hex[1]   // 1-7 or 0-8
                when (eventByte) {
                    'D' -> handleJoystick(btnChar.digitToIntOrNull() ?: return)
                    'A', 'B', 'C' -> handleButton(eventByte, btnChar.digitToIntOrNull() ?: return)
                }
            }
            // 4-char = 2-byte joystick (e.g. "D3" stored as 0x44 0x33)
            hex.length == 4 -> {
                try {
                    val b1 = hex.substring(0, 2).toInt(16)
                    val b2 = hex.substring(2, 4).toInt(16)
                    if (b1 == 'D'.code) {
                        handleJoystick(b2 - '0'.code)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing 4-char hex: $hex", e)
                }
            }
        }
    }

    private fun handleButton(event: Char, btn: Int) {
        when {
            // R modifier (Trigger NEAR)
            event == 'B' && btn == 6 -> { rHeld = true; return }
            event == 'C' && btn == 6 -> { rHeld = false; return }

            // L modifier (Trigger FAR)
            event == 'B' && btn == 7 -> { lHeld = true; return }
            event == 'C' && btn == 7 -> { lHeld = false; stopJoyRepeat(); return }

            // @ button (Center)
            event == 'A' && btn == 1 -> emit(Command.STOP)
            event == 'B' && btn == 1 -> emit(Command.STOP) // Long press

            // Thumb buttons: A=Right, B=Left, C=Up, D=Down
            event == 'A' && btn == 2 -> emit(Command.TOGGLE_DIRECTION) // A click
            event == 'A' && btn == 3 -> emit(Command.TOGGLE_DIRECTION) // B click
            
            event == 'A' && btn == 4 -> { // C (Up)
                if (rHeld) emit(Command.SPEED_UP_FAST) else emit(Command.SPEED_UP)
            }
            event == 'A' && btn == 5 -> { // D (Down)
                if (rHeld) emit(Command.SPEED_DOWN_FAST) else emit(Command.SPEED_DOWN)
            }
        }
    }

    private fun handleJoystick(dir: Int) {
        stopJoyRepeat()
        if (dir == 0) return

        val cmd = when (dir) {
            1 -> Command.SPEED_UP
            2 -> Command.SPEED_DOWN
            3 -> Command.TOGGLE_DIRECTION
            4 -> Command.TOGGLE_DIRECTION
            else -> null
        } ?: return

        emit(cmd)
        joyRepeatRunnable = object : Runnable {
            override fun run() {
                emit(cmd)
                mainHandler.postDelayed(this, JOY_REPEAT_MS)
            }
        }
        mainHandler.postDelayed(joyRepeatRunnable!!, JOY_REPEAT_MS)
    }

    private fun stopJoyRepeat() {
        joyRepeatRunnable?.let { mainHandler.removeCallbacks(it) }
        joyRepeatRunnable = null
    }

    private fun emit(cmd: Command) {
        mainHandler.post { onCommand?.invoke(cmd) }
    }

    fun connectToDevice(device: BluetoothDevice) {
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(d: BluetoothDevice) = Unit
            override fun onDeviceDisconnecting(d: BluetoothDevice) = Unit
            override fun onDeviceReady(d: BluetoothDevice) = Unit
            override fun onDeviceConnected(d: BluetoothDevice) {
                Log.i(TAG, "Lookbon remote connected")
                mainHandler.post { onConnected?.invoke() }
            }
            override fun onDeviceFailedToConnect(d: BluetoothDevice, reason: Int) {
                Log.w(TAG, "Lookbon remote failed: $reason")
                mainHandler.post { onDisconnected?.invoke() }
            }
            override fun onDeviceDisconnected(d: BluetoothDevice, reason: Int) {
                Log.i(TAG, "Lookbon remote disconnected")
                mainHandler.post { onDisconnected?.invoke() }
            }
        })
        connect(device)
            .retry(3, 300)
            .useAutoConnect(false)
            .enqueue()
    }

    override fun close() {
        stopJoyRepeat()
        super.close()
    }
}
