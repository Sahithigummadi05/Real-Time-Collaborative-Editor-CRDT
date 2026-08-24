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

That last rule is the whole algorithm, and it's a handful of lines:

```java
var previous = origin;
while (previous.next != null && previous.next.id.compareTo(op.id()) > 0) {
    previous = previous.next;      // walk past concurrent inserts that sort after this one
}
node.next = previous.next;
previous.next = node;
```

Which character wins the earlier position is arbitrary — but it is arbitrary *identically
everywhere*, and that is all convergence requires.

## Proof it actually converges

A convergence claim is worthless unless it's tested against orderings a human wouldn't think to
try, so the tests generate random edit histories across 4 replicas and replay each one in **25
different randomized delivery orders**, asserting every replay produces identical text. Ten seeds,
60 operations each.

Those replays are randomized but **causally valid** — an insert never arrives before the character
it attaches to — because that's the precondition textbook RGA is stated against. Delivery that
violates causality is covered separately and more aggressively in
[Surviving a network that reorders](#surviving-a-network-that-reorders) below.

**And the tests are proven to have teeth.** `SkipRuleIsLoadBearingTest` reimplements the document
with the ordering rule deleted — splicing each insert directly after its origin, which is the
intuitive and wrong implementation — and asserts that it *does* diverge on the same history the
real one handles. A test suite that would pass with or without the mechanism it's meant to verify
proves nothing; this one demonstrably fails when the algorithm is broken.

| Test | What it establishes |
|---|---|
| `RgaConvergenceTest` | Random 4-replica histories converge across 250 randomized causal replays |
| `SkipRuleIsLoadBearingTest` | Removing RGA's ordering rule *does* cause divergence — the tests can fail |
| `OutOfOrderDeliveryTest` | Convergence survives fully reversed and arbitrarily shuffled (non-causal) delivery |
| `CrossImplementationConformanceTest` | The Java and JavaScript implementations produce byte-identical documents |
| `RgaDocumentTest` | Insert/delete/tombstone semantics, idempotent replay, Lamport clock advance |
| `RgaPerformanceTest` | Insert cost stays flat as the document grows |
| `EditorSyncIntegrationTest` | Two and three real WebSocket clients converge end-to-end through the running server |
| `OfflineReconnectIntegrationTest` | Edits made while disconnected survive; partitioned replicas merge on reconnect |

```
46 tests, 0 failures
```

## Two implementations, one algorithm

RGA exists twice here: in Java for the server, and in JavaScript so each browser tab holds a real
replica and can edit without a round-trip. **That duplication is the most dangerous thing in the
codebase.** Both sides pass their own tests, both look correct in review, and if they ever disagree
about how to order two concurrent inserts, replicas diverge silently — producing exactly the
corruption the project exists to prevent. No per-language test suite can catch it, because each
one only ever compares an implementation against itself.

So they're compared against each other directly. `CrossImplementationConformanceTest` generates
operation histories, runs them through the Java implementation and — by shelling out to Node —
through the browser implementation, and requires the resulting documents to match character for
character. It covers heavy concurrency at identical positions (where tie-breaking decides the
outcome), fully reversed delivery, and arbitrary non-causal shuffles (where the buffering logic has
to behave identically on both sides).

**Verified to catch real divergence.** Flipping a single comparison in the JavaScript tie-break —
`a.replicaId < b.replicaId ? -1 : 1` to `? 1 : -1` — makes all four conformance tests fail
immediately, and restoring it makes them pass. The harness detects a one-character difference in
ordering policy between the two languages.

The test skips rather than fails when Node isn't installed, so a JVM-only machine can still build.

## Surviving a network that reorders

Textbook RGA assumes **causal delivery** — an insert never arrives before the character it anchors
to. A single ordered WebSocket satisfies that, so it's easy to build something that looks correct
and quietly isn't.

It isn't a safe assumption in general: multiple connections, peer-to-peer sync, a client replaying
a partial log, or a reconnect that interleaves buffered and live traffic can all deliver an
operation early. The first version of this code responded by guessing — an insert with no known
origin was placed at the start of the document, and a delete for an unknown character was silently
dropped, resurrecting it when the insert later arrived. Both are silent corruption.

Now operations that arrive early are **buffered against the id they're waiting for** and applied
the moment it lands, cascading transitively so one late operation can release a whole chain:

```java
var missing = missingDependency(op);
if (missing != null) {
    pending.computeIfAbsent(missing, k -> new ArrayList<>()).add(op);
    return;                       // hold it — never guess a position
}
applyNow(op);
releaseDependents(op.id());       // this may unblock a chain
```

`OutOfOrderDeliveryTest` delivers histories fully reversed, and in 160 arbitrary shuffles with no
causality respected at all, requiring the final text to match in-order delivery exactly. The
`indexOf` fallbacks that previously papered over this are now hard failures, because with
buffering in place reaching them would mean a real invariant broke.

## Performance

Correct-but-quadratic is unusable for an editor, so scaling is measured rather than assumed
(`mvn test -Dtest=RgaPerformanceTest`).

The original implementation stored characters in an `ArrayList` with a map from id to array index.
That map had to be rewritten on every insert, and resolving a caret position meant scanning the
document — so cost grew with length. It was **quadratic**, and measurably so:

| | Before (array + index map) | After (linked list + id→node map) |
|---|---|---|
| Append 16,000 chars | 504 ms | **13 ms** |
| Prepend 8,000 chars (worst case) | 1,366 ms | **5 ms** |
| Per-character cost | climbing: 7 → 10 → 31 µs | flat: ~0.6–1.1 µs |

The fix was choosing the right data structure rather than micro-optimising the wrong one. A
singly-linked list with a map from id to node removes index bookkeeping entirely — splicing is a
pointer swap, origins are hash lookups, and a tail pointer makes appending O(1). Applying a remote
operation is now **O(1) regardless of document size**; positions are resolved only where a human
supplies one.

What matters in that table isn't the speedup, it's that per-character cost stopped growing. A
constant means the editor behaves the same in a 500-character note and a 50,000-character
document.

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

Each tab holds a **real replica**, so local edits apply instantly without a server round-trip.

## Editing offline

A disconnected tab keeps working, and this is the second bug the project shipped with before it
was tested properly. The client's `send()` looked reasonable:

```js
if (socket && socket.readyState === WebSocket.OPEN) socket.send(...);   // else: silently dropped
```

Locally everything looked fine — the characters appeared, because the local replica had applied
them. But nothing was queued, so every edit typed while the socket was down was **permanently
invisible to every other replica**. The README claimed offline edits "converge on reconnect" while
the code quietly discarded them.

Operations now go to an **outbox** and flush on reconnect. Resending is safe by construction: every
operation carries a unique id, and both the server and every replica ignore ids they have already
applied, so a flush overlapping with what the server received before the drop cannot duplicate
characters.

`OfflineReconnectIntegrationTest` drives this against the real server — including a full network
partition where both clients disconnect, edit independently, then reconnect and merge with nothing
lost and nothing duplicated.

| Component | Role |
|---|---|
| `crdt/RgaDocument` | The algorithm — linked list + id→node map, with a buffer for early-arriving operations. No Spring, no I/O, no threads, so convergence is directly testable |
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
mvn test          # 46 tests
```

## Honest limitations

Things this deliberately does **not** do, so nobody has to discover them by surprise:

- **Tombstones grow without bound.** Deleted characters are never reclaimed, so a long-lived
  document's memory grows with total edits, not current length. Real systems solve this with
  garbage collection once all replicas have acknowledged a deletion — which needs version vectors
  this doesn't have.
- **One document.** There's a single shared document, not rooms or per-document sessions.
- **In-memory only.** The operation log doesn't survive a restart.
- **No authentication, no presence/cursors.** Out of scope for demonstrating the merge algorithm.
- **Node.js is required for the cross-implementation conformance test.** It skips (rather than
  fails) when Node is absent, so those four tests simply do not run on a JVM-only machine.

## Why RGA and not something else

| Approach | Trade-off |
|---|---|
| **Operational Transformation** | Mature (Google Docs), but transform functions are hard to prove correct and generally need a central server |
| **RGA** (this) | Order-independent merge, works peer-to-peer, simple to verify — costs tombstone growth |
| **Logoot / LSEQ** | Avoids tombstones with fractional position ids — ids can grow unboundedly under heavy interleaving |
| **Yjs / Automerge** | Production-grade optimised CRDTs — the right choice for real products, but using one would hide the algorithm this project exists to demonstrate |
