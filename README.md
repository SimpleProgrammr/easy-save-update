# Easy Safe Update — Serwer HTTPS w Java

> Server made by XD team. Server Is a provider of update files for our solution

---

## Contents

1. [Basic info](#1-basic-info)
2. [Architecture](#2-architecture)
3. [Requirements](#3-requirements)
4. [SSL/TLS Configuration](#4-ssltls-configuration)
5. [Installation and execution](#5-installation-and-execution)
6. [API Endpoints](#6-api-endpoint)
7. [FAQ](#7-known-problems-and-faq)
8. [Authors](#8-authors)

---

## 1. Basic info

|                 | Value                             |
|-----------------|-----------------------------------|
| Project version | `0.1.0`                           |
| Java version    | Java 24                           |
| Default port    | `8443`                            |
| Status          | np. W trakcie rozwoju / Produkcja |

### Projects goal

Successful completion of the hackaton quest

---

## 2. Architecture

### Components

```
[ Client ]
    |
    | HTTPS
    |
[ HTTPS Java server ]
    |
    ├── [ Version control ]
    ├── [ Cohort managment ]
    └── [ Update distributuion ]
```

### Components description

| Warstwa          | Technologia |
|------------------|-------------|
| Warstwa HTTP     | HTTPS 1.1   |
| Cohort managment | Self-built  |
| Cohort managment | Self-built  |

### Project structure
```
src/
├── HttpsServer.java
updates/
└── <Device ID>
     ├── conf.txt
     └── <New update version>.xdu
```

- HttpsServer.java contains all code for this simple HTTPS server
- conf.txt is a list of 16 cohort IDs with corresponding to them file name with update. This approach ensures that updates are rolled out in controlled speed.
- \<Device ID> is a folder named using 5 characters representing an app distribution(first 5 characters in serial number). It gives easy way to maintain more than one variation of software
- \<New update version>.xdu is a file containing update

---

## 3. Requirements

### Environmental requirements

- **Java:** `>= 23`
- **Internet connection**

### Environmental variables (Should be changed)

| Variable           | Description          | Wartość domyślna  |
|--------------------|----------------------|-------------------|
| `PORT`             | Lintening port       | `8443`            |
| `KEYSTORE`         | Key name             | `kosciuszkon.jks` |
| `KS_PASS`          | Keystora password    | `HASSLO123`       |
| `KEY_PASS`         | Private key password | `HASSLO123`       |
| `UPDATE_PATH`      | Path to updates      | `./updates`       |
| `MAX_QUERY_LENGTH` |                      | `64`              |
| `MAX_PARAM_COUNT`  |                      | `2`               |
| `MAX_KEY_LENGTH`   |                      | `10`              |
| `MAX_VALUE_LENGTH` |                      | `32`              |

### Configuration file - "serve_config.txt"
| Line | Variable           |
|------|--------------------|
| 1    | `PORT`             |
| 2    | `KEYSTORE`         |
| 3    | `KS_PASS`          |
| 4    | `KEY_PASS`         |
| 5    | `UPDATE_PATH`      |
| 6    | `MAX_QUERY_LENGTH` |
| 7    | `MAX_PARAM_COUNT`  |
| 8    | `MAX_KEY_LENGTH`   |
| 9    | `MAX_VALUE_LENGTH` |
---

## 4. SSL/TLS configuration

### Certificate generation (self-signed, for development)

```bash
# Change '-storepass', `-keypass` and `-dname`
# Generating self-signed JKS keystore certificate
keytool -genkeypair \
  -alias kosciuszkon \
  -keyalg RSA \
  -keysize 2048 \
  -validity 365 \
  -keystore kosciuszkon.jks \
  -storepass PASSWD \
  -keypass PASSWD \
  -dname "CN=localhost, OU=Dev, O=Company, L=City, ST=State, C=2CharCountyCode"
```
---

## 5. Installation and execution

### Cloning sorce code

```bash
git clone https://github.com/SimpleProgrammr/easy-save-update.git
cd easy-save-update
```

### Budowanie projektu

Java 11+
```bash
java src/HttpsServer.java
```

Maven
```maven
<project>
  <modelVersion>0.1.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>easy-save-update/artifactId>
  <version>0.1.0</version>

  <properties>
    <maven.compiler.source>23</maven.compiler.source>
    <maven.compiler.target>23</maven.compiler.target>
  </properties>

  <build>
    <plugins>
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>exec-maven-plugin</artifactId>
        <version>3.1.0</version>
        <configuration>
          <mainClass>HttpsServer</mainClass>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```
```bash

mkdir -p src/main/java
mv src/HttpsServer.java src/main/java/

mvn compile          # only compilation
mvn package          # compilation + jar in target
mvn exec:java        # compilation + execution
```


Gradle
```gradle
plugins {
    id 'java'
    id 'application'
}

application {
    mainClass = 'HttpsServer'
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
}
```


```bash
mkdir -p src/main/java
mv src/HttpsServer.java src/main/java/

gradle wrapper          # One time - creates ./gradlew
./gradlew build         # compilation + JAR in build/libs/
./gradlew run           # compilation + execution
./gradlew jar           # only JAR
```

### Execution

```bash
# Bezpośrednio z JAR
java -jar target/server.jar

```

---

## 6. API Endpoint

### Overview

| Method | Path                                 | Description                         |
|--------|--------------------------------------|-------------------------------------|
| GET    | `/get_newest?serial=<serial_number>` | Getting newest version for your app |
| GET    | `/files/<filename>`                  | Getting update file                 |

### Detailed endpoint description


**GET /?serial=<serial_number>**
```
<newest update file name>
```

**GET /file/<filename>**
```
Downloads a update file
```
**Error codes:**

| Code | Decription         |
|------|--------------------|
| 403  | No permisions      |
| 404  | Resource not fount |

---

## 7. Known problems and FAQ

### Problems

| Issue                                   | Cause                 | Solution                          |
|-----------------------------------------|-----------------------|-----------------------------------|
| `SSLHandshakeException`                 | Untrusted certificate | Add certifate CA to truststore    |
| `BindException: Address already in use` | Port 8443 in use      | Change port or stop other process |


### FAQ

**Q: How to change the listening port?**
> Change the global variable `SERVER_PORT` in code.

**Q: How to renew certificate?**
> Generate new one

---

## 8. Authors

### Authors

| Imię i nazwisko | Rola       | Kontakt       |
|-----------------|------------|---------------|
| Michał Pelon    | Programmer | Github issues |
| Piotr Kumka     | Supervisor | Github issues |
| Andrzej Solecki | Monke      | Github issues |

### Changes log

| Wersja | Data       | Opis zmian |
|--------|------------|------------|
| 0.1.0  | 2026.05.09 | init       |
