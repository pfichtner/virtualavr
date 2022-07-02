FROM node:16

ADD . /app
WORKDIR /app/
RUN npm i
RUN apt-get update && apt-get install -y socat && rm -rf /var/lib/apt/lists/*

ADD entrypoint.sh /usr/local/bin
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

