package com.sahithi.collab.crdt;

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
 * <p>How it works. Every character is an {@link Element} with a unique {@link OpId} and a pointer
 * to the element it was inserted after ("origin"). Applying an insert means: find the origin, then
 * walk right past any elements with a <i>higher</i> id than the incoming one, and splice in there.
 *
 * <p>That skip rule is the entire trick. Two replicas inserting concurrently after the same origin
 * will each walk past the other's element or not, but because they compare the same total order on
 * ids, they make the same decision and land on the same final ordering. The character that "wins"
 * the earlier position is arbitrary — but it is arbitrary <i>identically everywhere</i>, which is
 * all convergence requires.
 *
 * <p>This class is deliberately free of Spring, I/O, and threading so its convergence property can
 * be tested directly with randomized operation orderings (see {@code RgaConvergenceTest}).
 */
public class RgaDocument {

    /** Sentinel origin meaning "insert at the very beginning of the document". */
    public static final OpId HEAD = new OpId(0, "");

    private record Element(OpId id, OpId originId, char value, boolean deleted) {
        Element asDeleted() {
            return new Element(id, originId, value, true);
        }
    }

    private final List<Element> elements = new ArrayList<>();
    private final Map<OpId, Integer> indexById = new HashMap<>();
    private final Set<OpId> applied = new HashSet<>();

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
        var target = visibleElementAt(visibleIndex);
        if (target == null) {
            throw new IndexOutOfBoundsException("No visible character at index " + visibleIndex);
        }
        var op = new Operation.Delete(nextId(), target.id());
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
     */
    public void apply(Operation op) {
        if (!applied.add(op.id())) {
            return;
        }
        // Keep the Lamport clock ahead of everything observed, so this replica's future ids sort
        // after operations it has already seen. Without this, a replica that has been quiet would
        // issue low ids and its inserts would drift left of concurrent ones.
        counter = Math.max(counter, op.id().counter());

        switch (op) {
            case Operation.Insert insert -> applyInsert(insert);
            case Operation.Delete delete -> applyDelete(delete);
        }
    }

    private void applyInsert(Operation.Insert op) {
        var scanFrom = op.originId().equals(HEAD) ? 0 : indexOf(op.originId()) + 1;

        // Walk right past concurrent inserts that sort after this one. Every replica compares the
        // same ids with the same rule, so every replica stops at the same place.
        var insertAt = scanFrom;
        while (insertAt < elements.size() && elements.get(insertAt).id().compareTo(op.id()) > 0) {
            insertAt++;
        }

        elements.add(insertAt, new Element(op.id(), op.originId(), op.value(), false));
        reindexFrom(insertAt);
    }

    private void applyDelete(Operation.Delete op) {
        var index = indexById.get(op.targetId());
        if (index == null) {
            // The target hasn't arrived yet. Dropping the delete would resurrect the character
            // once the insert lands, so this is a real gap - see the causal-delivery note in the
            // README. The WebSocket relay preserves per-connection order, which is enough for the
            // demo, but a production build needs a buffer for out-of-order deletes.
            return;
        }
        var element = elements.get(index);
        if (!element.deleted()) {
            elements.set(index, element.asDeleted());
        }
    }

    private int indexOf(OpId id) {
        var index = indexById.get(id);
        if (index == null) {
            // Same causal-delivery caveat as above: an insert whose origin is unknown can't be
            // placed correctly. Falling back to the start keeps the document readable rather than
            // throwing, and is only reachable under out-of-order delivery.
            return -1;
        }
        return index;
    }

    private void reindexFrom(int start) {
        for (var i = start; i < elements.size(); i++) {
            indexById.put(elements.get(i).id(), i);
        }
    }

    // ---------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------

    /** The visible document text, tombstones excluded. */
    public String text() {
        var sb = new StringBuilder(elements.size());
        for (var element : elements) {
            if (!element.deleted()) {
                sb.append(element.value());
            }
        }
        return sb.toString();
    }

    /** Number of visible characters. */
    public int length() {
        var count = 0;
        for (var element : elements) {
            if (!element.deleted()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Total elements including tombstones. Exposed because unbounded tombstone growth is a real
     * property of this design worth being able to observe and talk about honestly.
     */
    public int elementCountIncludingTombstones() {
        return elements.size();
    }

    private OpId originForVisibleIndex(int visibleIndex) {
        if (visibleIndex <= 0) {
            return HEAD;
        }
        var seen = 0;
        for (var element : elements) {
            if (element.deleted()) {
                continue;
            }
            seen++;
            if (seen == visibleIndex) {
                return element.id();
            }
        }
        // Past the end: append after the last element, tombstone or not.
        return elements.isEmpty() ? HEAD : elements.get(elements.size() - 1).id();
    }

    private Element visibleElementAt(int visibleIndex) {
        var seen = 0;
        for (var element : elements) {
            if (element.deleted()) {
                continue;
            }
            if (seen == visibleIndex) {
                return element;
            }
            seen++;
        }
        return null;
    }
}
