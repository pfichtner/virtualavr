const fs = require("fs");
const fetch = require('node-fetch');
const avr8js = require('avr8js');

// import { CPU, avrInstruction, AVRIOPort, portDConfig, PinState, AVRTimer, timer0Config } from 'avr8js';

const arduinoCode = fs.readFileSync("code.ino").toString();

const runCode = async () => {
        // Compile the arduino source code
        const result = await fetch('https://hexi.wokwi.com/build', {
                method: 'post',
                body: JSON.stringify({ sketch: arduinoCode }),
                headers: { 'Content-Type': 'application/json' }
        });
        const { hex, stderr } = await result.json();
        if (!hex) {
                console.log(stderr);
                return;
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

