package com.sahithi.collab.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahithi.collab.crdt.RgaDocument;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * End-to-end test over real WebSockets: two independent clients, each with its own CRDT replica,
 * edit through the running server and must end up with identical text.
 *
 * <p>The unit tests prove the algorithm converges when operations are handed to it directly. This
 * proves the rest of the system doesn't undermine that - the wire encoding round-trips faithfully,
 * the relay forwards to the right peers, duplicates are suppressed, and a late joiner can rebuild
 * the document from history alone.
 */
/*
 * The server holds a single shared document (see the scope note in the README), and that document
 * is a Spring singleton that would otherwise carry text from one test into the next - making
 * assertions depend on test execution order. Rebuilding the context per test keeps each one
 * starting from an empty document. It costs a few seconds across the class, which is a fair price
 * for tests that mean what they say.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class EditorSyncIntegrationTest {

    @LocalServerPort
    private int port;

    private final ObjectMapper mapper = new ObjectMapper();
    private final OperationCodec codec = new OperationCodec();

    /** A test client: a CRDT replica plus the socket it syncs through. */
    private final class Client implements AutoCloseable {
        private final RgaDocument doc;
        private final WebSocketSession session;
        private final CountDownLatch synced = new CountDownLatch(1);

        Client(String replicaId) throws Exception {
            this.doc = new RgaDocument(replicaId);
            WebSocketHandler handler = new TextWebSocketHandler() {
                @Override
                protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
                    var node = mapper.readTree(message.getPayload());
                    var type = node.path("type").asText();
                    if ("sync".equals(type)) {
                        node.withArray("ops").forEach(op -> doc.apply(codec.decode(op)));
                        synced.countDown();
                    } else if ("op".equals(type)) {
                        doc.apply(codec.decode(node.get("op")));
                    }
                }
            };
            this.session = new StandardWebSocketClient()
                    .execute(handler, new org.springframework.web.socket.WebSocketHttpHeaders(),
                            URI.create("ws://localhost:" + port + "/ws/editor"))
                    .get(10, TimeUnit.SECONDS);
            assertThat(synced.await(10, TimeUnit.SECONDS)).as("initial sync frame").isTrue();
        }

        /** Types text at the end of this replica's document, broadcasting each character. */
        void type(String text) throws Exception {
            for (var ch : text.toCharArray()) {
                send(doc.insertAt(doc.length(), ch));
            }
        }

        void deleteAt(int index) throws Exception {
            send(doc.deleteAt(index));
        }

        private void send(com.sahithi.collab.crdt.Operation op) throws Exception {
            var payload = mapper.createObjectNode();
            payload.put("type", "op");
            payload.set("op", codec.encode(op));
            synchronized (session) {
                session.sendMessage(new TextMessage(payload.toString()));
            }
        }

        String text() {
            return doc.text();
        }

        @Override
        public void close() throws Exception {
            session.close(CloseStatus.NORMAL);
        }
    }

    /** Polls until both replicas agree, or fails with what each actually holds. */
    private void awaitConvergence(Client a, Client b) throws Exception {
        for (var i = 0; i < 100; i++) {
            if (a.text().equals(b.text())) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(a.text()).as("replicas failed to converge within 10s").isEqualTo(b.text());
    }

    @Test
    @DisplayName("an edit made by one client reaches the other")
    void editPropagatesBetweenClients() throws Exception {
        try (var a = new Client("A"); var b = new Client("B")) {
            a.type("hello");
            awaitConvergence(a, b);
            assertThat(b.text()).isEqualTo("hello");
        }
    }

    @Test
    @DisplayName("simultaneous edits from both clients converge with nothing lost")
    void concurrentEditsConverge() throws Exception {
        try (var a = new Client("A"); var b = new Client("B")) {
            // Both type at once, with no coordination and no waiting for each other.
            var typingA = new Thread(() -> {
                try {
                    a.type("AAAA");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            var typingB = new Thread(() -> {
                try {
                    b.type("BBBB");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            typingA.start();
            typingB.start();
            typingA.join();
            typingB.join();

            awaitConvergence(a, b);
            assertThat(a.text()).hasSize(8);
            assertThat(a.text().chars().filter(c -> c == 'A').count()).isEqualTo(4);
            assertThat(a.text().chars().filter(c -> c == 'B').count()).isEqualTo(4);
        }
    }

    @Test
    @DisplayName("a deletion by one client is reflected on the other")
    void deletePropagates() throws Exception {
        try (var a = new Client("A"); var b = new Client("B")) {
            a.type("abc");
            awaitConvergence(a, b);

            a.deleteAt(1);
            awaitConvergence(a, b);
            assertThat(a.text()).isEqualTo("ac");
            assertThat(b.text()).isEqualTo("ac");
        }
    }

    @Test
    @DisplayName("a client joining late rebuilds the document from replayed history")
    void lateJoinerReceivesFullHistory() throws Exception {
        try (var a = new Client("A"); var b = new Client("B")) {
            a.type("written before C arrives");
            awaitConvergence(a, b);
            var expected = a.text();

            try (var c = new Client("C")) {
                // C was not connected for any of those edits; the sync frame alone must suffice.
                for (var i = 0; i < 100 && !c.text().equals(expected); i++) {
                    Thread.sleep(100);
                }
                assertThat(c.text()).isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("three clients editing at once all end up with the same document")
    void threeWayConvergence() throws Exception {
        try (var a = new Client("A"); var b = new Client("B"); var c = new Client("C")) {
            var threads = new ArrayList<Thread>();
            for (var client : List.of(a, b, c)) {
                var t = new Thread(() -> {
                    try {
                        client.type(client.doc.replicaId().repeat(3));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                threads.add(t);
                t.start();
            }
            for (var t : threads) {
                t.join();
            }

            awaitConvergence(a, b);
            awaitConvergence(b, c);
            assertThat(a.text()).isEqualTo(b.text()).isEqualTo(c.text());
            assertThat(a.text()).hasSize(9);
        }
    }
}
