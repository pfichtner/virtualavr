#include <EEPROM.h>

struct Config {
	uint32_t magic;
	int16_t value;
};

const uint32_t MAGIC = 0xC0FFEE42UL;

void setup() {
	Serial.begin(115200);

	Config config;
	EEPROM.get(0, config);
	if (config.magic == MAGIC) {
		Serial.print("state=restored value=");
		Serial.println(config.value);
	} else {
		Serial.print("state=fresh virgin=");
		Serial.println(EEPROM.read(100));
		Config fresh = { MAGIC, 4711 };
		EEPROM.put(0, fresh);
		Serial.println("put=ok");
	}
	Serial.println("done");
}

void loop() {
}
