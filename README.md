# Treenail - CoreDSL frontend for Shortnail

## Setup

Pull in the CoreDSL frontend:
```
$ git submodule update --init
```

Treenail is a standard Gradle project (including the `./gradlew` wrapper):
```
$ gradle build
$ gradle test
$ gradle run --args <path/to/a.core_desc>
$ gradle install
$ ./app/build/install/app/bin/app <path/to/a.core_desc> -o isax.mlir
```

## License
Treenail is available under the Apache License v2.0, developed as part of the Scale4Edge project.
