FROM node:20-alpine

RUN apk add --no-cache bash socat curl gcc-avr g++ gcompat libc6-compat
RUN curl -fsSL https://raw.githubusercontent.com/arduino/arduino-cli/master/install.sh | BINDIR=/usr/local/bin sh
RUN arduino-cli core install arduino:avr

WORKDIR /app
ADD package-lock.json package.json /app/
RUN npm i
ADD virtualavr.js healthcheck.js /app/

WORKDIR /sketch
ADD /sketch /sketch/

ADD entrypoint.sh virtualavr-compile-arduino /usr/local/bin/
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

EXPOSE 8080
# Healthcheck opens a real WebSocket connection (including auth) using the
# ws package that is already installed for virtualavr.js, so no extra tool
# (e.g. websocat) is needed. See healthcheck.js for details.
HEALTHCHECK --start-period=3s --timeout=3s CMD ["node", "/app/healthcheck.js"]
