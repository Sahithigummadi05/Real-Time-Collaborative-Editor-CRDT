package com.sahithi.collab.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Counter-proof: shows the convergence tests would actually fail if the algorithm were wrong.
 *
 * <p>A passing test suite only means something if it can fail. {@link RgaConvergenceTest} asserts
 * that random histories converge — but if RGA's ordering rule were quietly removed, would those
 * tests notice, or would they pass regardless and prove nothing?
 *
 * <p>So this test reimplements the document with the one line that matters deleted: instead of
 * walking right past concurrent inserts with higher ids, {@link NaiveDocument} splices every
 * insert in immediately after its origin. That is the intuitive implementation, and it is wrong.
 * Replaying an identical history in two different causal orders then produces two different
 * documents — which is exactly the failure the real tests are there to catch.
 */
class SkipRuleIsLoadBearingTest {

    @Test
    @DisplayName("without RGA's ordering rule, the same history replayed in different orders diverges")
    void naiveInsertDivergesAcrossOrderings() {
        var history = generateConcurrentHistory();

        var firstOrder = replayNaive(history, new Random(1));
        var divergentFound = false;
        for (var attempt = 0; attempt < 50 && !divergentFound; attempt++) {
            var otherOrder = replayNaive(history, new Random(attempt + 2));
            if (!otherOrder.equals(firstOrder)) {
                divergentFound = true;
            }
        }

        assertThat(divergentFound)
                .as("the naive implementation must diverge - if it didn't, the convergence "
                        + "tests would pass with or without RGA's ordering rule and prove nothing")
                .isTrue();
    }

    @Test
    @DisplayName("the real implementation converges on the very history that breaks the naive one")
    void realImplementationConvergesOnTheSameHistory() {
        var history = generateConcurrentHistory();

        var reference = replayReal(history, new Random(1));
        for (var attempt = 0; attempt < 50; attempt++) {
            assertThat(replayReal(history, new Random(attempt + 2))).isEqualTo(reference);
        }
    }

    /**
     * Builds a history with heavy concurrency at the same positions - several replicas inserting
     * at index 0 and in the middle without seeing each other, which is precisely where ordering
     * rules matter.
     */
    private List<Operation> generateConcurrentHistory() {
        var a = new RgaDocument("A");
        var b = new RgaDocument("B");
        var c = new RgaDocument("C");
        var all = new ArrayList<Operation>();

        var shared = new ArrayList<Operation>();
        for (var ch : "base".toCharArray()) {
            shared.add(a.insertAt(a.length(), ch));
        }
        shared.forEach(b::apply);
        shared.forEach(c::apply);
        all.addAll(shared);

        // Three replicas now edit the same regions concurrently, with no exchange between them.
        for (var i = 0; i < 6; i++) {
            all.add(a.insertAt(0, (char) ('A' + i)));
            all.add(b.insertAt(0, (char) ('a' + i)));
            all.add(c.insertAt(Math.min(2, c.length()), (char) ('0' + i)));
        }
        return all;
    }

    private String replayReal(List<Operation> history, Random random) {
        var doc = new RgaDocument("replay");
        for (var op : causalOrder(history, random)) {
            doc.apply(op);
        }
        return doc.text();
    }

    private String replayNaive(List<Operation> history, Random random) {
        var doc = new NaiveDocument();
        for (var op : causalOrder(history, random)) {
            doc.apply(op);
        }
        return doc.text();
    }

    /** A randomized delivery order that still never delivers an insert before its origin. */
    private List<Operation> causalOrder(List<Operation> history, Random random) {
        var created = new HashMap<OpId, Operation>();
        for (var op : history) {
            if (op instanceof Operation.Insert) {
                created.put(op.id(), op);
            }
        }
        var remaining = new ArrayList<>(history);
        var delivered = new HashSet<OpId>();
        var ordered = new ArrayList<Operation>();

        while (!remaining.isEmpty()) {
            var ready = new ArrayList<Operation>();
            for (var op : remaining) {
                OpId needs = switch (op) {
                    case Operation.Insert insert ->
                            insert.originId().equals(RgaDocument.HEAD) ? null : insert.originId();
                    case Operation.Delete delete -> delete.targetId();
                };
                if (needs == null || !created.containsKey(needs) || delivered.contains(needs)) {
                    ready.add(op);
                }
            }
            var next = ready.get(random.nextInt(ready.size()));
            ordered.add(next);
            delivered.add(next.id());
            remaining.remove(next);
        }
        return ordered;
    }

    /**
     * The intuitive-but-wrong implementation: place each insert directly after its origin and
     * never consider concurrent inserts competing for the same slot.
     */
    private static final class NaiveDocument {
        private record Element(OpId id, char value, boolean deleted) {
        }

        private final List<Element> elements = new ArrayList<>();
        private final Map<OpId, Integer> index = new HashMap<>();

        void apply(Operation op) {
            switch (op) {
                case Operation.Insert insert -> {
                    var at = insert.originId().equals(RgaDocument.HEAD)
                            ? 0
                            : index.getOrDefault(insert.originId(), -1) + 1;
                    elements.add(at, new Element(insert.id(), insert.value(), false));
                    reindex();
                }
                case Operation.Delete delete -> {
                    var i = index.get(delete.targetId());
                    if (i != null && !elements.get(i).deleted()) {
                        elements.set(i, new Element(elements.get(i).id(), elements.get(i).value(), true));
                    }
                }
            }
        }

        private void reindex() {
            index.clear();
            for (var i = 0; i < elements.size(); i++) {
                index.put(elements.get(i).id(), i);
            }
        }

        String text() {
            var sb = new StringBuilder();
            for (var e : elements) {
                if (!e.deleted()) {
                    sb.append(e.value());
                }
            }
            return sb.toString();
        }
    }
}
