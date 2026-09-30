import { FaceLandmarker, FilesetResolver } from '@mediapipe/tasks-vision';
import { FACE_GATE, HEAD_POSE } from './proctorConfig.js';

/**
 * Face presence, face count and coarse head pose, using MediaPipe's Face
 * Landmarker.
 *
 * One model covers three of the five vision features, which is why it was
 * chosen over a plain face detector: a detector alone gives presence and count
 * but no orientation.
 *
 * Scope note: this reads head *orientation* only. No eye or iris tracking, no
 * lip tracking and no expression analysis - those are out of scope by design,
 * not merely unimplemented.
 *
 * The model and its WASM runtime are served from this application, so the
 * interview does not depend on reaching a CDN mid-session.
 */
export class FaceDetector {
  /**
   * @param {{withBoxes?: boolean}} [options] `withBoxes` adds a bounding box
   *   per face to the result, for the DEMO overlay. Off by default, so NORMAL
   *   mode carries nothing it will never draw.
   *
   *   It does not change detection. Boxes are computed on every frame either
   *   way - the face-count gate needs them - and a box is only the extent of
   *   landmarks this model already produced on the same frame: no extra
   *   inference, no second pass. The numbers the event engine sees are
   *   identical in both modes.
   */
  constructor({ withBoxes = false } = {}) {
    this.landmarker = null;
    this.lastVideoTime = -1;
    this.withBoxes = withBoxes;
    this.lastResult = { faceCount: 0, headDirection: null, yaw: null, pitch: null, boxes: [] };
  }

  async load(basePath = '/exam') {
    const fileset = await FilesetResolver.forVisionTasks(`${basePath}/wasm`);
    this.landmarker = await FaceLandmarker.createFromOptions(fileset, {
      baseOptions: {
        modelAssetPath: `${basePath}/models/face/face_landmarker.task`,
        delegate: 'GPU',
      },
      runningMode: 'VIDEO',
      // Two is enough: the question is "is more than one person in frame",
      // not how many.
      numFaces: 2,
      outputFacialTransformationMatrixes: true,
      outputFaceBlendshapes: false,
    });
  }

  get ready() {
    return this.landmarker != null;
  }

  /**
   * @param {HTMLVideoElement} video
   * @returns {{faceCount:number, headDirection:string|null, yaw:number|null,
   *   pitch:number|null, boxes:Array<{x:number,y:number,w:number,h:number}>}}
   *   Boxes are normalised 0..1 against the video frame, and are empty unless
   *   the detector was constructed with `withBoxes`.
   */
  detect(video) {
    if (!this.landmarker || video.readyState < 2) return this.lastResult;

    // detectForVideo requires a strictly increasing timestamp.
    if (video.currentTime === this.lastVideoTime) return this.lastResult;
    this.lastVideoTime = video.currentTime;

    let result;
    try {
      result = this.landmarker.detectForVideo(video, performance.now());
    } catch {
      // A dropped frame should not take the whole loop down.
      return this.lastResult;
    }

    // Boxes are now computed on every frame rather than only for the DEMO
    // overlay, because the face-count gate needs them. This is not extra
    // inference: a box is min/max over landmarks the model already returned for
    // this frame. `withBoxes` still decides only whether they are handed out
    // for drawing.
    const allBoxes = boundingBoxes(result.faceLandmarks);
    const kept = acceptedFaceIndices(allBoxes);

    const faceCount = kept.length;
    let headDirection = null;
    let yaw = null;
    let pitch = null;

    if (faceCount > 0) {
      // Head pose comes from the largest ACCEPTED face - acceptedFaceIndices
      // returns them largest first - not from index 0, which may have been
      // rejected as an artefact.
      const matrix = result.facialTransformationMatrixes?.[kept[0]]?.data;
      if (matrix) {
        ({ yaw, pitch } = eulerFromMatrix(matrix));
        headDirection = classifyHead(yaw, pitch);
      } else {
        headDirection = 'CENTER';
      }
    }

    this.lastResult = {
      faceCount,
      headDirection,
      yaw,
      pitch,
      // Only the accepted faces are drawn, so the overlay shows what the event
      // engine is actually being told rather than a box the gate discarded.
      boxes: this.withBoxes ? kept.map((i) => allBoxes[i]) : [],
    };
    return this.lastResult;
  }

  close() {
    this.landmarker?.close?.();
    this.landmarker = null;
  }
}

/**
 * The extent of each face's landmarks, as a normalised box.
 *
 * Reads points the model already returned for the frame, so it adds no
 * inference of its own. Used by the face-count gate below, and by the DEMO
 * overlay for drawing.
 */
export function boundingBoxes(faceLandmarks) {
  if (!faceLandmarks?.length) return [];

  return faceLandmarks.map((points) => {
    let minX = 1;
    let minY = 1;
    let maxX = 0;
    let maxY = 0;
    for (const p of points) {
      if (p.x < minX) minX = p.x;
      if (p.y < minY) minY = p.y;
      if (p.x > maxX) maxX = p.x;
      if (p.y > maxY) maxY = p.y;
    }
    return { x: minX, y: minY, w: maxX - minX, h: maxY - minY };
  });
}

/**
 * Decides which of the landmarker's faces count as genuinely separate people.
 *
 * The problem this solves: a phone held in front of the face made the
 * landmarker report the partially-occluded facial region as a second face, so
 * the most suspicious-looking gesture produced the most likely false
 * `MULTIPLE_FACES`. See docs/system/quality/LIMITATIONS.md.
 *
 * Two rules, both on geometry the model already produced:
 *
 *   1. a face must cover at least `minAreaFraction` of the frame - an artefact
 *      is usually a fragment of a face rather than a whole one;
 *   2. it must be at least `minCentreDistance` from every face already
 *      accepted - a phantom sits almost on top of the face that produced it,
 *      whereas two real people are well apart.
 *
 * Largest first, so the biggest face is always kept and the question is only
 * ever whether to believe the *additional* ones. That direction matters: this
 * can only ever remove a face, never invent one, so the worst case is a missed
 * observation rather than a candidate wrongly flagged.
 *
 * @param {Array<{x:number,y:number,w:number,h:number}>} boxes normalised boxes
 * @returns {number[]} indices into `boxes`, largest area first
 */
export function acceptedFaceIndices(boxes) {
  if (!boxes?.length) return [];

  const candidates = boxes
    .map((box, index) => ({ index, box, area: box.w * box.h }))
    .filter((c) => c.area >= FACE_GATE.minAreaFraction)
    .sort((a, b) => b.area - a.area);

  const accepted = [];
  for (const candidate of candidates) {
    const distinct = accepted.every(
      (other) => centreDistance(candidate.box, other.box) >= FACE_GATE.minCentreDistance,
    );
    if (distinct) accepted.push(candidate);
  }
  return accepted.map((c) => c.index);
}

/** Distance between two normalised boxes' centres. */
function centreDistance(a, b) {
  return Math.hypot(a.x + a.w / 2 - (b.x + b.w / 2), a.y + a.h / 2 - (b.y + b.h / 2));
}

/**
 * Extracts yaw and pitch from MediaPipe's 4x4 facial transformation matrix.
 * The array is column-major, so element (row r, column c) is `m[c * 4 + r]`.
 */
export function eulerFromMatrix(m) {
  const r20 = m[2];
  const r21 = m[6];
  const r22 = m[10];

  const yaw = Math.atan2(-r20, Math.hypot(r21, r22)) * (180 / Math.PI);
  const pitch = Math.atan2(r21, r22) * (180 / Math.PI);
  return { yaw, pitch };
}

/**
 * Buckets a head pose into CENTER / LEFT / RIGHT / DOWN.
 *
 * Down is checked first: someone reading from their lap is looking down even
 * though their head is also slightly turned.
 */
export function classifyHead(yaw, pitch) {
  if (pitch != null && pitch < HEAD_POSE.pitchDownDegrees) return 'DOWN';
  if (yaw == null) return 'CENTER';
  if (yaw > HEAD_POSE.yawDegrees) return 'RIGHT';
  if (yaw < -HEAD_POSE.yawDegrees) return 'LEFT';
  return 'CENTER';
}
