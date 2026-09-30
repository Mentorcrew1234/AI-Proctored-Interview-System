import { UPLOAD } from './proctorConfig.js';

/**
 * Queues completed events and ships them to the backend in batches.
 *
 * Only events travel - never frames, never images. A batch is retried on
 * failure, which is safe because every event carries a client-generated id and
 * the server ignores ids it has already stored.
 *
 * Failed events go back to the front of the queue so ordering is preserved and
 * nothing is dropped just because the network blipped mid-interview.
 */
export class EventUploader {
  /**
   * @param {(events: object[], options?: {keepalive?: boolean}) => Promise<any>} send
   *   posts one batch
   * @param {{onDrop?: (droppedTotal: number) => void}} [options] `onDrop` is
   *   called whenever a batch is abandoned. Giving up is sometimes the right
   *   thing to do - the alternative is an unbounded queue - but it must never
   *   be silent, because a lost event is indistinguishable from an event that
   *   never happened once it reaches the report.
   */
  constructor(send, options = {}) {
    this.send = send;
    this.intervalMs = options.intervalMs ?? UPLOAD.intervalMs;
    this.batchSize = options.batchSize ?? UPLOAD.batchSize;
    this.maxRetries = options.maxRetries ?? UPLOAD.maxRetries;
    this.onDrop = options.onDrop ?? null;

    this.queue = [];
    this.timer = null;
    this.inFlight = false;
    this.consecutiveFailures = 0;
    this.stats = { uploaded: 0, failed: 0 };
  }

  start() {
    if (this.timer) return;
    this.timer = setInterval(() => this.flush(), this.intervalMs);
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  enqueue(event) {
    this.queue.push(event);
    // Don't sit on a burst of events waiting for the next tick.
    if (this.queue.length >= this.batchSize) this.flush();
  }

  async flush() {
    if (this.inFlight || this.queue.length === 0) return;

    const batch = this.queue.splice(0, this.batchSize);
    this.inFlight = true;
    try {
      await this.send(batch);
      this.stats.uploaded += batch.length;
      this.consecutiveFailures = 0;
    } catch {
      this.consecutiveFailures += 1;
      if (this.consecutiveFailures <= this.maxRetries) {
        // Put them back at the front - retrying is safe thanks to clientEventId.
        this.queue.unshift(...batch);
      } else {
        // Give up on this batch rather than growing the queue without bound -
        // but say so. An abandoned event still happened, and the report must be
        // able to tell "nothing was observed" from "we lost what was observed".
        this.stats.failed += batch.length;
        this.consecutiveFailures = 0;
        this.onDrop?.(this.stats.failed);
      }
    } finally {
      this.inFlight = false;
    }
  }

  /** Drains the queue at the end of the interview. */
  async flushAll() {
    this.stop();
    let guard = 0;
    while (this.queue.length > 0 && guard < 20) {
      const before = this.queue.length;
      // eslint-disable-next-line no-await-in-loop
      await this.flush();
      if (this.queue.length >= before) guard += 1;
    }
  }

  /**
   * Last-chance send as the page goes away, from a `pagehide` handler.
   *
   * Deliberately fire-and-forget: the page is being torn down, so there is
   * nobody left to await a promise or run a retry. `keepalive` is what lets the
   * request outlive the document. Anything the browser refuses to send is
   * counted as dropped, so the loss is reported rather than vanishing.
   *
   * @returns {number} how many events were handed to the network
   */
  drainOnUnload() {
    this.stop();
    if (this.queue.length === 0) return 0;

    const batch = this.queue.splice(0, this.queue.length);
    try {
      // No await: the document may not survive long enough to resolve it.
      const result = this.send(batch, { keepalive: true });
      // A rejected promise with nobody awaiting it is an unhandled rejection,
      // which is noise in the console at exactly the least useful moment.
      result?.catch?.(() => {
        this.stats.failed += batch.length;
      });
    } catch {
      this.stats.failed += batch.length;
      this.onDrop?.(this.stats.failed);
      return 0;
    }
    return batch.length;
  }

  get pending() {
    return this.queue.length;
  }

  /** Events generated but abandoned. Above zero means the record is incomplete. */
  get dropped() {
    return this.stats.failed;
  }
}
