from behave import given, when, then
import json
import time
import uuid
from websocket_listener import WebSocketListener

print(">> pin_alias_steps.py LOADED")

def resolve_alias(context, alias):
    return context.aliases.get(alias, alias)

def parse_value(value):
    if value.lower() in ("true", "false", "on", "off"):
        return value.lower() == "true" or value.lower() == "on"
    return int(value)

def send_ws_message(ws, message):
    reply_id = str(uuid.uuid4())
    message["replyId"] = reply_id
    ws.send(json.dumps(message))
    print(f"Sent WebSocket message: {json.dumps(message)}")
    return reply_id

def wait_for_reply(listener, reply_id, timeout=20):
    start_time = time.time()
    while time.time() - start_time < timeout:
        messages = listener.get_all_messages()
        for msg in messages:
            if msg.get("replyId") == reply_id and msg.get("executed"):
                print(f"Received reply for replyId {reply_id}")
                return
        time.sleep(0.1)
    raise AssertionError(f"Reply for replyId {reply_id} not received within {timeout} seconds.")

def wait_for_ws_message(listener, pin, expected_state, timeout=20):
    start_time = time.time()
    while time.time() - start_time < timeout:
        pin_messages = [msg for msg in listener.get_all_messages() if msg.get("type") == "pinState" and msg.get("pin") == pin]
        if pin_messages and pin_messages[-1].get("state") == expected_state:
            return
        time.sleep(0.1)
    raise AssertionError(f"Expected state {expected_state} for pin {pin} not received within {timeout} seconds.")

@given('the following pins are assigned')
def step_define_aliases(context):
    context.aliases = {row['alias']: row['pin'] for row in context.table}
    print("Defined aliases:", context.aliases)

@given('the pin of {alias} is monitored')
def step_watch_pin(context, alias):
    pin = resolve_alias(context, alias)
    reply_id = send_ws_message(context.listener.ws, {"type": "pinMode", "pin": pin, "mode": "digital"})
    wait_for_reply(context.listener, reply_id)

@given('the {alias} is set to {value}')
@when('the {alias} is set to {value}')
def step_set_pin(context, alias, value):
    pin = resolve_alias(context, alias)
    parsed_value = parse_value(value)
    reply_id = send_ws_message(context.listener.ws, {"type": "pinState", "pin": pin, "state": parsed_value})
    wait_for_reply(context.listener, reply_id)

@then('the {alias} should be {state}')
def step_check_pin_state(context, alias, state):
    pin = resolve_alias(context, alias)
    expected_state = parse_value(state)
    wait_for_ws_message(context.listener, pin, expected_state)
