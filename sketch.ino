void setup() {
	Serial.begin(115200);
	pinMode(LED_BUILTIN, OUTPUT);
}

void loop() {
	if (Serial.available()) {
		Serial.print("Arduino serial line received: ");
		while (Serial.available()) {
			char in = (char) Serial.read();
			Serial.print(in);
		}
		Serial.println();
	}
	Serial.println("Loop");
	digitalWrite(LED_BUILTIN, HIGH);
	delay(500);
	digitalWrite(LED_BUILTIN, LOW);
	delay(500);
}

