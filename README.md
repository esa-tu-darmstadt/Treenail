# Treenail - CoreDSL frontend for Longnail

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
$ ./app/build/install/app/bin/app <path/to/a.core_desc> isax.mlir
```

## License
Treenail is proprietary software, developed as part of the Scale4Edge project.
```
Copyright 2022 Embedded Systems and Applications Group
               Department of Computer Science
               Technical University of Darmstadt, Germany
```
