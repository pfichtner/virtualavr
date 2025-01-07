#define DIGITAL_OUTPUT LED_BUILTIN
#define ANALOG_OUTPUT 10

#define DIGITAL_INPUT 11
#define ANALOG_INPUT A0

boolean digitalInputState;
int analogInputState;

void setup() {
        Serial.begin(115200);
        pinMode(DIGITAL_OUTPUT, OUTPUT);
        pinMode(ANALOG_OUTPUT, OUTPUT);
        pinMode(DIGITAL_INPUT, INPUT);
        pinMode(ANALOG_INPUT, INPUT);
        Serial.println("Welcome virtualavr!");
}

void loop() {
	if (Serial.available()) {
	    Serial.print("Echo response: ");
	    while (Serial.available()) {
	            char in = (char) Serial.read();
	            Serial.print(in);
	    }
	    Serial.println();
	}
	Serial.println("Loop");
	
	readDigital();        
	readAnalog();        
	
	analogWrite(ANALOG_OUTPUT, 0);
	digitalWrite(DIGITAL_OUTPUT, HIGH);
	delay(100);
	
	analogWrite(ANALOG_OUTPUT, 42);
	digitalWrite(DIGITAL_OUTPUT, LOW);
	delay(100);
}

void readDigital() {
	boolean tmp = digitalRead(DIGITAL_INPUT);
	if (tmp != digitalInputState) {
		digitalInputState = tmp;
		Serial.print("State-Change-D11: ");        	
		Serial.println(tmp ? "ON" : "OFF");        	
	}
}

void readAnalog() {
	int tmp = analogRead(ANALOG_INPUT);
	if (tmp != analogInputState) {
		analogInputState = tmp;
		Serial.print("State-Change-A0: ");        	
		Serial.println(tmp);        	
	}
}
