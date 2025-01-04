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
const streamZip = require('node-stream-zip');

const ws = require('ws');

// import { CPU, avrInstruction, AVRIOPort, portDConfig, PinState, AVRTimer, timer0Config } from 'avr8js';

const PUBLISH_MILLIS = process.env.PUBLISH_MILLIS || 250;
const MIN_DIFF_TO_PUBLISH = process.env.MIN_DIFF_TO_PUBLISH || 0;

let messageQueue = [];
var portB;
var portD;
var cpu;
var adc;
const listeningModes = {};
var serialDebug;
var lastPublish = new Date();

// TODO Is there a define in avr8js's boards? 
const arduinoPinOnPortB = [ 'D8', 'D9', 'D10','D11','D12', 'D13' ];
const arduinoPinOnPortC = [ 'A0', 'A1', 'A2', 'A3', 'A4',  'A5', 'A6', 'A7' ]
const arduinoPinOnPortD = [ 'D0', 'D1', 'D2', 'D3', 'D4',  'D5', 'D6', 'D7' ];
const clockFrequency = 16e6;  // 16 MHz
// TODO const pwmFrequencies = { 'D5': 980, 'D6': 980 }; // others 490

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


const compileArduinoSketch = async (inputFilename, sketchContent, libraryContent) => {
    try {
        if (libraryContent) {
            const libraryList = libraryContent.split('\n')
                .map(line => line.trim())
                .filter(line => line !== '' && !line.startsWith('#'));

            for (const library of libraryList) {
                const installCommand = `arduino-cli lib install "${library}"`;
                const { stdout, stderr } = await execAsync(installCommand);

                if (stderr) {
                    console.error(`Error installing library: ${library}`, stderr);
                }
            }
        }

        const tempDir = await prepareTemporaryDirectory();  // Generate a unique temporary directory
        const sketchName = path.basename(inputFilename, '.ino');  // Get the sketch name without the extension
        const sketchDir = path.join(tempDir, sketchName);  // Create a subdirectory with the sketch name

        if (!fs.existsSync(sketchDir)) {
            fs.mkdirSync(sketchDir, { recursive: true });
        }

        const sketchFilePath = path.join(sketchDir, inputFilename);
        await fsp.writeFile(sketchFilePath, sketchContent);

        const buildExtraFlags = process.env.BUILD_EXTRA_FLAGS
            ? process.env.BUILD_EXTRA_FLAGS
                .split(/\s+(?=-D)/)
                .map(flag => flag.replace(/\\/g, "\\\\").replace(/"/g, '\\"'))
                .map(flag => `\\"${flag}\\"`)
                .join(" ")
            : "";
        const buildPropertyFlag = buildExtraFlags
            ? `--build-property "build.extra_flags=${buildExtraFlags}"`
            : "";
        const compileCommand = `arduino-cli compile --fqbn arduino:avr:uno ${buildPropertyFlag} --output-dir ${tempDir} ${sketchDir}`;
        const { stdout, stderr } = await execAsync(compileCommand);

        if (stderr) {
            console.error('Compiler warnings/errors:', stderr);
        }

        const hexFilename = path.join(tempDir, `${inputFilename}.hex`);
        return await fsp.readFile(hexFilename);
    } catch (error) {
        console.error('Error during sketch compilation:', error);
        throw error; // Re-throw error for upstream handling
    }
};

const runCode = async (inputFilename, portCallback) => {
    let hexContent = "";
    if (inputFilename.endsWith('.hex')) {
        hexContent = fs.readFileSync(inputFilename);
    } else if (inputFilename.endsWith('.zip')) {
        const zip = new streamZip.async({ file: inputFilename });

        const sketchBuf = await zip.entryData('sketch.ino');
        const sketchContent = sketchBuf.toString();

        if (await zip.entry('libraries.txt')) {
            const librariesBuf = await zip.entryData('libraries.txt');
            libraryContent = librariesBuf.toString();
        } else {
            libraryContent = undefined;
        }

        await zip.close();
        hexContent = await compileArduinoSketch('sketch.ino', sketchBuf.toString(), libraryContent);
    } else {
        const sketchContent = fs.readFileSync(inputFilename).toString();
        hexContent = await compileArduinoSketch(inputFilename, sketchContent);
    }

    if (!hexContent) {
        console.error("Error on compilation");
        return;
    }

    const { data } = intelhex.parse(hexContent);
    const progData = new Uint8Array(data);

    // Set up the simulation
    cpu = new avr8js.CPU(new Uint16Array(progData.buffer));
    // Attach the virtual hardware
    portB = new avr8js.AVRIOPort(cpu, avr8js.portBConfig);
    portD = new avr8js.AVRIOPort(cpu, avr8js.portDConfig);
    adc = new avr8js.AVRADC(cpu, avr8js.adcConfig);

    const portStates = {};
    const handlePort = (port, arduinoPins, portCallback) => {
        port.addListener(() => {
            for (let pin = 0; pin < arduinoPins.length; pin++) {
                const arduinoPin = arduinoPins[pin];
                const state = port.pinState(pin) === avr8js.PinState.High;

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
        });
    };
    handlePort(portB, arduinoPinOnPortB, portCallback);
    handlePort(portD, arduinoPinOnPortD, portCallback);

    const usart = new avr8js.AVRUSART(cpu, avr8js.usart0Config, clockFrequency);
    usart.onByteTransmit = data => {
            const arrBuff = new Uint8Array(1);
            arrBuff[0] = data;
            process.stdout.write(arrBuff);
            if (serialDebug) {
                portCallback({ type: 'serialDebug', direction: 'TX', bytes: [data] });
            }
    }
    const buff = [];
    usart.onRxComplete = () => sendNextChar(buff, usart);
    process.stdin.setRawMode(true);
    process.stdin.on('data', data => {
            var bytes = Array.prototype.slice.call(data, 0);
            for (let i = 0; i < bytes.length; i++) buff.push(bytes[i]);
            sendNextChar(buff, usart);
            if (serialDebug) {
                portCallback({ type: 'serialDebug', direction: 'RX', bytes: bytes });
            }
    });

    new avr8js.AVRTimer(cpu, avr8js.timer0Config);
    new avr8js.AVRTimer(cpu, avr8js.timer1Config);
    new avr8js.AVRTimer(cpu, avr8js.timer2Config);
    while (true) {
        for (let i = 0; i < 500000; i++) {
            avr8js.avrInstruction(cpu);
            cpu.tick();
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
            const processPortState = (port, arduinoPins) => {
                for (const arduinoPin in portStates) {
                    const entry = portStates[arduinoPin];
                    const avrPin = arduinoPins.indexOf(arduinoPin);

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

            processPortState(portB, arduinoPinOnPortB);
            processPortState(portD, arduinoPinOnPortD);
        }
    }
}

function sendNextChar(buff, usart) {
    const ch = buff.shift();
    if (ch !== undefined) {
        usart.writeByte(ch);
    }
}

function processMessage(msg, callbackPinState) {
    // { "type": "pinMode", "pin": "D12", "mode": "analog" }
    const avrPinB = arduinoPinOnPortB.indexOf(msg.pin);
    const avrPinC = arduinoPinOnPortC.indexOf(msg.pin);
    const avrPinD = arduinoPinOnPortD.indexOf(msg.pin);
    if (msg.type === 'pinMode') {
        if (msg.mode === 'analog' || msg.mode === 'pwm') {
            listeningModes[msg.pin] = 'analog';
        } else if (msg.mode === 'digital') {
            listeningModes[msg.pin] = 'digital';
            const cpuTime = (cpu.cycles / clockFrequency).toFixed(6);
            // Immediately publish the current state
            if (avrPinB >= 0) {
                const state = portB.pinState(avrPinB) === avr8js.PinState.High;
                callbackPinState({ type: 'pinState', pin: msg.pin, state: state, cpuTime: cpuTime });
            } else if (avrPinD >= 0) {
                const state = portD.pinState(avrPinD) === avr8js.PinState.High;
                callbackPinState({ type: 'pinState', pin: msg.pin, state: state, cpuTime: cpuTime });
            }
        } else {
            listeningModes[msg.pin] = undefined;
        }
    } else if (msg.type === 'fakePinState' || msg.type === 'pinState') {
        if (typeof msg.state === 'boolean') {
            // { "type": "pinState", "pin": "D12", "state": true }
            if (avrPinB >= 0) {
                portB.setPin(avrPinB, msg.state);
            } else if (avrPinD >= 0) {
                portD.setPin(avrPinD, msg.state);
            }
        } else if (typeof msg.state === 'number') {
            // { "type": "pinState", "pin": "D12", "state": 42 }
            if (avrPinC >= 0) {
                adc.channelValues[avrPinC] = msg.state * 5 / 1024;
            } else if (avrPinD >= 0) {
                adc.channelValues[avrPinD] = msg.state * 5 / 1024;
            }
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
            try {
                messageQueue.push(JSON.parse(data));
            } catch (e) {
                console.log(e);
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
