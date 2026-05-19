/*
ESP32 QMC6308 Magnetometer + Linear Motor Controller (NimBLE, 8-Byte Packets)
-----------------------------------------------------------------------------
Features:
- QMC6308 16-bit magnetometer via I2C (SDA=18, SCL=19)
- BLE service 0xAE30, 8-byte notify packets on AE02
- AE03 = motor command write, AE10 = RS485 passthrough
- GPIO5 set to HIGH drive state
*/
#include <NimBLEDevice.h>
#include <Wire.h>

HardwareSerial DebugUart(2);

// =====================================================
// GPIO & I2C
// =====================================================
#define RS485_TX       21
#define RS485_RX       17        // Changed from 19 to avoid SCL conflict
#define PIN_I2C_SDA    18
#define PIN_I2C_SCL    19
#define PIN_HIGH_DRIVE 5
#define PIN_MOTOR_L    22
#define PIN_MOTOR_R    23

// =====================================================
// QMC6308 Registers
// =====================================================
#define QMC6308_ADDR   0x2C
#define REG_CTRL       0x0A
#define DATA_X_LSB     0x01

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

void motorStop();
void motorDrive(char dir);

// =====================================================
// BLE Server Callbacks
// =====================================================
class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
        bleConnected = true;
        //pServer->updateConnParams(connInfo.getConnHandle(), 6, 12, 0, 400);
    }
    void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override {
        bleConnected = false;
        pServer->startAdvertising();
    }
};

// =====================================================
// AE03 Write Callback
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
// AE10 Status Callback
// =====================================================
class AE10Callbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic *pCharacteristic, NimBLEConnInfo& connInfo) override {
        const std::string& value = pCharacteristic->getValue();
        if (value.length() == 0) return;
        DebugUart.write((const uint8_t*)value.data(), value.length());
    }
};

// =====================================================
// Motor Control
// =====================================================
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
// QMC6308 Init & Read
// =====================================================
void initQMC6308() {
    // Configure: Continuous mode, 80Hz ODR
    Wire.beginTransmission(QMC6308_ADDR);
    Wire.write(REG_CTRL);
    //Wire.write(0xC1);  // Bit 7: Continuous, Bit 6-5: 80Hz, Bit 0: Normal
    Wire.write(0x81);  // ~20Hz ODR
    int err = Wire.endTransmission();
    Serial.printf("[QMC6308] Init Reg 0x0A: %s (err=%d)\n", err == 0 ? "OK" : "FAIL", err);
    delay(20);
}

void sendSensorPacket() {
    // Read 6 bytes starting from 0x01 (X LSB)
    Wire.beginTransmission(QMC6308_ADDR);
    Wire.write(DATA_X_LSB);
    int err = Wire.endTransmission(false);
    if (err != 0) {
        Serial.printf("[I2C FAIL] Tx: err=%d\n", err);
        return;
    }
    
    int available = Wire.requestFrom(QMC6308_ADDR, 6);
    if (available != 6) {
        Serial.printf("[I2C FAIL] Rx: got %d/6\n", available);
        return;
    }

    // Parse little-endian 16-bit signed values
    int16_t raw_x = Wire.read() | (Wire.read() << 8);
    int16_t raw_y = Wire.read() | (Wire.read() << 8);
    int16_t raw_z = Wire.read() | (Wire.read() << 8);

    // DEBUG: Print raw values
    //Serial.printf("[QMC6308] X=%d Y=%d Z=%d\n", raw_x, raw_y, raw_z);

    // Pack 8-byte BLE packet:
    // [0] Header, [1] Seq, [2-3] X, [4-5] Y, [6-7] Z
    static uint8_t packetSeq = 0;
    uint8_t pkt[8];
    pkt[0] = 0xA5;                    // Header
    pkt[1] = packetSeq++;             // Sequence
    pkt[2] = raw_x & 0xFF;            // X LSB
    pkt[3] = (raw_x >> 8) & 0xFF;     // X MSB
    pkt[4] = raw_y & 0xFF;            // Y LSB
    pkt[5] = (raw_y >> 8) & 0xFF;     // Y MSB
    pkt[6] = raw_z & 0xFF;            // Z LSB
    pkt[7] = (raw_z >> 8) & 0xFF;     // Z MSB

    if(charAE02) {
        charAE02->setValue(pkt, sizeof(pkt));
        charAE02->notify();
    }
}

// =====================================================
// Setup
// =====================================================
void setup() {
    DebugUart.begin(19200, SERIAL_8N1, RS485_RX, RS485_TX);
    Serial.begin(115200);
    delay(1000);
    Serial.println("=== ESP32 QMC6308 Boot ===");

    // I2C Initialization
    Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL, 400000);
    initQMC6308();

    // GPIO5 High Drive
    pinMode(PIN_HIGH_DRIVE, OUTPUT);
    digitalWrite(PIN_HIGH_DRIVE, HIGH);

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
    if(motorStopTime > 0 && millis() > motorStopTime) {
        motorStop();
        motorStopTime = 0;
    }

    static uint32_t lastSensorTime = 0;
    // ~20Hz streaming (50ms interval)
    if(bleConnected && (millis() - lastSensorTime >= 50)) {
        lastSensorTime = millis();
        sendSensorPacket();
    }
}