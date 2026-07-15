# Lead Routing Engine

[Versione italiana](#versione-italiana)

Kotlin + Ktor microservice that receives leads from real-estate portals
and assigns them in real time to available agents, handling concurrency
on the last available slot and reliable notification via the outbox
pattern.

## Running the project with IntelliJ IDEA

### Prerequisites

- IntelliJ IDEA (Community or Ultimate) with the Kotlin plugin enabled
  (bundled by default in recent versions).
- JDK 25 installed and configured in IntelliJ.
- Port `8080` free — if you already have this project's server running
  from a previous IntelliJ session, stop it first (a stale run holds
  the port and any leads it already assigned, which will skew the
  demo).

### 1. Open the project

- `File -> Open...` and select the repository root (the folder
  containing `build.gradle.kts`).
- IntelliJ detects the Gradle project automatically and imports the
  dependencies (requires an internet connection on first run, to
  download Gradle and the Ktor/Kotlin dependencies).

### 2. Configure the JDK

- `File -> Project Structure -> Project` -> set **SDK: 25** (or
  whichever local JDK 25 install is available; if none is listed, use
  `Add SDK -> Download JDK...` and pick 25).
- Wait for Gradle to resync the project (sync icon top-right, or
  `View -> Tool Windows -> Gradle -> Reload All Gradle Projects`).

### 3. Start the server

Two equivalent ways:

- **From IntelliJ**: open `src/main/kotlin/com/example/main.kt`, click
  the ▶ icon next to `fun main(...)` and choose "Run 'MainKt'".
- **From the integrated terminal** (`View -> Tool Windows ->
  Terminal`):
  ```
  ./gradlew run
  ```

The server starts on `http://localhost:8080`.

### 4. Try the endpoint

```
POST http://localhost:8080/v1/leads
Content-Type: application/json

{
  "customerName": "Mario Rossi",
  "customerEmail": "mario@test.com",
  "customerPhone": "3331234567",
  "propertyId": "prop-1",
  "city": "Milano"
}
```

The seed agents available for routing are defined in
[`Bootstrap.kt`](src/main/kotlin/com/example/Bootstrap.kt) (Milano and
Roma). A request for a city with no agents responds `409 Conflict`.

The [`requests.http`](requests.http) file has a ready-to-run set of
requests — open it in IntelliJ and click the ▶ icon next to each block
(IntelliJ's built-in HTTP client, no Postman/curl needed).

### 5. Run the tests

- **From IntelliJ**: right-click the `src/test` folder -> "Run 'Tests
  in ...'".
- **From the terminal**:
  ```
  ./gradlew test
  ```

Includes a concurrency test
([`LeadRoutingServiceConcurrencyTest`](src/test/kotlin/com/example/routing/LeadRoutingServiceConcurrencyTest.kt))
that fires concurrent requests to verify the per-agent capacity limit
is never exceeded.

## Documentation

- [`docs/system-design.md`](docs/system-design.md) — how to scale the
  service to thousands of leads/sec.
- [`docs/decision-log.md`](docs/decision-log.md) — rationale behind
  the technical choices (concurrency, outbox pattern, framework).

Both documents include an Italian version reachable via a link at the
top of the file.

---

## Versione italiana

Microservizio Kotlin + Ktor che riceve lead da portali immobiliari e li
assegna in tempo reale ad agenti disponibili, con gestione della
concorrenza sull'ultimo slot disponibile e notifica affidabile tramite
pattern outbox.

### Come avviare il progetto con IntelliJ IDEA

#### Prerequisiti

- IntelliJ IDEA (Community o Ultimate) con il plugin Kotlin abilitato
  (incluso di default nelle versioni recenti).
- JDK 25 installato e configurato in IntelliJ.
- Porta `8080` libera — se hai già il server di questo progetto in
  esecuzione da una sessione IntelliJ precedente, fermalo prima
  (un'istanza residua tiene occupata la porta e mantiene i lead già
  assegnati, falsando la demo).

#### 1. Apri il progetto

- `File -> Open...` e seleziona la cartella radice del repository
  (quella che contiene `build.gradle.kts`).
- IntelliJ rileva automaticamente il progetto Gradle e importa le
  dipendenze (richiede connessione internet al primo avvio, per
  scaricare Gradle e le dipendenze Ktor/Kotlin).

#### 2. Configura il JDK

- `File -> Project Structure -> Project` -> imposta **SDK: 25** (o la
  versione JDK 25 disponibile in locale; se non è presente, usa
  `Add SDK -> Download JDK...` e scegli la 25).
- Attendi che Gradle risincronizzi il progetto (icona di sync in alto a
  destra, o `View -> Tool Windows -> Gradle -> Reload All Gradle
  Projects`).

#### 3. Avvia il server

Due modi equivalenti:

- **Da IntelliJ**: apri `src/main/kotlin/com/example/main.kt`, clicca
  sull'icona ▶ accanto a `fun main(...)` e scegli "Run 'MainKt'".
- **Da terminale integrato** (`View -> Tool Windows -> Terminal`):
  ```
  ./gradlew run
  ```

Il server parte su `http://localhost:8080`.

#### 4. Prova l'endpoint

```
POST http://localhost:8080/v1/leads
Content-Type: application/json

{
  "customerName": "Mario Rossi",
  "customerEmail": "mario@test.com",
  "customerPhone": "3331234567",
  "propertyId": "prop-1",
  "city": "Milano"
}
```

Gli agenti disponibili per il routing sono definiti in
[`Bootstrap.kt`](src/main/kotlin/com/example/Bootstrap.kt) (Milano e
Roma). Una richiesta per una città senza agenti risponde `409
Conflict`.

Il file [`requests.http`](requests.http) contiene un set di richieste
pronte all'uso — aprilo in IntelliJ e clicca l'icona ▶ accanto a ogni
blocco (client HTTP integrato di IntelliJ, niente Postman/curl
necessari).

#### 5. Esegui i test

- **Da IntelliJ**: click destro sulla cartella `src/test` -> "Run
  'Tests in ...'".
- **Da terminale**:
  ```
  ./gradlew test
  ```

Include un test di concorrenza
([`LeadRoutingServiceConcurrencyTest`](src/test/kotlin/com/example/routing/LeadRoutingServiceConcurrencyTest.kt))
che lancia richieste parallele per verificare che il limite di
capacità per agente non venga mai superato.

### Documentazione

- [`docs/system-design.md`](docs/system-design.md) — come scalare il
  servizio a migliaia di lead/sec.
- [`docs/decision-log.md`](docs/decision-log.md) — motivazione delle
  scelte tecniche (concorrenza, outbox pattern, framework).

Entrambi i documenti includono una versione italiana raggiungibile da
un link in cima al file.
