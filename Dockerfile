FROM node:16

ADD . /app
WORKDIR /app/
RUN npm ci
RUN apt-get update && apt-get install -y socat && rm -rf /var/lib/apt/lists/*

CMD socat -v pty,rawer,link=/dev/ttyFoobar EXEC:'node virtualavr.js',pty,rawer

