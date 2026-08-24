package com.sahithi.collab.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Convergence when the network misbehaves.
 *
 * <p>{@link RgaConvergenceTest} shuffles delivery but keeps it causally valid — an insert never
 * arrives before the character it anchors to. That's the precondition RGA is stated against, and
 * a single ordered WebSocket connection happens to satisfy it.
 *
 * <p>Real deployments don't. Multiple connections, a peer-to-peer sync, a client replaying a
 * partial log, or a reconnect that interleaves buffered and live traffic can all deliver an
 * operation before its dependency. Earlier this class of arrival was handled by guessing a
 * position, which silently corrupted the document. These tests pin down the fix: operations that
 * arrive early are buffered until their dependency lands, so the final text is identical to
 * in-order delivery.
 */
class OutOfOrderDeliveryTest {

    /** A linear "hello world" history, plus a deletion, from a single replica. */
    private List<Operation> linearHistory() {
        var doc = new RgaDocument("A");
        var ops = new ArrayList<Operation>();
        for (var ch : "hello world".toCharArray()) {
            ops.add(doc.insertAt(doc.length(), ch));
        }
        ops.add(doc.deleteAt(5)); // remove the space
        return ops;
    }

    @Test
    @DisplayName("fully reversed delivery still produces the correct text")
    void reversedDeliveryConverges() {
        var history = linearHistory();
        var expected = replayInOrder(history);

        var doc = new RgaDocument("R");
        var reversed = new ArrayList<>(history);
        Collections.reverse(reversed);
        reversed.forEach(doc::apply);

        assertThat(doc.text()).isEqualTo(expected);
        assertThat(doc.pendingOperationCount())
                .as("everything should eventually be released from the buffer")
                .isZero();
    }

    @Test
    @DisplayName("a delete arriving before its target does not get lost")
    void deleteBeforeInsertIsNotDropped() {
        var source = new RgaDocument("A");
        var insertA = source.insertAt(0, 'a');
        var insertB = source.insertAt(1, 'b');
        var deleteB = source.deleteAt(1);

        var doc = new RgaDocument("R");
        doc.apply(deleteB); // target doesn't exist yet - must be held, not discarded
        assertThat(doc.pendingOperationCount()).isEqualTo(1);

        doc.apply(insertA);
        doc.apply(insertB); // this releases the buffered delete

        assertThat(doc.text()).isEqualTo("a");
        assertThat(doc.pendingOperationCount()).isZero();
    }

    @Test
    @DisplayName("a chain of operations delivered back-to-front unblocks transitively")
    void chainedDependenciesReleaseTransitively() {
        var source = new RgaDocument("A");
        var ops = new ArrayList<Operation>();
        for (var ch : "abcdef".toCharArray()) {
            ops.add(source.insertAt(source.length(), ch));
        }

        var doc = new RgaDocument("R");
        // Deliver 'f' first, then 'e', ... each one waiting on the previous. Delivering 'a' last
        // must cascade and release all five in one go.
        for (var i = ops.size() - 1; i >= 1; i--) {
            doc.apply(ops.get(i));
        }
        assertThat(doc.pendingOperationCount()).isEqualTo(5);
        assertThat(doc.text()).isEmpty();

        doc.apply(ops.get(0));

        assertThat(doc.text()).isEqualTo("abcdef");
        assertThat(doc.pendingOperationCount()).isZero();
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 5, 13, 42, 77, 101, 256, 999})
    @DisplayName("arbitrary non-causal shuffles converge to the in-order result")
    void arbitraryShufflesConverge(int seed) {
        var history = multiReplicaHistory(new Random(seed));
        var expected = replayInOrder(history);

        var random = new Random(seed * 31L);
        for (var attempt = 0; attempt < 20; attempt++) {
            var shuffled = new ArrayList<>(history);
            Collections.shuffle(shuffled, random); // no causality respected at all
            var doc = new RgaDocument("R" + attempt);
            shuffled.forEach(doc::apply);

            assertThat(doc.text())
                    .as("shuffle #%d (seed %d) must match in-order delivery", attempt, seed)
                    .isEqualTo(expected);
            assertThat(doc.pendingOperationCount()).isZero();
        }
    }

    @Test
    @DisplayName("operations still missing leave the document consistent, not corrupted")
    void partialDeliveryHoldsRatherThanGuessing() {
        var source = new RgaDocument("A");
        var first = source.insertAt(0, 'x');
        var second = source.insertAt(1, 'y');

        var doc = new RgaDocument("R");
        doc.apply(second); // depends on 'x', which never arrives

        // The safe outcome is "not yet visible", not "inserted somewhere arbitrary".
        assertThat(doc.text()).isEmpty();
        assertThat(doc.pendingOperationCount()).isEqualTo(1);

        doc.apply(first);
        assertThat(doc.text()).isEqualTo("xy");
    }

    private String replayInOrder(List<Operation> history) {
        var doc = new RgaDocument("reference");
        history.forEach(doc::apply);
        return doc.text();
    }

    private List<Operation> multiReplicaHistory(Random random) {
        var a = new RgaDocument("A");
        var b = new RgaDocument("B");
        var all = new ArrayList<Operation>();

        var shared = new ArrayList<Operation>();
        for (var ch : "seed".toCharArray()) {
            shared.add(a.insertAt(a.length(), ch));
        }
        shared.forEach(b::apply);
        all.addAll(shared);

        for (var i = 0; i < 20; i++) {
            var doc = random.nextBoolean() ? a : b;
            if (doc.length() > 0 && random.nextInt(100) < 25) {
                all.add(doc.deleteAt(random.nextInt(doc.length())));
            } else {
                all.add(doc.insertAt(random.nextInt(doc.length() + 1), (char) ('a' + random.nextInt(26))));
            }
        }
        return all;
    }
}
