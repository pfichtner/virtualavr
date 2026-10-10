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
# Healthcheck opens a real WebSocket connection (including auth): the server
# rejects clients when WS_TOKEN is set, so the token must be sent as query
# parameter. It is URL-encoded first so tokens containing characters like
# &, =, +, / or spaces do not break the query string.
# node is used for the encoding because it is already part of this image.
# Alternatives would add dependencies for no benefit: jq ~343 KiB,
# php ~8 MB, python3 ~22 MB, perl ~38 MiB; busybox has no urlencode applet.
HEALTHCHECK --start-period=3s --timeout=3s \
  CMD TOKEN=$(node -e 'process.stdout.write(encodeURIComponent(process.env.WS_TOKEN||""))'); echo '{}' | websocat "ws://localhost:8080?token=$TOKEN" || exit 1
