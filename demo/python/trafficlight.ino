#define REF_PIN A0
#define VALUE_PIN A1

#define RED_LED 12
#define YELLOW_LED 11
#define GREEN_LED 10

void setup() {
  pinMode(REF_PIN, INPUT);
  pinMode(VALUE_PIN, INPUT);
  pinMode(RED_LED, OUTPUT);
  pinMode(YELLOW_LED, OUTPUT);
  pinMode(GREEN_LED, OUTPUT);
}

void loop() {
  int ref = analogRead(REF_PIN);
  int nintyPercentOfRef = ref * 0.9;
  int value = analogRead(VALUE_PIN);

  boolean redIsOn = value > ref;
  boolean yellowIsOn = !redIsOn && value > nintyPercentOfRef;
  boolean greenIsOn = !redIsOn && !yellowIsOn;

  digitalWrite(RED_LED, redIsOn ? HIGH : LOW);
  digitalWrite(YELLOW_LED, yellowIsOn ? HIGH : LOW);
  digitalWrite(GREEN_LED, greenIsOn ? HIGH : LOW);
}
