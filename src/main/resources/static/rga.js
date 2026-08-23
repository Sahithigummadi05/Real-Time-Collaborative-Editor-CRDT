/**
 * Browser-side replica of the RGA CRDT.
 *
 * This is a deliberate port of the Java RgaDocument, not a thin client: each tab holds a real
 * replica and applies its own edits locally and immediately, without waiting for the server. That
 * is what makes typing feel instant and what lets an offline tab keep working. The convergence
 * guarantee is what allows two independently-edited replicas to be merged later with no conflict
 * resolution step.
 *
 * The ordering rule below must stay byte-for-byte equivalent in behaviour to the Java version.
 * If the two sides ever disagree about how to order concurrent inserts, replicas silently
 * diverge - which is exactly the class of bug the Java convergence tests exist to prevent.
 */

const HEAD = { counter: 0, replicaId: '' };

function idEquals(a, b) {
  return a.counter === b.counter && a.replicaId === b.replicaId;
}

/** Total order over ids: counter first, replica id as tie-break. Mirrors OpId.compareTo. */
function compareIds(a, b) {
  if (a.counter !== b.counter) return a.counter < b.counter ? -1 : 1;
  if (a.replicaId === b.replicaId) return 0;
  return a.replicaId < b.replicaId ? -1 : 1;
}

function idKey(id) {
  return id.counter + '@' + id.replicaId;
}

export class RgaDocument {
  constructor(replicaId) {
    this.replicaId = replicaId;
    this.elements = [];
    this.applied = new Set();
    this.counter = 0;
  }

  nextId() {
    this.counter += 1;
    return { counter: this.counter, replicaId: this.replicaId };
  }

  /** Local insert at a visible index. Returns the operation to broadcast. */
  insertAt(visibleIndex, value) {
    const originId = this._originForVisibleIndex(visibleIndex);
    const op = { kind: 'insert', id: this.nextId(), originId, value };
    this.apply(op);
    return op;
  }

  /** Local delete at a visible index. Returns the operation to broadcast. */
  deleteAt(visibleIndex) {
    const target = this._visibleElementAt(visibleIndex);
    if (!target) throw new Error('No visible character at index ' + visibleIndex);
    const op = { kind: 'delete', id: this.nextId(), targetId: target.id };
    this.apply(op);
    return op;
  }

  apply(op) {
    const key = idKey(op.id);
    if (this.applied.has(key)) return; // at-least-once delivery: ignore replays
    this.applied.add(key);

    // Keep this replica's clock ahead of anything it has observed.
    if (op.id.counter > this.counter) this.counter = op.id.counter;

    if (op.kind === 'insert') this._applyInsert(op);
    else if (op.kind === 'delete') this._applyDelete(op);
  }

  _applyInsert(op) {
    let scanFrom = 0;
    if (!idEquals(op.originId, HEAD)) {
      const originIndex = this._indexOf(op.originId);
      scanFrom = originIndex + 1;
    }

    // Walk right past concurrent inserts that sort after this one. Same rule as the Java side,
    // so both reach the same position independently.
    let insertAt = scanFrom;
    while (insertAt < this.elements.length
           && compareIds(this.elements[insertAt].id, op.id) > 0) {
      insertAt += 1;
    }

    this.elements.splice(insertAt, 0, {
      id: op.id,
      originId: op.originId,
      value: op.value,
      deleted: false,
    });
  }

  _applyDelete(op) {
    const index = this._indexOf(op.targetId);
    if (index >= 0) this.elements[index].deleted = true;
  }

  _indexOf(id) {
    for (let i = 0; i < this.elements.length; i++) {
      if (idEquals(this.elements[i].id, id)) return i;
    }
    return -1;
  }

  text() {
    let out = '';
    for (const e of this.elements) if (!e.deleted) out += e.value;
    return out;
  }

  length() {
    let n = 0;
    for (const e of this.elements) if (!e.deleted) n += 1;
    return n;
  }

  _originForVisibleIndex(visibleIndex) {
    if (visibleIndex <= 0) return HEAD;
    let seen = 0;
    for (const e of this.elements) {
      if (e.deleted) continue;
      seen += 1;
      if (seen === visibleIndex) return e.id;
    }
    return this.elements.length === 0 ? HEAD : this.elements[this.elements.length - 1].id;
  }

  _visibleElementAt(visibleIndex) {
    let seen = 0;
    for (const e of this.elements) {
      if (e.deleted) continue;
      if (seen === visibleIndex) return e;
      seen += 1;
    }
    return null;
  }
}
