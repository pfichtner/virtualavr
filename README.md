### virtualavr

A AVR/Arduino Simulator based on [avr8js](https://github.com/wokwi/avr8js) with focus on automated tests. 
- You want to test your program on an integration level without flashing a real microprocessor every time? 
- You want to test some code that interacts with a microprocessor but you want to test without having real hardware connected (e.g. on a ci server)?

This is where virtualavr comes into play


Start the container
```docker run -v /dev:/dev -d virtualavr```

Connect to virtual serial device
```minicom -D /dev/virtualavr0```

Full example, you can pass the devicename as well the code that gets compiled and the executed on the cirtual AVR
```docker run -e VIRTUALDEVICE=/dev/ttyUSB0 -v /dev:/dev -v /path/to/myArduinoCode.ino:/app/code.ino -d virtualavr```

Environment variables supported
- VIRTUALDEVICE the full path of the virtual device that socat creates
- VERBOSITY verbosity args for socat e.g. "-d -d -v" see man socat for more infos

# Screencast of usage
<a href="http://pfichtner.github.io/virtualavr-asciinema/"><img src="https://pfichtner.github.io/virtualavr-asciinema/asciinema-poster.png" /></a>

# Todos
- Compile local instead of cloud service, using https://arduino.github.io/arduino-cli/0.22/installation/ and https://www.npmjs.com/package/arduino-cli
- Add an example (jest?): How to test firmware, e.g. firmware reading DHT22 values and writing infos/warnings to console/SSD1306
- Add an example (jest?): How to test some JS that interacts with firmware (e.g. firmata)
- We could use WS to interact with the simulator: "loadFirmware", "start", "stop", ...
- Possibility to define component layout, e.g. add a DHT22
- JS Callbacks for pin states/Components, e.g. DHT22
- Java-Bindings for pin states/Components, e.g. DHT22 (IPC, using websockets?)
- Could we implement upload? So that you can upload the compiled firmware to runniner container / /dev/virtualdevice?
  Could we use arduino firmware? https://github.com/arduino/ArduinoCore-avr/tree/master/bootloaders/atmega : If this works? Do we have tu upload elf binaries?

