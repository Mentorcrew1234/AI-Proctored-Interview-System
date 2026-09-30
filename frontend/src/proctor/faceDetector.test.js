import { describe, it, expect } from 'vitest';
import {
  acceptedFaceIndices,
  boundingBoxes,
  classifyHead,
  eulerFromMatrix,
} from './faceDetector.js';
import { FACE_GATE, HEAD_POSE } from './proctorConfig.js';

/**
 * The pure geometry behind face counting and head pose.
 *
 * These functions decide whether a `MULTIPLE_FACES` or `HEAD_TURN` observation
 * is recorded against a candidate, which makes them worth pinning even though
 * the model that feeds them cannot be run here.
 *
 * What these tests do NOT establish: whether the thresholds are right for a
 * real webcam, or whether the yaw sign matches MediaPipe's convention. Both
 * need a camera. See the note on the yaw tests below.
 */
describe('faceDetector geometry', () => {
  /** A normalised box centred on (cx, cy). */
  const box = (cx, cy, size) => ({ x: cx - size / 2, y: cy - size / 2, w: size, h: size });

  // A comfortable face: 25% of the frame across, so ~6% of its area - well
  // above the gate's 1.5% floor.
  const FACE = 0.25;

  describe('boundingBoxes', () => {
    it('is the extent of the landmarks, normalised', () => {
      const points = [
        { x: 0.2, y: 0.3 },
        { x: 0.5, y: 0.1 },
        { x: 0.4, y: 0.6 },
      ];

      const [b] = boundingBoxes([points]);
      expect(b.x).toBeCloseTo(0.2, 10);
      expect(b.y).toBeCloseTo(0.1, 10);
      expect(b.w).toBeCloseTo(0.3, 10);
      expect(b.h).toBeCloseTo(0.5, 10);
    });

    it('returns nothing for no faces', () => {
      expect(boundingBoxes([])).toEqual([]);
      expect(boundingBoxes(undefined)).toEqual([]);
    });
  });

  describe('acceptedFaceIndices', () => {
    it('accepts one ordinary face', () => {
      expect(acceptedFaceIndices([box(0.5, 0.5, FACE)])).toEqual([0]);
    });

    it('accepts two people who are genuinely apart', () => {
      const two = [box(0.25, 0.5, FACE), box(0.75, 0.5, FACE)];

      expect(acceptedFaceIndices(two)).toHaveLength(2);
    });

    // The regression this whole gate exists for: a phone held up in front of
    // the face made the landmarker report the occluded region as a second
    // face, so the gesture that looks most suspicious produced the most likely
    // false MULTIPLE_FACES.
    it('rejects a phantom face sitting on top of a real one', () => {
      const real = box(0.5, 0.5, FACE);
      const phantom = box(0.53, 0.52, FACE * 0.8); // same face, slightly offset

      expect(acceptedFaceIndices([real, phantom])).toEqual([0]);
    });

    it('rejects a fragment too small to be a face', () => {
      const real = box(0.3, 0.5, FACE);
      // Far away from the real face, so only the area rule can reject it.
      const speck = box(0.8, 0.5, 0.05); // 0.25% of the frame

      expect(acceptedFaceIndices([real, speck])).toEqual([0]);
    });

    it('keeps the largest face first, whatever order the model returned them', () => {
      const small = box(0.2, 0.5, FACE);
      const large = box(0.8, 0.5, FACE * 1.5);

      // Index 1 is the larger, so it leads - which is what decides whose head
      // pose is read.
      expect(acceptedFaceIndices([small, large])).toEqual([1, 0]);
    });

    it('can only ever remove a face, never invent one', () => {
      const many = [box(0.2, 0.2, FACE), box(0.8, 0.2, FACE), box(0.5, 0.8, FACE)];

      expect(acceptedFaceIndices(many).length).toBeLessThanOrEqual(many.length);
      expect(acceptedFaceIndices([])).toEqual([]);
    });

    it('turns on the area threshold rather than some other size', () => {
      const side = Math.sqrt(FACE_GATE.minAreaFraction);
      const anchor = box(0.2, 0.2, FACE); // far away, so only area can decide
      const justOver = box(0.8, 0.8, side * 1.05);
      const justUnder = box(0.8, 0.8, side * 0.95);

      expect(acceptedFaceIndices([anchor, justOver])).toHaveLength(2);
      expect(acceptedFaceIndices([anchor, justUnder])).toHaveLength(1);
    });

    it('turns on the separation threshold rather than some other distance', () => {
      const a = box(0.3, 0.5, FACE);
      const near = box(0.3 + FACE_GATE.minCentreDistance * 0.95, 0.5, FACE);
      const far = box(0.3 + FACE_GATE.minCentreDistance * 1.05, 0.5, FACE);

      expect(acceptedFaceIndices([a, near])).toHaveLength(1);
      expect(acceptedFaceIndices([a, far])).toHaveLength(2);
    });
  });

  describe('eulerFromMatrix', () => {
    // MediaPipe hands back a column-major 4x4, so element (row r, col c) is
    // m[c * 4 + r]. Built here from a rotation matrix rather than hard-coded,
    // so the test states the maths instead of restating the implementation.
    const columnMajor = (r) => [
      r[0][0], r[1][0], r[2][0], 0,
      r[0][1], r[1][1], r[2][1], 0,
      r[0][2], r[1][2], r[2][2], 0,
      0, 0, 0, 1,
    ];
    const rad = (deg) => (deg * Math.PI) / 180;

    /** Rotation about Y, the axis a head turns left/right around. */
    const aboutY = (deg) => {
      const c = Math.cos(rad(deg));
      const s = Math.sin(rad(deg));
      return columnMajor([
        [c, 0, s],
        [0, 1, 0],
        [-s, 0, c],
      ]);
    };

    /** Rotation about X, the axis a head nods around. */
    const aboutX = (deg) => {
      const c = Math.cos(rad(deg));
      const s = Math.sin(rad(deg));
      return columnMajor([
        [1, 0, 0],
        [0, c, -s],
        [0, s, c],
      ]);
    };

    it('reads zero rotation as facing forward', () => {
      const { yaw, pitch } = eulerFromMatrix(aboutY(0));

      expect(yaw).toBeCloseTo(0, 5);
      expect(pitch).toBeCloseTo(0, 5);
    });

    it('reads rotation about Y as yaw and leaves pitch alone', () => {
      const { yaw, pitch } = eulerFromMatrix(aboutY(30));

      expect(yaw).toBeCloseTo(30, 4);
      expect(pitch).toBeCloseTo(0, 4);
    });

    it('reads rotation about X as pitch and leaves yaw alone', () => {
      const { yaw, pitch } = eulerFromMatrix(aboutX(-25));

      expect(pitch).toBeCloseTo(-25, 4);
      expect(yaw).toBeCloseTo(0, 4);
    });

    // The two axes must not bleed into each other, which is the failure that
    // would make a head turn read as a nod.
    it('keeps the two axes independent when both are rotated', () => {
      const c = Math.cos(rad(20));
      const s = Math.sin(rad(20));
      const cx = Math.cos(rad(-15));
      const sx = Math.sin(rad(-15));
      // Ry(20) * Rx(-15), the order eulerFromMatrix assumes.
      const m = columnMajor([
        [c, s * sx, s * cx],
        [0, cx, -sx],
        [-s, c * sx, c * cx],
      ]);

      const { yaw, pitch } = eulerFromMatrix(m);

      expect(yaw).toBeCloseTo(20, 3);
      expect(pitch).toBeCloseTo(-15, 3);
    });

    it('is signed, so the two directions are distinguishable', () => {
      // What this does NOT settle is which physical direction the positive
      // sign corresponds to. That depends on MediaPipe's own coordinate
      // convention and can only be confirmed against a real camera - see
      // docs/system/quality/LIMITATIONS.md, "Head-pose direction". This test pins the maths;
      // it deliberately does not claim LEFT and RIGHT are the right way round.
      expect(eulerFromMatrix(aboutY(30)).yaw).toBeGreaterThan(0);
      expect(eulerFromMatrix(aboutY(-30)).yaw).toBeLessThan(0);
    });
  });

  describe('classifyHead', () => {
    it('calls a small deviation CENTER', () => {
      expect(classifyHead(0, 0)).toBe('CENTER');
      expect(classifyHead(HEAD_POSE.yawDegrees - 1, 0)).toBe('CENTER');
    });

    it('buckets yaw past the threshold', () => {
      expect(classifyHead(HEAD_POSE.yawDegrees + 1, 0)).toBe('RIGHT');
      expect(classifyHead(-HEAD_POSE.yawDegrees - 1, 0)).toBe('LEFT');
    });

    it('is inclusive at the threshold, so exactly 20 degrees is still CENTER', () => {
      expect(classifyHead(HEAD_POSE.yawDegrees, 0)).toBe('CENTER');
      expect(classifyHead(-HEAD_POSE.yawDegrees, 0)).toBe('CENTER');
    });

    it('checks down before sideways', () => {
      // Someone reading from their lap is looking down even though their head
      // is also turned, and DOWN is the more useful description.
      expect(classifyHead(40, HEAD_POSE.pitchDownDegrees - 5)).toBe('DOWN');
    });

    it('falls back to CENTER when yaw is unknown', () => {
      expect(classifyHead(null, 0)).toBe('CENTER');
      expect(classifyHead(null, null)).toBe('CENTER');
    });
  });
});
