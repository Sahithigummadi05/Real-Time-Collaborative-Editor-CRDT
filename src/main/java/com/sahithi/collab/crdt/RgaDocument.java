package com.sahithi.collab.crdt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A replicated text document built on RGA (Replicated Growable Array), a sequence CRDT.
 *
 * <p>The property that makes this worth the complexity: <b>applying the same set of operations in
 * any order, on any number of replicas, always yields the same text.</b> No locks, no server
 * arbitration, no operational transformation of concurrent edits against each other. Replicas can
 * be offline for an hour, reconnect, dump their operations at each other in whatever order they
 * arrive, and still agree.
 *
 * <p>How it works. Every character is a node with a unique {@link OpId} and a pointer to the
 * element it was inserted after ("origin"). Applying an insert means: find the origin, then walk
 * right past any elements with a <i>higher</i> id than the incoming one, and splice in there.
 *
 * <p>That skip rule is the entire trick. Two replicas inserting concurrently after the same origin
 * will each walk past the other's element or not, but because they compare the same total order on
 * ids, they make the same decision and land on the same final ordering. The character that "wins"
 * the earlier position is arbitrary — but it is arbitrary <i>identically everywhere</i>, which is
 * all convergence requires.
 *
 * <h2>Why a linked list rather than an array</h2>
 *
 * <p>The obvious representation is an {@code ArrayList} of characters plus a map from id to index.
 * That was the first implementation here and it was <b>quadratic</b>: every insert had to shift the
 * array and then rewrite every downstream index in the map, so cost grew with document size.
 * Measured, typing 8,000 characters at the start of a document took 1.37 seconds and per-character
 * cost was climbing steadily — an editor that gets slower the more you write.
 *
 * <p>A singly-linked list with a map from id to node removes the index bookkeeping entirely:
 * splicing is a pointer swap and origins are found by hash lookup, so applying a remote operation
 * is O(1) regardless of document size. Positions are then only resolved where a human actually
 * supplies one — a local edit at a caret — and appending is O(1) via a tail pointer. See
 * {@code RgaPerformanceTest} for the before/after numbers.
 *
 * <p>This class is deliberately free of Spring, I/O, and threading so its convergence property can
 * be tested directly with randomized operation orderings (see {@code RgaConvergenceTest}).
 */
public class RgaDocument {

    /** Sentinel origin meaning "insert at the very beginning of the document". */
    public static final OpId HEAD = new OpId(0, "");

    private static final class Node {
        final OpId id;
        final char value;
        boolean deleted;
        Node next;

        Node(OpId id, char value) {
            this.id = id;
            this.value = value;
        }
    }

    /** Fixed sentinel so "insert at the beginning" needs no special case in the splice logic. */
    private final Node head = new Node(HEAD, '\0');
    private Node tail = head;

    private final Map<OpId, Node> nodesById = new HashMap<>();
    private final Set<OpId> applied = new HashSet<>();

    /** Maintained incrementally; recomputing it per keystroke was one of the quadratic costs. */
    private int visibleCount;
    private int totalCount;

    /**
     * Operations that arrived before the element they depend on, keyed by the id they are waiting
     * for. An insert can't be positioned until its origin exists, and a delete can't tombstone a
     * character that hasn't arrived — so rather than guessing, they wait here until the missing
     * operation shows up, then apply immediately.
     */
    private final Map<OpId, List<Operation>> pending = new HashMap<>();

    /** Ids sitting in {@link #pending}, so a re-delivered operation isn't buffered twice. */
    private final Set<OpId> buffered = new HashSet<>();

    private final String replicaId;
    private long counter;

    public RgaDocument(String replicaId) {
        this.replicaId = replicaId;
    }

    public String replicaId() {
        return replicaId;
    }

    // ---------------------------------------------------------------------
    // Local edits: turn an index-based user action into a position-independent operation.
    // ---------------------------------------------------------------------

    /**
     * Records a local insertion at a visible character index and returns the operation to
     * broadcast. Index-based here is fine because it is resolved against <i>this</i> replica's
     * current state immediately; what goes on the wire is the resulting origin id.
     */
    public Operation.Insert insertAt(int visibleIndex, char value) {
        var originId = originForVisibleIndex(visibleIndex);
        var op = new Operation.Insert(nextId(), originId, value);
        apply(op);
        return op;
    }

    /** Records a local deletion at a visible character index and returns the operation to broadcast. */
    public Operation.Delete deleteAt(int visibleIndex) {
        var target = visibleNodeAt(visibleIndex);
        if (target == null) {
            throw new IndexOutOfBoundsException("No visible character at index " + visibleIndex);
        }
        var op = new Operation.Delete(nextId(), target.id);
        apply(op);
        return op;
    }

    private OpId nextId() {
        return new OpId(++counter, replicaId);
    }

    // ---------------------------------------------------------------------
    // Remote edits
    // ---------------------------------------------------------------------

    /**
     * Applies an operation from any replica, including this one.
     *
     * <p>Idempotent by id, because at-least-once delivery is normal in a WebSocket relay:
     * reconnect-and-resync will happily hand over operations a replica already has. Re-applying an
     * insert would otherwise duplicate a character.
     *
     * <p>Operations that arrive before their dependency are buffered rather than applied against a
     * document with nowhere to anchor them — see {@code OutOfOrderDeliveryTest}.
     */
    public void apply(Operation op) {
        if (applied.contains(op.id()) || buffered.contains(op.id())) {
            return;
        }

        var missing = missingDependency(op);
        if (missing != null) {
            // Out-of-order arrival. Hold it rather than applying it against a document that has
            // no anchor for it - guessing a position here is how replicas silently corrupt.
            pending.computeIfAbsent(missing, key -> new ArrayList<>()).add(op);
            buffered.add(op.id());
            return;
        }

        applyNow(op);
        releaseDependents(op.id());
    }

    /**
     * Applies everything that was waiting on {@code arrived}, and anything that was in turn
     * waiting on those - a single late operation can unblock a whole chain, so this drains
     * transitively rather than one level deep.
     */
    private void releaseDependents(OpId arrived) {
        var queue = new ArrayDeque<OpId>();
        queue.add(arrived);

        while (!queue.isEmpty()) {
            var unblocked = pending.remove(queue.poll());
            if (unblocked == null) {
                continue;
            }
            for (var waiting : unblocked) {
                buffered.remove(waiting.id());
                if (applied.contains(waiting.id())) {
                    continue;
                }
                applyNow(waiting);
                queue.add(waiting.id());
            }
        }
    }

    private void applyNow(Operation op) {
        applied.add(op.id());
        // Keep the Lamport clock ahead of everything observed, so this replica's future ids sort
        // after operations it has already seen. Without this, a replica that has been quiet would
        // issue low ids and its inserts would drift left of concurrent ones.
        counter = Math.max(counter, op.id().counter());

        switch (op) {
            case Operation.Insert insert -> applyInsert(insert);
            case Operation.Delete delete -> applyDelete(delete);
        }
    }

    /** The element id this operation needs before it can be applied, or null if it's ready. */
    private OpId missingDependency(Operation op) {
        OpId required = switch (op) {
            case Operation.Insert insert -> insert.originId().equals(HEAD) ? null : insert.originId();
            case Operation.Delete delete -> delete.targetId();
        };
        if (required == null || nodesById.containsKey(required)) {
            return null;
        }
        return required;
    }

    /**
     * How many operations are currently held back waiting on dependencies. Steady-state this is
     * zero; a persistently non-zero value means operations were lost in transit rather than merely
     * reordered.
     */
    public int pendingOperationCount() {
        return buffered.size();
    }

    /**
     * Splices a character into the sequence. O(1) apart from walking concurrent inserts competing
     * for the same slot, which is bounded by how many replicas edited that exact position at once
     * — in practice a handful, not a function of document length.
     */
    private void applyInsert(Operation.Insert op) {
        // Guaranteed present: apply() buffers inserts whose origin hasn't arrived.
        var origin = op.originId().equals(HEAD) ? head : nodesById.get(op.originId());

        // Walk right past concurrent inserts that sort after this one. Every replica compares the
        // same ids with the same rule, so every replica stops at the same place.
        var previous = origin;
        while (previous.next != null && previous.next.id.compareTo(op.id()) > 0) {
            previous = previous.next;
        }

        var node = new Node(op.id(), op.value());
        node.next = previous.next;
        previous.next = node;
        if (node.next == null) {
            tail = node;
        }

        nodesById.put(op.id(), node);
        visibleCount++;
        totalCount++;
    }

    private void applyDelete(Operation.Delete op) {
        // Guaranteed present: apply() buffers deletes whose target hasn't arrived.
        var node = nodesById.get(op.targetId());
        if (!node.deleted) {
            node.deleted = true;
            visibleCount--;
        }
    }

    // ---------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------

    /** The visible document text, tombstones excluded. */
    public String text() {
        var sb = new StringBuilder(visibleCount);
        for (var node = head.next; node != null; node = node.next) {
            if (!node.deleted) {
                sb.append(node.value);
            }
        }
        return sb.toString();
    }

    /** Number of visible characters. O(1). */
    public int length() {
        return visibleCount;
    }

    /**
     * Total elements including tombstones. Exposed because unbounded tombstone growth is a real
     * property of this design worth being able to observe and talk about honestly.
     */
    public int elementCountIncludingTombstones() {
        return totalCount;
    }

    /**
     * Resolves a caret position to the id of the character it follows.
     *
     * <p>Appending — by far the common case while typing — short-circuits to the tail, so it costs
     * nothing. Other positions walk the list, which is proportional to how far into the document
     * the caret sits rather than to the document's total length.
     */
    private OpId originForVisibleIndex(int visibleIndex) {
        if (visibleIndex <= 0) {
            return HEAD;
        }
        if (visibleIndex >= visibleCount) {
            return tail.id; // append: the sentinel's id when the document is empty
        }
        var seen = 0;
        for (var node = head.next; node != null; node = node.next) {
            if (node.deleted) {
                continue;
            }
            seen++;
            if (seen == visibleIndex) {
                return node.id;
            }
        }
        return tail.id;
    }

    private Node visibleNodeAt(int visibleIndex) {
        if (visibleIndex < 0 || visibleIndex >= visibleCount) {
            return null;
        }
        var seen = 0;
        for (var node = head.next; node != null; node = node.next) {
            if (node.deleted) {
                continue;
            }
            if (seen == visibleIndex) {
                return node;
            }
            seen++;
        }
        return null;
    }
}
