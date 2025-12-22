#!/usr/bin/env bats

# Why test the entrypoint separately and not within the container? The better test would actually be to start the entrypoint within the container, but the whole container creates files in /dev and /tmp and this normally with root rights. This makes at least simple testing more difficult, so it was preferred to test the entrypoint before the image build and outside the image/container. 


setup() {
  ENTRYPOINT_PID=""
  PATH="$PWD/tests/mocks:$PWD:$PATH" # Ensure socat mock is found
  TEMP_ENTRYPOINT=$(mktemp)          # Temporary entrypoint with adjusted ROOTDIR
  TEST_ROOTDIR=$(mktemp -d)          # Dynamic test root directory

  # Create necessary directories for the test
  mkdir -p "$TEST_ROOTDIR/dev" "$TEST_ROOTDIR/tmp"

  # Create a modified version of entrypoint.sh for testing
  sed "s|ROOTDIR=\"\"|ROOTDIR=\"$TEST_ROOTDIR\"|g" entrypoint.sh > "$TEMP_ENTRYPOINT"
  chmod +x "$TEMP_ENTRYPOINT"

  echo "Setup complete with TEST_ROOTDIR=$TEST_ROOTDIR"
}

teardown() {
  echo "Cleaning up: removing test directory $TEST_ROOTDIR and temporary entrypoint $TEMP_ENTRYPOINT"
  rm -rf "$TEST_ROOTDIR" "$TEMP_ENTRYPOINT"
}

fail() {
  echo "$1" >&2
  [[ -n "$ENTRYPOINT_PID" ]] && kill "$ENTRYPOINT_PID" 2>/dev/null
  exit 1
}

@test "entrypoint creates /dev/virtualavr0 if VIRTUALDEVICE is unset, then removes it" {
  unset VIRTUALDEVICE

  echo "Running entrypoint with VIRTUALDEVICE unset..."
  "$TEMP_ENTRYPOINT" &
  ENTRYPOINT_PID=$!

  sleep 1
  
  VIRTUALDEVICE="/dev/virtualavr0"

  # Verify the device was created in /dev
  echo "Checking if device $TEST_ROOTDIR$VIRTUALDEVICE exists..."
  [ -e "$TEST_ROOTDIR$VIRTUALDEVICE" ] || fail "Device $TEST_ROOTDIR$VIRTUALDEVICE not found."

  echo "Kill entrypoint to trigger cleanup..."
  kill "$ENTRYPOINT_PID"
  sleep 1

  # Ensure the device was removed
  echo "Checking if device $TEST_ROOTDIR$VIRTUALDEVICE was removed..."
  [ ! -e "$TEST_ROOTDIR$VIRTUALDEVICE" ] || fail "Device $TEST_ROOTDIR$VIRTUALDEVICE still exists."
}

@test "entrypoint creates device in /tmp if VIRTUALDEVICE is an empty string, then removes it" {
  export VIRTUALDEVICE=""
  
  echo "Running entrypoint with VIRTUALDEVICE as an empty string..."
  "$TEMP_ENTRYPOINT" &
  ENTRYPOINT_PID=$!

  sleep 1

  GENERATED_DEVICE=$(find "$TEST_ROOTDIR/tmp" -name "virtualavr*" -type f)
  echo "Generated device path in /tmp: $GENERATED_DEVICE"

  # Verify that a file matching the pattern was created and matches the expected format
  [[ -z "$GENERATED_DEVICE" || ! "$GENERATED_DEVICE" =~ ^$TEST_ROOTDIR/tmp/virtualavr[a-zA-Z0-9]{10}$ ]] && fail "No device matching pattern virtualavr[a-zA-Z0-9]{10}$ found in $TEST_ROOTDIR/tmp."

  # Kill entrypoint to trigger cleanup
  echo "Killing entrypoint to trigger cleanup..."
  kill "$ENTRYPOINT_PID"
  
  sleep 1

  # Ensure the dynamically created device was removed
  echo "Checking if device $GENERATED_DEVICE was removed..."
  [ ! -e "$GENERATED_DEVICE" ] || fail "Device $GENERATED_DEVICE still exists."
}

@test "entrypoint fails if VIRTUALDEVICE exists and OVERWRITE_VIRTUALDEVICE is not set" {
  export VIRTUALDEVICE="/dev/someExistingDevice"

  touch "$TEST_ROOTDIR/$VIRTUALDEVICE"  # Simulate that the device already exists
  unset OVERWRITE_VIRTUALDEVICE

  echo "Running entrypoint with VIRTUALDEVICE already existing and OVERWRITE_VIRTUALDEVICE unset..."
  run bash "$TEMP_ENTRYPOINT"
  
  # Verify that the entrypoint fails and the appropriate message is output
  echo "Checking if entrypoint failed with the correct error message..."
  [ "$status" -ne 0 ] || fail "Entry point did not fail as expected."
  [[ "$output" == *"set OVERWRITE_VIRTUALDEVICE if it should get overwritten"* ]] || fail "Expected error message not found."
}

@test "entrypoint does not remove existing device if OVERWRITE_VIRTUALDEVICE is set" {
  export VIRTUALDEVICE="/dev/someExistingDevice"

  touch "$TEST_ROOTDIR/$VIRTUALDEVICE"  # Simulate that the device already exists
  export OVERWRITE_VIRTUALDEVICE=true

  echo "Running entrypoint with VIRTUALDEVICE already existing and OVERWRITE_VIRTUALDEVICE set..."
  "$TEMP_ENTRYPOINT" &
  ENTRYPOINT_PID=$!

  sleep 1

  # Ensure the device file still exists during entrypoint execution
  echo "Checking if device $TEST_ROOTDIR/$VIRTUALDEVICE still exists during entrypoint execution..."
  [ -e "$TEST_ROOTDIR/$VIRTUALDEVICE" ] || fail "Device $TEST_ROOTDIR/$VIRTUALDEVICE was removed."

  echo "Kill entrypoint to trigger cleanup..."
  kill "$ENTRYPOINT_PID"
  sleep 1

  # Ensure the device file still exists after entrypoint execution
  echo "Checking if device $TEST_ROOTDIR/$VIRTUALDEVICE was not removed after entrypoint stopped..."
  [ -e "$TEST_ROOTDIR/$VIRTUALDEVICE" ] || fail "Device $TEST_ROOTDIR/$VIRTUALDEVICE was removed."
}

@test "entrypoint uses TCP serial mode when SERIAL_TCP is set" {
  export SERIAL_TCP="host.example.com:12345"

  # Prepare mock socat to capture arguments and PID
  SOCAT_CALLED_FILE="$TEST_ROOTDIR/socat_called.txt"
  SOCAT_PID_FILE="$TEST_ROOTDIR/socat.pid"
  mkdir -p "$TEST_ROOTDIR/mocks"
  echo -e '#!/usr/bin/env bash\n'\
'echo "$*" > '"$SOCAT_CALLED_FILE"'\n'\
'sleep 60 &\necho $! > '"$SOCAT_PID_FILE" > "$TEST_ROOTDIR/mocks/socat"
  chmod +x "$TEST_ROOTDIR/mocks/socat"

  PATH="$TEST_ROOTDIR/mocks:$PATH"

  # Run entrypoint
  bash "$TEMP_ENTRYPOINT" &
  ENTRYPOINT_PID=$!
  sleep 1

  # Verify TCP host and port are passed to socat
  SOCAT_CALLED=$(cat "$SOCAT_CALLED_FILE")
  [[ "$SOCAT_CALLED" == *"tcp:host.example.com:12345"* ]] || fail "socat did not get correct TCP host:port"

  # Verify no PTY device created
  [ ! -e "$TEST_ROOTDIR/dev/virtualavr0" ] || fail "/dev/virtualavr0 should not exist in TCP mode"
  TMP_DEVICE=$(find "$TEST_ROOTDIR/tmp" -name "virtualavr*" -type f)
  [ -z "$TMP_DEVICE" ] || fail "No temporary device should be created in TCP mode"

  # Kill socat process manually
  SOCAT_PID=$(cat "$SOCAT_PID_FILE")
  kill "$SOCAT_PID"
  wait "$ENTRYPOINT_PID" 2>/dev/null || true
}
