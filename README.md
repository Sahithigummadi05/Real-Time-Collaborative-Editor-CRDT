# Collaborative Text Editor (RGA CRDT)

![CI](https://github.com/Sahithigummadi05/project3/actions/workflows/ci.yml/badge.svg)

A real-time collaborative editor — open it in two tabs, type in both at once, and the edits merge
without conflicts. There is no locking, no "last write wins", and no server deciding whose edit
survives.

Built on **RGA (Replicated Growable Array)**, a sequence CRDT, in Java 21 / Spring Boot with a
dependency-free browser client.

## The problem this solves

Two people put the cursor at the same spot and type simultaneously. The naive approach is to send
"insert 'X' at index 5" — but by the time that message arrives, the other replica has shifted, and
index 5 now means something else. Characters land in the wrong place, or get lost, or the two
documents silently drift apart forever.

The usual industrial answer is Operational Transformation: keep a central server that transforms
each incoming edit against every concurrent edit it didn't know about. It works, but the transform
functions are notoriously hard to get right, and the server becomes a required arbiter.

A CRDT takes a different route — make the merge operation *mathematically* order-independent, so
no transformation or arbitration is needed at all:

- Every character gets a globally unique id: `(lamportCounter, replicaId)`.
- An insert says **"after this character id"**, never "at index 5". Identity is stable; indices aren't.
- Deletes are **tombstones** — the element stays, marked dead. It has to: a concurrent insert may
  still name it as an anchor, and would have nowhere to attach if it were truly removed.
- When two inserts compete for the same slot, both replicas resolve it by comparing ids under the
  same total order — so both independently reach the *same* answer with zero communication.

That last rule is the whole algorithm, and it's four lines:

```java
var insertAt = indexOf(op.originId()) + 1;
while (insertAt < elements.size() && elements.get(insertAt).id().compareTo(op.id()) > 0) {
    insertAt++;   // walk past concurrent inserts that sort after this one
}
elements.add(insertAt, element);
```

Which character wins the earlier position is arbitrary — but it is arbitrary *identically
everywhere*, and that is all convergence requires.

## Proof it actually converges

A convergence claim is worthless unless it's tested against orderings a human wouldn't think to
try, so the tests generate random edit histories across 4 replicas and replay each one in **25
different randomized delivery orders**, asserting every replay produces identical text. Ten seeds,
60 operations each.

Delivery orders are randomized but **causally valid** — an insert is never delivered before the
character it attaches to. That's the standard precondition for CRDT convergence, so testing
non-causal orders would be testing something the algorithm never promised.

**And the tests are proven to have teeth.** `SkipRuleIsLoadBearingTest` reimplements the document
with the ordering rule deleted — splicing each insert directly after its origin, which is the
intuitive and wrong implementation — and asserts that it *does* diverge on the same history the
real one handles. A test suite that would pass with or without the mechanism it's meant to verify
proves nothing; this one demonstrably fails when the algorithm is broken.

| Test | What it establishes |
|---|---|
| `RgaConvergenceTest` | Random 4-replica histories converge across 250 randomized causal replays |
| `SkipRuleIsLoadBearingTest` | Removing RGA's ordering rule *does* cause divergence — the tests can fail |
| `RgaDocumentTest` | Insert/delete/tombstone semantics, idempotent replay, Lamport clock advance |
| `EditorSyncIntegrationTest` | Two and three real WebSocket clients converge end-to-end through the running server |

```
26 tests, 0 failures
```

## Architecture

```
Browser tab A                    Spring Boot server                  Browser tab B
┌──────────────┐                ┌──────────────────┐               ┌──────────────┐
│ RgaDocument  │  ── op ──────► │ DocumentSession  │ ── op ──────► │ RgaDocument  │
│  (replica)   │                │  (replica + log) │               │  (replica)   │
│              │ ◄────── op ─── │                  │ ◄─────── op ──│              │
└──────────────┘                └──────────────────┘               └──────────────┘
   local edits                   relay, not arbiter                   local edits
   apply instantly               replays history to                   apply instantly
                                 late joiners
```

**The server is a relay, not an authority.** It never decides who wins a conflict and never
transforms one edit against another — that's the point of using a CRDT instead of OT. It keeps a
replica only so a client joining an hour late has somewhere to fetch history from. The clients
would still converge if they gossiped directly.

Each tab holds a **real replica**, so local edits apply instantly without a server round-trip, and
a disconnected tab keeps working — its operations converge on reconnect.

| Component | Role |
|---|---|
| `crdt/RgaDocument` | The algorithm. No Spring, no I/O, no threads — so convergence is directly testable |
| `crdt/OpId`, `crdt/Operation` | Identity and position-independent edits |
| `ws/DocumentSession` | Server replica + operation log for late joiners |
| `ws/EditorWebSocketHandler` | Fan-out relay |
| `ws/OperationCodec` | Hand-written wire format (a contract shared with JS — no Java type names on the wire) |
| `static/rga.js` | Browser port of the same algorithm |

## Running it

```bash
mvn spring-boot:run
# open http://localhost:8080 in two tabs and type in both
```

```bash
mvn test          # 26 tests
```

## Honest limitations

Things this deliberately does **not** do, so nobody has to discover them by surprise:

- **Causal delivery is assumed, not enforced.** Convergence is only guaranteed when an insert
  arrives after the character it anchors to. A single WebSocket connection preserves order, which
  is enough here — but a production build with multiple transports or peer-to-peer gossip needs a
  buffer that holds operations until their dependencies arrive. `RgaDocument` currently degrades
  gracefully rather than crashing when this is violated, which is not the same as being correct.
- **Tombstones grow without bound.** Deleted characters are never reclaimed, so a long-lived
  document's memory grows with total edits, not current length. Real systems solve this with
  garbage collection once all replicas have acknowledged a deletion — which needs version vectors
  this doesn't have.
- **One document.** There's a single shared document, not rooms or per-document sessions.
- **In-memory only.** The operation log doesn't survive a restart.
- **No authentication, no presence/cursors.** Out of scope for demonstrating the merge algorithm.

## Why RGA and not something else

| Approach | Trade-off |
|---|---|
| **Operational Transformation** | Mature (Google Docs), but transform functions are hard to prove correct and generally need a central server |
| **RGA** (this) | Order-independent merge, works peer-to-peer, simple to verify — costs tombstone growth |
| **Logoot / LSEQ** | Avoids tombstones with fractional position ids — ids can grow unboundedly under heavy interleaving |
| **Yjs / Automerge** | Production-grade optimised CRDTs — the right choice for real products, but using one would hide the algorithm this project exists to demonstrate |
