/*
ESP32 MMC5603 Magnetometer + Linear Motor Controller (NimBLE Stream Optimized)
--------------------------------------------------------------------------
Features:
- High-frequency 100ms BLE streaming
- MMC5603 20-bit magnetometer via I2C (SDA=18, SCL=19)
- BLE service 0xAE30
- AE02 = notify 11-byte magnetometer packets
- AE03 = motor command write
- AE10 = status read/write (Optimized for No-Response streaming)
- GPIO5 set to HIGH drive state
*/
#include <NimBLEDevice.h> // Using NimBLE for fast, non-blocking GATT streaming
#include <Wire.h>

HardwareSerial DebugUart(2);    // Send to UART-RS485

// =====================================================
// GPIO & I2C
// =====================================================
#define RS485_TX       21
//#define RS485_RX       19
#define RS485_RX       17

#define PIN_I2C_SDA    18
#define PIN_I2C_SCL    19
#define PIN_HIGH_DRIVE 5

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

// Forward Declarations
void motorStop();
void motorDrive(char dir);

// =====================================================
// BLE Server Callbacks (NimBLE 2.x Compatible)
// =====================================================
class ServerCallbacks : public NimBLEServerCallbacks
{
    void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override
    {
        bleConnected = true;
        // Force fast BLE connection interval: 7.5ms min, 15ms max, 0 latency, 400ms timeout
        pServer->updateConnParams(connInfo.getConnHandle(), 6, 12, 0, 400);
    }

    void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override
    {
        bleConnected = false;
        pServer->startAdvertising();
    }
};

// =====================================================
// AE03 Write Callback
// =====================================================
class AE03Callbacks : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pCharacteristic, NimBLEConnInfo& connInfo) override
    {
        const std::string& value = pCharacteristic->getValue();
        if(value.length() != 5) return;

        const uint8_t *d = (const uint8_t*)value.data();
        if(d[0] != 's') return;

        char dir = d[1];
        uint16_t runtime = d[3] | (d[4] << 8);

        // Safety stop before applying new command
        motorStop(); 

        if (runtime == 0) {
            Serial.println("[BLE COMMAND] Intentional Motor Stop via AE03");
            return;
        }

        // Apply new overriding command parameters instantly
        delayMicroseconds(10); // Tiny safety dead-time gap for H-bridge MOSFET stability
        motorDrive(dir);
        motorStopTime = millis() + runtime;
    }
};

// =====================================================
// AE10 Status Callback
// =====================================================
class AE10Callbacks : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pCharacteristic, NimBLEConnInfo& connInfo) override
    {
        const std::string& value = pCharacteristic->getValue();
        size_t len = value.length();
        if (len == 0) return;
        // Direct binary forward to RS485
        DebugUart.write((const uint8_t*)value.data(), len);
    }
};

// =====================================================
// Motor Control Execution
// =====================================================
void motorDrive(char dir)
{
    if(dir == 'L') {
        digitalWrite(PIN_MOTOR_L, HIGH);
        digitalWrite(PIN_MOTOR_R, LOW);
    }
    else if(dir == 'R') {
        digitalWrite(PIN_MOTOR_L, LOW);
        digitalWrite(PIN_MOTOR_R, HIGH);
    }
    else {
        motorStop();
    }
}

void motorStop()
{
    motorStopTime = 0;
    digitalWrite(PIN_MOTOR_L, LOW);
    digitalWrite(PIN_MOTOR_R, LOW);
}

// =====================================================
// MMC5603 I2C Read & Packet Packing
// =====================================================
void initMMC5603()
{
    // I2C Address: 0x30 (7-bit)
    // CTRL0 (0x1C): 
    //   Bit 5 = 1 (Enable Auto Set/Reset)
    //   Bit 4 = 0 (Disable CMM)
    //   Bits 1:0 = 01 (Target ~20Hz measurement rate)
    //   Binary: 0b00100001 -> 0x21
    Wire.beginTransmission(0x30);
    Wire.write(0x1C);
    Wire.write(0x21);
    int err = Wire.endTransmission();
    Serial.printf("[MMC5603] Init Reg 0x1C: %s (err=%d)\n", err == 0 ? "OK" : "FAIL", err);
    
    // CTRL1 (0x1D): Default configuration
    Wire.beginTransmission(0x30);
    Wire.write(0x1D);
    Wire.write(0x00);
    err = Wire.endTransmission();
    Serial.printf("[MMC5603] Init Reg 0x1D: %s (err=%d)\n", err == 0 ? "OK" : "FAIL", err);
    
    delay(50); // Allow sensor to stabilize after configuration
}

void sendSensorPacket()
{
    uint8_t data[9]; // Holds raw register values
    
    // 1. Read X, Y, Z High & Mid bytes (Registers 0x00 - 0x05)
    Wire.beginTransmission(0x30);
    Wire.write(0x00);
    int err = Wire.endTransmission(false); // Repeated start
    if (err != 0) {
        Serial.printf("[I2C FAIL] Tx 0x00: err=%d\n", err);
        return;
    }
    
    int available = Wire.requestFrom(0x30, 6);
    if (available < 6) {
        Serial.printf("[I2C FAIL] Rx 6 bytes: got %d\n", available);
        return;
    }
    for(int i = 0; i < 6; i++) data[i] = Wire.read();

    // 2. Read X, Y, Z Low nibbles (Registers 0x08, 0x09, 0x0A)
    Wire.beginTransmission(0x30);
    Wire.write(0x08);
    err = Wire.endTransmission(false);
    if (err != 0) {
        Serial.printf("[I2C FAIL] Tx 0x08: err=%d\n", err);
        return;
    }
    
    available = Wire.requestFrom(0x30, 3);
    if (available < 3) {
        Serial.printf("[I2C FAIL] Rx 3 bytes: got %d\n", available);
        return;
    }
    data[6] = Wire.read() & 0x0F; // X[3:0]
    data[7] = Wire.read() & 0x0F; // Y[3:0]
    data[8] = Wire.read() & 0x0F; // Z[3:0]

    // DEBUG: Print raw sensor registers
    Serial.printf("[MMC5603] Raw: %02X %02X %02X %02X %02X %02X | %02X %02X %02X\n",
                  data[0], data[1], data[2], data[3], data[4], data[5],
                  data[6], data[7], data[8]);

    // 3. Pack into BLE payload (11 bytes)
    static uint8_t packetSeq = 0;
    uint8_t pkt[11];
    
    pkt[0]  = 0xA5;              // Header
    pkt[1]  = packetSeq++;       // Sequence Number
    pkt[2]  = data[0];           // X High [19:12]
    pkt[3]  = data[1];           // X Mid  [11:4]
    pkt[4]  = data[2];           // Y High [19:12]
    pkt[5]  = data[3];           // Y Mid  [11:4]
    pkt[6]  = data[4];           // Z High [19:12]
    pkt[7]  = data[5];           // Z Mid  [11:4]
    pkt[8]  = data[6];           // X Low  [3:0]
    pkt[9]  = data[7];           // Y Low  [3:0]
    pkt[10] = data[8];           // Z Low  [3:0]

    if(charAE02) {
        charAE02->setValue(pkt, sizeof(pkt));
        charAE02->notify();
    } else {
        Serial.println("[ERROR] charAE02 is NULL!");
    }
}

// =====================================================
// Setup
// =====================================================
void setup()
{
    DebugUart.begin(19200, SERIAL_8N1, RS485_RX, RS485_TX);
    Serial.begin(115200);

    // I2C Initialization
    Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL, 100000);
    initMMC5603();

    // GPIO5 High Drive Output
    pinMode(PIN_HIGH_DRIVE, OUTPUT);
    digitalWrite(PIN_HIGH_DRIVE, HIGH);

    // Motor Pins Setup
    pinMode(PIN_MOTOR_L, OUTPUT);
    pinMode(PIN_MOTOR_R, OUTPUT);
    motorStop();

    // Init NimBLE Device Profile
    NimBLEDevice::init("Steer_UART"); 

    NimBLEServer *server = NimBLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());

    NimBLEService *service = server->createService(SERVICE_UUID);

    // AE02 Notify
    charAE02 = service->createCharacteristic(
        CHAR_AE02_UUID,
        NIMBLE_PROPERTY::NOTIFY
    );

    // AE03 Write Only Control Loop
    NimBLECharacteristic *charAE03 = service->createCharacteristic(
        CHAR_AE03_UUID,
        NIMBLE_PROPERTY::WRITE_NR
    );
    charAE03->setCallbacks(new AE03Callbacks());

    // AE10 Deep Stream Control
    charAE10 = service->createCharacteristic(
        CHAR_AE10_UUID,
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE_NR
    );
    charAE10->setCallbacks(new AE10Callbacks());

    service->start();

    NimBLEAdvertising *advertising = NimBLEDevice::getAdvertising();
    advertising->setName("Steer_UART"); 
    advertising->enableScanResponse(true); 
    advertising->addServiceUUID(SERVICE_UUID);
    advertising->start();

    Serial.println("NimBLE High-Speed Stack Active! MMC5603 Initialized.");
}

// =====================================================
// Main Loop
// =====================================================
void loop()
{
    // Motor timeout protector
    if(motorStopTime > 0 && millis() > motorStopTime) {
        motorStop();
        motorStopTime = 0;
    }

    // Non-blocking sensor streaming (10Hz, 100ms)
    static uint32_t lastSensorTime = 0;
    if(bleConnected && (millis() - lastSensorTime >= 100)) {  
        lastSensorTime = millis();
        sendSensorPacket();
    }
}