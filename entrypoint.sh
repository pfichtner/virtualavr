#!/usr/bin/env bash

set -Eeuo pipefail
ROOTDIR="" # used to replace the ROOTDIR for tests

cleanup() {
    [ -n "${PID:-}" ] && kill "$PID" 2>/dev/null
    [ "$CLEANUP_VIRTUALDEVICE" == 'true' ] && rm -f "${ROOTDIR}${VIRTUALDEVICE}"
    [ -n "${HEXFILE:-}" ] && rm -f "$HEXFILE"
}

CLEANUP_VIRTUALDEVICE=false
FILENAME=${FILENAME:-'sketch.ino'}
BAUDRATE=${BAUDRATE:-9600}

trap 'cleanup' EXIT

# ------------------------------------------------------------
# Compile sketch
# ------------------------------------------------------------

HEXFILE="$(mktemp /tmp/virtualavr-hex-XXXXXX)"
HEXFILE="$HEXFILE.hex"
virtualavr-compile-arduino.sh "/sketch/$FILENAME" "$HEXFILE" || exit 1

# TCP serial mode: connect to a TCP port on the host instead of creating a local PTY
# This allows the serial port to work on macOS/Windows with Docker Desktop
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
wait $PID
