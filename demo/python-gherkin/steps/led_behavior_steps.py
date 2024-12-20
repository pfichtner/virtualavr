from behave import given, when, then
import json
import time
from websocket_listener import WebSocketListener

def resolve_alias(context, alias):
    return context.aliases.get(alias, alias)

def parse_value(value):
    if value.lower() in ("true", "false", "on", "off"):
        return value.lower() == "true" or value.lower() == "on"
    return int(value)

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
def step_define_aliases(context):
    context.aliases = {row['alias']: row['pin'] for row in context.table}
    print("Defined aliases:", context.aliases)

@given('pin {alias} is watched')
def step_watch_pin(context, alias):
    pin = resolve_alias(context, alias)
    send_ws_message(context.listener.ws, {"type": "pinMode", "pin": pin, "mode": "digital"})

@given('pin {alias} is set to {value}')
@when('pin {alias} is set to {value}')
def step_set_pin(context, alias, value):
    pin = resolve_alias(context, alias)
    parsed_value = parse_value(value)
    send_ws_message(context.listener.ws, {"type": "pinState", "pin": pin, "state": parsed_value})

@then('pin {alias} should be {state}')
def step_check_pin_state(context, alias, state):
    pin = resolve_alias(context, alias)
    expected_state = parse_value(state)
    wait_for_ws_message(context.listener, pin, expected_state)
