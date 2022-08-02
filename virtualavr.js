const fs = require("fs");
const fetch = require('node-fetch');
const avr8js = require('avr8js');
const intelhex = require('intel-hex');

const streamZip = require('node-stream-zip');

const ws = require('ws');

// import { CPU, avrInstruction, AVRIOPort, portDConfig, PinState, AVRTimer, timer0Config } from 'avr8js';

var portB;
var adc;

// TODO Is there a define in avr8js's boards? PORTB: arduino pins D8,D9,D10,D11,D12,D13,D20,D21
const arduinoPinOnPortB = [ 'D8','D9','D10','D11','D12','D13','D20','D21' ];

// analog ports A0,A1,A2,A3,A4,A5,A6,A7 (19,20,21,22,23,24,25,26)
const arduinoPinOnPortC = [ 'A0','A1','A2','A3','A4','A5','A6','A7' ]


const args = process.argv.slice(2);

const runCode = async (inputFilename, portCallback) => {
	let sketch = fs.readFileSync(inputFilename).toString();
	let files = [];

	if (!inputFilename.endsWith('.hex')) {
		if (inputFilename.endsWith('.zip')) {
				const zip = new streamZip.async({ file: inputFilename });
				const sketchBuf = await zip.entryData('sketch.ino');
				sketch = sketchBuf.toString();
				const libraries = await zip.entryData('libraries.txt');
				files.push({ name: "libraries.txt", content: libraries.toString() });
				await zip.close();
		}
		const result = await fetch('https://hexi.wokwi.com/build', {
			method: 'post',
			body: JSON.stringify({ board: "uno", sketch, files }),
			headers: { 'Content-Type': 'application/json' }
		});
		const { hex, stderr } = await result.json();
		if (!hex) {
			console.log(stderr);
			return;
		}
		sketch = hex;
	}

	const { data } = intelhex.parse(sketch);
	const progData = new Uint8Array(data);

	// Set up the simulation
	const cpu = new avr8js.CPU(new Uint16Array(progData.buffer));
	// Attach the virtual hardware
	portB = new avr8js.AVRIOPort(cpu, avr8js.portBConfig);
	const portStates = {};
	portB.addListener(() => {
// console.log("portB");
		for (let pin = 0; pin <= 7; pin++) {
			const arduinoPin = arduinoPinOnPortB[pin];
			const state = portB.pinState(pin) === avr8js.PinState.High;


			let entry = portStates[arduinoPin];
			if (entry === undefined) {
				entry = { lastStateCycles: 0, lastUpdateCycles: 0, ledHighCycles: 0 };
				portStates[arduinoPin] = entry
			}
			if (entry.lastState === undefined ? state == avr8js.PinState.High : entry.lastState !== state) {
				portCallback(arduinoPin, state);
			}
			if (entry.lastState === undefined || entry.lastState !== state) {
// TODO why does === do not work here?
				if (entry.lastState == avr8js.PinState.High) {
					const delta = cpu.cycles - entry.lastStateCycles;
					entry.ledHighCycles += delta;
				}
				entry.lastState = state;
				entry.lastStateCycles = cpu.cycles;
			}
		}
	});

	adc = new avr8js.AVRADC(cpu, avr8js.adcConfig);

	const usart = new avr8js.AVRUSART(cpu, avr8js.usart0Config, 16e6);
	usart.onByteTransmit = data => process.stdout.write(String.fromCharCode(data));
	const buff = [];
	usart.onRxComplete = () => sendNextChar(buff, usart);
	process.stdin.on('data', data => {
		const bytes = data.toString();
		for (let i = 0; i < bytes.length; i++) buff.push(bytes.charCodeAt(i));
		sendNextChar(buff, usart);
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

		for (const led in portStates) {
			const entry = portStates[led];
			const cyclesSinceUpdate = cpu.cycles - entry.lastUpdateCycles;
                        const avrPin = arduinoPinOnPortB.indexOf(led);
			// TODO why does === do not work here?
			if (portB.pinState(avrPin) == avr8js.PinState.High) {
				entry.ledHighCycles += cpu.cycles - entry.lastStateCycles;
			}
			// console.log(led + ".value = " + entry.ledHighCycles > 0);
			// console.log(led + ".brightness = " + Math.round(entry.ledHighCycles / cyclesSinceUpdate * 255));
			entry.lastUpdateCycles = cpu.cycles;
			entry.lastStateCycles = cpu.cycles;
			entry.ledHighCycles = 0;
		}


	}

}

function sendNextChar(buff, usart) {
	const ch = buff.shift();
	if (ch) {
		usart.writeByte(ch);
	}
}

function main() {
	// const callback = (pin, state) => {};
	const wss = new ws.WebSocketServer({
		port: 8080,
		perMessageDeflate: {
			concurrencyLimit: 2, // Limits zlib concurrency for perf.
			threshold: 1024 // Size (in bytes) below which messages
		}
	});
	const callback = (pin, state) => {
		wss.clients.forEach(function each(client) {
			if (client !== ws && client.readyState === ws.WebSocket.OPEN) {
				client.send(JSON.stringify({ type: 'pinState', pin, state}));
			}
		});

	};

       wss.on('connection', function connection(ws) {
               ws.on('message', function message(data) {
		try {
                      const obj = JSON.parse(data);
                      if (obj.type == 'fakePinState') {
                              if (typeof obj.state === 'boolean') {
				      const avrPin = arduinoPinOnPortB.indexOf(obj.pin);
				      if (avrPin >= 0)
					      portB.setPin(avrPin, obj.state == 1);
			      }

                              if (typeof obj.state === 'number') {
				      const avrPin = arduinoPinOnPortC.indexOf(obj.pin);
				      if (avrPin >= 0) {
					      adc.channelValues[avrPin] = obj.state * 5 / 1024;
				      }
                              }

                      }
		} catch (e) {
			console.log(e);
		}
               });
       });




	runCode(args.length == 0 ? 'sketch.ino' : args[0], callback);
}

if (require.main === module) {
	main();
}

module.exports = {
	runCode
}
