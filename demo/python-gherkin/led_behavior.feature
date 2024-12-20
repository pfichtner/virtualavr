Feature: LED Behavior based on Reference and Value Pins

  Background:
    Given the following aliases are defined:
      | alias       | pin  |
      | REFERENCE   | A0   |
      | VALUE       | A1   |
      | GREEN_LED   | D10  |
      | YELLOW_LED  | D11  |
      | RED_LED     | D12  |

  Scenario: Value equals 90% of Reference, Green LED is ON
    Given pin GREEN_LED is watched
    And pin YELLOW_LED is watched
    And pin RED_LED is watched
    And pin REFERENCE is set to 1000
    When pin VALUE is set to 900
    Then pin GREEN_LED should be ON
    And pin YELLOW_LED should be OFF
    And pin RED_LED should be OFF

  Scenario: Value greater than Reference, Red LED is ON
    Given pin GREEN_LED is watched
    And pin YELLOW_LED is watched
    And pin RED_LED is watched
    And pin REFERENCE is set to 1022
    When pin VALUE is set to 1023
    Then pin GREEN_LED should be OFF
    And pin YELLOW_LED should be OFF
    And pin RED_LED should be ON

  Scenario: Value is greater within 90% of Reference, Yellow LED is ON
    Given pin GREEN_LED is watched
    And pin YELLOW_LED is watched
    And pin RED_LED is watched
    And pin REFERENCE is set to 1000
    When pin VALUE is set to 901
    Then pin GREEN_LED should be OFF
    And pin YELLOW_LED should be ON
    And pin RED_LED should be OFF
