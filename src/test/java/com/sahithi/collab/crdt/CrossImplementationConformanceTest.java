package com.sahithi.collab.crdt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahithi.collab.ws.OperationCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Differential test between the two implementations of the algorithm.
 *
 * <p>This project contains RGA twice: once in Java for the server, once in JavaScript so each
 * browser tab can hold a real replica and edit without a round-trip. That duplication is the
 * single most dangerous thing in the codebase. Both sides pass their own tests, both look correct
 * in review, and if they ever disagree about how to order two concurrent inserts, replicas diverge
 * silently — producing exactly the corruption the whole project exists to prevent. Nothing in a
 * per-language test suite can catch that, because each suite only ever compares an implementation
 * against itself.
 *
 * <p>So the two are compared directly: identical operation histories are fed through the Java
 * implementation and, via Node, through the browser implementation, and the resulting documents
 * must match character for character. Histories include heavy concurrency at identical positions
 * (where tie-breaking decides the outcome) and non-causal delivery (where the buffering logic has
 * to behave identically), because those are the paths where a subtle divergence would hide.
 *
 * <p>Skipped rather than failed when Node isn't installed, so a JVM-only machine can still build.
 */
class CrossImplementationConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OperationCodec CODEC = new OperationCodec();

    private static boolean nodeAvailable;

    @BeforeAll
    static void detectNode() {
        try {
            var process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            nodeAvailable = process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            nodeAvailable = false;
        }
    }

    @Test
    @DisplayName("both implementations agree on heavily concurrent edits at the same position")
    void agreeOnConcurrentInsertsAtSamePosition() throws Exception {
        assumeTrue(nodeAvailable, "Node.js not available - skipping cross-implementation test");

        var histories = new ArrayList<List<Operation>>();
        for (var seed = 0; seed < 10; seed++) {
            histories.add(concurrentHistory(new Random(seed)));
        }

        assertImplementationsAgree(histories);
    }

    @Test
    @DisplayName("both implementations agree on random multi-replica histories")
    void agreeOnRandomHistories() throws Exception {
        assumeTrue(nodeAvailable, "Node.js not available - skipping cross-implementation test");

        var histories = new ArrayList<List<Operation>>();
        for (var seed = 100; seed < 115; seed++) {
            histories.add(randomHistory(new Random(seed)));
        }

        assertImplementationsAgree(histories);
    }

    @Test
    @DisplayName("both implementations buffer out-of-order delivery identically")
    void agreeOnNonCausalDelivery() throws Exception {
        assumeTrue(nodeAvailable, "Node.js not available - skipping cross-implementation test");

        var histories = new ArrayList<List<Operation>>();
        for (var seed = 200; seed < 212; seed++) {
            var random = new Random(seed);
            var history = new ArrayList<>(randomHistory(random));
            // Deliberately destroy causality - both sides must hold operations back and release
            // them in the same way, or they will disagree.
            Collections.shuffle(history, random);
            histories.add(history);
        }

        assertImplementationsAgree(histories);
    }

    @Test
    @DisplayName("both implementations agree when delivery is fully reversed")
    void agreeOnReversedDelivery() throws Exception {
        assumeTrue(nodeAvailable, "Node.js not available - skipping cross-implementation test");

        var histories = new ArrayList<List<Operation>>();
        for (var seed = 300; seed < 308; seed++) {
            var history = new ArrayList<>(randomHistory(new Random(seed)));
            Collections.reverse(history);
            histories.add(history);
        }

        assertImplementationsAgree(histories);
    }

    // ------------------------------------------------------------------
    // Comparison harness
    // ------------------------------------------------------------------

    private void assertImplementationsAgree(List<List<Operation>> histories) throws Exception {
        var javaTexts = new ArrayList<String>();
        var javaPending = new ArrayList<Integer>();
        for (var history : histories) {
            var doc = new RgaDocument("java-replica");
            history.forEach(doc::apply);
            javaTexts.add(doc.text());
            javaPending.add(doc.pendingOperationCount());
        }

        var jsResult = runJavaScript(histories);

        for (var i = 0; i < histories.size(); i++) {
            assertThat(jsResult.texts().get(i))
                    .as("history #%d (%d ops): JS and Java must produce identical text",
                            i, histories.get(i).size())
                    .isEqualTo(javaTexts.get(i));
            assertThat(jsResult.pending().get(i))
                    .as("history #%d: both implementations must hold back the same operations", i)
                    .isEqualTo(javaPending.get(i));
        }

        // Guard against a vacuous pass: if every history produced empty text, the comparison above
        // would succeed without exercising anything.
        assertThat(javaTexts).anyMatch(text -> !text.isEmpty());
    }

    private record JsResult(List<String> texts, List<Integer> pending) {
    }

    private JsResult runJavaScript(List<List<Operation>> histories) throws Exception {
        var payload = MAPPER.createObjectNode();
        var historiesNode = payload.putArray("histories");
        for (var history : histories) {
            var arr = historiesNode.addArray();
            history.forEach(op -> arr.add(CODEC.encode(op)));
        }

        var inputFile = Files.createTempFile("rga-conformance", ".json");
        var runner = Path.of("src", "test", "resources", "js", "conformance-runner.mjs").toAbsolutePath();
        var rgaJs = Path.of("src", "main", "resources", "static", "rga.js").toAbsolutePath();
        try {
            Files.writeString(inputFile, payload.toString(), StandardCharsets.UTF_8);

            var process = new ProcessBuilder(
                    "node", runner.toString(), rgaJs.toString(), inputFile.toString())
                    .start();

            var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("node run timed out").isTrue();
            assertThat(process.exitValue())
                    .as("node runner failed:%n%s", stderr)
                    .isZero();

            var parsed = MAPPER.readTree(stdout);
            var texts = new ArrayList<String>();
            parsed.withArray("texts").forEach(node -> texts.add(node.asText()));
            var pending = new ArrayList<Integer>();
            parsed.withArray("pending").forEach(node -> pending.add(node.asInt()));
            return new JsResult(texts, pending);
        } finally {
            Files.deleteIfExists(inputFile);
        }
    }

    // ------------------------------------------------------------------
    // History generation
    // ------------------------------------------------------------------

    /** Several replicas inserting at the same positions without seeing each other. */
    private List<Operation> concurrentHistory(Random random) {
        var a = new RgaDocument("A");
        var b = new RgaDocument("B");
        var c = new RgaDocument("C");
        var all = new ArrayList<Operation>();

        var shared = new ArrayList<Operation>();
        for (var ch : "shared".toCharArray()) {
            shared.add(a.insertAt(a.length(), ch));
        }
        shared.forEach(b::apply);
        shared.forEach(c::apply);
        all.addAll(shared);

        for (var i = 0; i < 8; i++) {
            // All three target index 0 and the middle - the positions where tie-breaking decides
            // the result, and therefore where the two implementations could disagree.
            all.add(a.insertAt(0, (char) ('A' + random.nextInt(26))));
            all.add(b.insertAt(0, (char) ('a' + random.nextInt(26))));
            all.add(c.insertAt(Math.min(3, c.length()), (char) ('0' + random.nextInt(10))));
        }
        return all;
    }

    /** Two replicas editing and syncing at random, with deletions mixed in. */
    private List<Operation> randomHistory(Random random) {
        var a = new RgaDocument("A");
        var b = new RgaDocument("B");
        var all = new ArrayList<Operation>();
        var unsentFromA = new ArrayList<Operation>();

        var shared = new ArrayList<Operation>();
        for (var ch : "base text".toCharArray()) {
            shared.add(a.insertAt(a.length(), ch));
        }
        shared.forEach(b::apply);
        all.addAll(shared);

        for (var step = 0; step < 30; step++) {
            var useA = random.nextBoolean();
            var doc = useA ? a : b;

            Operation op;
            if (doc.length() > 0 && random.nextInt(100) < 25) {
                op = doc.deleteAt(random.nextInt(doc.length()));
            } else {
                op = doc.insertAt(random.nextInt(doc.length() + 1), (char) ('a' + random.nextInt(26)));
            }
            all.add(op);
            if (useA) {
                unsentFromA.add(op);
            } else {
                b.apply(op);
            }

            if (random.nextInt(100) < 35) {
                unsentFromA.forEach(b::apply);
                unsentFromA.clear();
            }
        }
        return all;
    }
}
