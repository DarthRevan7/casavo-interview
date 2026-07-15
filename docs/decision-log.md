# Decision Log

[Versione italiana](#versione-italiana)

This document explains the non-obvious technical decisions made while
building the lead routing service, and the alternatives that were
considered and rejected.

## 1. Pessimistic locking (Mutex) vs optimistic concurrency (CAS)

**Decision:** use a `Mutex` (kotlinx.coroutines) to serialize the
"count current load -> decide if capacity is available -> save
assignment" sequence for a given agent.

**Alternatives considered:**
- **Optimistic concurrency** via `AtomicInteger`/`compareAndSet` (or
  `compareAndExchange`): no coroutine ever waits; instead, each attempt
  reads the current value, computes a result, and retries if another
  writer changed the value first.

**Why pessimistic here:** the decision isn't "update one number
atomically" — it's "read a derived value across multiple records
(count of assignments in the last 24h), decide, then write a new
record". A single `AtomicInteger` doesn't naturally represent this;
using CAS would mean either maintaining a separate atomic counter
alongside the assignment list (two sources of truth to keep in sync) or
wrapping the whole read-decide-write sequence in a manual retry loop,
which is exactly what `Mutex.withLock` already gives for free with
clearer code. Given the challenge explicitly calls out correctness on
the "last available slot" as the main evaluation criterion, the simpler
and more obviously-correct option was preferred over a marginal
throughput gain from optimistic retries, which mainly pays off when
contention is expected to be very low — the opposite of what this
service should assume under load.

## 2. Per-agent lock granularity, not a single global lock

**Decision:** lock ownership is keyed by `agentId`, stored in a
`ConcurrentHashMap<String, Mutex>` created via `computeIfAbsent`.

**Alternatives considered:**
- One global `Mutex` for the entire routing decision.
- One `Mutex` per city.

**Why per-agent:** a single global lock would serialize every lead
across every city, even though a lead for Milan and one for Rome never
compete for the same resource. Locking per city is closer, but still
forces two agents in the same city to wait on each other even when they
have independent capacity. Locking per agent is the smallest unit of
actual contention — it matches the granularity at which conflicts can
occur (two leads racing for the same agent's last slot) and
maximizes the ability to serve unrelated leads in parallel.

## 3. `Mutex` vs `ConcurrentHashMap.compute()` for the atomic section

**Decision:** wrap the capacity check + save in `Mutex.withLock`, not in
a single `compute()` call on the assignments map.

**Why not `compute()` alone:** `compute()` guarantees atomicity for a
single key of a single map. The capacity check here requires scanning
*multiple* assignment records belonging to the same agent (`count
where agentId == X and assignedAt > 24h ago`) — it isn't a single-value
update. `compute()` would only help if load was tracked as one counter
value; the code instead needs to read a derived aggregate and then
write a new, unrelated record (`Assignment`) to a different collection.
A `Mutex` scoped to the agent id cleanly wraps that whole
read-then-write sequence, regardless of how many repository calls it
spans, which is a more direct fit than forcing the logic into a single
map-entry mutation. (For the notification status update in the outbox
worker — a true single-key update — `computeIfPresent` is used
directly, since that operation *does* match `compute()`'s shape.)

## 4. Outbox pattern for agent notification

**Decision:** persist the `Assignment` (with `notificationStatus =
PENDING_NOTIFICATION`) before attempting to notify the agent. A
separate background worker polls for pending notifications and retries
with exponential backoff, independent of the request/response cycle.

**Alternatives considered:**
- Notify the agent synchronously inside the `POST /v1/leads` request
  handler, and fail the whole request if notification fails.

**Why outbox:** the requirement is explicit — a failing external
notification must never lose the lead. Notifying synchronously couples
the reliability of an external system (out of our control) to the
success of the core business operation (the assignment, which is
already correct and durable at that point). Separating "assignment
decided" from "agent notified" means the lead is safely persisted
first, and notification delivery becomes a retryable concern that
doesn't block or risk the request. The tradeoff is eventual (not
immediate) delivery of the notification, which is acceptable since the
assignment itself is already final and correct.

## 5. Ktor as the framework

**Decision:** Ktor over Javalin/Http4k (Spring/Quarkus excluded by the
challenge's constraints).

**Why:** Ktor's coroutine-first request handling matches the
concurrency model already used for the routing logic (`Mutex`,
`suspend` functions) — there's no impedance mismatch between the web
layer and the domain logic's concurrency primitives, unlike a
thread-blocking framework where coroutines would need to be bridged
back to blocking I/O. Its plugin-based configuration (explicit
`install(...)` calls, no annotation-driven magic) also keeps what's
active in the application visible in code, which matters when the
service's main complexity is intentionally in a few explicit files
(`LeadRoutingService`, `NotificationOutboxWorker`) rather than hidden
in framework configuration.

## 6. In-memory storage now, Postgres later

**Decision:** ship with `ConcurrentHashMap`-backed repositories behind
interfaces (`AgentRepository`, `AssignmentRepository`), rather than
integrating a real database for the challenge submission.

**Why:** the challenge explicitly allows an in-memory/mock persistence
layer and prioritizes routing logic and concurrency correctness within
the time budget. Keeping repositories behind interfaces means the
in-memory implementation can be swapped for a Postgres-backed one
without touching `LeadRoutingService` or the routing endpoint — the
migration path is described in `docs/system-design.md` rather than
implemented, since it's an infrastructure change orthogonal to the
concurrency problem the challenge is actually evaluating.

## 7. Candidate ordering (least-loaded/round-robin) computed outside the lock

**Decision:** `orderByLoadThenRoundRobin` reads each candidate agent's
current load and produces a preference order *before* any lock is
acquired. The lock is only taken once, per candidate, inside the loop
that actually attempts the assignment.

**Why this isn't a race condition:** this read is a heuristic, not a
source of truth — it only decides *which agent to try first*. The
correctness guarantee (never exceeding capacity) comes entirely from
the check-and-save that happens inside `Mutex.withLock` for the
specific candidate being attempted. If the snapshot used for ordering
is stale by the time the lock is acquired (e.g. another request filled
the agent in between), the in-lock check simply finds no capacity and
the loop moves on to the next candidate — no double-booking is
possible either way. Computing the order under a lock would require
locking every candidate agent at once just to pick one, which
serializes unrelated agents against each other for no correctness
benefit — the exact granularity mistake decision #2 avoids.

## 8. Single-instance outbox worker

**Decision:** `NotificationOutboxWorker` runs as one polling coroutine
per application instance, with no coordination between instances.

**Why this is acceptable for the submission, and not beyond it:** with
exactly one instance (the current deployment target), there is only
ever one worker polling `PENDING_NOTIFICATION` assignments, so there is
no risk of two workers double-sending the same notification. This
stops being true the moment the service scales horizontally — multiple
instances would each run their own worker and could pick up the same
pending assignment. That failure mode, and the fix (a durable queue or
`SELECT ... FOR UPDATE SKIP LOCKED` so workers safely compete for
distinct rows), is intentionally left to `docs/system-design.md` §5
rather than implemented here, since it only matters once the
single-instance assumption is dropped.

---

## Versione italiana

Questo documento spiega le decisioni tecniche non ovvie prese nella
costruzione del servizio di lead routing, e le alternative considerate
e scartate.

### 1. Lock pessimistico (Mutex) vs concorrenza ottimistica (CAS)

**Decisione:** usare un `Mutex` (kotlinx.coroutines) per serializzare
la sequenza "conta il carico attuale -> decidi se c'è capacità ->
salva l'assegnazione" per un dato agente.

**Alternative considerate:**
- **Concorrenza ottimistica** via `AtomicInteger`/`compareAndSet` (o
  `compareAndExchange`): nessuna coroutine aspetta mai; ogni tentativo
  legge il valore attuale, calcola un risultato, e riprova se un altro
  writer ha cambiato il valore nel frattempo.

**Perché pessimistico qui:** la decisione non è "aggiorna un numero
atomicamente" — è "leggi un valore derivato da più record (conteggio
degli assignment nelle ultime 24h), decidi, poi scrivi un nuovo
record". Un singolo `AtomicInteger` non rappresenta naturalmente
questo caso; usare CAS significherebbe mantenere un contatore atomico
separato accanto alla lista degli assignment (due fonti di verità da
tenere sincronizzate), oppure avvolgere l'intera sequenza
leggi-decidi-scrivi in un retry-loop manuale, che è esattamente ciò che
`Mutex.withLock` offre già gratuitamente con codice più chiaro. Dato
che la challenge indica esplicitamente la correttezza sull'"ultimo
slot disponibile" come criterio di valutazione principale, è stata
preferita l'opzione più semplice e più ovviamente corretta rispetto a
un guadagno marginale di throughput dei tentativi ottimistici, che
paga soprattutto quando la contesa è attesa molto bassa — l'opposto di
ciò che questo servizio dovrebbe assumere sotto carico.

### 2. Granularità del lock per-agente, non un unico lock globale

**Decisione:** il possesso del lock è indicizzato per `agentId`,
tenuto in una `ConcurrentHashMap<String, Mutex>` creata tramite
`computeIfAbsent`.

**Alternative considerate:**
- Un unico `Mutex` globale per l'intera decisione di routing.
- Un `Mutex` per città.

**Perché per-agente:** un lock globale unico serializzerebbe ogni lead
across tutte le città, anche se un lead per Milano e uno per Roma non
competono mai per la stessa risorsa. Un lock per città è più vicino,
ma costringe comunque due agenti della stessa città ad aspettarsi a
vicenda anche quando hanno capacità indipendente. Un lock per agente è
la più piccola unità di contesa reale — corrisponde esattamente alla
granularità a cui può verificarsi un conflitto (due lead in gara per
l'ultimo slot dello stesso agente) e massimizza la capacità di servire
lead non correlati in parallelo.

### 3. `Mutex` vs `ConcurrentHashMap.compute()` per la sezione atomica

**Decisione:** avvolgere il check di capacità + salvataggio in
`Mutex.withLock`, non in una singola chiamata `compute()` sulla mappa
degli assignment.

**Perché non `compute()` da solo:** `compute()` garantisce atomicità
per una singola chiave di una singola mappa. Il check di capacità qui
richiede di scandire *più* record di assignment appartenenti allo
stesso agente (`conta dove agentId == X e assignedAt > 24h fa`) — non
è l'aggiornamento di un singolo valore. `compute()` aiuterebbe solo se
il carico fosse tracciato come un unico valore contatore; il codice
invece deve leggere un aggregato derivato e poi scrivere un nuovo
record non correlato (`Assignment`) in una collezione diversa. Un
`Mutex` scoped all'id dell'agente avvolge in modo pulito l'intera
sequenza leggi-poi-scrivi, indipendentemente da quante chiamate al
repository comprenda, il che si adatta più direttamente rispetto a
forzare la logica in una singola mutazione di entry di mappa. (Per
l'aggiornamento dello stato di notifica nel worker dell'outbox — un
vero aggiornamento a chiave singola — viene usato direttamente
`computeIfPresent`, perché quell'operazione *corrisponde* effettivamente
alla forma di `compute()`.)

### 4. Pattern outbox per la notifica all'agente

**Decisione:** persistere l'`Assignment` (con `notificationStatus =
PENDING_NOTIFICATION`) prima di tentare di notificare l'agente. Un
worker separato in background esegue polling delle notifiche pendenti
e riprova con backoff esponenziale, indipendentemente dal ciclo
richiesta/risposta.

**Alternative considerate:**
- Notificare l'agente in modo sincrono dentro l'handler della
  richiesta `POST /v1/leads`, facendo fallire l'intera richiesta se la
  notifica fallisce.

**Perché outbox:** il requisito è esplicito — una notifica esterna che
fallisce non deve mai far perdere il lead. Notificare in modo sincrono
accoppierebbe l'affidabilità di un sistema esterno (fuori dal nostro
controllo) al successo dell'operazione di business principale
(l'assegnazione, che a quel punto è già corretta e durevole). Separare
"assegnazione decisa" da "agente notificato" significa che il lead
viene persistito in sicurezza per primo, e la consegna della notifica
diventa una responsabilità ritentabile che non blocca né mette a
rischio la richiesta. Il trade-off è una consegna della notifica
eventuale (non immediata), accettabile dato che l'assegnazione stessa
è già finale e corretta.

### 5. Ktor come framework

**Decisione:** Ktor rispetto a Javalin/Http4k (Spring/Quarkus esclusi
dai vincoli della challenge).

**Perché:** la gestione delle richieste coroutine-first di Ktor
corrisponde al modello di concorrenza già usato nella logica di
routing (`Mutex`, funzioni `suspend`) — non c'è disallineamento tra il
layer web e le primitive di concorrenza della logica di dominio, a
differenza di un framework thread-blocking dove le coroutine
andrebbero ripiegate su I/O bloccante. La sua configurazione basata su
plugin (chiamate esplicite a `install(...)`, nessuna magia guidata da
annotazioni) mantiene visibile nel codice cosa è attivo
nell'applicazione, il che conta quando la complessità principale del
servizio è intenzionalmente concentrata in pochi file espliciti
(`LeadRoutingService`, `NotificationOutboxWorker`) invece che nascosta
nella configurazione del framework.

### 6. Persistenza in-memory ora, Postgres dopo

**Decisione:** consegnare con repository basati su `ConcurrentHashMap`
dietro interfacce (`AgentRepository`, `AssignmentRepository`), invece
di integrare un vero database per la consegna della challenge.

**Perché:** la challenge consente esplicitamente un layer di
persistenza in-memory/mock e dà priorità alla logica di routing e alla
correttezza della concorrenza nel budget di tempo dato. Tenere i
repository dietro interfacce significa che l'implementazione in-memory
può essere sostituita con una basata su Postgres senza toccare
`LeadRoutingService` o l'endpoint di routing — il percorso di
migrazione è descritto in `docs/system-design.md` invece che
implementato, trattandosi di un cambiamento infrastrutturale
ortogonale al problema di concorrenza che la challenge sta
effettivamente valutando.

### 7. Ordinamento dei candidati (least-loaded/round-robin) calcolato fuori dal lock

**Decisione:** `orderByLoadThenRoundRobin` legge il carico attuale di
ogni agente candidato e produce un ordine di preferenza *prima* che
venga acquisito qualsiasi lock. Il lock viene preso solo una volta, per
candidato, dentro il loop che effettivamente tenta l'assegnazione.

**Perché questa non è una race condition:** questa lettura è
un'euristica, non una fonte di verità — decide solo *quale agente
provare per primo*. La garanzia di correttezza (non superare mai la
capacità) viene interamente dal check-and-save che avviene dentro
`Mutex.withLock` per lo specifico candidato tentato. Se lo snapshot
usato per l'ordinamento è obsoleto nel momento in cui il lock viene
acquisito (es. un'altra richiesta ha riempito l'agente nel frattempo),
il check dentro il lock semplicemente non trova capacità e il loop
passa al candidato successivo — nessuna doppia assegnazione è
possibile in nessuno dei due casi. Calcolare l'ordine sotto lock
richiederebbe di bloccare tutti gli agenti candidati contemporaneamente
solo per sceglierne uno, serializzando agenti non correlati tra loro
senza alcun beneficio di correttezza — esattamente l'errore di
granularità che la decisione #2 evita.

### 8. Worker outbox single-instance

**Decisione:** `NotificationOutboxWorker` gira come un'unica coroutine
di polling per istanza dell'applicazione, senza coordinamento tra
istanze.

**Perché è accettabile per la consegna, e non oltre:** con esattamente
un'istanza (il target di deploy attuale), c'è sempre e solo un worker
che fa polling degli assignment `PENDING_NOTIFICATION`, quindi non c'è
rischio che due worker inviino la stessa notifica due volte. Questo
smette di essere vero nel momento in cui il servizio scala
orizzontalmente — più istanze eseguirebbero ciascuna il proprio worker
e potrebbero prendere in carico lo stesso assignment pendente. Questo
failure mode, e la soluzione (una coda durevole o `SELECT ... FOR
UPDATE SKIP LOCKED` così i worker competono in sicurezza per righe
distinte), è intenzionalmente lasciato a `docs/system-design.md` §5
invece che implementato qui, dato che conta solo una volta abbandonata
l'assunzione di singola istanza.
