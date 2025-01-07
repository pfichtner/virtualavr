#ifndef TRY_ME_TO_OVERWRITE
#define TRY_ME_TO_OVERWRITE "failed to overwrite"
#endif

void setup() {
    Serial.begin(115200);
    while (!Serial);
}

void loop() {
    Serial.println(TRY_ME_TO_OVERWRITE);
    delay(SLEEP_MILLIS_NOT_DEFINED_IN_SKETCH_SO_THEY_HAVE_TO_GET_PASSED_TO_MAKE_SKETCH_COMPILEABLE_AT_ALL);
}

