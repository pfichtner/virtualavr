Feature: LED Behavior based on Reference and Value Pins

  Background:
    Given the following aliases are defined:
      | alias       | pin  |
      | REFERENCE   | A0   |
      | VALUE       | A1   |
      | GREEN_LED   | D10  |
      | YELLOW_LED  | D11  |
      | RED_LED     | D12  |
    And the pin GREEN_LED is watched
    And the pin YELLOW_LED is watched
    And the pin RED_LED is watched

  Scenario: Value equals 90% of Reference, green led is on
    When the pin REFERENCE is set to 1000
    When the pin VALUE is set to 900
    Then the pin GREEN_LED should be on
    And the pin YELLOW_LED should be off
    And the pin RED_LED should be off

  Scenario: Value greater than Reference, red led is on
    When the pin REFERENCE is set to 1022
    When the pin VALUE is set to 1023
    Then the pin GREEN_LED should be off
    And the pin YELLOW_LED should be off
    And the pin RED_LED should be on

  Scenario: Value is greater within 90% of Reference, yellow led is on
    When the pin REFERENCE is set to 1000
    When the pin VALUE is set to 901
    Then the pin GREEN_LED should be off
    And the pin YELLOW_LED should be on
    And the pin RED_LED should be off
