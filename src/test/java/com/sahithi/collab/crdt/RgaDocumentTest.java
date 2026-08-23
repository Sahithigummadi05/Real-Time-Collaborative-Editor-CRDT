package com.sahithi.collab.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Basic single-replica behaviour: the boring cases that must hold before convergence matters. */
class RgaDocumentTest {

    @Test
    @DisplayName("typing characters left to right produces the typed text")
    void sequentialTyping() {
        var doc = new RgaDocument("A");
        var text = "hello";
        for (var i = 0; i < text.length(); i++) {
            doc.insertAt(i, text.charAt(i));
        }
        assertThat(doc.text()).isEqualTo("hello");
        assertThat(doc.length()).isEqualTo(5);
    }

    @Test
    @DisplayName("inserting at the start pushes existing text right")
    void insertAtStart() {
        var doc = new RgaDocument("A");
        doc.insertAt(0, 'b');
        doc.insertAt(0, 'a');
        assertThat(doc.text()).isEqualTo("ab");
    }

    @Test
    @DisplayName("inserting in the middle lands between neighbours")
    void insertInMiddle() {
        var doc = new RgaDocument("A");
        doc.insertAt(0, 'a');
        doc.insertAt(1, 'c');
        doc.insertAt(1, 'b');
        assertThat(doc.text()).isEqualTo("abc");
    }

    @Test
    @DisplayName("deleted characters disappear from the text but remain as tombstones")
    void deleteLeavesTombstone() {
        var doc = new RgaDocument("A");
        for (var c : "abc".toCharArray()) {
            doc.insertAt(doc.length(), c);
        }
        doc.deleteAt(1);

        assertThat(doc.text()).isEqualTo("ac");
        assertThat(doc.length()).isEqualTo(2);
        // The tombstone has to stay: a concurrent insert may still name 'b' as its origin.
        assertThat(doc.elementCountIncludingTombstones()).isEqualTo(3);
    }

    @Test
    @DisplayName("applying the same operation twice is a no-op")
    void applyIsIdempotent() {
        var source = new RgaDocument("A");
        var op = source.insertAt(0, 'x');

        var replica = new RgaDocument("B");
        replica.apply(op);
        replica.apply(op);
        replica.apply(op);

        // At-least-once delivery is normal on reconnect; without dedupe this would read "xxx".
        assertThat(replica.text()).isEqualTo("x");
    }

    @Test
    @DisplayName("a replica's clock advances past operations it receives")
    void lamportClockAdvancesOnReceive() {
        var a = new RgaDocument("A");
        var b = new RgaDocument("B");

        // A types 5 characters while B is idle.
        for (var i = 0; i < 5; i++) {
            b.apply(a.insertAt(i, 'x'));
        }

        // B's first insert must sort after everything it has already seen, not back at counter 1.
        var bOp = b.insertAt(b.length(), 'z');
        assertThat(bOp.id().counter()).isGreaterThan(5L);
    }
}
