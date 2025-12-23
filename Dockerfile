FROM node:20-alpine

RUN apk add --no-cache bash socat curl gcc-avr g++ gcompat libc6-compat websocat
RUN curl -fsSL https://raw.githubusercontent.com/arduino/arduino-cli/master/install.sh | BINDIR=/usr/local/bin sh
RUN arduino-cli core install arduino:avr

WORKDIR /app
ADD package-lock.json package.json /app/
RUN npm i
ADD virtualavr.js /app/

WORKDIR /sketch
ADD /sketch /sketch/

ADD entrypoint.sh virtualavr-compile-arduino /usr/local/bin/
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

EXPOSE 8080
HEALTHCHECK --start-period=3s --timeout=3s \
  CMD echo '{}' | websocat ws://localhost:8080 || exit 1
