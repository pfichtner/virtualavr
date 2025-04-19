const os = require('os')
const fs = require('fs');
const fsp = require('fs').promises;
const fetch = require('node-fetch');
const avr8js = require('avr8js');
const { exec } = require('child_process');
const util = require('util');
const execAsync = util.promisify(exec);
const intelhex = require('intel-hex');

const path = require('path');
const tmp = require('tmp');
const mkdirp = require('mkdirp');
const extract = require('extract-zip');

const ws = require('ws');

const PUBLISH_MILLIS = process.env.PUBLISH_MILLIS || 250;
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


const arduinoPinOnPort = {};
const uniquePorts = Array.from(new Set(Object.values(unoPinMappings).map(pinObj => pinObj.port)));
uniquePorts.forEach(port => {
    arduinoPinOnPort[port] = Object.keys(unoPinMappings)
        .filter(pin => unoPinMappings[pin].port === port)
        .sort((a, b) => unoPinMappings[a].pin - unoPinMappings[b].pin);
});

const args = process.argv.slice(2);


const prepareTemporaryDirectory = async () => {
    return new Promise((resolve, reject) => {
        tmp.dir({ unsafeCleanup: true, prefix: 'arduino-sketch-' }, async (err, dirPath, cleanupCallback) => {
            if (err) {
                return reject(new Error(`Failed to create temporary directory: ${err.message}`));
            }

            try {
                resolve(dirPath);
            } catch (error) {
                reject(new Error(`Failed to clean up temporary directory: ${error.message}`));
            }
        });
    });
};



async function compileArduinoSketch(sketchDir) {
    try {
        const librariesFile = path.join(sketchDir, 'libraries.txt');
        if (fs.existsSync(librariesFile)) {
            const libraryContent = await fsp.readFile(librariesFile, 'utf8');
            const libraryList = libraryContent.split('\n')
                .map(line => line.trim())
                .filter(line => line !== '' && !line.startsWith('#'));

            for (const library of libraryList) {
                const installCommand = false // TODO introduce some FLAG if unsafe ops should be enabled
                    ? `arduino-cli config set library.enable_unsafe_install true; arduino-cli lib install --git-url "${library}"`
                    : `arduino-cli lib install "${library}"`;

                const { stdout, stderr } = await execAsync(installCommand);
                if (stderr) {
                    console.error(`Error installing library: ${library}`, stderr);
                }
            }
        }

        const fqbn = process.env.BUILD_FQBN || "arduino:avr:uno";
        const buildExtraFlags = process.env.BUILD_EXTRA_FLAGS
            ? process.env.BUILD_EXTRA_FLAGS
                .split(/\s+(?=-D)/)
                .map(flag => flag.replace(/\\/g, "\\\\").replace(/"/g, '\\"'))
                .map(flag => `"${flag}"`)
                .join(" ")
            : "";
        const buildPropertyFlag = buildExtraFlags
            ? `--build-property "build.extra_flags=${buildExtraFlags}"`
            : "";

        const targetDir = fs.mkdtempSync(path.join(os.tmpdir(), 'arduino-build-'));
        const compileCommand = `arduino-cli compile --fqbn ${fqbn} ${buildPropertyFlag} --output-dir ${targetDir} ${sketchDir}`;
        const { stdout, stderr } = await execAsync(compileCommand);

        if (stderr) {
            console.error('Compiler warnings/errors:', stderr);
        }

        const hexFilename = path.join(targetDir, path.basename(sketchDir)) + '.ino.hex';
        const hexContent = await fsp.readFile(hexFilename);

        fs.rmSync(targetDir, { recursive: true, force: true });
        return hexContent;
    } catch (error) {
        console.error('Error during sketch compilation:', error);
        throw error;
    }
}

const runCode = async (inputFilename, portCallback) => {
    let hexContent = "";

    const fullPath = path.join('/sketch', inputFilename);
    if (inputFilename.endsWith('.hex')) {
        hexContent = fs.readFileSync(inputFilename);
    } else if (inputFilename.endsWith('.zip')) {
        const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'arduino-sketch-'));
        const copyTarget = path.join(tmpDir, 'sketch');
        await extract(fullPath, { dir: copyTarget });
        hexContent = await compileArduinoSketch(copyTarget);
        fs.rmSync(tmpDir, { recursive: true, force: true });
    } else if (inputFilename.endsWith('.ino')) {
        const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'arduino-sketch-'));

        const fileName = path.basename(inputFilename);
        const lastDotIndex = fileName.lastIndexOf('.');
        const baseName = lastDotIndex !== -1 ? fileName.substring(0, lastDotIndex) : fileName;

        const copyTarget = path.join(tmpDir, baseName);
        fs.mkdirSync(copyTarget);

        fs.cpSync(path.join('/sketch', path.dirname(inputFilename)), copyTarget, { recursive: true });

        hexContent = await compileArduinoSketch(copyTarget);
        fs.rmSync(tmpDir, { recursive: true, force: true });
    } else if (fs.existsSync(fullPath) && fs.statSync(fullPath).isDirectory()) {
        const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'arduino-sketch-'));

        const baseName = path.basename(inputFilename);

        const copyTarget = path.join(tmpDir, baseName);
        fs.mkdirSync(copyTarget);

        fs.cpSync(path.join('/sketch', inputFilename), copyTarget, { recursive: true });

        hexContent = await compileArduinoSketch(copyTarget);
        fs.rmSync(tmpDir, { recursive: true, force: true });
    } else {
        hexContent = await compileArduinoSketch(path.dirname(inputFilename));
    }

    if (!hexContent) {
        console.error("Error on compilation");
        return;
    }

    const { data } = intelhex.parse(hexContent);
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
        const arduinoPins = arduinoPinOnPort[portName];
        port.addListener(() => {
            for (let i = 0; i < arduinoPins.length; i++) {
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
                        portCallback({ type: 'pinState', pin: 'D' + arduinoPin, state: state, cpuTime: cpuTime, deprecated: true, note: "pins with D-prefix are deprecated" }); // deprecated
                        entry.lastStatePublished = state;
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
    while (true) {
        if (!isPaused) {
            for (let i = 0; i < 500000; i++) {
                avr8js.avrInstruction(cpu);
                cpu.tick();
            }
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

            // Function to process a port's state
            const processPortState = (portName) => {
                const port = ports[portName];
                const arduinoPins = arduinoPinOnPort[portName];
                for (const arduinoPin in portStates) {
                    const entry = portStates[arduinoPin];
                    const avrPin = arduinoPins.indexOf(arduinoPin) >= 0 ? arduinoPins.indexOf(arduinoPin) : arduinoPins.indexOf('D' + arduinoPin);

                    if (avrPin >= 0) {
                        if (port.pinState(avrPin) === avr8js.PinState.High) {
                            entry.pinHighCycles += (cpu.cycles - entry.lastStateCycles);
                        }
                        if (String(listeningModes[arduinoPin]) === 'analog') {
                            const cyclesSinceUpdate = cpu.cycles - entry.lastUpdateCycles;
                            if (cyclesSinceUpdate > 0) {
                                // TODO fix pwmFrequencies
                                const state = Math.round(entry.pinHighCycles / cyclesSinceUpdate * 255);
                                if (Math.abs(state - entry.lastStatePublished) > MIN_DIFF_TO_PUBLISH) {
                                    const cpuTime = (cpu.cycles / clockFrequency).toFixed(6);
                                    portCallback({ type: 'pinState', pin: arduinoPin, state: state, cpuTime: cpuTime });
                                    portCallback({ type: 'pinState', pin: 'D' + arduinoPin, state: state, cpuTime: cpuTime, deprecated: true, note: "pins with D-prefix are deprecated" }); // deprecated
                                    entry.lastStatePublished = state;
                                }
                            }
                        }
                        entry.lastUpdateCycles = cpu.cycles;
                        entry.lastStateCycles = cpu.cycles;
                        entry.pinHighCycles = 0;
                    }
                }
            };

            processPortState('B');
            processPortState('D');
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
    if (msg.pin && msg.pin.startsWith('D')) msg.pin = msg.pin.substring(1); // deprecated
    const avrPinB = arduinoPinOnPort['B'].indexOf(msg.pin);
    const avrPinC = arduinoPinOnPort['C'].indexOf(msg.pin);
    const avrPinD = arduinoPinOnPort['D'].indexOf(msg.pin);
    if (msg.type === 'pinMode') {
        if (msg.mode === 'analog' || msg.mode === 'pwm') {
            listeningModes[msg.pin] = 'analog';
        } else if (msg.mode === 'digital') {
            listeningModes[msg.pin] = 'digital';
            const cpuTime = (cpu.cycles / clockFrequency).toFixed(6);
            // Immediately publish the current state
            if (avrPinB >= 0) {
                const state = ports['B'].pinState(avrPinB) === avr8js.PinState.High;
                callbackPinState({ type: 'pinState', pin: msg.pin, state: state, cpuTime: cpuTime });
                callbackPinState({ type: 'pinState', pin: 'D' + msg.pin, state: state, cpuTime: cpuTime, deprecated: true, note: "pins with D-prefix are deprecated" }); // deprecated
            } else if (avrPinD >= 0) {
                const state = ports['D'].pinState(avrPinD) === avr8js.PinState.High;
                callbackPinState({ type: 'pinState', pin: msg.pin, state: state, cpuTime: cpuTime });
                callbackPinState({ type: 'pinState', pin: 'D' + msg.pin, state: state, cpuTime: cpuTime, deprecated: true, note: "pins with D-prefix are deprecated" }); // deprecated
            }
        } else {
            listeningModes[msg.pin] = undefined;
        }
    } else if (msg.type === 'fakePinState' || msg.type === 'pinState') {
        if (typeof msg.state === 'boolean') {
            // { "type": "pinState", "pin": "12", "state": true }
            if (avrPinB >= 0) {
                ports['B'].setPin(avrPinB, msg.state);
            } else if (avrPinD >= 0) {
                ports['D'].setPin(avrPinD, msg.state);
            }
        } else if (typeof msg.state === 'number') {
            // { "type": "pinState", "pin": "12", "state": 42 }
            if (avrPinC >= 0) {
                adc.channelValues[avrPinC] = msg.state * 5 / 1024;
            } else if (avrPinD >= 0) {
                adc.channelValues[avrPinD] = msg.state * 5 / 1024;
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
    const callbackPinState = (msg) => {
        wss.clients.forEach(client => {
            if (client !== ws && client.readyState === ws.WebSocket.OPEN) {
                client.send(JSON.stringify(msg));
            }
        });
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
