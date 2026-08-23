package com.sahithi.collab.crdt;

/**
 * Globally unique, totally ordered identifier for a single character insertion.
 *
 * <p>{@code counter} is a Lamport clock, so it captures causality: if one insert happened after
 * another was observed, it carries a strictly higher counter. {@code replicaId} breaks ties
 * between genuinely concurrent inserts that happen to share a counter value.
 *
 * <p>The tie-break is what makes convergence possible at all. When two replicas insert at the
 * same position simultaneously, neither "happened first" — there is no fact of the matter about
 * which should win. So the algorithm needs an arbitrary but *identical everywhere* rule, and
 * comparing (counter, replicaId) gives every replica the same answer without any coordination.
 */
public record OpId(long counter, String replicaId) implements Comparable<OpId> {

    @Override
    public int compareTo(OpId other) {
        var byCounter = Long.compare(counter, other.counter);
        return byCounter != 0 ? byCounter : replicaId.compareTo(other.replicaId);
    }

    @Override
    public String toString() {
        return counter + "@" + replicaId;
    }
}
