from behave import given, when, then
import json
import time
from websocket_listener import WebSocketListener

def pin_mode(pin, mode):
    return {"type": "pinMode", "pin": pin, "mode": mode}

def pin_state(pin, state):
    return {"type": "pinState", "pin": pin, "state": state}

def send_ws_message(ws, message):
    ws.send(json.dumps(message))
    print(f"Sent WebSocket message: {json.dumps(message)}")

def wait_for_ws_message(listener, pin, expected_state, timeout=20):
    start_time = time.time()
    while time.time() - start_time < timeout:
        pin_messages = [msg for msg in listener.get_all_messages() if msg.get("pin") == pin]
        if pin_messages and pin_messages[-1].get("state") == expected_state:
            return
        time.sleep(0.1)
    raise AssertionError(f"Expected state {expected_state} for pin {pin} not received within {timeout} seconds.")

@given(u'the following aliases are defined')
def step_impl(context):
    context.aliases = {row['alias']: row['pin'] for row in context.table}
    print("Defined aliases:", context.aliases)  # Debugging output

@given('pin {alias} is watched')
def step_impl(context, alias):
    pin = context.aliases.get(alias, alias)
    send_ws_message(context.listener.ws, pin_mode(pin, "digital"))

@given('pin {alias} is set to {value}')
@when('pin {alias} is set to {value}')
def step_set_pin(context, alias, value):
    pin = context.aliases.get(alias, alias)
    if value.lower() in ("true", "false"):
        parsed_value = value.lower() == "true"
    else:
        parsed_value = int(value)
    send_ws_message(context.listener.ws, pin_state(pin, parsed_value))

@then('pin {alias} should be {state}')
def step_check_led_state(context, alias, state):
    pin = context.aliases.get(alias, alias)
    if state.lower() in ("true", "false", "on", "off"):
        expected_state = state.lower() == "true" or state.lower() == "on"
    else:
        expected_state = int(state)

    wait_for_ws_message(context.listener, pin, expected_state)
