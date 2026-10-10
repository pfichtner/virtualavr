// Healthcheck for the virtualavr WebSocket server.
//
// It opens a real WebSocket connection, including the token query parameter
// that the server requires when WS_TOKEN is set (see virtualavr.js). The token
// is URL-encoded so tokens containing characters like &, =, +, / or spaces do
// not break the query string.
//
// The server reports an invalid token by terminating the connection right
// after the handshake (the handshake itself succeeds), so a connection that is
// still open after a short grace period is considered healthy.
const WebSocket = require('ws');

const GRACE_MILLIS = 500;
const TIMEOUT_MILLIS = 2500;

const token = process.env.WS_TOKEN || '';
const url = token
    ? 'ws://localhost:8080?token=' + encodeURIComponent(token)
    : 'ws://localhost:8080';

let done = false;

const timeout = setTimeout(() => fail(), TIMEOUT_MILLIS);

function fail() {
    if (done) {
        return;
    }
    done = true;
    process.exit(1);
}

function succeed() {
    if (done) {
        return;
    }
    done = true;
    process.exit(0);
}

const socket = new WebSocket(url);

socket.on('open', () => {
    // Send a harmless message, mirroring the message the previous websocat
    // based healthcheck sent.
    socket.send('{}');
    setTimeout(() => {
        if (socket.readyState === WebSocket.OPEN) {
            succeed();
        } else {
            fail();
        }
    }, GRACE_MILLIS);
});

// The server terminates the connection (abruptly, code 1006) when the token is
// wrong, and the connection also closes if the server is gone: both are
// unhealthy. A healthy connection is closed by this process exiting above.
socket.on('close', () => fail());
socket.on('error', () => fail());
