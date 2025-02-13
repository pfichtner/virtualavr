from behave import given, when, then
import json
import time
import uuid
from websocket_listener import WebSocketListener

def resolve_alias(context, alias):
    return context.aliases.get(alias, alias)

def parse_value(value):
    if value.lower() in ("true", "false", "on", "off", "high", "low"):
        return value.lower() == "true" or value.lower() == "on" or value.lower() == "high"
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

def wait_for_pin_toggle_count(listener, pin, expected_toggles, timeout=20):
    start_time = time.time()
    toggle_count = None
    last_state = None

    while time.time() - start_time < timeout:
        toggle_count = 0
        pin_messages = [msg for msg in listener.get_all_messages() if msg.get("pin") == pin]
        for msg in pin_messages:
            current_state = msg.get("state")
            if last_state is not None and current_state != last_state:
                toggle_count += 1
            last_state = current_state
        if toggle_count >= expected_toggles:
            return
        time.sleep(0.1)
    raise AssertionError(f"Expected {expected_toggles} toggles for pin {pin}, but only {toggle_count} toggles were received within {timeout} seconds.")

def clear_message_queue(listener):
    listener.get_all_messages()  # Fetching all messages clears the queue
    print("Cleared the message queue.")

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

@given('the {alias} should be {state}')
@then('the {alias} should be {state}')
def step_check_pin_state(context, alias, state):
    pin = resolve_alias(context, alias)
    expected_state = parse_value(state)
    wait_for_ws_message(context.listener, pin, expected_state)

@given('the message queue is cleared')
@when('the message queue is cleared')
def step_clear_message_queue(context):
    clear_message_queue(context.listener)

@given('the {alias} was toggled {times} times')
@then('the {alias} was toggled {times} times')
def step_check_pin_state(context, alias, times):
    pin = resolve_alias(context, alias)
    wait_for_pin_toggle_count(context.listener, pin, int(times))
