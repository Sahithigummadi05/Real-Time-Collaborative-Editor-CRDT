package com.sahithi.collab.ws;

import com.sahithi.collab.crdt.Operation;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Relays operations between connected editors.
 *
 * <p>On connect, a client is replayed the full operation history so it can rebuild the document
 * locally. After that it is a pure fan-out: an operation arrives, gets applied to the server's
 * replica, and is forwarded to everyone else verbatim. Nothing is transformed or reordered on the
 * way through — the CRDT makes that unnecessary, which is what keeps this handler trivial.
 */
@Component
public class EditorWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EditorWebSocketHandler.class);

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final DocumentSession documentSession;
    private final OperationCodec codec;

    public EditorWebSocketHandler(DocumentSession documentSession, OperationCodec codec) {
        this.documentSession = documentSession;
        this.codec = codec;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);

        var payload = codec.mapper().createObjectNode();
        payload.put("type", "sync");
        var ops = payload.putArray("ops");
        documentSession.history().forEach(op -> ops.add(codec.encode(op)));
        session.sendMessage(new TextMessage(payload.toString()));

        log.info("Client {} connected ({} clients, {} ops replayed)",
                session.getId(), sessions.size(), ops.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        var node = codec.mapper().readTree(message.getPayload());
        if (!"op".equals(node.path("type").asText())) {
            return;
        }

        Operation op;
        try {
            op = codec.decode(node.get("op"));
        } catch (RuntimeException e) {
            // A malformed message from one client must not take down the relay for everyone else.
            log.warn("Discarding malformed operation from {}: {}", session.getId(), e.toString());
            return;
        }

        if (!documentSession.apply(op)) {
            return; // already seen; don't echo duplicates back into the room
        }
        broadcastExcept(session.getId(), op);
    }

    private void broadcastExcept(String senderId, Operation op) {
        var payload = codec.mapper().createObjectNode();
        payload.put("type", "op");
        payload.set("op", codec.encode(op));
        var text = new TextMessage(payload.toString());

        sessions.forEach((id, target) -> {
            if (id.equals(senderId) || !target.isOpen()) {
                return;
            }
            try {
                synchronized (target) {
                    // WebSocketSession is not safe for concurrent sends, and several inbound
                    // handler threads can fan out to the same recipient at once.
                    target.sendMessage(text);
                }
            } catch (IOException e) {
                log.warn("Failed to relay to {}: {}", id, e.toString());
            }
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("Client {} disconnected ({} remaining)", session.getId(), sessions.size());
    }
}
