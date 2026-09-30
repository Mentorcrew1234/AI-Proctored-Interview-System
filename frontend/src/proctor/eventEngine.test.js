import { describe, it, expect, beforeEach } from 'vitest';
import { EventEngine } from './eventEngine.js';

/**
 * The event engine is the piece that decides what actually gets recorded about
 * a candidate, so these tests care most about the two ways it could be unfair:
 * reporting something that did not really happen, and reporting one thing many
 * times.
 */
describe('EventEngine', () => {
  let events;
  let engine;
  let counter;

  const T0 = 1_700_000_000_000; // fixed epoch so assertions are readable

  beforeEach(() => {
    events = [];
    counter = 0;
    engine = new EventEngine((e) => events.push(e), {
      idFactory: () => `id-${++counter}`,
    });
  });

  /** Feeds the same detection every 200 ms for `ms` milliseconds. */
  function feed(detection, ms, startAt = T0, stepMs = 200) {
    let t = startAt;
    const end = startAt + ms;
    while (t <= end) {
      engine.update(t, detection);
      t += stepMs;
    }
    return end;
  }

  const noFace = { faceCount: 0 };
  const oneFace = { faceCount: 1, headDirection: 'CENTER' };
  const twoFaces = { faceCount: 2, headDirection: 'CENTER' };
  const phone = (score = 0.9) => ({ faceCount: 1, headDirection: 'CENTER', phone: { score, bbox: [1, 2, 3, 4] } });

  const typesOf = (t) => events.filter((e) => e.type === t);

  // ---- the anti-noise guarantees -------------------------------------------

  it('reports nothing for a condition that does not last long enough', () => {
    feed(oneFace, 400);
    // Face gone for 2s, but NO_FACE needs 3s.
    const t = feed(noFace, 2000, T0 + 600);
    feed(oneFace, 2000, t + 200);

    expect(typesOf('NO_FACE')).toHaveLength(0);
  });

  it('reports a sustained condition exactly once, not once per frame', () => {
    feed(oneFace, 400);
    const t = feed(noFace, 8000, T0 + 600); // 8s of no face at 5 fps = 40 frames
    feed(oneFace, 3000, t + 200);

    expect(typesOf('NO_FACE')).toHaveLength(1);
  });

  it('does not open a second event while one is already open', () => {
    feed(noFace, 20000);
    // Still open (never cleared), and under the 30s split threshold.
    expect(typesOf('NO_FACE')).toHaveLength(0);
    expect(engine.activeTypes()).toContain('NO_FACE');
  });

  // ---- honest durations ----------------------------------------------------

  it('backdates the start to when the condition actually began', () => {
    feed(oneFace, 400);
    const t = feed(noFace, 6000, T0 + 600);
    feed(oneFace, 2000, t + 200);

    const [event] = typesOf('NO_FACE');
    // Started at T0+600 when the face first disappeared, not 3s later when
    // the rule confirmed it.
    expect(new Date(event.startTime).getTime()).toBe(T0 + 600);
    expect(event.durationMs).toBeGreaterThanOrEqual(6000);
  });

  it('ends the event when the condition stopped, not when it was noticed', () => {
    feed(oneFace, 400);
    const gone = feed(noFace, 6000, T0 + 600);
    // Face returns; exit debounce is 1s, but the event should end at the return.
    feed(oneFace, 3000, gone + 200);

    const [event] = typesOf('NO_FACE');
    expect(new Date(event.endTime).getTime()).toBe(gone + 200);
  });

  it('does not close on a momentary flicker back to the normal state', () => {
    feed(oneFace, 400);
    let t = feed(noFace, 5000, T0 + 600);
    engine.update(t + 200, oneFace); // single blip, shorter than the 1s exit
    t = feed(noFace, 4000, t + 400);
    feed(oneFace, 3000, t + 200);

    // One continuous event, not two.
    expect(typesOf('NO_FACE')).toHaveLength(1);
    expect(typesOf('NO_FACE')[0].durationMs).toBeGreaterThan(9000);
  });

  // ---- confidence ----------------------------------------------------------

  it('ignores a detection below the confidence floor', () => {
    const t = feed(phone(0.4), 6000); // floor is 0.55
    feed(oneFace, 3000, t + 200);

    expect(typesOf('PHONE_DETECTED')).toHaveLength(0);
  });

  it('records the highest confidence seen during the event', () => {
    let t = feed(phone(0.6), 3000);
    t = feed(phone(0.95), 2000, t + 200);
    t = feed(phone(0.7), 2000, t + 200);
    feed(oneFace, 3000, t + 200);

    expect(typesOf('PHONE_DETECTED')[0].confidence).toBeCloseTo(0.95);
  });

  it('keeps detector details such as the bounding box', () => {
    const t = feed(phone(0.9), 4000);
    feed(oneFace, 3000, t + 200);

    expect(typesOf('PHONE_DETECTED')[0].details.bbox).toEqual([1, 2, 3, 4]);
  });

  // ---- multiple faces / persons -------------------------------------------

  it('opens MULTIPLE_FACES with the face count attached', () => {
    const t = feed(twoFaces, 4000);
    feed(oneFace, 3000, t + 200);

    const [event] = typesOf('MULTIPLE_FACES');
    expect(event.details.faceCount).toBe(2);
  });

  it('applies the confidence floor to MULTIPLE_PERSONS', () => {
    const weak = { faceCount: 1, headDirection: 'CENTER', personCount: 2, personScore: 0.4 };
    const t = feed(weak, 6000);
    feed(oneFace, 3000, t + 200);

    expect(typesOf('MULTIPLE_PERSONS')).toHaveLength(0);
  });

  // ---- head turn -----------------------------------------------------------

  it('reports a sustained head turn with its direction', () => {
    const left = { faceCount: 1, headDirection: 'LEFT' };
    const t = feed(left, 6000);
    feed(oneFace, 3000, t + 200);

    const [event] = typesOf('HEAD_TURN');
    expect(event.details.direction).toBe('LEFT');
    expect(event.durationMs).toBeGreaterThanOrEqual(6000);
  });

  it('does not report a head turn when no face is visible', () => {
    // A missing face is already NO_FACE; claiming a head turn too would be
    // double-reporting something the detector cannot actually see.
    const t = feed({ faceCount: 0, headDirection: 'LEFT' }, 8000);
    feed(oneFace, 3000, t + 200);

    expect(typesOf('HEAD_TURN')).toHaveLength(0);
    expect(typesOf('NO_FACE')).toHaveLength(1);
  });

  it('ignores a brief glance away', () => {
    const left = { faceCount: 1, headDirection: 'LEFT' };
    feed(oneFace, 400);
    const t = feed(left, 2000, T0 + 600); // under the 4s threshold
    feed(oneFace, 3000, t + 200);

    expect(typesOf('HEAD_TURN')).toHaveLength(0);
  });

  // ---- long events ---------------------------------------------------------

  it('splits a very long event so a crashed tab cannot lose it', () => {
    feed(noFace, 70000, T0, 1000);

    // 70s of no face -> two completed 30s slices, third still open.
    const split = typesOf('NO_FACE');
    expect(split.length).toBeGreaterThanOrEqual(2);
    expect(split.every((e) => e.details.split === true)).toBe(true);
    expect(engine.activeTypes()).toContain('NO_FACE');
  });

  it('closes everything still open when the session ends', () => {
    feed(noFace, 8000);
    expect(typesOf('NO_FACE')).toHaveLength(0);

    engine.closeAll(T0 + 9000);

    const [event] = typesOf('NO_FACE');
    expect(event.details.closedAtSessionEnd).toBe(true);
    expect(event.durationMs).toBe(9000);
    expect(engine.activeTypes()).toHaveLength(0);
  });

  // ---- face presence baseline ---------------------------------------------

  it('records FACE_PRESENT at the start and again after the face returns', () => {
    feed(oneFace, 1000);
    expect(typesOf('FACE_PRESENT')).toHaveLength(1);

    const t = feed(noFace, 6000, T0 + 1200);
    feed(oneFace, 2000, t + 200);

    expect(typesOf('FACE_PRESENT')).toHaveLength(2);
  });

  // ---- browser events ------------------------------------------------------

  it('opens and closes a tab switch', () => {
    engine.browserEventStart('TAB_SWITCH', T0);
    engine.browserEventEnd('TAB_SWITCH', T0 + 4000);

    const [event] = typesOf('TAB_SWITCH');
    expect(event.durationMs).toBe(4000);
  });

  it('discards a window blur shorter than its confirmation delay', () => {
    engine.browserEventStart('WINDOW_BLUR', T0);
    engine.browserEventEnd('WINDOW_BLUR', T0 + 200); // under the 500ms enter

    expect(typesOf('WINDOW_BLUR')).toHaveLength(0);
  });

  it('confirms a window blur that lasts long enough', () => {
    engine.browserEventStart('WINDOW_BLUR', T0);
    engine.tickBrowserEvents(T0 + 800);
    engine.browserEventEnd('WINDOW_BLUR', T0 + 3000);

    expect(typesOf('WINDOW_BLUR')).toHaveLength(1);
  });

  it('ignores a repeated start for an already open event', () => {
    engine.browserEventStart('TAB_SWITCH', T0);
    engine.browserEventStart('TAB_SWITCH', T0 + 500);
    engine.browserEventStart('TAB_SWITCH', T0 + 900);
    engine.browserEventEnd('TAB_SWITCH', T0 + 2000);

    expect(typesOf('TAB_SWITCH')).toHaveLength(1);
    expect(typesOf('TAB_SWITCH')[0].durationMs).toBe(2000);
  });

  // ---- upload contract -----------------------------------------------------

  it('gives every event a unique id so retries can be de-duplicated', () => {
    const t = feed(noFace, 8000);
    feed(oneFace, 3000, t + 200);
    const t2 = feed(phone(0.9), 5000, t + 4000);
    feed(oneFace, 3000, t2 + 200);

    const ids = events.map((e) => e.clientEventId);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('emits ISO timestamps and a numeric duration the API accepts', () => {
    const t = feed(noFace, 8000);
    feed(oneFace, 3000, t + 200);

    const [event] = typesOf('NO_FACE');
    expect(event.startTime).toMatch(/^\d{4}-\d{2}-\d{2}T.*Z$/);
    expect(event.endTime).toMatch(/^\d{4}-\d{2}-\d{2}T.*Z$/);
    expect(typeof event.durationMs).toBe('number');
    expect(event.durationMs).toBeGreaterThan(0);
  });

  it('tracks several concurrent conditions independently', () => {
    const both = { faceCount: 2, headDirection: 'CENTER', phone: { score: 0.9, bbox: [0, 0, 1, 1] } };
    const t = feed(both, 6000);
    feed(oneFace, 4000, t + 200);

    expect(typesOf('MULTIPLE_FACES')).toHaveLength(1);
    expect(typesOf('PHONE_DETECTED')).toHaveLength(1);
    expect(typesOf('NO_FACE')).toHaveLength(0);
  });
});
