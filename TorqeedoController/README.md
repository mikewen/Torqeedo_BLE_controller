# Torqeedo BLE Controller (Android)

An Android application for remote wireless control of **Torqeedo Travel 1003** electric outboard motors via a **Bluetooth Low Energy (BLE)** to RS485 bridge (AC6328/ESP32/ESP32-C3).

This project implements the **TQ Bus protocol** (proprietary RS485-based protocol) over BLE, allowing you to use your smartphone as a digital throttle for your electric boat motor.

## Key Features
*   **Wireless Throttle**: Control speed and direction over Bluetooth with a 500ms safety hardware watchdog.
*   **Digital Steering**: Integrated steering control with precise incremental steps and center reset.
*   **Backlash Compensation**: Configurable "Backlash Time" (ms) to eliminate mechanical play in steering actuators when changing directions.
*   **Boat Profiles**: Quick-switch presets for different vessels (**CL16**, **MAC25**, **TOY**) to handle varying steering geometries and motor types.
*   **2D Vector Steering Feedback**: High-resolution support for dual linear Hall position sensors using a **2D Vector Path Interpolation Engine**. This handles the non-monotonic "decreasing ratio" problem that occurs when a magnet passes directly over a sensor in a 45-degree geometry.
*   **QMC6308/MMC5603 Magnetometer Support**: High-precision steering position using magnetic sensors with **Least-Squares Ellipse Fitting** to compensate for hard-iron and soft-iron distortions.
*   **Dual-Stage Smoothing**: Real-time Low Pass Filtering (LPF) applied to raw ADC inputs and the final calculated angle to ensure a stable display in high-vibration marine environments.
*   **Signal Quality Monitoring**: Automatic magnitude gating (`MIN_MAGNITUDE`) to lock the steering angle and ignore noise when the magnet is removed or too far from sensors.
*   **Integrated Autopilot**: Maintain a target heading automatically using a PID controller (requires WitMotion heading sensor).
*   **Advanced Calibration Suite**: 
    *   **BIAS Correction**: Record baseline voltages with the magnet removed to eliminate sensor offset.
    *   **2D Vector Mapping**: Manually map Center (0°), Port (22.5°/35°), and Starboard (22.5°/35°) reference points in vector space.
    *   **Ellipse Fit**: Geometric calibration that fits a least-squares ellipse to magnetic data, normalizing it to a perfect unit circle for linear angular feedback.
    *   **Auto-Run Sweep**: Automated calibration routine that returns the motor to center and performs a timed sweep to generate a high-resolution 128-point 2D path arc.
*   **Manual Actuator Control**: Direct buttons in the UI to drive the linear steering motor for setup and positioning.
*   **Real-time Telemetry**: Monitor Motor RPM, Temperature (°C), **Battery Current (Amps)**, and **Steering Angle**.
*   **Dual GPS Sources**: Supports high-accuracy **External BLE GPS** devices (0xA3 header), prioritizing them over internal phone GPS.
*   **LOOKBON Remote Support**: Full integration with LOOKBON BLE remotes for tactile throttle and steering control.
*   **Voice Feedback**: Text-to-speech prompts for status changes, throttle adjustments, and calibration milestones.
*   **Configurable Steering Pulse**: Adjust steering motor runtime (ms) per step via UI to match your actuator's speed.
*   **Persistent Settings**: "Raw Data", "Logging", "Voice Prompts", "Backlash Time", and "Boat Profile" preferences are saved automatically.

## Magnetometer Calibration (Ellipse Fit)

### Changes Implemented
The magnetic steering position logic was upgraded from a simple 1D mapping to a 2D geometric fit:
1.  **Algebraic Distance Minimization**: Implements a least-squares fitter for the ellipse equation $Ax^2 + Bxy + Cy^2 + Dx + Ey + F = 0$.
2.  **Normalization Engine**: Translates raw (X, Y) magnetic coordinates to the ellipse center, rotates to align with principal axes, and scales to a unit circle.
3.  **Angular Mapping**: Converts normalized coordinates to a linear 0-360° magnetic heading, which is then mapped to rudder percentage.
4.  **Hybrid Fallback**: The system automatically uses high-precision ellipse data if a valid calibration is saved, otherwise falling back to legacy mode.

### How to use
To calibrate the Magnetometer:
1.  Navigate to the **Calibration** screen.
2.  In the **Magnetometer** section, tap **Start**.
3.  Physically move the rudder/motor through its **full range** of motion (Port to Starboard several times).
4.  Tap **Stop & Fit**. The app will calculate the optimal geometric ellipse.
5.  If the "Steering" percentage responds smoothly as you move the rudder, tap **Save**.
6.  **Set the Physical Zero**:
    *   Align the motor/shaft to the exact physical center (straight ahead).
    *   Tap the **Zero** button in the **Physical Mapping** section. This anchors the 0% position to the current magnetic angle.
7.  **Set Max Limits**: Move to full Port and tap **Port Max**, then full Starboard and tap **Stbd Max**.

## Hardware Setup
The app communicates with an **AC6328**, **ESP32**, or **ESP32-C3** BLE-UART bridge connected to the motor's RS485 lines and steering sensors.


### Connection Architecture
```
Android Smartphone           BLE Bridge (AC6328)          Torqeedo Motor
(The App)                    (Firmware)                   (Internal ECU)
──────────────               ─────────────────            ──────────────
buildDrive(speed) ──BLE ae10──▶ uart_write(frame) ──RS485──▶ TQ Bus Input
sendSteer(value)  ──BLE ae03──▶ uart_write(cmd)   ──GPIO ──▶ Steering Actuator
parseStatus(raw)  ◀─BLE ae02── uart_rx_callback   ◀─RS485── STATUS Reply
parseSteer(0xA8)  ◀─BLE ae02── Hall Sensors ADC   ◀─ADC  ── Linear Position
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


### Steering Position Sensor Protocol (7-byte)
The bridge sends Hall sensor data via notifications on `0xAE02` at ~20Hz:
| Byte | Field | Type | Description |
| :--- | :--- | :--- | :--- |
| 0 | Header | u8 | 0xA8 |
| 1-2 | Sensor A | u16LE | Raw ADC reading from Hall Sensor A |
| 3-4 | Sensor B | u16LE | Raw ADC reading from Hall Sensor B |
| 5-6 | VCC | u16LE | Bridge supply voltage (for compensation) |

### QMC6308 Magnetometer Data Protocol (8-byte)
The bridge sends signed 16-bit magnetometer data via notifications on `0xAE02`:
| Byte | Field | Description |
| :--- | :--- | :--- |
| 0 | Header | 0xA5 |
| 1 | Sequence | Packet sequence number |
| 2-3 | X | X-axis (Signed 16-bit LE) |
| 4-5 | Y | Y-axis (Signed 16-bit LE) |
| 6-7 | Z | Z-axis (Signed 16-bit LE) |

## Steering Calibration Workflow

To ensure accurate positioning across the full range of movement:

1.  **BIAS**: Remove the magnet from the sensor and click **"Calibrate BIAS"**. This records the stable baseline voltage for each sensor.
2.  **Center**: Manually center the rudder (0°) using the **Drive Left/Right** buttons and click **"Center 0°"**.
3.  **Target Reference**: Manually drive to Port 22.5° and click **"Port 22.5°"**. Repeat for Starboard side. This provides the "target vector" for the auto-sweep.
4.  **Auto Run**: Click **"Auto Port 22.5"**. 
    *   The app drives the motor back to the calibrated **Center** (0°).
    *   It performs a continuous timed sweep towards Port until the target vector is reached.
    *   Samples are recorded at 20Hz to reconstruct the exact 2D arc path.
    *   A high-resolution 128-point LUT is generated and saved to storage.

## User Interface & Controls

| Component | Function |
| :--- | :--- |
| **Boat Profile Switch** | Toggle between **CL16**, **MAC25**, and **TOY** profiles. |
| **Forward/Reverse Switch** | Large central toggle to flip motor direction. |
| **Speed (+) / (−)** | **Tap**: ±2% Speed. **Hold**: Smoothly adjust speed (10%/sec). |
| **Steer (L1/R1, L5/R5)** | Adjust steering angle. **L1/R1** for fine tuning, **L5/R5** for coarse. **Hold** for repeat. |
| **Backlash Time** | Configure compensation (ms) for mechanical play. |
| **Runtime Scale** | Configure steering pulse duration (ms per unit). |
| **STOP Button** | Immediately resets speed magnitude to 0%. |
| **RESET Button** | Centers the steering (returns value to 0). |
| **Telemetry Card** | Displays RPM, Course, SOG (Knots), **Amps**, **Watts**, and **Steer Position**. |
| **Manual Drive** | Hold buttons in the calibration screen to drive the linear motor left/right. |
| **Auto Port/Stbd** | Triggers the automated path-mapping routine. |
| **Ellipse Fit Buttons** | **Start/Stop/Save/Clear** buttons for MMC5603 magnetic calibration. |
| **Ratio & Magnitude** | Real-time display of vector signal quality to verify hardware placement. |
| **Autopilot Toggle** | Enable automatic heading hold. PID gains (Kp, Ki, Kd) are configurable via the UI. |

## Dependencies
*   [Nordic BLE Library](https://github.com/NordicSemiconductor/Android-BLE-Library): For robust communication.
*   [Google Play Services Location](https://developers.google.com/android/guides/setup): For high-accuracy GPS tracking.

## Keywords
Torqeedo Control, Electric Outboard, Hall Position Sensor, QMC6308, Backlash Compensation, Boat Profiles, 2D Vector Interpolation, BLE Throttle, Boat Motor App, RS485 BLE, ESP32-C3, TQ Bus Protocol, Digital Steering, Autopilot.
