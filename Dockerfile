FROM node:16

WORKDIR /sketch
ADD /sketch /sketch/

WORKDIR /app
ADD virtualavr.js package-lock.json package.json /app/
RUN npm i
RUN apt-get update && apt-get install -y socat && rm -rf /var/lib/apt/lists/*

ADD entrypoint.sh /usr/local/bin
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

