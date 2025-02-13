Feature: Alert if circuit is opened

  Background:
    Given the following pins are assigned:
      | alias       | pin  | description                                         |
      | TX_PIN      | D11  | transmitter connected to that pin                   |
      | INPUT_PIN   | D2   | the pin where the loop from GND ends for monitoring |
      | LED_PIN     | D13  | if the loop is opened this led is lit               |
    And the pin of TX_PIN is monitored
    And the pin of LED_PIN is monitored

  Scenario: If the loop is closed the LED_PIN is not lit and TX_PIN was toggled
    When the INPUT_PIN is set to high
    Then the LED_PIN should be off
    And the TX_PIN was toggled 50 times

  Scenario: If the loop is opened the LED_PIN is lit and TX_PIN was toggled
    Given the INPUT_PIN is set to high
    And the LED_PIN should be off
    And the TX_PIN was toggled 50 times
    And the message queue is cleared
    When the INPUT_PIN is set to low
    Then the LED_PIN should be on
    And the TX_PIN was toggled 50 times

  Scenario: If the state keeps unchanged the message will be sent after PUBLISH_ONLY_EVERY_X_MS again
    Given the INPUT_PIN is set to high
    And the LED_PIN should be off
    And the TX_PIN was toggled 50 times
    When the message queue is cleared
    Then the TX_PIN was toggled 50 times
