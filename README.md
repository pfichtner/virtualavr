
Start the container
```docker run -v /dev:/dev -d virtualavr```

Connect to virtual serial device
```minicom -D /dev/virtualavr0```

Full example, you can pass the devicename as well the code that gets compiled and the executed on the cirtual AVR
```docker run -e VIRTUALDEVICE=/dev/mydemoavr -v /dev:/dev -v /path/to/myArduinoCode.ino:/app/code.ino -d virtualavr```

TODOS
- Compile local instead of cloud service
- Possibility to define component layout, e.g. add a DHT22
- JS Callbacks for pin states/Components, e.g. DHT22
- Java-Bindings for pin states/Components, e.g. DHT22

