#define HAS_SERIAL
#define LED_PIN LED_BUILTIN

#define TX_PIN 11
#define INT_PIN_1 2

#ifndef PUBLISH_ONLY_EVERY_X_MS
#define PUBLISH_ONLY_EVERY_X_MS 300000 // 5min: 5 * 60 * 1000
#endif

#ifdef HAS_SERIAL
  #define debug(x) Serial.println(x)
#else
  #define debug(x)  // No operation
#endif

#define WIRE_OPENED LOW
#define WIRE_CLOSED HIGH

const uint32_t deviceID = 0xF0002;

volatile int state = -1;
unsigned long previousMillis = 0;

bool lastState = HIGH;
// Hardware optimizations: 
// - Add an external pull-up resistor (1k–10kΩ) to the input pin to strengthen the signal.
// - Place a small capacitor (e.g., 0.1 µF ceramic capacitor) between the input pin and ground to filter out high-frequency noise. 
//   This works as a simple low-pass filter to smooth out the signal.


void sendBit(bool state) {
#ifdef HAS_SERIAL
  Serial.print(state ? '1' : '0');
#endif
  digitalWrite(TX_PIN, HIGH);
  delayMicroseconds(state? 1050 : 350);
  digitalWrite(TX_PIN, LOW);
  delayMicroseconds(state ? 350 : 1050);
}

void sendSignal(uint32_t id, uint8_t switchState) {
  sendBit(false);
  for (int i = (20-1); i >= 0; i--) sendBit(id & (1UL << i));
  for (int i = (4-1); i >= 0; i--) sendBit(switchState & (1 << i));
}

void setup() {
#ifdef HAS_SERIAL
  Serial.begin(115200);
#endif
#ifdef LED_PIN
  pinMode(LED_PIN, OUTPUT);
#endif

  pinMode(INT_PIN_1, INPUT_PULLUP);
  pinMode(TX_PIN, OUTPUT);

  debug("Setup complete, entering loop...");
}

void loop() {
  unsigned long currentMillis = millis();

  int newState = digitalRead(INT_PIN_1) == WIRE_CLOSED ? 0 : 1;
#ifdef LED_PIN
  digitalWrite(LED_PIN, newState == 0 ? LOW : HIGH);
#endif

  if (newState != state || (currentMillis - previousMillis >= PUBLISH_ONLY_EVERY_X_MS)) {
    state = newState;
    previousMillis = currentMillis;
    debug("State change detected");
    debug(state);
    debug("Sending EV1527 signal... ");
    sendSignal(deviceID, state == 1 ? 0xF : 0x0);
    debug("");
  }

}
