# Project Treenail - CoreDSL frontend for Longnail

## Setup

Install the CoreDSL (Xtext) parser in your local Maven repository. You [currently](https://github.com/Minres/CoreDSL/issues/33) need a **Java 11 VM** for this step.
```
$ git clone -b develop https://github.com/Minres/CoreDSL.git ; cd CoreDSL
$ mvn -pl "!com.minres.coredsl.ui.tests" -B install --file pom.xml
```

From there, Treenail is a standard Gradle project (including the `./gradlew` wrapper), and compatible with newer JVMs as well.
```
$ gradle build
$ gradle test
$ gradle run --args <path/to/a.core_desc>
```

## License
Treenail is proprietary software, developed as part of the Scale4Edge project.
```
Copyright 2022 Embedded Systems and Applications Group
               Department of Computer Science
               Technical University of Darmstadt, Germany
```
