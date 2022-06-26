
Start the container
```docker run -v /dev:/dev -d virtualavr```

Connect to virtual serial device
```minicom -D /dev/virtualavr0```

TODOS
- Fix newline behaviour
- Dumb default app (code.ino) (can be overriden -v /my/code.foo:/app/code.ino)
- Compile local instead of cloud service
- Possibility to define component layout, e.g. add a DHT22
- JS Callbacks for pin states/Components, e.g. DHT22
- Java-Bindings for pin states/Components, e.g. DHT22

