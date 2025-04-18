int incomingByte = 0;

void setup() {
	Serial.begin(115200);
	while (!Serial);
}

void loop() {
	if (Serial.available() > 0) {
		incomingByte = Serial.read();
		Serial.write(incomingByte);
		if (incomingByte == 255) {
			Serial.write(0);
		}
	}
	delay(100);
 }
