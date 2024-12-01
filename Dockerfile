FROM node:16-alpine

RUN apk add --no-cache bash socat

WORKDIR /sketch
ADD /sketch /sketch/

WORKDIR /app
ADD package-lock.json package.json /app/
RUN npm i
ADD virtualavr.js /app/

ADD entrypoint.sh /usr/local/bin
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

