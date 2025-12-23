#include <Adafruit_NeoPixel.h>

#define PIN 6
#define NUMPIXELS 1

Adafruit_NeoPixel pixels(NUMPIXELS, PIN, NEO_GRB + NEO_KHZ800);

void setup() {
  pixels.begin();
  pixels.setPixelColor(0, pixels.Color(255,0,0));
  pixels.show();
}

void loop() {
  // do nothing
}

