[![Publish Docker image](https://github.com/pfichtner/virtualavr/actions/workflows/docker-publish.yml/badge.svg)](https://github.com/pfichtner/virtualavr/actions/workflows/docker-publish.yml)
[![Docker Pulls](https://img.shields.io/docker/pulls/pfichtner/virtualavr.svg?maxAge=604800)](https://hub.docker.com/r/pfichtner/virtualavr/)

### virtualavr

A AVR/Arduino Simulator based on [avr8js](https://github.com/wokwi/avr8js) with focus on automated tests. 
- You want to test your microcontroller program on an integration level without flashing a real microprocessor every time? 
- You want to test some code that interacts with a microprocessor but you want to test without having real hardware connected and flashed (e.g. on a ci server)?

This is where virtualavr comes into play

virtualavr comes as a Docker image that provides a virtual AVR including a virtual serial device which you can connect to just like to real hardware. 


Start the container (will load the included blink sketch)
```docker run -v /dev:/dev -d pfichtner/virtualavr```

Connect to virtual serial device
```minicom -D /dev/virtualavr0```

Full example, you can pass the devicename as well the code that gets compiled and the executed on the virtual AVR
```docker run -e VIRTUALDEVICE=/dev/ttyUSB0 -e FILENAME=myArduinoSketch.ino -v /dev:/dev -v /path/of/the/sketch:/sketch -d pfichtner/virtualavr```

Environment variables supported
- VIRTUALDEVICE the full path of the virtual device that socat creates (defaults to /dev/virtualavr0)
- FILENAME the name of the ino/hex/zip file (defaults to sketch.ino). Zipfile content is wokwi structure (sketch.ino, libraries.txt). If the filename ends with '.hex' it gets passed to virtualavr directly
- BAUDRATE baudrate to use (defaults to 9600). Hint: If haven't seen problems when baudrate differs from the really used one
- VERBOSITY verbosity args for socat e.g. "-d -d -v" see man socat for more infos

# Screencast of usage
<a href="http://pfichtner.github.io/virtualavr-asciinema/"><img src="https://pfichtner.github.io/virtualavr-asciinema/asciinema-poster.png" /></a>


# What's inside? How does it work? 
- The heart is [avr8js](https://github.com/wokwi/avr8js)
- virtualavr.js runs inside a node process, and links nodejs' stdin/stdout to avr8js' virtual serial port
- [socat](http://www.dest-unreach.org/socat/) creates a virtual serial port on the local machine (better said inside the docker container) and links this virtual serial port to nodejs' stdin/stdout. That way you get a virtual serial port which is connected to the serial port of the simulator (avr8js)
- Due to the whole thing is packaged inside a docker container the serial port is inside that docker container, too (and only). So you have to do volume mounts (-v /dev:/dev) so that you get access to the "in-docker-device" on your local computer 
- Virtualavr starts a websocket server you can connect to. Using that websocket connection you can control the states of the analog/digital pins as well cou get informed about things hapening on the virtual AVR e.g. state changes of the pins 

![virtualavr.png](docs/images/virtualavr.png)

# Todos
- Add write support for digital PWM pins! 
- Add support for running simulator without VIRTUALDEVICE
- Expose SerialRX/SerialTX events (and have tests for them)
- Compile local instead of cloud service, using https://arduino.github.io/arduino-cli/0.22/installation/ and https://www.npmjs.com/package/arduino-cli
- Add an example (jest?): How to test firmware, e.g. firmware reading DHT22 values and writing infos/warnings to console/SSD1306
- Add an example (jest?): How to test some JS that interacts with firmware (e.g. firmata)
- We could use WS to interact with the simulator: "loadFirmware", "start", "stop", ...
- Possibility to define component layout, e.g. add a DHT22
- JS Callbacks for pin states/Components, e.g. DHT22
- Java-Bindings for pin states/Components, e.g. DHT22 (IPC, using websockets?)
- Could we implement upload? So that you can upload the compiled firmware to runniner container / /dev/virtualdevice?
  Could we use arduino firmware? https://github.com/arduino/ArduinoCore-avr/tree/master/bootloaders/atmega : If this works? Do we have to upload elf binaries?

