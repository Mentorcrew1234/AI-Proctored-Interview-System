/**
 * Every threshold used by the proctoring pipeline, in one place.
 *
 * These values are deliberately conservative. The cost of a missed observation
 * is low; the cost of a false one is a candidate wrongly flagged, so each rule
 * has to hold for a sustained period before it is reported at all.
 */

export const DETECTION_INTERVALS = {
  /** Face landmarking is cheap, so it can run often. */
  faceMs: 200,
  /** Object detection is much heavier - once a second is enough for a phone. */
  objectMs: 1000,
};

export const HEAD_POSE = {
  /** Beyond this yaw the head is considered turned left or right. */
  yawDegrees: 20,
  /** Below this pitch the head is considered looking down. */
  pitchDownDegrees: -15,
};

/**
 * What has to be true before a landmarker result counts as a *separate* face.
 *
 * This exists because of the single most misleading failure the pipeline had:
 * a phone held up in front of the face made the landmarker treat the
 * partially-occluded facial region as a second face, producing a false
 * `MULTIPLE_FACES` on exactly the gesture that looks most suspicious.
 *
 * Both checks are about geometry the model already gave us, not new inference.
 * A phantom face sits almost on top of the real one and is usually smaller;
 * two real people are well apart. So a candidate face must be big enough to be
 * a face at all, and far enough from every already-accepted face to be a
 * different one.
 *
 * Values are normalised 0..1 against the video frame, so they do not depend on
 * the camera's resolution.
 *
 * These numbers are deliberately conservative - they can only ever REMOVE a
 * second face, never add one, so the failure mode is a missed observation
 * rather than a wrongly flagged candidate. They have not yet been tuned against
 * a real webcam; see docs/system/quality/LIMITATIONS.md.
 */
export const FACE_GATE = {
  /**
   * Minimum share of the frame a face's landmark box must cover. A real face
   * at webcam distance covers several per cent; occlusion phantoms are small.
   */
  minAreaFraction: 0.015,
  /**
   * Minimum distance between two faces' centres before the second is believed
   * to be a different person rather than an artefact of the first.
   */
  minCentreDistance: 0.15,
};

/**
 * Rule definitions.
 *
 * - `enterMs`      how long the condition must hold before an event opens
 * - `exitMs`       how long it must stop before the event closes (stops flicker)
 * - `minDurationMs` events shorter than this are discarded entirely
 * - `minConfidence` detector score required before the condition counts at all
 */
export const EVENT_RULES = {
  NO_FACE: { enterMs: 3000, exitMs: 1000, minDurationMs: 3000, minConfidence: null },
  MULTIPLE_FACES: { enterMs: 2000, exitMs: 1500, minDurationMs: 2000, minConfidence: null },
  MULTIPLE_PERSONS: { enterMs: 3000, exitMs: 2000, minDurationMs: 3000, minConfidence: 0.6 },
  PHONE_DETECTED: { enterMs: 1500, exitMs: 2000, minDurationMs: 1500, minConfidence: 0.55 },
  HEAD_TURN: { enterMs: 4000, exitMs: 1500, minDurationMs: 4000, minConfidence: null },

  // Browser events are unambiguous signals rather than probabilistic detections,
  // so they open immediately and close as soon as focus returns.
  TAB_SWITCH: { enterMs: 0, exitMs: 0, minDurationMs: 0, minConfidence: null },
  WINDOW_BLUR: { enterMs: 500, exitMs: 0, minDurationMs: 0, minConfidence: null },
  FULLSCREEN_EXIT: { enterMs: 0, exitMs: 0, minDurationMs: 0, minConfidence: null },
};

/**
 * An event open for longer than this is split into consecutive events. Without
 * this, closing the tab during a long event would lose it entirely, because an
 * event is only uploaded once it closes.
 */
export const MAX_OPEN_MS = 30000;

export const UPLOAD = {
  /** How often queued events are sent. */
  intervalMs: 10000,
  /** Send immediately once this many are queued. */
  batchSize: 20,
  maxRetries: 5,
  retryBackoffMs: 2000,
};

export const OBJECT_CLASSES = {
  person: 'person',
  phone: 'cell phone',
};
