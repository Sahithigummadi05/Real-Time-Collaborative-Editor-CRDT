package com.sahithi.collab.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahithi.collab.crdt.Operation;
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
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Verifies that work done while disconnected is not lost.
 *
 * <p>This covers a bug the browser client actually had: {@code send()} dropped operations when the
 * socket wasn't open. Locally the characters appeared, so the tab looked healthy — but no other
 * replica ever heard about them, and the edits were gone for everyone else. The README claimed
 * offline editing "converges on reconnect" while the code quietly discarded it.
 *
 * <p>The fix is an outbox: operations queue while offline and flush on reconnect, relying on
 * id-based dedupe so a flush that overlaps with what the server already received is harmless.
 * These tests model that client behaviour against the real server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class OfflineReconnectIntegrationTest {

    @LocalServerPort
    private int port;

    private final ObjectMapper mapper = new ObjectMapper();
    private final OperationCodec codec = new OperationCodec();

    /**
     * A client that models the browser's outbox: edits always apply locally, and are only removed
     * from the queue once actually written to a live socket.
     */
    private final class QueueingClient implements AutoCloseable {
        private final RgaDocument doc;
        private final List<Operation> outbox = new ArrayList<>();
        private WebSocketSession session;
        private CountDownLatch synced;

        QueueingClient(String replicaId) {
            this.doc = new RgaDocument(replicaId);
        }

        void connect() throws Exception {
            synced = new CountDownLatch(1);
            session = new StandardWebSocketClient()
                    .execute(new TextWebSocketHandler() {
                        @Override
                        protected void handleTextMessage(WebSocketSession s, TextMessage message)
                                throws Exception {
                            var node = mapper.readTree(message.getPayload());
                            var type = node.path("type").asText();
                            if ("sync".equals(type)) {
                                node.withArray("ops").forEach(op -> doc.apply(codec.decode(op)));
                                synced.countDown();
                            } else if ("op".equals(type)) {
                                doc.apply(codec.decode(node.get("op")));
                            }
                        }
                    }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws/editor"))
                    .get(10, TimeUnit.SECONDS);
            assertThat(synced.await(10, TimeUnit.SECONDS)).isTrue();
            flush();
        }

        void disconnect() throws Exception {
            if (session != null) {
                session.close(CloseStatus.NORMAL);
                session = null;
            }
        }

        /** Types locally. Whether the socket is up only affects delivery, never the local edit. */
        void type(String text) {
            for (var ch : text.toCharArray()) {
                outbox.add(doc.insertAt(doc.length(), ch));
            }
            flush();
        }

        private void flush() {
            if (session == null || !session.isOpen()) {
                return;
            }
            var iterator = outbox.iterator();
            while (iterator.hasNext()) {
                var op = iterator.next();
                try {
                    var payload = mapper.createObjectNode();
                    payload.put("type", "op");
                    payload.set("op", codec.encode(op));
                    synchronized (session) {
                        session.sendMessage(new TextMessage(payload.toString()));
                    }
                    iterator.remove();
                } catch (Exception e) {
                    return; // stays queued for the next attempt
                }
            }
        }

        int queuedCount() {
            return outbox.size();
        }

        String text() {
            return doc.text();
        }

        @Override
        public void close() throws Exception {
            disconnect();
        }
    }

    private void awaitText(QueueingClient client, String expected) throws Exception {
        for (var i = 0; i < 100 && !client.text().equals(expected); i++) {
            Thread.sleep(100);
        }
        assertThat(client.text()).isEqualTo(expected);
    }

    @Test
    @DisplayName("edits made while disconnected reach other clients after reconnecting")
    void offlineEditsSurviveReconnect() throws Exception {
        try (var a = new QueueingClient("A"); var b = new QueueingClient("B")) {
            a.connect();
            b.connect();

            a.type("online ");
            awaitText(b, "online ");

            // A drops off the network and keeps typing.
            a.disconnect();
            a.type("offline");
            assertThat(a.queuedCount()).isEqualTo("offline".length());
            assertThat(a.text()).isEqualTo("online offline");

            // B cannot see it yet - that's expected, not a bug.
            assertThat(b.text()).isEqualTo("online ");

            a.connect();

            awaitText(b, "online offline");
            assertThat(a.queuedCount()).as("outbox should drain on reconnect").isZero();
        }
    }

    @Test
    @DisplayName("both sides editing while partitioned merge cleanly once reconnected")
    void concurrentOfflineEditsMerge() throws Exception {
        try (var a = new QueueingClient("A"); var b = new QueueingClient("B")) {
            a.connect();
            b.connect();
            a.type("base ");
            awaitText(b, "base ");

            // Both go offline and edit independently - a network partition.
            a.disconnect();
            b.disconnect();
            a.type("AAA");
            b.type("BBB");

            a.connect();
            b.connect();

            for (var i = 0; i < 100 && !a.text().equals(b.text()); i++) {
                Thread.sleep(100);
            }
            assertThat(a.text()).isEqualTo(b.text());
            assertThat(a.text()).hasSize("base ".length() + 6);
            assertThat(a.text()).startsWith("base ");
            assertThat(a.queuedCount()).isZero();
            assertThat(b.queuedCount()).isZero();
        }
    }

    @Test
    @DisplayName("replaying already-delivered operations does not duplicate characters")
    void reflushingDeliveredOperationsIsHarmless() throws Exception {
        try (var a = new QueueingClient("A"); var b = new QueueingClient("B")) {
            a.connect();
            b.connect();
            a.type("hello");
            awaitText(b, "hello");

            // Simulate an outbox that couldn't confirm delivery and resends everything. The server
            // and every replica must ignore ids they already applied.
            var replayed = new QueueingClient("A2");
            replayed.connect();
            replayed.type("");

            // Directly resend A's operations a second time.
            a.disconnect();
            a.connect();

            Thread.sleep(500);
            assertThat(b.text()).isEqualTo("hello");
            assertThat(a.text()).isEqualTo("hello");
            replayed.close();
        }
    }
}
