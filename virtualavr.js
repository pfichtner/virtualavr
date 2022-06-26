const yargs = require('yargs');

const fs = require("fs");
const fetch = require('node-fetch');
const avr8js = require('avr8js');

// import { CPU, avrInstruction, AVRIOPort, portDConfig, PinState, AVRTimer, timer0Config } from 'avr8js';

const argv = yargs
	.command('lyr', 'Tells whether an year is leap year or not', {}
	)
	.option('inputfile', {
		alias: 'i',
		description: 'The path of the sketch file (can be hex) to read',
		type: 'String', 
		// TODO when passed, this will become an array!?
		default: "code.ino"
	})
	.option('ishex', {
		alias: 'b',
		description: 'Do not compile the input file since it is hex',
		type: 'boolean'
	})
// .option('type', {
// 	alias: 't',
// 	description: 'Type of the input file',
// 	type: 'boolean'
// })
	.help()
	.alias('help', 'h').argv;


const runCode = async () => {
	const fileContent = fs.readFileSync(argv.inputfile).toString();
	var hex;
	if (argv.ishex) {
		hex = fileContent;
	} else {
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
	}

	const { data } = require('intel-hex').parse(hex);
	const progData = new Uint8Array(data);

	// Set up the simulation
	const cpu = new avr8js.CPU(new Uint16Array(progData.buffer));
	// Attach the virtual hardware
	// const port = new avr8js.AVRIOPort(cpu, avr8js.portDConfig);
	// port.addListener(() => {
	//      const state = port.pinState(7) === avr8js.PinState.High;
	//      console.log('LED', state);
	// });

	const usart = new avr8js.AVRUSART(cpu, avr8js.usart0Config, 16e6);
	usart.onByteTransmit = (value) => process.stdout.write(String.fromCharCode(value));

	process.stdin.setRawMode(true);
	process.stdin.on('data', data => {
		const bytes = data.toString();
		for (var i = 0; i < bytes.length; i++) usart.writeByte(bytes.charCodeAt(i));
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

runCode();

