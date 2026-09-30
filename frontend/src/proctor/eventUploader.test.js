import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { EventUploader } from './eventUploader.js';

/**
 * The client half of the idempotency contract.
 *
 * The server ignores event ids it has already stored, which only helps if the
 * client actually retries rather than dropping events on the first network
 * blip. These tests cover that, and the bound on how long it keeps trying.
 */
describe('EventUploader', () => {
  let sent;

  const event = (id) => ({
    clientEventId: id,
    type: 'NO_FACE',
    startTime: '2026-08-15T10:00:00.000Z',
    endTime: '2026-08-15T10:00:05.000Z',
    durationMs: 5000,
  });

  beforeEach(() => {
    sent = [];
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  const okSender = async (batch) => {
    sent.push(batch);
  };

  it('does not send anything while the queue is empty', async () => {
    const uploader = new EventUploader(okSender);
    await uploader.flush();

    expect(sent).toHaveLength(0);
  });

  it('sends queued events on flush', async () => {
    const uploader = new EventUploader(okSender);
    uploader.enqueue(event('a'));
    uploader.enqueue(event('b'));

    await uploader.flush();

    expect(sent).toHaveLength(1);
    expect(sent[0]).toHaveLength(2);
    expect(uploader.pending).toBe(0);
  });

  it('flushes immediately once a full batch has accumulated', () => {
    const uploader = new EventUploader(okSender, { batchSize: 3 });
    uploader.enqueue(event('a'));
    uploader.enqueue(event('b'));
    expect(sent).toHaveLength(0);

    // Reaching the batch size should not wait for the next interval tick.
    uploader.enqueue(event('c'));

    expect(sent).toHaveLength(1);
  });

  it('splits a large queue into batches', async () => {
    const uploader = new EventUploader(okSender, { batchSize: 2 });
    ['a', 'b', 'c', 'd', 'e'].forEach((id) => uploader.queue.push(event(id)));

    await uploader.flush();
    await uploader.flush();
    await uploader.flush();

    expect(sent.map((b) => b.length)).toEqual([2, 2, 1]);
  });

  it('puts a failed batch back so nothing is lost', async () => {
    let attempt = 0;
    const flaky = async (batch) => {
      attempt += 1;
      if (attempt === 1) throw new Error('network down');
      sent.push(batch);
    };

    const uploader = new EventUploader(flaky);
    uploader.enqueue(event('a'));

    await uploader.flush();
    expect(sent).toHaveLength(0);
    expect(uploader.pending).toBe(1); // retained, not dropped

    await uploader.flush();
    expect(sent).toHaveLength(1);
    expect(sent[0][0].clientEventId).toBe('a');
  });

  it('preserves order when a batch is retried', async () => {
    let fail = true;
    const flaky = async (batch) => {
      if (fail) {
        fail = false;
        throw new Error('network down');
      }
      sent.push(batch);
    };

    const uploader = new EventUploader(flaky, { batchSize: 2 });
    uploader.enqueue(event('a'));
    uploader.enqueue(event('b'));
    await uploader.flush(); // fails, both go back to the front

    uploader.enqueue(event('c'));
    await uploader.flush();

    expect(sent[0].map((e) => e.clientEventId)).toEqual(['a', 'b']);
  });

  it('retries the same event ids, so the server can de-duplicate', async () => {
    const attempts = [];
    const flaky = async (batch) => {
      attempts.push(batch.map((e) => e.clientEventId));
      if (attempts.length < 3) throw new Error('network down');
    };

    const uploader = new EventUploader(flaky);
    uploader.enqueue(event('stable-id'));

    await uploader.flush();
    await uploader.flush();
    await uploader.flush();

    // Identical ids each time: a retry can never create a duplicate row.
    expect(attempts).toEqual([['stable-id'], ['stable-id'], ['stable-id']]);
  });

  it('gives up on a batch after the retry limit rather than growing forever', async () => {
    const alwaysFails = async () => {
      throw new Error('network down');
    };

    const uploader = new EventUploader(alwaysFails, { maxRetries: 2 });
    uploader.enqueue(event('a'));

    await uploader.flush();
    await uploader.flush();
    await uploader.flush();

    expect(uploader.pending).toBe(0);
    expect(uploader.stats.failed).toBe(1);
  });

  it('counts successful uploads', async () => {
    const uploader = new EventUploader(okSender);
    uploader.enqueue(event('a'));
    uploader.enqueue(event('b'));

    await uploader.flush();

    expect(uploader.stats.uploaded).toBe(2);
    expect(uploader.stats.failed).toBe(0);
  });

  it('does not start a second flush while one is in flight', async () => {
    let resolveSend;
    const slow = () => new Promise((resolve) => {
      resolveSend = resolve;
    });

    const uploader = new EventUploader(slow);
    uploader.enqueue(event('a'));

    const first = uploader.flush();
    uploader.enqueue(event('b'));
    await uploader.flush(); // must be ignored, not sent concurrently

    resolveSend();
    await first;

    expect(uploader.pending).toBe(1); // 'b' still queued, not lost
  });

  it('sends on its interval once started', async () => {
    const uploader = new EventUploader(okSender, { intervalMs: 10000 });
    uploader.start();
    uploader.enqueue(event('a'));

    expect(sent).toHaveLength(0);
    await vi.advanceTimersByTimeAsync(10000);

    expect(sent).toHaveLength(1);
    uploader.stop();
  });

  it('drains everything when the interview ends', async () => {
    const uploader = new EventUploader(okSender, { batchSize: 2 });
    ['a', 'b', 'c', 'd', 'e'].forEach((id) => uploader.enqueue(event(id)));

    await uploader.flushAll();

    expect(uploader.pending).toBe(0);
    expect(sent.flat()).toHaveLength(5);
  });

  it('stops draining rather than looping forever when the server is unreachable', async () => {
    const alwaysFails = async () => {
      throw new Error('network down');
    };
    const uploader = new EventUploader(alwaysFails, { maxRetries: 1 });
    uploader.enqueue(event('a'));

    // Must terminate: a candidate closing their laptop should not hang.
    await uploader.flushAll();

    expect(uploader.pending).toBe(0);
  });

  // ---- reporting a loss ---------------------------------------------------
  //
  // Giving up on a batch is sometimes right - the alternative is an unbounded
  // queue - but it must never be silent. Once an event is dropped it is
  // indistinguishable, at the report, from an event that never happened.

  it('announces a drop rather than losing it quietly', async () => {
    const alwaysFails = async () => {
      throw new Error('network down');
    };
    const drops = [];
    const uploader = new EventUploader(alwaysFails, {
      maxRetries: 1,
      onDrop: (total) => drops.push(total),
    });
    uploader.enqueue(event('a'));

    await uploader.flush(); // first failure - requeued
    await uploader.flush(); // retries exhausted - abandoned

    expect(drops).toEqual([1]);
    expect(uploader.dropped).toBe(1);
  });

  it('does not report a drop while it is still retrying', async () => {
    const alwaysFails = async () => {
      throw new Error('network down');
    };
    const drops = [];
    const uploader = new EventUploader(alwaysFails, {
      maxRetries: 3,
      onDrop: (total) => drops.push(total),
    });
    uploader.enqueue(event('a'));

    await uploader.flush();

    expect(drops).toEqual([]);
    expect(uploader.pending).toBe(1);
  });

  // ---- the last chance ----------------------------------------------------

  it('sends what is queued with keepalive when the page goes away', () => {
    const options = [];
    const uploader = new EventUploader(
      async (events, opts) => {
        sent.push(events);
        options.push(opts);
      },
      // Above the number enqueued, so nothing auto-flushes first and the whole
      // queue is genuinely still waiting when the page goes.
      { batchSize: 10 },
    );
    ['a', 'b', 'c'].forEach((id) => uploader.enqueue(event(id)));

    const drained = uploader.drainOnUnload();

    expect(drained).toBe(3);
    expect(sent.flat()).toHaveLength(3);
    expect(options[0]).toEqual({ keepalive: true });
    expect(uploader.pending).toBe(0);
  });

  it('takes the whole queue at once, not one batch of it', () => {
    const unloadSends = [];
    // A send that never settles holds `inFlight`, so the queue is free to grow
    // past batchSize the way it does on a stalled network.
    const uploader = new EventUploader(
      (events, opts) => {
        if (opts?.keepalive) unloadSends.push(events);
        return new Promise(() => {});
      },
      { batchSize: 2 },
    );

    ['a', 'b'].forEach((id) => uploader.enqueue(event(id))); // auto-flushed, stuck
    ['c', 'd', 'e'].forEach((id) => uploader.enqueue(event(id)));
    expect(uploader.pending).toBe(3);

    uploader.drainOnUnload();

    // There is no next tick to catch a remainder, so batchSize must not apply.
    expect(unloadSends).toHaveLength(1);
    expect(unloadSends[0]).toHaveLength(3);
    expect(uploader.pending).toBe(0);
  });

  it('counts an unload send the browser refuses as dropped', async () => {
    const uploader = new EventUploader(async () => {
      throw new Error('body too large');
    });
    uploader.enqueue(event('a'));

    uploader.drainOnUnload();
    await Promise.resolve();

    // The report must learn the event existed, even though it never arrived.
    expect(uploader.dropped).toBe(1);
  });

  it('is a no-op when there is nothing queued', () => {
    const uploader = new EventUploader(okSender);

    expect(uploader.drainOnUnload()).toBe(0);
    expect(sent).toHaveLength(0);
  });
});
