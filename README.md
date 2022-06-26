
Start the container
```docker run -v /dev:/dev -d virtualavr```

Connect to virtual serial device
```minicom -D /dev/virtualavr0```

Full example, you can pass the devicename as well the code that gets compiled and the executed on the cirtual AVR
```docker run -e VIRTUALDEVICE=/dev/ttyUSB0 -v /dev:/dev -v /path/to/myArduinoCode.ino:/app/code.ino -d virtualavr```

Environment variables supported
- VIRTUALDEVICE the full path of the virtual device that socat creates
- VERBOSITY verbosity args for socat e.g. "-d -d -v" see man socat for more infos

TODOS
- Compile local instead of cloud service, using https://arduino.github.io/arduino-cli/0.22/installation/ and https://www.npmjs.com/package/arduino-cli
- Possibility to define component layout, e.g. add a DHT22
- JS Callbacks for pin states/Components, e.g. DHT22
- Java-Bindings for pin states/Components, e.g. DHT22

