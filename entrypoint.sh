#!/bin/bash -e

cd /sketch

FILENAME=${FILENAME:-'sketch.ino'}
BAUDRATE=${BAUDRATE:-9600}

socat ${VERBOSITY:-} pty,rawer,link=${VIRTUALDEVICE:-/dev/virtualavr0},group-late=dialout,mode=660,ispeed=$BAUDRATE,ospeed=$BAUDRATE,b$BAUDRATE EXEC:"node /app/virtualavr.js $FILENAME",pty,rawer

