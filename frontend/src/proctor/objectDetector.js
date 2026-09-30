import '@tensorflow/tfjs';
import * as cocoSsd from '@tensorflow-models/coco-ssd';
import { OBJECT_CLASSES, EVENT_RULES } from './proctorConfig.js';

/**
 * Detects people and mobile phones with COCO-SSD.
 *
 * COCO-SSD is a general 80-class detector; we use exactly two of those classes
 * and ignore the rest. It is markedly heavier than face landmarking, which is
 * why it runs on its own slower loop.
 *
 * Known limitation, documented rather than hidden: the `cell phone` class also
 * fires on other small dark rectangles - a TV remote, a wallet, a card. That is
 * why a detection has to clear a confidence floor *and* persist for over a
 * second before it becomes an event, and why the report calls it "observed"
 * rather than anything stronger.
 */
export class ObjectDetector {
  /**
   * @param {{withBoxes?: boolean}} [options] `withBoxes` keeps the per-person
   *   boxes for the DEMO overlay. Off by default, so NORMAL mode carries no
   *   data it will never draw.
   *
   *   COCO-SSD already returns a box with every prediction - the phone box has
   *   always been kept because the event details carry it. This only stops
   *   discarding the ones for people, so detection is unchanged.
   */
  constructor({ withBoxes = false } = {}) {
    this.model = null;
    this.withBoxes = withBoxes;
    this.lastResult = { personCount: 0, personScore: null, phone: null, personBoxes: [] };
  }

  async load(basePath = '/exam') {
    // Served locally so the interview does not depend on a CDN.
    this.model = await cocoSsd.load({
      base: 'lite_mobilenet_v2',
      modelUrl: `${basePath}/models/coco-ssd/model.json`,
    });
  }

  get ready() {
    return this.model != null;
  }

  /**
   * @param {HTMLVideoElement} video
   * @returns {{personCount:number, personScore:number|null,
   *   phone:{score:number,bbox:number[]}|null,
   *   personBoxes:Array<{score:number,bbox:number[]}>}}
   *   Boxes are in video pixels. `personBoxes` is empty unless the detector was
   *   constructed with `withBoxes`.
   */
  async detect(video) {
    if (!this.model || video.readyState < 2) return this.lastResult;

    let predictions;
    try {
      // Ask for a few extra boxes so a second person is not crowded out by
      // high-scoring detections of the candidate.
      predictions = await this.model.detect(video, 10);
    } catch {
      return this.lastResult;
    }

    const persons = predictions.filter((p) => p.class === OBJECT_CLASSES.person);
    const phones = predictions
      .filter((p) => p.class === OBJECT_CLASSES.phone)
      .sort((a, b) => b.score - a.score);

    const personFloor = EVENT_RULES.MULTIPLE_PERSONS.minConfidence ?? 0;
    const confidentPersons = persons.filter((p) => p.score >= personFloor);

    const best = phones[0];
    this.lastResult = {
      personCount: confidentPersons.length,
      personScore: confidentPersons.length
        ? Math.min(...confidentPersons.map((p) => p.score))
        : null,
      phone: best
        ? { score: round3(best.score), bbox: best.bbox.map((n) => Math.round(n)) }
        : null,
      personBoxes: this.withBoxes
        ? confidentPersons.map((p) => ({
            score: round3(p.score),
            bbox: p.bbox.map((n) => Math.round(n)),
          }))
        : [],
    };
    return this.lastResult;
  }

  dispose() {
    this.model?.dispose?.();
    this.model = null;
  }
}

function round3(n) {
  return Math.round(n * 1000) / 1000;
}
