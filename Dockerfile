FROM node:16-alpine

RUN apk add --no-cache bash socat curl gcc-avr g++ gcompat libc6-compat
RUN curl -fsSL https://raw.githubusercontent.com/arduino/arduino-cli/master/install.sh | BINDIR=/usr/local/bin sh
RUN arduino-cli core install arduino:avr

WORKDIR /sketch
ADD /sketch /sketch/

WORKDIR /app
ADD package-lock.json package.json /app/
RUN npm i
ADD virtualavr.js /app/

ADD entrypoint.sh /usr/local/bin
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

