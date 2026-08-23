package com.sahithi.collab.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sahithi.collab.crdt.OpId;
import com.sahithi.collab.crdt.Operation;
import com.sahithi.collab.crdt.RgaDocument;

/**
 * Wire format for operations.
 *
 * <p>Written by hand rather than leaning on Jackson polymorphic type handling: the wire format is
 * a contract shared with a JavaScript client that knows nothing about Java class names, so making
 * it explicit keeps the two sides honestly in sync and keeps Java type info out of the protocol.
 */
@org.springframework.stereotype.Component
public final class OperationCodec {

    private final ObjectMapper mapper = new ObjectMapper();

    public ObjectNode encode(Operation op) {
        var node = mapper.createObjectNode();
        node.set("id", encodeId(op.id()));
        switch (op) {
            case Operation.Insert insert -> {
                node.put("kind", "insert");
                node.set("originId", encodeId(insert.originId()));
                node.put("value", String.valueOf(insert.value()));
            }
            case Operation.Delete delete -> {
                node.put("kind", "delete");
                node.set("targetId", encodeId(delete.targetId()));
            }
        }
        return node;
    }

    public Operation decode(JsonNode node) {
        var id = decodeId(node.get("id"));
        var kind = node.get("kind").asText();
        return switch (kind) {
            case "insert" -> new Operation.Insert(
                    id,
                    decodeId(node.get("originId")),
                    node.get("value").asText().charAt(0));
            case "delete" -> new Operation.Delete(id, decodeId(node.get("targetId")));
            default -> throw new IllegalArgumentException("Unknown operation kind: " + kind);
        };
    }

    private ObjectNode encodeId(OpId id) {
        var node = mapper.createObjectNode();
        node.put("counter", id.counter());
        node.put("replicaId", id.replicaId());
        return node;
    }

    private OpId decodeId(JsonNode node) {
        if (node == null || node.isNull()) {
            return RgaDocument.HEAD;
        }
        return new OpId(node.get("counter").asLong(), node.get("replicaId").asText());
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
