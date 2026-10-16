#!/usr/bin/env bash

set -Eeuo pipefail
ROOTDIR="" # used to replace the ROOTDIR for tests

cleanup() {
    [ -n "${PID:-}" ] && kill "$PID" 2>/dev/null || true
    [ "$CLEANUP_VIRTUALDEVICE" == 'true' ] && rm -f "${ROOTDIR}${VIRTUALDEVICE}"
    [ -n "${HEXFILE:-}" ] && rm -f "$HEXFILE"
}

CLEANUP_VIRTUALDEVICE=false
FILENAME=${FILENAME:-'sketch.ino'}
BAUDRATE=${BAUDRATE:-9600}
WATCH=${WATCH:-false}
WATCH_INTERVAL=${WATCH_INTERVAL:-1}

trap 'cleanup' EXIT

# ------------------------------------------------------------
# Compile sketch
# ------------------------------------------------------------

compile_sketch() {
    local hexfile
    hexfile="$(mktemp /tmp/virtualavr-hex-XXXXXX)"
    hexfile="$hexfile.hex"
    if ! virtualavr-compile-arduino "/sketch/$FILENAME" "$hexfile" >&2; then
        rm -f "$hexfile"
        return 1
    fi
    echo "$hexfile"
}

# ------------------------------------------------------------
# Start simulator
# ------------------------------------------------------------

start_simulator() {
    local hexfile="$1"
    SERIAL_TCP=${SERIAL_TCP:-}

    if [ -n "$SERIAL_TCP" ]; then
        echo "Using TCP serial mode: connecting to $SERIAL_TCP"
        socat ${VERBOSITY:-} tcp:"$SERIAL_TCP" EXEC:"node /app/virtualavr.js $hexfile",pty,rawer,fdin=3,fdout=4 &
    else
        # Standard PTY mode: create a local virtual serial device
        [ -z "${VIRTUALDEVICE+x}" ] && VIRTUALDEVICE="/dev/virtualavr0"

        if [ -n "$VIRTUALDEVICE" -a -e "${ROOTDIR}$VIRTUALDEVICE" ]; then
            if [ ! -v OVERWRITE_VIRTUALDEVICE ]; then
                echo "$VIRTUALDEVICE already exists, set OVERWRITE_VIRTUALDEVICE if it should get overwritten" >&2
                exit 1
            fi
        else
            [ -z "$VIRTUALDEVICE" ] && VIRTUALDEVICE=$(mktemp "${ROOTDIR}/tmp/virtualavrXXXXXXXXXX") && VIRTUALDEVICE="${VIRTUALDEVICE#$ROOTDIR}"
            CLEANUP_VIRTUALDEVICE=true
        fi

        socat ${VERBOSITY:-} pty,rawer,link="${ROOTDIR}${VIRTUALDEVICE}",user=${DEVICEUSER:-'root'},group=${DEVICEGROUP:-'dialout'},mode=${DEVICEMODE:-660},b$BAUDRATE EXEC:"node /app/virtualavr.js $hexfile",pty,rawer,fdin=3,fdout=4 &
    fi

    PID=$!
}

# ------------------------------------------------------------
# Get latest file mtime in a directory (for change detection)
# ------------------------------------------------------------

get_file_mtime() {
    local dir="$1"
    find "$dir" -type f -exec stat -c '%y' {} + 2>/dev/null | sort | tail -1
}

# ------------------------------------------------------------
# Main
# ------------------------------------------------------------

if [ "$WATCH" == "true" ]; then
    echo "Watch mode enabled. Monitoring /sketch for changes (interval: ${WATCH_INTERVAL}s)..."
    local_last_mtime=$(get_file_mtime "/sketch")

    while true; do
        echo "Compiling sketch..."
        if ! HEXFILE=$(compile_sketch); then
            echo "Compilation failed. Retrying in ${WATCH_INTERVAL}s..." >&2
            sleep "$WATCH_INTERVAL"
            continue
        fi

        echo "Starting simulator..."
        start_simulator "$HEXFILE"

        # Wait for simulator to stop, polling for file changes
        while kill -0 "$PID" 2>/dev/null; do
            sleep "$WATCH_INTERVAL"
            current_mtime=$(get_file_mtime "/sketch")
            if [ "$current_mtime" != "$local_last_mtime" ]; then
                echo "Detected changes in /sketch. Recompiling..."
                local_last_mtime="$current_mtime"
                kill "$PID" 2>/dev/null || true
                wait "$PID" 2>/dev/null || true
                break
            fi
        done

        # Brief pause before restarting
        sleep 0.5
    done
else
    echo "Compiling sketch..."
    HEXFILE=$(compile_sketch) || exit 1

    echo "Starting simulator..."
    start_simulator "$HEXFILE"

    wait $PID
fi