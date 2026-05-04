# Torqeedo BLE Controller (Android)

An Android application for remote wireless control of **Torqeedo Travel 1003** electric outboard motors via a **Bluetooth Low Energy (BLE)** to RS485 bridge (AC6328/ESP32).

This project implements the **TQ Bus protocol** (proprietary RS485-based protocol) over BLE, allowing you to use your smartphone as a digital throttle for your electric boat motor.

## Key Features
*   **Wireless Throttle**: Control speed and direction over Bluetooth.
*   **Real-time Telemetry**: Monitor Motor RPM, Temperature (°C), and **Battery Current (Amps)**.
*   **Power Estimation**: Calculate estimated power consumption in Watts based on current sensor data and an assumed 47V bus.
*   **LOOKBON Remote Support**: Full integration with LOOKBON BLE remotes for tactile throttle and direction control.
*   **Auto-Reconnect**: Automatically restores connection to the BLE Remote when powered on (persists across app restarts).
*   **Voice Feedback**: Text-to-speech prompts for connection status ("Motor connected", "Remote disconnected") and throttle changes.
*   **Persistent Settings**: "Raw Data", "Logging", and "Voice Prompts" preferences are saved automatically.
*   **Safety First**: Automatic motor stop on BLE disconnection (via 500ms hardware watchdog).
*   **Modern UI**: High-contrast, dark nautical theme with improved button visibility for outdoor use.
*   **Precise Control**: Incremental 2% speed steps with smooth auto-acceleration on long-press.
*   **GPS Integration**: Monitor speed over ground (SOG) in knots and course over ground (COG).

## Hardware Setup
The app is designed to communicate with an **AC6328** BLE-UART bridge connected to the motor's RS485 lines.

### Connection Architecture
```
Android Smartphone           BLE Bridge (AC6328)          Torqeedo Motor
(The App)                    (Firmware)                   (Internal ECU)
──────────────               ─────────────────            ──────────────
buildDrive(speed) ──BLE ae10──▶ uart_write(frame) ──RS485──▶ TQ Bus Input
parseStatus(raw)  ◀─BLE ae02── uart_rx_callback   ◀─RS485── STATUS Reply
readCurrent()     ◀─BLE ae10── CC6903 Sensor Val
```

**Bridge Requirements**:
*   **Baud Rate**: 19200, 8N1.
*   **RS485**: Half-duplex.
*   **BLE Service**: `0xAE30` (Service), `0xAE10` (Write/Read), `0xAE02` (Notify).
*   **Current Sensor**: CC6903SO-30A (±30A Range).

## User Interface & Controls

| Component | Function |
| :--- | :--- |
| **Forward/Reverse Switch** | Large central toggle to flip motor direction. |
| **Speed (+) Button** | **Tap**: +2% Speed. **Hold**: Smoothly increase speed (10%/sec). |
| **Speed (−) Button** | **Tap**: -2% Speed. **Hold**: Smoothly decrease speed (10%/sec). |
| **STOP Button** | Immediately resets speed magnitude to 0%. |
| **Telemetry Card** | Displays real-time RPM, Course, SOG (Knots), **Amps**, and **Estimated Watts (47V)**. |

### Remote Control Mapping (LOOKBON)
The app maps the following buttons on the LOOKBON BLE remote:
*   **Joystick Up/Down**: Increase/Decrease speed.
*   **Joystick Left/Right**: Toggle Direction (Forward/Reverse).
*   **Center Button (@)**: Emergency STOP.
*   **Trigger (R) + Thumb Up/Down**: Fast speed adjustment (10% steps).

## Development

### Prerequisites
*   Android Studio Ladybug or newer.
*   Kotlin 2.2.0+.
*   Android 8.0 (API 26) or higher.

### Build
```bash
./gradlew assembleDebug
./gradlew installDebug
```

## Dependencies
*   [Nordic BLE Library](https://github.com/NordicSemiconductor/Android-BLE-Library): For robust, reactive Bluetooth communication.
*   [Google Play Services Location](https://developers.google.com/android/guides/setup): For high-accuracy GPS speed and course tracking.
*   [Material Components](https://github.com/material-components/material-components-android): Modern UI components.

## Keywords
Torqeedo Control, Electric Outboard, Travel 1003, BLE Throttle, Boat Motor App, RS485 BLE, AC6328, ESP32 Boat Control, TQ Bus Protocol, Current Sensor CC6903, Power Monitoring, BLE Remote Control.
