FROM node:16

ADD . /app
WORKDIR /app/
RUN npm ci
RUN apt-get update && apt-get install -y socat && rm -rf /var/lib/apt/lists/*

# https://www.losant.com/blog/how-to-access-serial-devices-in-docker
CMD socat -v pty,rawer,link=/dev/virtualavr0,group-late=dialout,mode=660 EXEC:'node virtualavr.js',pty,rawer

