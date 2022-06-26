#!/bin/bash -e

socat pty,rawer,link=${VIRTUALDEVICE:-/dev/virtualavr0},group-late=dialout,mode=660 EXEC:'node virtualavr.js',pty,rawer

