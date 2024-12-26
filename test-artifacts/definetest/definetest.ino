#ifndef TRY_ME_TO_OVERWRITE
#define TRY_ME_TO_OVERWRITE "failed to overwrite"
#endif

void setup() {
    Serial.begin(115200);
    while (!Serial);
}

void loop() {
    Serial.println(TRY_ME_TO_OVERWRITE);
    delay(1000);
}
