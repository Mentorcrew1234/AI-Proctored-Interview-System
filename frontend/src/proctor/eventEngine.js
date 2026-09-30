import { EVENT_RULES, MAX_OPEN_MS } from './proctorConfig.js';

/**
 * Turns a continuous stream of per-frame detections into a small number of
 * discrete, meaningful events.
 *
 * Why this exists: a raw detector fires ~5 times a second. Uploading that would
 * mean thousands of rows per interview, most of them noise from a hand passing
 * the lens or a single bad frame. This engine collapses it to "a phone was
 * visible for 5.2 seconds, once".
 *
 * Each rule is a small state machine:
 *
 *   IDLE --condition true--> PENDING --held for enterMs--> OPEN
 *     ^                         |                            |
 *     |                condition false               condition false
 *     +-------------------------+                            |
 *     |                                          held clear for exitMs
 *     +------------------------------------------------------+
 *
 * The event's start time is when the condition *actually* began, not when it was
 * confirmed, so durations are honest. An event shorter than minDurationMs is
 * discarded rather than reported.
 *
 * Deliberately pure: no DOM, no timers, no network. Time is passed in, events
 * come out through a callback. That is what makes it testable without a browser.
 */
export class EventEngine {
  /**
   * @param {(event: object) => void} onEvent called once per completed event
   * @param {{rules?: object, maxOpenMs?: number, idFactory?: () => string}} options
   */
  constructor(onEvent, options = {}) {
    this.onEvent = onEvent;
    this.rules = options.rules ?? EVENT_RULES;
    this.maxOpenMs = options.maxOpenMs ?? MAX_OPEN_MS;
    this.idFactory = options.idFactory ?? defaultIdFactory;
    /** @type {Map<string, object>} one state record per event type */
    this.states = new Map();
    this.faceWasPresent = null;
  }

  stateFor(type) {
    if (!this.states.has(type)) {
      this.states.set(type, {
        candidateSince: null,
        openedAt: null,
        clearSince: null,
        maxConfidence: null,
        details: {},
      });
    }
    return this.states.get(type);
  }

  /**
   * Feed one round of detections.
   *
   * @param {number} now epoch millis
   * @param {object} detection normalised detector output:
   *   { faceCount, headDirection, personCount, phone: {score, bbox}|null,
   *     personScore }
   */
  update(now, detection) {
    const {
      faceCount = 0,
      headDirection = null,
      personCount = 0,
      phone = null,
      personScore = null,
    } = detection ?? {};

    this.applyRule('NO_FACE', now, faceCount === 0, null, {});

    this.applyRule('MULTIPLE_FACES', now, faceCount >= 2, null, { faceCount });

    this.applyRule('MULTIPLE_PERSONS', now, personCount >= 2, personScore, { personCount });

    this.applyRule(
      'PHONE_DETECTED',
      now,
      phone != null,
      phone?.score ?? null,
      phone?.bbox ? { bbox: phone.bbox } : {},
    );

    // A turned head only counts when a face is actually visible; otherwise
    // NO_FACE already describes the situation and reporting both is misleading.
    const headTurned = faceCount >= 1 && headDirection != null && headDirection !== 'CENTER';
    this.applyRule('HEAD_TURN', now, headTurned, null, { direction: headDirection });

    this.trackFacePresence(now, faceCount);
  }

  /**
   * Advances one rule's state machine.
   *
   * @param {string} type       event type
   * @param {number} now        epoch millis
   * @param {boolean} rawActive is the underlying condition true this frame
   * @param {number|null} confidence detector score, if the detector reports one
   * @param {object} details    extra payload to attach to the event
   */
  applyRule(type, now, rawActive, confidence, details) {
    const rule = this.rules[type];
    if (!rule) return;

    // A detection below the confidence floor is treated as no detection at all.
    const active =
      rawActive && (rule.minConfidence == null || (confidence ?? 1) >= rule.minConfidence);

    const state = this.stateFor(type);

    if (active) {
      state.clearSince = null;

      if (state.openedAt == null) {
        if (state.candidateSince == null) {
          state.candidateSince = now;
          state.maxConfidence = confidence;
          state.details = { ...details };
        } else {
          state.maxConfidence = maxOrNull(state.maxConfidence, confidence);
          state.details = { ...state.details, ...details };
        }

        if (now - state.candidateSince >= rule.enterMs) {
          // Backdate to when the condition really started.
          state.openedAt = state.candidateSince;
        }
      } else {
        state.maxConfidence = maxOrNull(state.maxConfidence, confidence);
        state.details = { ...state.details, ...details };

        // Split a very long event so a crashed tab cannot swallow it.
        if (now - state.openedAt >= this.maxOpenMs) {
          this.emit(type, state.openedAt, now, state, { split: true });
          state.openedAt = now;
          state.candidateSince = now;
          state.maxConfidence = confidence;
        }
      }
      return;
    }

    // Condition is not active this frame.
    if (state.openedAt == null) {
      // Never confirmed - forget the partial candidate entirely.
      state.candidateSince = null;
      state.maxConfidence = null;
      state.details = {};
      return;
    }

    if (state.clearSince == null) {
      state.clearSince = now;
    }
    if (now - state.clearSince >= rule.exitMs) {
      // The event ended when the condition stopped, not when we noticed.
      this.emit(type, state.openedAt, state.clearSince, state, {});
      this.resetState(state);
    }
  }

  /**
   * FACE_PRESENT is a baseline marker rather than a suspicious event: it is
   * recorded once at the start and again whenever the face comes back, so the
   * timeline shows monitoring was actually working.
   */
  trackFacePresence(now, faceCount) {
    const present = faceCount >= 1;
    if (this.faceWasPresent === null) {
      if (present) this.emitInstant('FACE_PRESENT', now, {});
      this.faceWasPresent = present;
      return;
    }
    if (present && !this.faceWasPresent) {
      this.emitInstant('FACE_PRESENT', now, {});
    }
    this.faceWasPresent = present;
  }

  /** Browser-level signal starting (tab hidden, window blurred, fullscreen exited). */
  browserEventStart(type, now, details = {}) {
    const state = this.stateFor(type);
    if (state.openedAt != null) return; // already open
    state.candidateSince = now;
    state.details = { ...details };
    const rule = this.rules[type];
    if (!rule || rule.enterMs === 0) {
      state.openedAt = now;
    }
  }

  /** The matching signal ending (tab visible, focus returned, fullscreen re-entered). */
  browserEventEnd(type, now) {
    const state = this.stateFor(type);
    if (state.openedAt == null) {
      // Ended before it was confirmed - nothing worth reporting.
      this.resetState(state);
      return;
    }
    this.emit(type, state.openedAt, now, state, {});
    this.resetState(state);
  }

  /**
   * Confirms any browser event whose enterMs has now elapsed. Called from the
   * detection loop so a blur that lasts long enough still opens even though no
   * DOM event fires while the window stays blurred.
   */
  tickBrowserEvents(now) {
    for (const type of ['TAB_SWITCH', 'WINDOW_BLUR', 'FULLSCREEN_EXIT']) {
      const state = this.states.get(type);
      const rule = this.rules[type];
      if (!state || !rule) continue;
      if (state.openedAt == null && state.candidateSince != null
          && now - state.candidateSince >= rule.enterMs) {
        state.openedAt = state.candidateSince;
      }
      if (state.openedAt != null && now - state.openedAt >= this.maxOpenMs) {
        this.emit(type, state.openedAt, now, state, { split: true });
        state.openedAt = now;
        state.candidateSince = now;
      }
    }
  }

  /** Closes every open event. Called when the interview ends. */
  closeAll(now) {
    for (const [type, state] of this.states.entries()) {
      if (state.openedAt != null) {
        this.emit(type, state.openedAt, now, state, { closedAtSessionEnd: true });
        this.resetState(state);
      }
    }
  }

  emit(type, startedAt, endedAt, state, extraDetails) {
    const durationMs = Math.max(0, endedAt - startedAt);
    const rule = this.rules[type];

    // Too brief to be meaningful - discard rather than report noise.
    if (rule && durationMs < rule.minDurationMs) return;

    this.onEvent({
      clientEventId: this.idFactory(),
      type,
      startTime: new Date(startedAt).toISOString(),
      endTime: new Date(endedAt).toISOString(),
      durationMs,
      confidence: state.maxConfidence ?? null,
      details: { ...state.details, ...extraDetails },
    });
  }

  /** A point-in-time marker with no duration. */
  emitInstant(type, now, details) {
    this.onEvent({
      clientEventId: this.idFactory(),
      type,
      startTime: new Date(now).toISOString(),
      endTime: new Date(now).toISOString(),
      durationMs: 0,
      confidence: null,
      details,
    });
  }

  resetState(state) {
    state.candidateSince = null;
    state.openedAt = null;
    state.clearSince = null;
    state.maxConfidence = null;
    state.details = {};
  }

  /** Event types currently open - drives the live status chips on screen. */
  activeTypes() {
    const active = [];
    for (const [type, state] of this.states.entries()) {
      if (state.openedAt != null) active.push(type);
    }
    return active;
  }
}

function maxOrNull(a, b) {
  if (a == null) return b ?? null;
  if (b == null) return a;
  return Math.max(a, b);
}

function defaultIdFactory() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return 'evt-' + Date.now() + '-' + Math.random().toString(16).slice(2, 10);
}
