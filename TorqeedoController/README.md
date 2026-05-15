# Torqeedo BLE Controller (Android)

An Android application for remote wireless control of **Torqeedo Travel 1003** electric outboard motors via a **Bluetooth Low Energy (BLE)** to RS485 bridge (AC6328/ESP32).

This project implements the **TQ Bus protocol** (proprietary RS485-based protocol) over BLE, allowing you to use your smartphone as a digital throttle for your electric boat motor.

## Key Features
*   **Wireless Throttle**: Control speed and direction over Bluetooth.
*   **Digital Steering**: Integrated steering control with precise incremental steps and center reset.
*   **Integrated Autopilot**: Maintain a target heading automatically using a PID controller (requires WitMotion heading sensor).
*   **Configurable Steering Pulse**: Adjust steering motor runtime (ms) per step via UI to match your actuator's speed.
*   **Real-time Telemetry**: Monitor Motor RPM, Temperature (°C), **Battery Current (Amps)**, and **Steering Position**.
*   **Dual GPS Sources**: Supports high-accuracy **External BLE GPS** devices. If a BLE GPS source (0xA3 header) is detected, the app automatically prioritizes it over the phone's internal GPS.
*   **Power Estimation**: Calculate estimated power consumption in Watts based on current sensor data and an assumed 47V bus.
*   **LOOKBON Remote Support**: Full integration with LOOKBON BLE remotes for tactile throttle, steering, and direction control.
*   **Auto-Reconnect**: Automatically restores connection to the BLE Remote when powered on (persists across app restarts).
*   **Voice Feedback**: Text-to-speech prompts for connection status ("Motor connected", "G P S connected"), throttle changes, and steering actions.
*   **Persistent Settings**: "Raw Data", "Logging", "Voice Prompts", and "Steer Scale" preferences are saved automatically.
*   **Safety First**: Automatic motor stop on BLE disconnection (via 500ms hardware watchdog).
*   **Modern UI**: High-contrast, dark nautical theme with improved button visibility for outdoor use.

## Hardware Setup
The app is designed to communicate with an **AC6328** BLE-UART bridge connected to the motor's RS485 lines.

### Connection Architecture
```
Android Smartphone           BLE Bridge (AC6328)          Torqeedo Motor
(The App)                    (Firmware)                   (Internal ECU)
──────────────               ─────────────────            ──────────────
buildDrive(speed) ──BLE ae10──▶ uart_write(frame) ──RS485──▶ TQ Bus Input
sendSteer(value)  ──BLE ae03──▶ uart_write(cmd)   ──GPIO ──▶ Steering Actuator
parseStatus(raw)  ◀─BLE ae02── uart_rx_callback   ◀─RS485── STATUS Reply
parseGps(0xA3)    ◀─BLE ae02── GNSS Module SSS    ◀─UART ── NMEA/Binary
readCurrent()     ◀─BLE ae10── CC6903 Sensor Val
```

### Steering Command Protocol (5-byte)
The steering command is sent to characteristic `0xAE03`:
| Byte | Meaning |
| :--- | :--- |
| 0 | 's' (Command Header) |
| 1 | 'L' or 'R' (Direction) |
| 2 | PWM / Power (Fixed at 100) |
| 3 | Runtime Low Byte (ms) |
| 4 | Runtime High Byte (ms) |

### External GPS Protocol (17-byte)
The app listens for notifications on `0xAE02` with the following binary format (Source: $GNRMC):
| Byte | Field | Type | Scale (Multiplier) |
| :--- | :--- | :--- | :--- |
| 0 | Header | u8 | 0xA3 |
| 1-4 | Time | u32 | HHMMSS.sss * 1000 |
| 5-8 | Latitude | s32 | Decimal Degrees * 10^6 |
| 9-12 | Longitude | s32 | Decimal Degrees * 10^6 |
| 13-14 | Speed | u16 | Knots * 100 |
| 15-16 | Course | u16 | Degrees * 100 |

**Bridge Requirements**:
*   **Baud Rate**: 19200, 8N1.
*   **RS485**: Half-duplex.
*   **BLE Service**: `0xAE30` (Service), `0xAE10` (Drive Write/Read), `0xAE03` (Steer Write), `0xAE02` (Notify).
*   **Current Sensor**: CC6903SO-30A (±30A Range).

## User Interface & Controls

| Component | Function |
| :--- | :--- |
| **Scan Buttons** | Dedicated buttons to scan for **Motor**, **Remote**, **GPS**, or **Heading** (WitMotion) sensors. |
| **Forward/Reverse Switch** | Large central toggle to flip motor direction. |
| **Speed (+) / (−)** | **Tap**: ±2% Speed. **Hold**: Smoothly adjust speed (10%/sec). |
| **Steer (L1/R1, L5/R5)** | Adjust steering angle. **L1/R1** for fine tuning, **L5/R5** for coarse. **Hold** for repeat. |
| **Autopilot** | Enable automatic heading hold. Target heading can be adjusted in real-time. |
| **Runtime Scale** | Configure steering pulse duration (ms per unit). Adjustable via slider (0-500ms). |
| **STOP Button** | Immediately resets speed magnitude to 0%. |
| **RESET Button** | Centers the steering (returns value to 0). |
| **Telemetry Card** | Displays RPM, Course, SOG (Knots), **Amps**, **Watts**, and **Steer Position**. |

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
*   [Google Play Services Location](https://developers.google.com/android/guides/setup): For high-accuracy GPS speed and course tracking (used when BLE GPS is unavailable).
*   [Material Components](https://github.com/material-components/material-components-android): Modern UI components.

## Keywords
Torqeedo Control, Electric Outboard, Travel 1003, BLE Throttle, Boat Motor App, RS485 BLE, AC6328, ESP32 Boat Control, TQ Bus Protocol, Current Sensor CC6903, Power Monitoring, BLE Remote Control, Digital Steering, BLE GPS, Autopilot, Heading Hold.
