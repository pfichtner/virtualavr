#!/usr/bin/env bats

# Why test the entrypoint separately and not within the container? The better test would actually be to start the entrypoint within the container, but the whole container creates files in /dev and /tmp and this normally with root rights. This makes at least simple testing more difficult, so it was preferred to test the entrypoint before the image build and outside the image/container. 

setup() {
  export PATH="$PWD:$PWD/tests/mocks:$PATH" # Ensure socat mock is found
  export TEMP_ENTRYPOINT=$(mktemp)          # Temporary entrypoint with adjusted ROOTDIR
  export TEST_ROOTDIR=$(mktemp -d)          # Dynamic test root directory

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

@test "entrypoint creates /dev/virtualavr0 if VIRTUALDEVICE is unset, then removes it" {
  unset VIRTUALDEVICE

  echo "Running entrypoint with VIRTUALDEVICE unset..."
  "$TEMP_ENTRYPOINT" &
  ENTRYPOINT_PID=$!

  sleep 1
  
  VIRTUALDEVICE="/dev/virtualavr0"

  # Verify the device was created in /dev
  echo "Checking if device $TEST_ROOTDIR$VIRTUALDEVICE exists..."
  [ -e "$TEST_ROOTDIR$VIRTUALDEVICE" ] || { echo "Device $TEST_ROOTDIR$VIRTUALDEVICE not found."; exit 1; }

  echo "Kill entrypoint to trigger cleanup..."
  kill "$ENTRYPOINT_PID"
  sleep 1

  # Ensure the device was removed
  echo "Checking if device $TEST_ROOTDIR$VIRTUALDEVICE was removed..."
  [ ! -e "$TEST_ROOTDIR$VIRTUALDEVICE" ] || { echo "Device $TEST_ROOTDIR$VIRTUALDEVICE still exists."; exit 1; }
}

@test "entrypoint creates device in /tmp if VIRTUALDEVICE is an empty string, then removes it" {
  export VIRTUALDEVICE=""
  
  echo "Running entrypoint with VIRTUALDEVICE as an empty string..."
  "$TEMP_ENTRYPOINT" &
  ENTRYPOINT_PID=$!

  sleep 1

  GENERATED_DEVICE=$(find "$TEST_ROOTDIR/tmp" -name "virtualavr*" -type f)
  echo "Generated device path in /tmp: $GENERATED_DEVICE"

  # Verify that a file matching the pattern was created
  if [[ -z "$GENERATED_DEVICE" ]]; then
    echo "No device matching pattern virtualavrXXXXXXXXXX found in $TEST_ROOTDIR/tmp."
    exit 1
  fi
  
  # Verify that a file matching the pattern was created
  [[ ! "$GENERATED_DEVICE" =~ ^$TEST_ROOTDIR/tmp/virtualavr[a-zA-Z0-9]{10}$ ]] && { echo "No device matching pattern virtualavrXXXXXXXXXX found in $TEST_ROOTDIR/tmp."; exit 1; }

  # Kill entrypoint to trigger cleanup
  echo "Killing entrypoint to trigger cleanup..."
  kill "$ENTRYPOINT_PID"
  
  sleep 1

  # Ensure the dynamically created device was removed
  echo "Checking if device $GENERATED_DEVICE was removed..."
  [ ! -e "$GENERATED_DEVICE" ] || { echo "Device $GENERATED_DEVICE still exists."; exit 1; }
}

@test "entrypoint fails if VIRTUALDEVICE exists and OVERWRITE_VIRTUALDEVICE is not set" {
  export VIRTUALDEVICE="/dev/someExistingDevice"

  touch "$TEST_ROOTDIR/$VIRTUALDEVICE"  # Simulate that the device already exists
  unset OVERWRITE_VIRTUALDEVICE

  echo "Running entrypoint with VIRTUALDEVICE already existing and OVERWRITE_VIRTUALDEVICE unset..."
  run bash "$TEMP_ENTRYPOINT"
  
  # Verify that the entrypoint fails and the appropriate message is output
  echo "Checking if entrypoint failed with the correct error message..."
  [ "$status" -ne 0 ] || { echo "Entry point did not fail as expected."; exit 1; }
  [[ "$output" == *"set OVERWRITE_VIRTUALDEVICE if it should get overwritten"* ]] || { echo "Expected error message not found."; exit 1; }
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
  [ -e "$TEST_ROOTDIR/$VIRTUALDEVICE" ] || { echo "Device $TEST_ROOTDIR/$VIRTUALDEVICE was removed."; exit 1; }

  echo "Kill entrypoint to trigger cleanup..."
  kill "$ENTRYPOINT_PID"
  sleep 1

  # Ensure the device file still exists after entrypoint execution
  echo "Checking if device $TEST_ROOTDIR/$VIRTUALDEVICE was not removed after entrypoint stopped..."
  [ -e "$TEST_ROOTDIR/$VIRTUALDEVICE" ] || { echo "Device $TEST_ROOTDIR/$VIRTUALDEVICE was removed."; exit 1; }
}
