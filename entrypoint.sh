#!/usr/bin/env bash

set -Eeuo pipefail

cleanup() {
    [ -n "${PID:-}" ] && kill "$PID"
    [ "$CLEANUP_VIRTUALDEVICE" == 'true' ] && rm -f "$VIRTUALDEVICE"
}

FILENAME=${FILENAME:-'sketch.ino'}
BAUDRATE=${BAUDRATE:-9600}
CLEANUP_VIRTUALDEVICE=false

trap 'cleanup' EXIT
cd /sketch

[ -z "${VIRTUALDEVICE+x}" ] && VIRTUALDEVICE="/dev/virtualavr0"
if [ -e "$VIRTUALDEVICE" ]; then
    if [ ! -v OVERWRITE_VIRTUALDEVICE ]; then
        echo "$VIRTUALDEVICE already exists, set \$OVERWRITE_VIRTUALDEVICE if it should get overwritten" >&2
        exit 1
    fi
else
    [ -z "$VIRTUALDEVICE" ] && VIRTUALDEVICE=$(mktemp /tmp/virtualavrXXXXXXXXXX)
    CLEANUP_VIRTUALDEVICE=true
fi

socat ${VERBOSITY:-} pty,rawer,link=${VIRTUALDEVICE},user=${DEVICEUSER:-'root'},group=${DEVICEGROUP:-'dialout'},mode=${DEVICEMODE:-660},b$BAUDRATE EXEC:"node /app/virtualavr.js $FILENAME",pty,rawer,fdin=3,fdout=4 &

PID=$!
wait $PID

