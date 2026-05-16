/*
    ESP32 Rudder Feedback + Linear Motor Controller (NimBLE Stream Optimized)
    --------------------------------------------------------------------------
    Features:
    - High-frequency 200ms-500ms streaming safe
    - Read 2x ES491UA analog Hall sensors
    - BLE service 0xAE30
    - AE02 = notify raw sensor packets
    - AE03 = motor command write
    - AE10 = status read/write (Optimized for No-Response streaming)
*/

#include <NimBLEDevice.h> // Using NimBLE for fast, non-blocking GATT streaming

HardwareSerial DebugUart(2);    //Send to UART-RS485

typedef struct
{
    float filtered;
} adc_filter_t;

adc_filter_t filtA;
adc_filter_t filtB;
adc_filter_t filtVcc;

// =====================================================
// GPIO
// =====================================================
#define RS485_TX    21
#define RS485_RX    19 

#define PIN_SENSOR_A   34
#define PIN_SENSOR_B   35
#define PIN_VCC_SENSE  36

#define PIN_MOTOR_L    22
#define PIN_MOTOR_R    23

// =====================================================
// PWM
// =====================================================
//#define PWM_FREQ       2000
//#define PWM_RESOLUTION 8

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
//void motorDrive(char dir, uint8_t pwm);
void motorDrive(char dir);

// =====================================================
// BLE Server Callbacks (Corrected NimBLE 2.x Signature)
// =====================================================
class ServerCallbacks : public NimBLEServerCallbacks
{
    // FIX: Removed 'const', using 'NimBLEConnInfo&' reference directly
    void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override
    {
        bleConnected = true;
        // Force fast BLE connection interval: 7.5ms min, 15ms max, 0 latency, 400ms timeout
        pServer->updateConnParams(connInfo.getConnHandle(), 6, 12, 0, 400);
    }

    // FIX: Removed 'const', using 'NimBLEConnInfo&' reference directly
    void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override
    {
        bleConnected = false;
        pServer->startAdvertising();
    }
};

// =====================================================
// AE03 Write Callback (Corrected NimBLE 2.x Signature)
// =====================================================
class AE03Callbacks : public NimBLECharacteristicCallbacks
{
    // FIX: Removed 'const', using 'NimBLEConnInfo&' reference directly
    void onWrite(NimBLECharacteristic *pCharacteristic, NimBLEConnInfo& connInfo) override
    {
        //std::string value = pCharacteristic->getValue();
        // Use reference to avoid heap allocation on every command
        const std::string& value = pCharacteristic->getValue();

        //Serial.printf("[NIMBLE STREAM] AE03 len=%d\n", value.length());

        if(value.length() != 5)
            return;

        const uint8_t *d = (const uint8_t*)value.data();
        if(d[0] != 's') return;

        char dir = d[1];
        //uint8_t pwm = d[2];

        // Combine the two 8-bit bytes into a 16-bit runtime integer
        uint16_t runtime = d[3] | (d[4] << 8);

        // OPTION A OVERRIDE & STOP CHECK: 
        // If runtime is 0, it means an intentional Stop command was issued.
        // If runtime is greater than 0, we still stop the previous cycle 
        // to reset the H-Bridge lines safely before running the new command.
        motorStop(); 

        if (runtime == 0)
        {
            Serial.println("[BLE COMMAND] Intentional Motor Stop via AE03");
            return; // Exit early since motor is now safely stopped
        }

        // Apply new overriding command parameters instantly
        delayMicroseconds(10); // Tiny safety dead-time gap for H-bridge MOSFET stability
        //motorDrive(dir, pwm);
        motorDrive(dir);
        motorStopTime = millis() + runtime;
    }
};

// =====================================================
// AE10 Status Callback (Corrected NimBLE 2.x Signature)
// =====================================================
class AE10Callbacks : public NimBLECharacteristicCallbacks
{
    void onWrite(NimBLECharacteristic *pCharacteristic, NimBLEConnInfo& connInfo) override
    {
        // Zero-copy: reference avoids heap allocation on every packet
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
/*
void motorDrive(char dir, uint8_t pwm)
{
    pwm = constrain(pwm, 0, 255);

    if(dir == 'L')
    {
        ledcWrite(PIN_MOTOR_L, pwm);
        ledcWrite(PIN_MOTOR_R, 0);
    }
    else if(dir == 'R')
    {
        ledcWrite(PIN_MOTOR_L, 0);
        ledcWrite(PIN_MOTOR_R, pwm);
    }
    else
    {
        motorStop();
    }
}

void motorStop()
{
    motorStopTime = 0;
    ledcWrite(PIN_MOTOR_L, 0);
    ledcWrite(PIN_MOTOR_R, 0);
}
*/

void motorDrive(char dir)
{
    if(dir == 'L')
    {
        digitalWrite(PIN_MOTOR_L, HIGH);
        digitalWrite(PIN_MOTOR_R, LOW);
    }
    else if(dir == 'R')
    {
        digitalWrite(PIN_MOTOR_L, LOW);
        digitalWrite(PIN_MOTOR_R, HIGH);
    }
    else
    {
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
// ADC Filter Logic
// =====================================================
uint16_t readADCFiltered(uint8_t pin, adc_filter_t *f)
{
    const int N = 32;
    uint16_t minv = 65535;
    uint16_t maxv = 0;
    uint32_t sum = 0;

    for(int i = 0; i < N; i++)
    {
        uint16_t v = analogRead(pin);
        sum += v;
        if(v < minv) minv = v;
        if(v > maxv) maxv = v;
    }

    sum -= minv;
    sum -= maxv;
    float avg = sum / (float)(N - 2);

    const float alpha = 0.15f;
    f->filtered = f->filtered * (1.0f - alpha) + avg * alpha;

    return (uint16_t)f->filtered;
}

// =====================================================
// Send Sensor Packet
// =====================================================
void sendSensorPacket()
{
    uint16_t a = readADCFiltered(PIN_SENSOR_A, &filtA);
    uint16_t b = readADCFiltered(PIN_SENSOR_B, &filtB);
    uint16_t vcc = readADCFiltered(PIN_VCC_SENSE, &filtVcc);

    uint8_t pkt[7];
    pkt[0] = 0xA8;
    pkt[1] = a & 0xFF;
    pkt[2] = (a >> 8) & 0xFF;
    pkt[3] = b & 0xFF;
    pkt[4] = (b >> 8) & 0xFF;
    pkt[5] = vcc & 0xFF;
    pkt[6] = (vcc >> 8) & 0xFF;

    charAE02->setValue(pkt, sizeof(pkt));
    charAE02->notify();
}

// =====================================================
// Setup
// =====================================================
void setup()
{
    DebugUart.begin(19200, SERIAL_8N1, RS485_RX, RS485_TX);
    Serial.begin(115200);

    // ADC Setup
    analogReadResolution(12);
    analogSetWidth(12);
    analogSetPinAttenuation(PIN_SENSOR_A, ADC_6db);
    analogSetPinAttenuation(PIN_SENSOR_B, ADC_6db);
    analogSetPinAttenuation(PIN_VCC_SENSE, ADC_11db);

    // PWM Setup
    //ledcAttach(PIN_MOTOR_L, PWM_FREQ, PWM_RESOLUTION);
    //ledcAttach(PIN_MOTOR_R, PWM_FREQ, PWM_RESOLUTION);
    pinMode(PIN_MOTOR_L, OUTPUT);
    pinMode(PIN_MOTOR_R, OUTPUT);
    motorStop();

    // Init NimBLE Device Profile
    NimBLEDevice::init("Steer_UART"); // Forced name change to clear Android local GATT cache


    NimBLEServer *server = NimBLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());

    NimBLEService *service = server->createService(SERVICE_UUID);

    // AE02 Notify (NimBLE auto-handles 2002 descriptor allocation internally)
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

    // AE10 Deep Stream Control (Enforces clean NO_RESPONSE maps)
    charAE10 = service->createCharacteristic(
        CHAR_AE10_UUID,
        NIMBLE_PROPERTY::READ |
        NIMBLE_PROPERTY::WRITE_NR
    );
    charAE10->setCallbacks(new AE10Callbacks());

    service->start();

    NimBLEAdvertising *advertising = NimBLEDevice::getAdvertising();
    
    advertising->setName("Steer_UART"); 
    advertising->enableScanResponse(true); 
    advertising->addServiceUUID(SERVICE_UUID);
    
    // Start broadcasting over the air
    advertising->start();

    Serial.println("NimBLE High-Speed Stack Active!");
}

// =====================================================
// Main Loop
// =====================================================
void loop()
{
    // Motor timeout protector
    if(motorStopTime > 0 && millis() > motorStopTime)
    {
        motorStop();
        motorStopTime = 0;
    }

    // Non-blocking sensor streaming (5Hz , 200ms)
    static uint32_t lastSensorTime = 0;
    if(bleConnected && (millis() - lastSensorTime >= 200))  
    {
        lastSensorTime = millis();
        sendSensorPacket();
    }
}
