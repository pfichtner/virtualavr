#!/bin/bash -e

cd /sketch

FILENAME=${FILENAME:-'sketch.ino'}
BAUDRATE=${BAUDRATE:-9600}

# Create a temporary file if VIRTUALDEVICE is explicitly set to an empty string
[ -v VIRTUALDEVICE ] && [ -z "$VIRTUALDEVICE" ] && VIRTUALDEVICE=$(mktemp /tmp/virtualavrXXXX)

socat ${VERBOSITY:-} pty,rawer,link=${VIRTUALDEVICE:-'/dev/virtualavr0'},user=${DEVICEUSER:-'root'},group=${DEVICEGROUP:-'dialout'},mode=${DEVICEMODE:-660},b$BAUDRATE EXEC:"node /app/virtualavr.js $FILENAME",pty,rawer,fdin=3,fdout=4

