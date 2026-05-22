/*
ESP32 VL53L0X Distance Sensor + Linear Motor Controller (NimBLE)
----------------------------------------------------------------
- Reads VL53L0X via I2C (SDA=18, SCL=19) at 5 Hz
- BLE service 0xAE30, 4‑byte notify packets on AE02:
    [0xA9, seq, distance_low, distance_high]
- AE03 = motor command write, AE10 = RS485 passthrough
- GPIO21 set to HIGH drive state to power VL53L0X
*/
#include <NimBLEDevice.h>
#include <Wire.h>
#include <VL53L0X.h>              // Pololu VL53L0X library

HardwareSerial DebugUart(2);

// =====================================================
// GPIO & I2C
// =====================================================
#define RS485_TX       16
#define RS485_RX       17
#define PIN_I2C_SDA    18
#define PIN_I2C_SCL    19
#define PIN_HIGH_DRIVE 21
#define PIN_MOTOR_L    22
#define PIN_MOTOR_R    23

// =====================================================
// BLE UUIDs
// =====================================================
#define SERVICE_UUID   "0000AE30-0000-1000-8000-00805F9B34FB"
#define CHAR_AE02_UUID "0000AE02-0000-1000-8000-00805F9B34FB"
#define CHAR_AE03_UUID "0000AE03-0000-1000-8000-00805F9B34FB"
#define CHAR_AE10_UUID "0000AE10-0000-1000-8000-00805F9B34FB"

// =====================================================
// BLE Globals
// =====================================================
NimBLECharacteristic *charAE02;
NimBLECharacteristic *charAE10;
volatile bool bleConnected = false;
volatile uint32_t motorStopTime = 0;

// =====================================================
// VL53L0X Object
// =====================================================
VL53L0X tof;

// =====================================================
// Timing for 5 Hz output
// =====================================================
uint32_t lastSendTime = 0;
const uint32_t SEND_INTERVAL_MS = 200;   // 5 Hz

// =====================================================
// Motor Control
// =====================================================
void motorStop();
void motorDrive(char dir);

void motorDrive(char dir) {
    if(dir == 'L') {
        digitalWrite(PIN_MOTOR_L, HIGH);
        digitalWrite(PIN_MOTOR_R, LOW);
    } else if(dir == 'R') {
        digitalWrite(PIN_MOTOR_L, LOW);
        digitalWrite(PIN_MOTOR_R, HIGH);
    } else {
        motorStop();
    }
}

void motorStop() {
    motorStopTime = 0;
    digitalWrite(PIN_MOTOR_L, LOW);
    digitalWrite(PIN_MOTOR_R, LOW);
}

// =====================================================
// BLE Server Callbacks
// =====================================================
class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
        bleConnected = true;
    }
    void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override {
        bleConnected = false;
        pServer->startAdvertising();
    }
};

// =====================================================
// AE03 Write Callback (Motor Command)
// =====================================================
class AE03Callbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *pCharacteristic, NimBLEConnInfo& connInfo) override {
        const std::string& value = pCharacteristic->getValue();
        if(value.length() != 5){
            Serial.println("AE03 too short");
            return;
        } 
        const uint8_t *d = (const uint8_t*)value.data();
        if(d[0] != 's') return;
        char dir = d[1];
        uint16_t runtime = d[3] | (d[4] << 8);
        motorStop();
        if (runtime == 0) {
            Serial.println("[BLE CMD] Intentional Stop via AE03");
            return;
        }
        delayMicroseconds(10);
        motorDrive(dir);
        motorStopTime = millis() + runtime;
    }
};

// =====================================================
// AE10 Status Callback (RS485 Passthrough)
// =====================================================
class AE10Callbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *pCharacteristic, NimBLEConnInfo& connInfo) override {
        const std::string& value = pCharacteristic->getValue();
        if (value.length() == 0) return;
        DebugUart.write((const uint8_t*)value.data(), value.length());
    }
};

// =====================================================
// Setup
// =====================================================
const uint32_t BUDGET_US = SEND_INTERVAL_MS * 1000;
void setup() {
    DebugUart.begin(19200, SERIAL_8N1, RS485_RX, RS485_TX);
    Serial.begin(115200);
    delay(1000);
    Serial.println("=== ESP32 VL53L0X + Motor Controller ===");

    // I2C Initialization
    Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL, 100000);  // 100 kHz for VL53L0X

    // VL53L0X Initialization
    tof.setAddress(0x29);   // default address, can be changed if needed
    tof.init();
    tof.setTimeout(500);

    // Configure for High Accuracy preset
    // 1. Reset pulse periods to standard short-range defaults (Maximum close-up resolution)
    tof.setVcselPulsePeriod(VL53L0X::VcselPeriodPreRange, 14);
    tof.setVcselPulsePeriod(VL53L0X::VcselPeriodFinalRange, 10);

    // 2. Tighten the signal constraints to ignore erratic noise/reflections
    tof.setSignalRateLimit(0.25);       // Default is 0.25. Do not lower it.
    tof.writeReg16Bit(0x44, 10 << 4);   //tof.setSigmaLimit(10);              // Tighten standard deviation limit (default is 18)

    tof.setMeasurementTimingBudget(BUDGET_US);  // 200 ms timing budget
    tof.startContinuous(SEND_INTERVAL_MS);   // 200 ms = 5 Hz continuous reading

    Serial.println("[VL53L0X] Sensor ready, continuous mode at 5 Hz");

    // GPIO21 High Drive
    pinMode(PIN_HIGH_DRIVE, OUTPUT);
    digitalWrite(PIN_HIGH_DRIVE, HIGH);
    delay(1000);

    // Motor Pins
    pinMode(PIN_MOTOR_L, OUTPUT);
    pinMode(PIN_MOTOR_R, OUTPUT);
    motorStop();

    // NimBLE Setup
    NimBLEDevice::init("Steer_UART");
    NimBLEServer *server = NimBLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());

    NimBLEService *service = server->createService(SERVICE_UUID);

    charAE02 = service->createCharacteristic(CHAR_AE02_UUID, NIMBLE_PROPERTY::NOTIFY);

    NimBLECharacteristic *charAE03 = service->createCharacteristic(
        CHAR_AE03_UUID, NIMBLE_PROPERTY::WRITE_NR);
    charAE03->setCallbacks(new AE03Callbacks());

    charAE10 = service->createCharacteristic(
        CHAR_AE10_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE_NR);
    charAE10->setCallbacks(new AE10Callbacks());

    service->start();

    NimBLEAdvertising *advertising = NimBLEDevice::getAdvertising();
    advertising->setName("Steer_UART");
    advertising->enableScanResponse(true);
    advertising->addServiceUUID(SERVICE_UUID);
    advertising->start();

    Serial.println("[BLE] Advertising Started");
}

// =====================================================
// Main Loop
// =====================================================
void loop() {
    // Stop motor if runtime expired
    if (motorStopTime > 0 && millis() > motorStopTime) {
        motorStop();
        motorStopTime = 0;
    }

    // Send distance data at 5 Hz (200 ms interval)
    uint32_t now = millis();
    if (now - lastSendTime >= SEND_INTERVAL_MS) {
        lastSendTime = now;

        // Read distance from VL53L0X (blocking but very short, ~5 ms)
        uint16_t distance = tof.readRangeContinuousMillimeters();
        if (tof.timeoutOccurred()) {
            Serial.println("[VL53L0X] Timeout");
            distance = 0xFFFF;   // indicate error
        }

        // Build 4‑byte packet: header(0xA9), seq, distance LSB, distance MSB
        static uint8_t packetSeq = 0;
        uint8_t pkt[4];
        pkt[0] = 0xA9;
        pkt[1] = packetSeq++;
        pkt[2] = distance & 0xFF;
        pkt[3] = (distance >> 8) & 0xFF;

        if (bleConnected && charAE02) {
            charAE02->setValue(pkt, sizeof(pkt));
            charAE02->notify();
        }
    }
}
