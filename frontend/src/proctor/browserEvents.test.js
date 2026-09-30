// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { attachBrowserEvents, requestFullscreen } from './browserEvents.js';

/**
 * The bridge from browser signals into the event engine.
 *
 * Worth testing carefully despite being thin: it produces three of the eight
 * event types - `TAB_SWITCH`, `WINDOW_BLUR` and `FULLSCREEN_EXIT` - and those
 * are the three docs/system/quality/LIMITATIONS.md describes as the *most* reliable in the
 * system, because they come from deterministic browser APIs rather than from
 * inference. Everything downstream trusts them accordingly.
 *
 * The engine is a spy here. What matters is which calls this module makes and
 * when; what the engine then does with them is the event engine's own tests.
 */
describe('attachBrowserEvents', () => {
  let engine;
  let detach;
  let onChange;

  /** Records calls without reimplementing the engine. */
  const spyEngine = () => ({
    started: [],
    ended: [],
    browserEventStart(type, at, details) {
      this.started.push({ type, at, details });
    },
    browserEventEnd(type, at) {
      this.ended.push({ type, at });
    },
  });

  /** jsdom exposes document.hidden as a getter, so it has to be redefined. */
  const setHidden = (hidden) => {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
  };

  const setFullscreen = (element) => {
    Object.defineProperty(document, 'fullscreenElement', {
      configurable: true,
      get: () => element,
    });
  };

  const fire = (target, type) => target.dispatchEvent(new Event(type));

  beforeEach(() => {
    engine = spyEngine();
    onChange = vi.fn();
    setHidden(false);
    setFullscreen(document.documentElement);
    detach = attachBrowserEvents(engine, { onChange });
  });

  afterEach(() => {
    detach?.();
  });

  // ---- TAB_SWITCH ---------------------------------------------------------

  it('opens TAB_SWITCH when the tab is hidden and closes it on return', () => {
    setHidden(true);
    fire(document, 'visibilitychange');

    expect(engine.started.map((e) => e.type)).toEqual(['TAB_SWITCH']);
    expect(engine.ended).toEqual([]);

    setHidden(false);
    fire(document, 'visibilitychange');

    expect(engine.ended.map((e) => e.type)).toEqual(['TAB_SWITCH']);
  });

  it('records why the event was raised, so the timeline can say', () => {
    setHidden(true);
    fire(document, 'visibilitychange');

    expect(engine.started[0].details).toEqual({ reason: 'visibilitychange' });
  });

  // ---- WINDOW_BLUR --------------------------------------------------------

  it('opens WINDOW_BLUR on blur and closes it on focus', () => {
    fire(window, 'blur');
    expect(engine.started.map((e) => e.type)).toEqual(['WINDOW_BLUR']);

    fire(window, 'focus');
    expect(engine.ended.map((e) => e.type)).toEqual(['WINDOW_BLUR']);
  });

  // Switching tabs fires BOTH visibilitychange and blur. Recording each would
  // report one action twice, against a candidate, on a timeline a human reads.
  it('does not double-count a tab switch as a blur as well', () => {
    setHidden(true);
    fire(document, 'visibilitychange');
    fire(window, 'blur');

    expect(engine.started.map((e) => e.type)).toEqual(['TAB_SWITCH']);
    expect(engine.started.filter((e) => e.type === 'WINDOW_BLUR')).toEqual([]);
  });

  it('still records a blur that happens while the tab is visible', () => {
    // Alt-tabbing to another application: the tab is not hidden, so this is
    // the only signal there is.
    setHidden(false);
    fire(window, 'blur');

    expect(engine.started.map((e) => e.type)).toEqual(['WINDOW_BLUR']);
  });

  // ---- FULLSCREEN_EXIT ----------------------------------------------------

  it('opens FULLSCREEN_EXIT on leaving fullscreen and closes it on return', () => {
    setFullscreen(null);
    fire(document, 'fullscreenchange');
    expect(engine.started.map((e) => e.type)).toEqual(['FULLSCREEN_EXIT']);

    setFullscreen(document.documentElement);
    fire(document, 'fullscreenchange');
    expect(engine.ended.map((e) => e.type)).toEqual(['FULLSCREEN_EXIT']);
  });

  // ---- the three are independent ------------------------------------------

  it('tracks all three conditions at once without confusing them', () => {
    setHidden(true);
    fire(document, 'visibilitychange');
    setFullscreen(null);
    fire(document, 'fullscreenchange');

    expect(engine.started.map((e) => e.type).sort())
      .toEqual(['FULLSCREEN_EXIT', 'TAB_SWITCH']);
  });

  // ---- the caller is told --------------------------------------------------

  it('notifies on every signal, so the UI can reflect the state', () => {
    setHidden(true);
    fire(document, 'visibilitychange');
    fire(window, 'focus');
    setFullscreen(null);
    fire(document, 'fullscreenchange');

    expect(onChange).toHaveBeenCalledTimes(3);
  });

  it('works without an onChange callback', () => {
    detach();
    detach = attachBrowserEvents(engine);

    setHidden(true);
    expect(() => fire(document, 'visibilitychange')).not.toThrow();
    expect(engine.started).toHaveLength(1);
  });

  // ---- detaching ----------------------------------------------------------

  it('stops recording once detached', () => {
    detach();
    detach = null;

    setHidden(true);
    fire(document, 'visibilitychange');
    fire(window, 'blur');
    setFullscreen(null);
    fire(document, 'fullscreenchange');

    // A session that has ended must not keep writing to its engine.
    expect(engine.started).toEqual([]);
    expect(engine.ended).toEqual([]);
  });

  it('detaches every listener, not merely the first', () => {
    detach();
    detach = null;

    fire(window, 'blur');
    fire(window, 'focus');
    expect(engine.started).toEqual([]);
    expect(engine.ended).toEqual([]);
  });
});

describe('requestFullscreen', () => {
  afterEach(() => {
    delete document.documentElement.requestFullscreen;
  });

  it('reports success when the browser grants it', async () => {
    document.documentElement.requestFullscreen = vi.fn().mockResolvedValue(undefined);

    await expect(requestFullscreen()).resolves.toBe(true);
  });

  // Browsers grant fullscreen only from a user gesture and refuse otherwise.
  // A refusal must be an answer, not an exception: docs/system/quality/LIMITATIONS.md says a
  // browser that forbids fullscreen is allowed through with the limitation
  // stated, because the alternative is a retry button that can never succeed.
  it('reports failure rather than throwing when refused', async () => {
    document.documentElement.requestFullscreen = vi.fn()
      .mockRejectedValue(new Error('Permissions check failed'));

    await expect(requestFullscreen()).resolves.toBe(false);
  });

  it('can be pointed at a specific element', async () => {
    const element = document.createElement('div');
    element.requestFullscreen = vi.fn().mockResolvedValue(undefined);

    await expect(requestFullscreen(element)).resolves.toBe(true);
    expect(element.requestFullscreen).toHaveBeenCalled();
  });

  it('reports failure when the API is missing entirely', async () => {
    // Some embedded browsers simply do not expose it.
    await expect(requestFullscreen()).resolves.toBe(false);
  });
});
