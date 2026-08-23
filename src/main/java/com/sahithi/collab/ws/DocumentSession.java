package com.sahithi.collab.ws;

import com.sahithi.collab.crdt.OpId;
import com.sahithi.collab.crdt.Operation;
import com.sahithi.collab.crdt.RgaDocument;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The server's own replica of the document, plus the operation log used to bring new clients up
 * to date.
 *
 * <p>Worth being precise about the server's role: it is <b>not</b> an arbiter. It never decides
 * who "wins" a conflict and never transforms one client's edit against another's — that is the
 * whole point of using a CRDT rather than operational transformation. The server is a relay that
 * happens to keep a replica, so that a client joining an hour late has somewhere to get history
 * from. Clients would still converge without it if they gossiped directly.
 *
 * <p>Synchronised rather than lock-free: every mutation is a short in-memory splice, and the
 * contention here is a handful of WebSocket threads, not a hot path worth optimising. Correctness
 * first; the CRDT already removed the hard concurrency problem.
 */
@Component
public class DocumentSession {

    private final RgaDocument document = new RgaDocument("server");
    private final List<Operation> log = new ArrayList<>();
    private final Set<OpId> seen = new HashSet<>();

    /**
     * Applies an operation received from a client.
     *
     * <p>Dedupe is keyed on the operation id rather than inferred from whether the document text
     * changed. Those are not the same question: a delete of an already-deleted character leaves
     * the text untouched but is still a legitimately new operation, and a resent insert changes
     * nothing yet must not be rebroadcast.
     *
     * @return true if this operation was new and should be relayed to other clients
     */
    public synchronized boolean apply(Operation op) {
        if (!seen.add(op.id())) {
            return false;
        }
        document.apply(op);
        log.add(op);
        return true;
    }

    /** The full operation history, for replaying to a newly connected client. */
    public synchronized List<Operation> history() {
        return List.copyOf(log);
    }

    public synchronized String text() {
        return document.text();
    }
}
