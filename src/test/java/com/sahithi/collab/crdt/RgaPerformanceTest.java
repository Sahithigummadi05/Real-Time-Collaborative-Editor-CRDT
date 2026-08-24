package com.sahithi.collab.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Characterises how the document scales with size.
 *
 * <p>Convergence is the headline property, but a text CRDT also has to stay usable as a document
 * grows — an algorithm that is correct and quadratic is still unusable for real editing. This
 * measures insert cost at increasing document sizes and reports the growth ratio, so the scaling
 * behaviour is a measured fact rather than an assumption.
 *
 * <p>Absolute timings vary with machine load and are not asserted on; the ratio between successive
 * sizes is the meaningful signal.
 */
class RgaPerformanceTest {

    @Test
    @DisplayName("measure insert scaling as the document grows")
    void measureInsertScaling() {
        // Warm up first: without this the smallest size absorbs JIT compilation and reports a
        // per-character cost ~30x the steady-state one, which would make the scaling curve look
        // like it improves with size rather than staying flat.
        for (var i = 0; i < 3; i++) {
            timeAppend(2000);
            timePrepend(2000);
        }

        System.out.println("\n=== Typing at end of document ===");
        System.out.printf("%-10s %-12s %-14s %-10s%n", "chars", "elapsed", "µs/char", "vs prev");

        long previous = 0;
        for (var size : new int[] {2000, 4000, 8000, 16000}) {
            var elapsed = timeAppend(size);
            var ratio = previous == 0 ? 0 : (double) elapsed / previous;
            System.out.printf("%-10d %-12s %-14.2f %-10s%n",
                    size,
                    elapsed / 1_000_000 + " ms",
                    elapsed / 1000.0 / size,
                    previous == 0 ? "-" : String.format("%.2fx", ratio));
            previous = elapsed;
        }

        System.out.println("\n=== Typing at the START of document (worst case) ===");
        System.out.printf("%-10s %-12s %-14s %-10s%n", "chars", "elapsed", "µs/char", "vs prev");
        previous = 0;
        for (var size : new int[] {2000, 4000, 8000}) {
            var elapsed = timePrepend(size);
            var ratio = previous == 0 ? 0 : (double) elapsed / previous;
            System.out.printf("%-10d %-12s %-14.2f %-10s%n",
                    size,
                    elapsed / 1_000_000 + " ms",
                    elapsed / 1000.0 / size,
                    previous == 0 ? "-" : String.format("%.2fx", ratio));
            previous = elapsed;
        }
        System.out.println();

        // Guard rail rather than a benchmark assertion: a document this size must remain
        // interactive. Generous enough not to be flaky on a loaded CI box, tight enough to catch
        // a genuine complexity regression.
        var sanity = timeAppend(8000);
        assertThat(sanity / 1_000_000)
                .as("8k characters should not take seconds to type")
                .isLessThan(10_000);
    }

    private long timeAppend(int size) {
        var doc = new RgaDocument("perf");
        var start = System.nanoTime();
        for (var i = 0; i < size; i++) {
            doc.insertAt(doc.length(), 'x');
        }
        var elapsed = System.nanoTime() - start;
        assertThat(doc.length()).isEqualTo(size);
        return elapsed;
    }

    private long timePrepend(int size) {
        var doc = new RgaDocument("perf");
        var start = System.nanoTime();
        for (var i = 0; i < size; i++) {
            doc.insertAt(0, 'x');
        }
        var elapsed = System.nanoTime() - start;
        assertThat(doc.length()).isEqualTo(size);
        return elapsed;
    }
}
