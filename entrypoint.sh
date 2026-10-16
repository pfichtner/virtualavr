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
# Fixed hex path: in WATCH mode the entrypoint recompiles into this file and
# the running simulator picks up the change by itself (hot reload), so the
# path must stay stable across recompiles.
HEXFILE=${HEXFILE:-/tmp/virtualavr-current.hex}

trap 'cleanup' EXIT

# ------------------------------------------------------------
# Compile sketch
# ------------------------------------------------------------

compile_sketch() {
    # Compiles into the fixed $HEXFILE path (atomic rename performed by
    # virtualavr-compile-arduino). On failure the previously compiled hex
    # stays in place so the old firmware keeps running.
    if ! virtualavr-compile-arduino "/sketch/$FILENAME" "$HEXFILE" >&2; then
        return 1
    fi
}

# ------------------------------------------------------------
# Start simulator
# ------------------------------------------------------------

start_simulator() {
    SERIAL_TCP=${SERIAL_TCP:-}

    if [ -n "$SERIAL_TCP" ]; then
        echo "Using TCP serial mode: connecting to $SERIAL_TCP"
        socat ${VERBOSITY:-} tcp:"$SERIAL_TCP" EXEC:"node /app/virtualavr.js $HEXFILE",pty,rawer,fdin=3,fdout=4 &
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

        socat ${VERBOSITY:-} pty,rawer,link="${ROOTDIR}${VIRTUALDEVICE}",user=${DEVICEUSER:-'root'},group=${DEVICEGROUP:-'dialout'},mode=${DEVICEMODE:-660},b$BAUDRATE EXEC:"node /app/virtualavr.js $HEXFILE",pty,rawer,fdin=3,fdout=4 &
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

    echo "Compiling sketch..."
    until compile_sketch; do
        echo "Compilation failed. Retrying in ${WATCH_INTERVAL}s..." >&2
        sleep "$WATCH_INTERVAL"
    done

    while true; do
        echo "Starting simulator..."
        start_simulator

        # Wait for the simulator, polling for file changes. On a change the
        # sketch is recompiled into the same $HEXFILE (atomic rename); the
        # running simulator watches that file and hot-reloads the firmware by
        # itself, so socat, the simulator process, the PTY and all client
        # connections stay untouched. If a compilation fails the previous
        # firmware keeps running. If the simulator dies, fall through and
        # start a fresh one.
        compile_pending=false
        while kill -0 "$PID" 2>/dev/null; do
            sleep "$WATCH_INTERVAL"
            current_mtime=$(get_file_mtime "/sketch")
            if [ "$current_mtime" != "$local_last_mtime" ]; then
                echo "Detected changes in /sketch. Recompiling..."
                local_last_mtime="$current_mtime"
                compile_pending=true
            fi
            if [ "$compile_pending" == "true" ]; then
                if compile_sketch; then
                    compile_pending=false
                else
                    echo "Compilation failed. Keeping the previous firmware. Retrying in ${WATCH_INTERVAL}s..." >&2
                fi
            fi
        done

        # Brief pause before restarting
        sleep 0.5
    done
else
    echo "Compiling sketch..."
    compile_sketch || exit 1

    echo "Starting simulator..."
    start_simulator

    wait $PID
fi