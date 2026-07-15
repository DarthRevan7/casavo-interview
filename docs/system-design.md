# System Design: Scaling Lead Routing to Thousands of Leads/sec

[Versione italiana](#versione-italiana)

## Current implementation (this repo)

This document describes the state of the code as of commit
`d601ee6d7c8ea1f519cd68190c91dfb89ffaef83`
("feat: Add concurrency test for per-agent capacity enforcement") — the
snapshot that represents the challenge submission. Any further hardening
described below as future work is deliberately not implemented at that
commit, to keep the submission scoped to what the challenge asked for.

Single Ktor instance, in-memory state, per-agent `Mutex` to serialize the
"check capacity + assign" critical section. This is correct for a single
process but has two hard limits at scale:

1. **Single point of failure / no horizontal scaling** — all state (agents,
   assignments, locks) lives in the JVM heap of one instance. A second
   instance would have its own independent view of agent load, so the same
   agent could be over-booked across instances.
2. **In-memory storage** — no durability. A crash loses the audit trail and
   any pending outbox notifications.

The rest of this document describes what changes to reach thousands of
leads/sec across multiple instances, without changing the core routing
algorithm (least-loaded/round-robin among eligible agents, atomic
capacity check).

## 1. Horizontal scaling: sharding by city

Lead routing is naturally partitionable: an agent only ever competes for
capacity with leads in their own city. There is no cross-city coordination
needed to decide "is this agent full".

- Route incoming `POST /v1/leads` requests to a shard **by city** (e.g.
  consistent hashing of `city` at the load balancer / API gateway level,
  or a lightweight routing layer backed by a city->shard lookup).
- Each shard is a group of stateless Ktor instances that all coordinate
  through the same distributed lock/counter for that set of cities (see
  below). Instances within a shard can scale horizontally for throughput;
  cities are what determines which shard owns the authoritative capacity
  state.
- This keeps the hot path (capacity check + assignment) local to a shard
  instead of requiring global coordination for every request.

## 2. Replacing the in-process Mutex with distributed coordination

A JVM `Mutex` only protects a single process. Once there is more than one
instance handling leads for the same city, the atomic "count + decide +
assign" operation must be enforced across instances. Two realistic options:

**Option A — Redis as the coordination point (recommended first step)**

- Keep a per-agent counter in Redis (`INCR`/`DECR` with a sliding 24h
  window, e.g. a sorted set of assignment timestamps with `ZADD` +
  `ZREMRANGEBYSCORE` to expire old entries, or `ZCARD` to get the count).
- Use `ZADD` conditionally, or a Lua script (atomic on Redis) to make
  "count members in the last 24h, and if < 5 add the new one" a single
  atomic server-side operation — this is the distributed equivalent of the
  `Mutex.withLock { count; decide; save }` block in `LeadRoutingService`.
- Redis is single-threaded per command, so this reproduces the same
  correctness guarantee as the current in-process lock, just centralized.
  It becomes the new bottleneck, but Redis can handle very high throughput
  for simple counter operations, and can itself be sharded by city/agent
  key if needed.

**Option B — Kafka + a single-writer-per-key consumer**

- Publish incoming leads to a topic partitioned by `city` (or `agentId`
  once a candidate is chosen). Kafka guarantees ordering within a
  partition, so a single consumer per partition can process assignment
  decisions sequentially — no locking needed at all, because there is
  only one writer for that partition at a time.
- This trades lower latency (Option A is synchronous request/response) for
  higher throughput and natural backpressure handling, at the cost of the
  API becoming asynchronous (the client gets an ack that the lead was
  received, not necessarily the final assignment, unless a
  request/response correlation layer is added on top).
- Good fit if lead volume genuinely reaches thousands/sec and some
  assignment latency (tens to hundreds of ms) is acceptable in exchange
  for throughput and resilience.

For a take-home implementation, Option A is the more incremental change
from what exists now (it's a drop-in replacement for the `Mutex`, just
distributed) and is what I'd propose first; Option B is what I'd migrate to
if profiling showed the synchronous coordination step becoming the
bottleneck.

## 3. Caching the 24h load count

`countForAgentSince` currently scans all assignments for an agent on every
request. At scale this must not hit the source of truth on every check:

- Maintain the count as a materialized value (the Redis counter in Option A
  already does this — it's O(1) to read, not a scan).
- If a relational database is used for the durable audit trail (see below),
  keep Redis as the authoritative fast-path for capacity decisions, and
  treat the DB as append-only history that's asynchronously written
  (fits naturally with the outbox pattern already in place — the same
  mechanism used for notifications can be extended to persist the
  durable audit trail without blocking the request path).

## 4. Durable storage

Replace `InMemoryAssignmentRepository` with a real database once
correctness has been validated in-memory:

- **Postgres** for the audit trail (assignments, notification status) —
  relational, supports the outbox pattern well (a `notification_status`
  column + an index on `PENDING_NOTIFICATION` is exactly what the current
  in-memory worker already assumes).
- Partition/shard the audit table by city or by time (e.g. monthly) if
  volume requires it; the audit trail is written once per assignment and
  read rarely (mostly for reporting), so it's much less latency-sensitive
  than the capacity check itself.

## 5. Outbox worker at scale

The current single in-process polling worker doesn't scale past one
instance without duplicate notification risk (two instances both picking
up the same `PENDING_NOTIFICATION` row). At scale:

- Move the outbox to a durable queue (the DB table + `SELECT ... FOR
  UPDATE SKIP LOCKED` in Postgres, or a Kafka topic) so multiple worker
  instances can safely compete for work without double-sending.
- Same retry/backoff logic as today, just distributed across workers
  instead of one coroutine loop.

## 6. Observability at this scale

- **Metrics**: assignment latency (p50/p99), assignment success/failure
  rate per city, capacity-exhaustion rate (how often `NoEligibleAgentException`
  fires — signals under-staffed cities), outbox retry/failure counts.
- **Tracing**: propagate a request ID from `POST /v1/leads` through the
  routing decision and into the async notification, so a single lead's
  full lifecycle (received -> assigned -> notified) can be reconstructed
  even though notification happens on a separate async path.
- **Alerting**: sustained growth in `PENDING_NOTIFICATION` backlog size
  (outbox falling behind), or a spike in capacity-exhaustion for a city
  (business signal, not just an infra one).

## Summary of the migration path

| Concern              | Current (this repo)      | At scale                                  |
|-----------------------|---------------------------|--------------------------------------------|
| Capacity coordination  | In-process `Mutex`         | Redis atomic counter/Lua script (or Kafka partition-per-city) |
| Load count             | Linear scan per check      | O(1) cached counter                        |
| Assignment storage      | In-memory `ConcurrentHashMap` | Postgres, partitioned                    |
| Outbox worker           | Single in-process coroutine | Multiple workers, `SKIP LOCKED` or queue-based |
| Horizontal scaling      | Not possible                | Shard by city, stateless instances per shard |

---

## Versione italiana

### Implementazione attuale (questo repo)

Questo documento descrive lo stato del codice al commit
`d601ee6d7c8ea1f519cd68190c91dfb89ffaef83`
("feat: Add concurrency test for per-agent capacity enforcement") — lo
snapshot che rappresenta la consegna della challenge. Ogni ulteriore
irrobustimento descritto sotto come lavoro futuro non è
deliberatamente implementato a quel commit, per mantenere la consegna
nell'ambito di ciò che la challenge richiedeva.

Istanza Ktor singola, stato in-memory, `Mutex` per-agente per
serializzare la sezione critica "verifica capacità + assegna". Questo
è corretto per un singolo processo ma ha due limiti forti a scala:

1. **Punto singolo di fallimento / nessuna scalabilità orizzontale** —
   tutto lo stato (agenti, assignment, lock) vive nell'heap JVM di
   un'unica istanza. Una seconda istanza avrebbe una propria vista
   indipendente del carico degli agenti, quindi lo stesso agente
   potrebbe essere sovra-prenotato tra istanze diverse.
2. **Persistenza in-memory** — nessuna durabilità. Un crash fa perdere
   l'audit trail ed eventuali notifiche outbox pendenti.

Il resto di questo documento descrive quali cambiamenti servono per
raggiungere migliaia di lead/sec su più istanze, senza modificare
l'algoritmo di routing principale (least-loaded/round-robin tra gli
agenti eleggibili, check di capacità atomico).

### 1. Scalabilità orizzontale: sharding per città

Il lead routing è naturalmente partizionabile: un agente compete per
la capacità solo con i lead della propria città. Non serve
coordinamento cross-città per decidere "questo agente è pieno".

- Instradare le richieste `POST /v1/leads` in ingresso verso uno shard
  **per città** (es. hashing consistente di `city` a livello di load
  balancer/API gateway, oppure un layer di routing leggero basato su
  una lookup città->shard).
- Ogni shard è un gruppo di istanze Ktor stateless che coordinano tutte
  attraverso lo stesso lock/contatore distribuito per quell'insieme di
  città (vedi sotto). Le istanze dentro uno shard possono scalare
  orizzontalmente per il throughput; le città determinano quale shard
  possiede lo stato di capacità autoritativo.
- Questo mantiene il percorso critico (check di capacità +
  assegnazione) locale a uno shard invece di richiedere coordinamento
  globale per ogni richiesta.

### 2. Sostituire il Mutex in-process con coordinamento distribuito

Un `Mutex` della JVM protegge solo un singolo processo. Nel momento in
cui più di un'istanza gestisce lead per la stessa città, l'operazione
atomica "conta + decidi + assegna" deve essere garantita tra le
istanze. Due opzioni realistiche:

**Opzione A — Redis come punto di coordinamento (primo step
consigliato)**

- Tenere un contatore per-agente in Redis (`INCR`/`DECR` con finestra
  scorrevole di 24h, es. un sorted set di timestamp di assignment con
  `ZADD` + `ZREMRANGEBYSCORE` per far scadere le entry vecchie, o
  `ZCARD` per ottenere il conteggio).
- Usare `ZADD` condizionale, o uno script Lua (atomico su Redis) per
  rendere "conta i membri nelle ultime 24h, e se < 5 aggiungi il
  nuovo" un'unica operazione atomica lato server — è l'equivalente
  distribuito del blocco `Mutex.withLock { conta; decidi; salva }` in
  `LeadRoutingService`.
- Redis è single-threaded per comando, quindi riproduce la stessa
  garanzia di correttezza del lock in-process attuale, solo
  centralizzata. Diventa il nuovo collo di bottiglia, ma Redis può
  gestire throughput molto alto per semplici operazioni su contatori,
  e può a sua volta essere shardato per città/agente se necessario.

**Opzione B — Kafka + un consumer single-writer per chiave**

- Pubblicare i lead in ingresso su un topic partizionato per `city`
  (o `agentId` una volta scelto un candidato). Kafka garantisce
  l'ordinamento all'interno di una partizione, quindi un singolo
  consumer per partizione può processare le decisioni di assegnazione
  sequenzialmente — nessun locking necessario, perché c'è un solo
  writer per quella partizione alla volta.
- Questo scambia latenza più bassa (l'opzione A è request/response
  sincrono) con throughput più alto e gestione naturale della
  backpressure, al costo di rendere l'API asincrona (il client riceve
  un ack che il lead è stato ricevuto, non necessariamente
  l'assegnazione finale, a meno di aggiungere un layer di correlazione
  richiesta/risposta sopra).
- Adatto se il volume di lead raggiunge davvero le migliaia/sec ed è
  accettabile una latenza di assegnazione (decine-centinaia di ms) in
  cambio di throughput e resilienza.

Per un'implementazione take-home, l'Opzione A è il cambiamento più
incrementale rispetto a quanto esiste ora (è un sostituto drop-in del
`Mutex`, solo distribuito) ed è quella che proporrei per prima;
l'Opzione B è quella verso cui migrerei se il profiling mostrasse lo
step di coordinamento sincrono diventare il collo di bottiglia.

### 3. Cache del conteggio del carico a 24h

`countForAgentSince` attualmente scandisce tutti gli assignment di un
agente a ogni richiesta. A scala questo non deve colpire la fonte di
verità a ogni check:

- Mantenere il conteggio come valore materializzato (il contatore
  Redis dell'Opzione A lo fa già — è O(1) da leggere, non una
  scansione).
- Se viene usato un database relazionale per l'audit trail durevole
  (vedi sotto), tenere Redis come fast-path autoritativo per le
  decisioni di capacità, e trattare il DB come storia append-only
  scritta in modo asincrono (si adatta naturalmente al pattern outbox
  già presente — lo stesso meccanismo usato per le notifiche può
  essere esteso per persistere l'audit trail durevole senza bloccare
  il percorso della richiesta).

### 4. Persistenza durevole

Sostituire `InMemoryAssignmentRepository` con un vero database una
volta validata la correttezza in-memory:

- **Postgres** per l'audit trail (assignment, stato notifica) —
  relazionale, supporta bene il pattern outbox (una colonna
  `notification_status` + un indice su `PENDING_NOTIFICATION` è
  esattamente ciò che il worker in-memory attuale già presuppone).
- Partizionare/shardare la tabella di audit per città o per tempo
  (es. mensile) se il volume lo richiede; l'audit trail viene scritto
  una volta per assignment e letto raramente (soprattutto per
  reportistica), quindi è molto meno sensibile alla latenza rispetto
  al check di capacità stesso.

### 5. Worker outbox a scala

L'attuale worker di polling singolo in-process non scala oltre
un'istanza senza rischio di notifiche duplicate (due istanze che
prendono in carico la stessa riga `PENDING_NOTIFICATION`). A scala:

- Spostare l'outbox su una coda durevole (la tabella DB con `SELECT
  ... FOR UPDATE SKIP LOCKED` in Postgres, o un topic Kafka) così più
  istanze worker possono competere in sicurezza per il lavoro senza
  inviare doppie notifiche.
- Stessa logica di retry/backoff di oggi, solo distribuita tra worker
  invece che in un unico loop a coroutine.

### 6. Observability a questa scala

- **Metriche**: latenza di assegnazione (p50/p99), tasso di
  successo/fallimento delle assegnazioni per città, tasso di
  esaurimento capacità (quanto spesso scatta
  `NoEligibleAgentException` — segnala città sotto-organico), conteggi
  di retry/fallimento outbox.
- **Tracing**: propagare un request ID da `POST /v1/leads` attraverso
  la decisione di routing fino alla notifica asincrona, così l'intero
  ciclo di vita di un lead (ricevuto -> assegnato -> notificato) può
  essere ricostruito anche se la notifica avviene su un percorso
  asincrono separato.
- **Alerting**: crescita sostenuta della dimensione del backlog
  `PENDING_NOTIFICATION` (l'outbox che resta indietro), o un picco di
  esaurimento capacità per una città (segnale di business, non solo
  infrastrutturale).

### Riepilogo del percorso di migrazione

| Aspetto                 | Attuale (questo repo)         | A scala                                        |
|--------------------------|--------------------------------|--------------------------------------------------|
| Coordinamento capacità    | `Mutex` in-process              | Contatore atomico Redis/script Lua (o Kafka partition-per-città) |
| Conteggio carico           | Scansione lineare a ogni check  | Contatore cache O(1)                            |
| Persistenza assignment      | `ConcurrentHashMap` in-memory   | Postgres, partizionato                          |
| Worker outbox               | Coroutine singola in-process    | Worker multipli, `SKIP LOCKED` o basato su coda |
| Scalabilità orizzontale     | Non possibile                    | Sharding per città, istanze stateless per shard |
