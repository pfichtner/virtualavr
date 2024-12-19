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
var adc;
const listeningModes = {};
var serialDebug;
var lastPublish = new Date();

// TODO Is there a define in avr8js's boards? PORTB: arduino pins D8,D9,D10,D11,D12,D13,D20,D21
const arduinoPinOnPortB = [ 'D8','D9','D10','D11','D12','D13','D20','D21' ];

// analog ports A0,A1,A2,A3,A4,A5,A6,A7 (19,20,21,22,23,24,25,26)
const arduinoPinOnPortC = [ 'A0','A1','A2','A3','A4','A5','A6','A7' ]


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

        const compileCommand = `arduino-cli compile --fqbn arduino:avr:uno --output-dir ${tempDir} ${sketchDir}`;
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

const runCode = async (inputFilename, portCallback, serialCallback) => {
    let hexContent = "";
    if (inputFilename.endsWith('.hex')) {
        hexContent = fs.readFileSync(inputFilename);
    } else if (inputFilename.endsWith('.zip')) {
        const zip = new streamZip.async({ file: inputFilename });

	const sketchBuf = await zip.entryData('sketch.ino');
        const sketchContent = sketchBuf.toString();

	const librariesBuf = await zip.entryData('libraries.txt');
        const libraryContent = librariesBuf.toString();

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
    const cpu = new avr8js.CPU(new Uint16Array(progData.buffer));
    // Attach the virtual hardware
    portB = new avr8js.AVRIOPort(cpu, avr8js.portBConfig);
    const portStates = {};
    portB.addListener(() => {
// console.log("portB");
	    for (let pin = 0; pin < arduinoPinOnPortB.length; pin++) {
		    const arduinoPin = arduinoPinOnPortB[pin];
		    const state = portB.pinState(pin) === avr8js.PinState.High;

		    let entry = portStates[arduinoPin];
		    if (entry === undefined) {
			    entry = { lastState: undefined, lastStateCycles: 0, lastUpdateCycles: 0, pinHighCycles: 0 };
			    portStates[arduinoPin] = entry
		    }
		    if (entry.lastState !== state) {
			    if (entry.lastState) {
				    entry.pinHighCycles += (cpu.cycles - entry.lastStateCycles);
			    }
			    entry.lastState = state;
			    entry.lastStateCycles = cpu.cycles;
			    if (listeningModes[arduinoPinOnPortB[pin]] === 'digital') {
				    // TODO should we move all publishs out of the callback (also the digitals)?
				    // TODO throttle if there are to much messages (see lastStateCycles)
				    portCallback(arduinoPin, state);
				    entry.lastStatePublished = state;
			    }
		    }
	    }
    });

    adc = new avr8js.AVRADC(cpu, avr8js.adcConfig);

    const usart = new avr8js.AVRUSART(cpu, avr8js.usart0Config, 16e6);
    usart.onByteTransmit = data => {
	    const arrBuff = new Uint8Array(1);
	    arrBuff[0] = data;
	    process.stdout.write(arrBuff);
	    if (serialDebug) {
		    serialCallback('TX', [ data ]);
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
		    serialCallback('RX', bytes);
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
	    for (const pin in portStates) {
		const entry = portStates[pin];
		const avrPin = arduinoPinOnPortB.indexOf(pin);
		if (portB.pinState(avrPin) === avr8js.PinState.High) {
			entry.pinHighCycles += (cpu.cycles - entry.lastStateCycles);
		}
		if (listeningModes[pin] === 'analog') {
			const cyclesSinceUpdate = cpu.cycles - entry.lastUpdateCycles;
			const state = Math.round(entry.pinHighCycles / cyclesSinceUpdate * 255);
			if (Math.abs(state - entry.lastStatePublished) > MIN_DIFF_TO_PUBLISH) {
				portCallback(pin, state);
			}
			entry.lastStatePublished = state;
		}
		entry.lastUpdateCycles = cpu.cycles;
		entry.lastStateCycles = cpu.cycles;
		entry.pinHighCycles = 0;
	    }
	}
    }
}

function sendNextChar(buff, usart) {
	const ch = buff.shift();
	if (ch !== undefined) {
		usart.writeByte(ch);
	}
}

function processMessage(obj, callbackPinState) {
    // { "type": "pinMode", "pin": "D12", "mode": "analog" }
    if (obj.type === 'pinMode') {
        if (obj.mode === 'analog' || obj.mode === 'pwm') {
            listeningModes[obj.pin] = 'analog';
        } else if (obj.mode === 'digital') {
            listeningModes[obj.pin] = 'digital';
            // immediately publish the current state
            const avrPin = arduinoPinOnPortB.indexOf(obj.pin);
            if (avrPin >= 0) {
                const state = portB.pinState(avrPin) === avr8js.PinState.High;
                callbackPinState(obj.pin, state);
            }
        } else {
            listeningModes[obj.pin] = undefined;
        }
    } else if (obj.type === 'fakePinState' || obj.type === 'pinState') {
        // { "type": "pinState", "pin": "D12", "state": true }
        if (typeof obj.state === 'boolean') {
            const avrPin = arduinoPinOnPortB.indexOf(obj.pin);
            if (avrPin >= 0)
                portB.setPin(avrPin, obj.state == 1);
        }

        // { "type": "pinState", "pin": "D12", "state": 42 }
        if (typeof obj.state === 'number') {
            const avrPin = arduinoPinOnPortC.indexOf(obj.pin);
            if (adc && avrPin >= 0) {
                adc.channelValues[avrPin] = obj.state * 5 / 1024;
            }
        }
    } else if (obj.type === 'serialDebug') {
        serialDebug = obj.state;
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
	const callbackPinState = (pin, state) => {
		wss.clients.forEach(client => {
			if (client !== ws && client.readyState === ws.WebSocket.OPEN) {
				client.send(JSON.stringify({ type: 'pinState', pin, state}));
			}
		});
	};
	const callbackSerialDebug = (direction, bytes) => {
		wss.clients.forEach(client => {
			if (client !== ws && client.readyState === ws.WebSocket.OPEN) {
				client.send(JSON.stringify({ type: 'serialDebug', direction, bytes}));
			}
		});
	};

       wss.on('connection', function connection(ws) {
               ws.on('message', function message(data) {
                  messageQueue.push(JSON.parse(data));
               });
       });




	runCode(args.length == 0 ? 'sketch.ino' : args[0], callbackPinState, callbackSerialDebug);
}

if (require.main === module) {
	main();
}

module.exports = {
	runCode
}

