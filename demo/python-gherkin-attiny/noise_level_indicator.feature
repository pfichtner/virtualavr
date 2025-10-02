Feature: Noise Level Indicator Led Behavior

  Background: Alias defined and pins monitored
    Given the following pins are assigned
      | alias       | pin | description                          |
      | REFERENCE   | A1  | Reference noise level threshold      |
      | VALUE       | A3  | Current noise level                  |
      | GREEN_LED   | 0   | Indicates acceptable noise level     |
      | YELLOW_LED  | 1   | Indicates moderate noise level       |
      | RED_LED     | 4   | Indicates excessive noise level      |
    And the pin of GREEN_LED is monitored
    And the pin of YELLOW_LED is monitored
    And the pin of RED_LED is monitored

  Scenario: Noise level is within 90% of the reference, green led is on
    When the REFERENCE is set to 1000
    And the VALUE is set to 900
    Then the GREEN_LED should be on
    And the YELLOW_LED should be off
    And the RED_LED should be off

  Scenario: Noise level is slightly above 90% of the reference, yellow led is on
    When the REFERENCE is set to 1000
    And the VALUE is set to 901
    Then the GREEN_LED should be off
    And the YELLOW_LED should be on
    And the RED_LED should be off

  Scenario: Noise level exceeds the reference, red led is on
    When the REFERENCE is set to 1022
    And the VALUE is set to 1023
    Then the GREEN_LED should be off
    And the YELLOW_LED should be off
    And the RED_LED should be on
