package com.sahithi.collab.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The tests that justify using a CRDT at all.
 *
 * <p>A convergence claim is only worth something if it's checked against orderings a human
 * wouldn't think to try. So rather than hand-picking a few interleavings, these tests generate
 * random edit histories across several replicas and then replay each history in many different
 * randomized orders, asserting every replay lands on identical text.
 *
 * <p>Orders are randomized but <b>causally valid</b>: an insert is never delivered before the
 * element it attaches to. That's the standard precondition for CRDT convergence and the guarantee
 * a real transport is expected to provide, so testing arbitrary non-causal orders would be
 * testing something the algorithm never promised.
 */
class RgaConvergenceTest {

    @Test
    @DisplayName("two replicas inserting at the same position converge to the same text")
    void concurrentInsertAtSamePositionConverges() {
        var a = new RgaDocument("A");
        var b = new RgaDocument("B");

        // Both start from a shared "hi".
        var shared = new ArrayList<Operation>();
        shared.add(a.insertAt(0, 'h'));
        shared.add(a.insertAt(1, 'i'));
        shared.forEach(b::apply);

        // Now both type a different character at index 1, without seeing each other.
        var fromA = a.insertAt(1, 'X');
        var fromB = b.insertAt(1, 'Y');

        // Exchange.
        a.apply(fromB);
        b.apply(fromA);

        assertThat(a.text()).isEqualTo(b.text());
        // One of them wins the earlier slot; which one is arbitrary, but no character is lost.
        assertThat(a.text()).isIn("hXYi", "hYXi");
        assertThat(a.text()).hasSize(4);
    }

    @Test
    @DisplayName("concurrent delete and insert at the same spot both take effect")
    void concurrentDeleteAndInsertConverge() {
        var a = new RgaDocument("A");
        var b = new RgaDocument("B");

        var setup = new ArrayList<Operation>();
        for (var c : "abc".toCharArray()) {
            setup.add(a.insertAt(a.length(), c));
        }
        setup.forEach(b::apply);

        var deleteB = a.deleteAt(1); // A removes 'b'
        var insertZ = b.insertAt(1, 'Z'); // B inserts 'Z' before 'b', concurrently

        a.apply(insertZ);
        b.apply(deleteB);

        assertThat(a.text()).isEqualTo(b.text());
        assertThat(a.text()).isEqualTo("aZc");
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 7, 11, 42, 99, 123, 2024, 31337})
    @DisplayName("random multi-replica histories converge under every causal replay order")
    void randomHistoriesConvergeAcrossOrderings(int seed) {
        var random = new Random(seed);
        var history = generateRandomHistory(random, 4, 60);

        // A reference result: apply in generation order.
        var reference = replay(history, random, false);

        // Then many randomized causal orders. Every one must agree with the reference.
        for (var attempt = 0; attempt < 25; attempt++) {
            var replayed = replay(history, random, true);
            assertThat(replayed)
                    .as("replay #%d of %d ops (seed %d) must match the reference text",
                            attempt, history.size(), seed)
                    .isEqualTo(reference);
        }

        assertThat(reference).isNotEmpty();
    }

    @Test
    @DisplayName("a replica that goes offline and syncs later catches up exactly")
    void offlineReplicaCatchesUp() {
        var online = new RgaDocument("online");
        var offline = new RgaDocument("offline");

        var shared = new ArrayList<Operation>();
        for (var c : "start ".toCharArray()) {
            shared.add(online.insertAt(online.length(), c));
        }
        shared.forEach(offline::apply);

        // They diverge: each edits independently with no communication.
        var onlineOps = new ArrayList<Operation>();
        for (var c : "online".toCharArray()) {
            onlineOps.add(online.insertAt(online.length(), c));
        }
        var offlineOps = new ArrayList<Operation>();
        for (var c : "offline".toCharArray()) {
            offlineOps.add(offline.insertAt(offline.length(), c));
        }

        assertThat(online.text()).isNotEqualTo(offline.text());

        // Reconnect and exchange everything both ways.
        offlineOps.forEach(online::apply);
        onlineOps.forEach(offline::apply);

        assertThat(online.text()).isEqualTo(offline.text());
        assertThat(online.text()).startsWith("start ");
        assertThat(online.text()).hasSize("start ".length() + "online".length() + "offline".length());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Simulates several replicas editing independently and syncing at random moments, returning
     * the full set of operations produced. Replicas only ever build on state they have actually
     * observed, so the resulting history is causally well-formed by construction.
     */
    private List<Operation> generateRandomHistory(Random random, int replicaCount, int editCount) {
        var replicas = new ArrayList<RgaDocument>();
        for (var i = 0; i < replicaCount; i++) {
            replicas.add(new RgaDocument("r" + i));
        }
        // Per replica, the ops it has not yet sent to each peer.
        var pending = new ArrayList<List<Operation>>();
        for (var i = 0; i < replicaCount; i++) {
            pending.add(new ArrayList<>());
        }

        var all = new ArrayList<Operation>();

        for (var step = 0; step < editCount; step++) {
            var index = random.nextInt(replicaCount);
            var replica = replicas.get(index);

            var doDelete = replica.length() > 0 && random.nextInt(100) < 25;
            Operation op;
            if (doDelete) {
                op = replica.deleteAt(random.nextInt(replica.length()));
            } else {
                var at = replica.length() == 0 ? 0 : random.nextInt(replica.length() + 1);
                op = replica.insertAt(at, randomChar(random));
            }
            all.add(op);
            pending.get(index).add(op);

            // Occasionally flush one replica's backlog to everyone else.
            if (random.nextInt(100) < 40) {
                var source = random.nextInt(replicaCount);
                var backlog = pending.get(source);
                for (var target = 0; target < replicaCount; target++) {
                    if (target == source) {
                        continue;
                    }
                    backlog.forEach(replicas.get(target)::apply);
                }
                backlog.clear();
            }
        }
        return all;
    }

    private static char randomChar(Random random) {
        return (char) ('a' + random.nextInt(26));
    }

    /**
     * Replays a history into a fresh replica. When {@code shuffle} is set, the delivery order is
     * randomized while still respecting causality: an operation is only delivered once the
     * operation that created the element it refers to has been delivered.
     */
    private String replay(List<Operation> history, Random random, boolean shuffle) {
        var doc = new RgaDocument("replay");
        var remaining = new ArrayList<>(history);
        var delivered = new HashSet<OpId>();
        var creators = creatorIndex(history);

        while (!remaining.isEmpty()) {
            var ready = new ArrayList<Operation>();
            for (var op : remaining) {
                if (dependencySatisfied(op, delivered, creators)) {
                    ready.add(op);
                }
            }
            if (ready.isEmpty()) {
                throw new IllegalStateException(
                        "History is not causally replayable - " + remaining.size() + " ops stuck");
            }
            var next = shuffle ? ready.get(random.nextInt(ready.size())) : ready.get(0);
            doc.apply(next);
            delivered.add(next.id());
            remaining.remove(next);
        }
        return doc.text();
    }

    /** Maps each element id to the operation that brought it into existence. */
    private Map<OpId, Operation> creatorIndex(List<Operation> history) {
        var byId = new HashMap<OpId, Operation>();
        for (var op : history) {
            if (op instanceof Operation.Insert) {
                byId.put(op.id(), op);
            }
        }
        return byId;
    }

    private boolean dependencySatisfied(Operation op, Set<OpId> delivered, Map<OpId, Operation> creators) {
        OpId dependsOn = switch (op) {
            case Operation.Insert insert ->
                    insert.originId().equals(RgaDocument.HEAD) ? null : insert.originId();
            case Operation.Delete delete -> delete.targetId();
        };
        if (dependsOn == null) {
            return true;
        }
        // If nothing in this history created the referenced element, there is nothing to wait for.
        return !creators.containsKey(dependsOn) || delivered.contains(dependsOn);
    }
}
