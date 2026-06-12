const os = require('os')
const fs = require('fs');
const fsp = require('fs').promises;
const { performance } = require('perf_hooks');
const avr8js = require('avr8js');
const intelhex = require('intel-hex');

const ws = require('ws');

const PUBLISH_MILLIS = process.env.PUBLISH_MILLIS || 250;
const BATCH_MILLIS = Number(process.env.BATCH_MILLIS) || 0;
const INSTRUCTION_CHUNK_SIZE = Number(process.env.INSTRUCTION_CHUNK_SIZE) || 500000;
const REALTIME = process.env.REALTIME === 'true';
const MIN_DIFF_TO_PUBLISH = process.env.MIN_DIFF_TO_PUBLISH || 0;
let isPaused = !!process.env.PAUSE_ON_START;

// Open custom file descriptors
const input = fs.createReadStream(null, { fd: 3 });
const output = fs.createWriteStream(null, { fd: 4 });

let messageQueue = [];
var cpu;
var adc;
const ports = {};
const listeningModes = {};
const activeAnalogListeners = new Set();
// We don't strictly need a set for digital listeners since they are event-driven in handlePort,
// but tracking them helps maintain the separation requested.
const activeDigitalListeners = new Set();
let sending = false;
var serialDebug;
var lastPublish = new Date();

const clockFrequency = 16e6;  // 16 MHz
const unoPinMappings = {
     '0': { port: 'D', pin: 0 },
     '1': { port: 'D', pin: 1 },
     '2': { port: 'D', pin: 2 },
     '3': { port: 'D', pin: 3, pwmFrequency: 490 }, // PWM (Timer 2)
     '4': { port: 'D', pin: 4 },
     '5': { port: 'D', pin: 5, pwmFrequency: 980 }, // PWM (Timer 0)
     '6': { port: 'D', pin: 6, pwmFrequency: 980 }, // PWM (Timer 0)
     '7': { port: 'D', pin: 7 },
     '8': { port: 'B', pin: 0 },
     '9': { port: 'B', pin: 1, pwmFrequency: 490 }, // PWM (Timer 1)
    '10': { port: 'B', pin: 2, pwmFrequency: 490 }, // PWM (Timer 1)
    '11': { port: 'B', pin: 3, pwmFrequency: 490 }, // PWM (Timer 2)
    '12': { port: 'B', pin: 4 },
    '13': { port: 'B', pin: 5 },
    'A0': { port: 'C', pin: 0 },
    'A1': { port: 'C', pin: 1 },
    'A2': { port: 'C', pin: 2 },
    'A3': { port: 'C', pin: 3 },
    'A4': { port: 'C', pin: 4 },
    'A5': { port: 'C', pin: 5 },
};
// TODO use pwmFrequency


/**
 * Precomputed lookup tables for O(1) pin mapping.
 * 
 * pinToAvr: Maps Arduino pin names (e.g., '13', 'A0', 'D13') to their AVR port and pin bit.
 * portAvrPinToArduino: Maps AVR port names to an array of Arduino pin names, indexed by bit.
 */
const pinToAvr = {};
const portAvrPinToArduino = {};

for (const [arduinoPin, mapping] of Object.entries(unoPinMappings)) {
    pinToAvr[arduinoPin] = mapping;
    // Support 'D' prefix for digital pins (e.g., 'D13')
    if (!arduinoPin.startsWith('A')) {
        pinToAvr['D' + arduinoPin] = mapping;
    }

    if (!portAvrPinToArduino[mapping.port]) {
        portAvrPinToArduino[mapping.port] = [];
    }
    portAvrPinToArduino[mapping.port][mapping.pin] = arduinoPin;
}

const args = process.argv.slice(2);


const runCode = async (hexContent, portCallback) => {
    const { data } = intelhex.parse(fs.readFileSync(hexContent));
    const progData = new Uint8Array(data);

    // Set up the simulation
    cpu = new avr8js.CPU(new Uint16Array(progData.buffer));
    adc = new avr8js.AVRADC(cpu, avr8js.adcConfig);

    for (const mapping of Object.values(unoPinMappings)) {
        const portName = mapping.port;
        if (!ports[portName]) {
            ports[portName] = new avr8js.AVRIOPort(cpu, avr8js[`port${portName}Config`]);
        }
    }

    const portStates = {};
    const handlePort = (portName, portCallback) => {        
        const port = ports[portName];
        const arduinoPins = portAvrPinToArduino[portName] || [];
        let lastValue = 0;
        port.addListener((value) => {
            // Optimization: Only process pins if the port value has actually changed.
            // Using XOR to find which bits (pins) changed since the last update.
            const changed = value ^ lastValue;
            if (changed === 0) {
                return;
            }
            lastValue = value;

            for (let i = 0; i < arduinoPins.length; i++) {
                // Only process the pin if its corresponding bit in the port has changed.
                // This eliminates unnecessary pinState() calls and state checks for stable pins.
                if (changed & (1 << i)) {
                    const arduinoPin = arduinoPins[i];
                    const state = port.pinState(i) === avr8js.PinState.High;

                    let entry = portStates[arduinoPin];
                    if (entry === undefined) {
                        entry = { lastState: undefined, lastStateCycles: 0, lastUpdateCycles: cpu.cycles, lastStatePublished: 0, pinHighCycles: 0 };
                        portStates[arduinoPin] = entry;
                    }
                    if (entry.lastState !== state) {
                        if (entry.lastState) {
                            entry.pinHighCycles += (cpu.cycles - entry.lastStateCycles);
                        }
                        entry.lastState = state;
                        entry.lastStateCycles = cpu.cycles;
                        if (listeningModes[arduinoPin] === 'digital') {
                            // TODO: Should we move all publishes out of the callback (also the digitals)?
                            // TODO: Throttle if there are too many messages (see lastStateCycles)
                            const cpuTime = (cpu.cycles / clockFrequency).toFixed(6);
                            portCallback({ type: 'pinState', pin: arduinoPin, state: state, cpuTime: cpuTime });
                            entry.lastStatePublished = state;
                        }
                    }
                }
            }
        });
    };
    handlePort('B', portCallback);
    handlePort('D', portCallback);

    const usart = new avr8js.AVRUSART(cpu, avr8js.usart0Config, clockFrequency);
    usart.onByteTransmit = data => {
            const arrBuff = new Uint8Array(1);
            arrBuff[0] = data;
            output.write(arrBuff);
            if (serialDebug) {
                portCallback({ type: 'serialDebug', direction: 'TX', bytes: [data] });
            }
    }
    const buff = [];
    usart.onRxComplete = () => sendNextChar(buff, usart);
    input.on('data', data => {
            var bytes = Array.prototype.slice.call(data, 0);
            for (let i = 0; i < bytes.length; i++) buff.push(bytes[i]);
            if (!sending) {
                sending = true;
                sendNextChar(buff, usart);
            }
            if (serialDebug) {
                portCallback({ type: 'serialDebug', direction: 'RX', bytes: bytes });
            }
    });

    new avr8js.AVRTimer(cpu, avr8js.timer0Config);
    new avr8js.AVRTimer(cpu, avr8js.timer1Config);
    new avr8js.AVRTimer(cpu, avr8js.timer2Config);

    let syncStartTime = performance.now();
    let syncStartCycles = cpu.cycles;

    while (true) {
        if (!isPaused) {
            if (REALTIME) {
                const now = performance.now();
                const elapsedMs = now - syncStartTime;
                const targetCycles = Math.floor((elapsedMs * clockFrequency) / 1000);
                let cyclesToRun = targetCycles - (cpu.cycles - syncStartCycles);

                if (cyclesToRun > 0) {
                    // Limit catch-up to 100ms to prevent "spiral of death" if the host is overloaded
                    const maxBurst = clockFrequency / 10;
                    if (cyclesToRun > maxBurst) {
                        cyclesToRun = maxBurst;
                        // Reset sync markers if we hit the limit to avoid persistent lag
                        syncStartTime = now;
                        syncStartCycles = cpu.cycles;
                    }

                    while (cyclesToRun > 0 && !isPaused) {
                        const prevCycles = cpu.cycles;
                        avr8js.avrInstruction(cpu);
                        cpu.tick();
                        cyclesToRun -= (cpu.cycles - prevCycles);
                    }
                }
            } else {
                for (let i = 0; i < INSTRUCTION_CHUNK_SIZE; i++) {
                    avr8js.avrInstruction(cpu);
                    cpu.tick();
                }
            }
        } else {
            // Keep sync markers current while paused to avoid catch-up burst on unpause
            syncStartTime = performance.now();
            syncStartCycles = cpu.cycles;
        }
        await new Promise(resolve => setTimeout(resolve));

        try {
            while (messageQueue.length > 0) {
                processMessage(messageQueue.shift(), portCallback);
            }
        } catch (e) {
            console.log(e);
        }

        const now = new Date();
        if (now - lastPublish > PUBLISH_MILLIS) {
            lastPublish = now;

            for (const arduinoPin of activeAnalogListeners) {
                const mapping = pinToAvr[arduinoPin];
                if (!mapping) continue;

                const port = ports[mapping.port];
                const avrPin = mapping.pin;
                const entry = portStates[arduinoPin];
                if (!entry) continue;

                if (port.pinState(avrPin) === avr8js.PinState.High) {
                    entry.pinHighCycles += (cpu.cycles - entry.lastStateCycles);
                }

                const cyclesSinceUpdate = cpu.cycles - entry.lastUpdateCycles;
                if (cyclesSinceUpdate > 0) {
                    // TODO fix pwmFrequencies
                    const state = Math.round(entry.pinHighCycles / cyclesSinceUpdate * 255);
                    if (Math.abs(state - entry.lastStatePublished) > MIN_DIFF_TO_PUBLISH) {
                        const cpuTime = (cpu.cycles / clockFrequency).toFixed(6);
                        portCallback({ type: 'pinState', pin: arduinoPin, state: state, cpuTime: cpuTime });
                        entry.lastStatePublished = state;
                    }
                }
                
                entry.lastUpdateCycles = cpu.cycles;
                entry.lastStateCycles = cpu.cycles;
                entry.pinHighCycles = 0;
            }
        }
    }
}

function sendNextChar(buff, usart) {
    if (buff.length > 0) {
        const ch = buff.shift();
        usart.writeByte(ch);
    } else {
        sending = false;
    }
}

function processMessage(msg, callbackPinState) {
    // { "type": "pinMode", "pin": "12", "mode": "analog" }
    const mapping = pinToAvr[msg.pin];
    if (msg.type === 'pinMode') {
        if (msg.mode === 'analog' || msg.mode === 'pwm') {
            listeningModes[msg.pin] = 'analog';
            activeAnalogListeners.add(msg.pin);
            activeDigitalListeners.delete(msg.pin);
        } else if (msg.mode === 'digital') {
            listeningModes[msg.pin] = 'digital';
            activeAnalogListeners.delete(msg.pin);
            activeDigitalListeners.add(msg.pin);
            const cpuTime = (cpu.cycles / clockFrequency).toFixed(6);
            // Immediately publish the current state
            if (mapping && (mapping.port === 'B' || mapping.port === 'D')) {
                const state = ports[mapping.port].pinState(mapping.pin) === avr8js.PinState.High;
                callbackPinState({ type: 'pinState', pin: msg.pin, state: state, cpuTime: cpuTime });
            }
        } else {
            listeningModes[msg.pin] = undefined;
            activeAnalogListeners.delete(msg.pin);
            activeDigitalListeners.delete(msg.pin);
        }
    } else if (msg.type === 'fakePinState' || msg.type === 'pinState') {
        if (typeof msg.state === 'boolean') {
            // { "type": "pinState", "pin": "12", "state": true }
            if (mapping && (mapping.port === 'B' || mapping.port === 'D')) {
                ports[mapping.port].setPin(mapping.pin, msg.state);
            }
        } else if (typeof msg.state === 'number') {
            // { "type": "pinState", "pin": "12", "state": 42 }
            if (mapping && (mapping.port === 'C' || mapping.port === 'D')) {
                adc.channelValues[mapping.pin] = msg.state * 5 / 1024;
            }
        }
    } else if (msg.type === 'control') {
        if (msg.action === 'play' || msg.action === 'unpause') {
            isPaused = false;
        } else if (msg.action === 'pause') {
            isPaused = true;
        }
    } else if (msg.type === 'serialDebug') {
        serialDebug = msg.state;
    }
    if (msg.replyId) {
        callbackPinState({ ...msg, executed: true });
    }
}

function main() {
    // const callback = (pin, state) => {};
    const wss = new ws.WebSocketServer({
        port: 8080,
        perMessageDeflate: {
            concurrencyLimit: 2, // Limits zlib concurrency for perf.
            threshold: 1024 // Size (in bytes) below which messages should not be compressed if context takeover is disabled.
        }
    });

    const pendingMessages = [];
    let batchTimer = null;

    const flushMessages = () => {
        if (pendingMessages.length > 0) {
            pendingMessages.forEach(msg => {
                const json = JSON.stringify(msg);
                wss.clients.forEach(client => {
                    if (client !== ws && client.readyState === ws.WebSocket.OPEN) {
                        client.send(json);
                    }
                });
            });
            pendingMessages.length = 0;
        }
        batchTimer = null;
    };

    const callbackPinState = (msg) => {
        if (BATCH_MILLIS > 0) {
            pendingMessages.push(msg);
            if (!batchTimer) {
                batchTimer = setTimeout(flushMessages, BATCH_MILLIS);
            }
        } else {
            const json = JSON.stringify(msg);
            wss.clients.forEach(client => {
                if (client !== ws && client.readyState === ws.WebSocket.OPEN) {
                    client.send(json);
                }
            });
        }
    };

    wss.on('connection', function connection(ws) {
        ws.on('message', function message(data) {
            if (data) {
                try {
                    messageQueue.push(JSON.parse(data));
                } catch (e) {
                    console.error(`Failed to parse JSON: ${data}, Error: ${e.message}`);
                }
            }
        });
    });

    runCode(args.length == 0 ? 'sketch.ino' : args[0], callbackPinState);
}

if (require.main === module) {
    main();
}

module.exports = {
    runCode
}
