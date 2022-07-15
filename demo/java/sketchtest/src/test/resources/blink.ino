#define LED LED_BUILTIN

void setup() {
        Serial.begin(115200);
        pinMode(LED, OUTPUT);
}

void loop() {
        digitalWrite(LED, HIGH);
        delay(100);
        digitalWrite(LED, LOW);
        delay(100);
}
