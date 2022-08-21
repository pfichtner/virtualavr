void setup() {
	Serial.begin(115200);
}

byte val = 0;
void loop() {
 	Serial.write(val++);
	if (val == 255) {
		Serial.flush();
	}
}
