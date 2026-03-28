# Torqeedo BLE Controller (Android)

An Android application for remote wireless control of **Torqeedo Travel 1003** electric outboard motors via a **Bluetooth Low Energy (BLE)** to RS485 bridge (AC6328/ESP32).

This project implements the **TQ Bus protocol** (proprietary RS485-based protocol) over BLE, allowing you to use your smartphone as a digital throttle for your electric boat motor.

## Key Features
*   **Wireless Throttle**: Control speed and direction over Bluetooth.
*   **Real-time Telemetry**: Monitor Motor RPM, Power (Watts), and Temperature (°C).
*   **Safety First**: Automatic motor stop on BLE disconnection (via 500ms hardware watchdog).
*   **Modern UI**: High-contrast, dark nautical theme optimized for outdoor visibility.
*   **Precise Control**: Incremental 2% speed steps with smooth auto-acceleration on long-press.

## Hardware Setup
The app is designed to communicate with an **AC6328** BLE-UART bridge connected to the motor's RS485 lines.

### Connection Architecture
```
Android Smartphone           BLE Bridge (AC6328)          Torqeedo Motor
(The App)                    (Firmware)                   (Internal ECU)
──────────────               ─────────────────            ──────────────
buildDrive(speed) ──BLE ae10──▶ uart_write(frame) ──RS485──▶ TQ Bus Input
parseStatus(raw)  ◀─BLE ae02── uart_rx_callback   ◀─RS485── STATUS Reply
```

**Bridge Requirements**:
*   **Baud Rate**: 19200, 8N1.
*   **RS485**: Half-duplex.
*   **BLE Service**: `0xAE30` (Service), `0xAE10` (Write), `0xAE02` (Notify).

## User Interface & Controls

| Component | Function |
| :--- | :--- |
| **Forward/Reverse Switch** | Large central toggle to flip motor direction. |
| **Speed (+) Button** | **Tap**: +2% Speed. **Hold**: Smoothly increase speed (10%/sec). |
| **Speed (−) Button** | **Tap**: -2% Speed. **Hold**: Smoothly decrease speed (10%/sec). |
| **STOP Button** | Immediately resets speed magnitude to 0%. |
| **Telemetry Card** | Displays real-time data and error codes from the motor's ECU. |

The application maintains a **10 Hz (100ms) throttle loop**. If the BLE connection drops, the motor's internal safety watchdog will trigger an emergency stop within ~500ms.

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

### File Structure
*   `protocol/TorqeedoProtocol.kt`: CRC-8/Maxim implementation and TQ Bus frame builders.
*   `ble/TorqeedoBleManager.kt`: Nordic BLE library implementation for GATT communication.
*   `viewmodel/MainViewModel.kt`: Throttle loop logic and speed state management.
*   `ui/MainActivity.kt`: Event handling for the nautical control interface.

## Dependencies
*   [Nordic BLE Library](https://github.com/NordicSemiconductor/Android-BLE-Library): For robust, reactive Bluetooth communication.
*   [EasyPermissions KTX](https://github.com/vmadalin/EasyPermissions-Kotlin): For Android 12+ BLE permission handling.
*   [Material Components](https://github.com/material-components/material-components-android): Modern UI components.

## Keywords
Torqeedo Control, Electric Outboard, Travel 1003, BLE Throttle, Boat Motor App, RS485 BLE, AC6328, ESP32 Boat Control, TQ Bus Protocol, Nautical Android App.
