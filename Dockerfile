FROM node:16-alpine

# ubuntu/debian
#RUN apt-get update && apt-get install -y socat && rm -rf /var/lib/apt/lists/*
# alpine
RUN apk add --no-cache bash socat

WORKDIR /sketch
ADD /sketch /sketch/

WORKDIR /app
ADD package-lock.json package.json /app/
RUN npm i
ADD virtualavr.js /app/

ADD entrypoint.sh /usr/local/bin
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

