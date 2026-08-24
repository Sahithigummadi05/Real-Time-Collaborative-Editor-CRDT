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
| `RgaDocumentTest` | Insert/delete/tombstone semantics, idempotent replay, Lamport clock advance |
| `RgaPerformanceTest` | Insert cost stays flat as the document grows |
| `EditorSyncIntegrationTest` | Two and three real WebSocket clients converge end-to-end through the running server |

```
39 tests, 0 failures
```

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

Each tab holds a **real replica**, so local edits apply instantly without a server round-trip, and
a disconnected tab keeps working — its operations converge on reconnect.

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
mvn test          # 39 tests
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

## Why RGA and not something else

| Approach | Trade-off |
|---|---|
| **Operational Transformation** | Mature (Google Docs), but transform functions are hard to prove correct and generally need a central server |
| **RGA** (this) | Order-independent merge, works peer-to-peer, simple to verify — costs tombstone growth |
| **Logoot / LSEQ** | Avoids tombstones with fractional position ids — ids can grow unboundedly under heavy interleaving |
| **Yjs / Automerge** | Production-grade optimised CRDTs — the right choice for real products, but using one would hide the algorithm this project exists to demonstrate |
