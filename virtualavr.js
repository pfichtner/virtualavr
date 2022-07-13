const fs = require("fs");
const fetch = require('node-fetch');
const avr8js = require('avr8js');

// import { CPU, avrInstruction, AVRIOPort, portDConfig, PinState, AVRTimer, timer0Config } from 'avr8js';

var portB;

// TODO Is there a define in avr8js's boards? PORTB: arduino pins 8,9,10,11,12,13,20,21 ; avr pins 14,15,16,17,18,19,9,10
const arduinoPinOnPortB = [ 8,9,10,11,12,13,20,21 ];


const args = process.argv.slice(2);

const runCode = async (inputFilename, portCallback) => {
	let fileContent = fs.readFileSync(inputFilename).toString();

	if (!inputFilename.endsWith('.hex')) {
		const result = await fetch('https://hexi.wokwi.com/build', {
			method: 'post',
			body: JSON.stringify({ sketch: fileContent }),
			headers: { 'Content-Type': 'application/json' }
		});
		const { hex, stderr } = await result.json();
		if (!hex) {
			console.log(stderr);
			return;
		}
		fileContent = hex;
	}

	const { data } = require('intel-hex').parse(fileContent);
	const progData = new Uint8Array(data);

	// Set up the simulation
	const cpu = new avr8js.CPU(new Uint16Array(progData.buffer));
	// Attach the virtual hardware
	portB = new avr8js.AVRIOPort(cpu, avr8js.portBConfig);
	const portStates = {};
	portB.addListener(() => {
		for (let pin = 0; pin <= 7; pin++) {
			const arduinoPin = arduinoPinOnPortB[pin];
			const state = portB.pinState(pin) === avr8js.PinState.High;
			const oldState = portStates[arduinoPin];
			if (oldState != undefined && oldState != state) {
				portCallback(arduinoPin, state ? '1' : '0');
			}
			portStates[arduinoPin] = state;
		}
	});

	const usart = new avr8js.AVRUSART(cpu, avr8js.usart0Config, 16e6);
	usart.onByteTransmit = data => process.stdout.write(String.fromCharCode(data));
	const buff = [];
	usart.onRxComplete = () => sendNextChar(buff, usart);
	process.stdin.on('data', data => {
		const bytes = data.toString();
		for (let i = 0; i < bytes.length; i++) buff.push(bytes.charCodeAt(i));
		sendNextChar(buff, usart);
	});

	const timer = new avr8js.AVRTimer(cpu, avr8js.timer0Config);
	while (true) {
		for (let i = 0; i < 500000; i++) {
			avr8js.avrInstruction(cpu);
			cpu.tick();
		}
		await new Promise(resolve => setTimeout(resolve));
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
	const ws = require('ws');
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
				// client.send(`pinState(${pin},${state})`);
				client.send(JSON.stringify({ type: 'pinState', pin: pin, state: state}));
			}
		});

	};

       wss.on('connection', function connection(ws) {
               ws.on('message', function message(data) {
		try {
                      const obj = JSON.parse(data);
                      if (obj.type == 'fakePinState') {
                              const avrPin = arduinoPinOnPortB.indexOf(obj.pin);
                              if (avrPin >= 0)
                              // TODO How to set analog values?
                              portB.setPin(avrPin, obj.state == 1);
                      }
		} catch (e) {
			console.log(e);
		}
               });
       });




	runCode(args.length == 0 ? 'code.ino' : args[0], callback);
}

if (require.main === module) {
	main();
}

module.exports = {
	runCode
}
