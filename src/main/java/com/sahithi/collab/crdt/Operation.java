package com.sahithi.collab.crdt;

/**
 * A single edit, expressed so that it can be applied on any replica in any order.
 *
 * <p>Crucially, positions are never expressed as integer indices. "Insert at index 5" is
 * meaningless on a replica whose document has diverged — index 5 there refers to a different
 * character. Instead an insert names the {@code originId} it goes *after*, and a delete names the
 * exact {@code targetId} to remove. Those identities are stable no matter what else has happened.
 */
public sealed interface Operation {

    OpId id();

    record Insert(OpId id, OpId originId, char value) implements Operation {
    }

    /**
     * Deletes are tombstones rather than real removals: the element stays in the sequence marked
     * dead. It has to, because a concurrent insert may still name it as an origin, and that insert
     * would have nowhere to attach if the element had actually been removed.
     */
    record Delete(OpId id, OpId targetId) implements Operation {
    }
}
