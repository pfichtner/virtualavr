// left poti is reference, right poti is mic (actual value)

#define RED_LED 12
#define YELLOW_LED 11
#define GREEN_LED 10

void setup() {
  pinMode(A0, INPUT);
  pinMode(A1, INPUT);
  pinMode(RED_LED, OUTPUT);
  pinMode(YELLOW_LED, OUTPUT);
  pinMode(GREEN_LED, OUTPUT);
}

void loop() {
  int ref = analogRead(A0);
  int nintyPercentOfRef = ref * 0.9;
  int value = analogRead(A1);

  boolean redIsOn = value > ref;
  boolean yellowIsOn = !redIsOn && value > nintyPercentOfRef;
  boolean greenIsOn = !redIsOn && !yellowIsOn;

  digitalWrite(RED_LED, redIsOn ? HIGH : LOW);
  digitalWrite(YELLOW_LED, yellowIsOn ? HIGH : LOW);
  digitalWrite(GREEN_LED, greenIsOn ? HIGH : LOW);
}
