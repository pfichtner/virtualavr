#!/usr/bin/env bash

set -Eeuo pipefail
ROOTDIR="" # used to replace the ROOTDIR for tests

cleanup() {
    [ -n "${PID:-}" ] && kill "$PID"
    [ "$CLEANUP_VIRTUALDEVICE" == 'true' ] && rm -f "${ROOTDIR}${VIRTUALDEVICE}"
}

CLEANUP_VIRTUALDEVICE=false
FILENAME=${FILENAME:-'sketch.ino'}
BAUDRATE=${BAUDRATE:-9600}

trap 'cleanup' EXIT


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

socat ${VERBOSITY:-} pty,rawer,link="${ROOTDIR}${VIRTUALDEVICE}",user=${DEVICEUSER:-'root'},group=${DEVICEGROUP:-'dialout'},mode=${DEVICEMODE:-660},b$BAUDRATE EXEC:"node /app/virtualavr.js $FILENAME",pty,rawer,fdin=3,fdout=4 &

PID=$!
wait $PID
