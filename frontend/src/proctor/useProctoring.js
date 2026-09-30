import { useEffect, useRef, useState } from 'react';
import { EventEngine } from './eventEngine.js';
import { EventUploader } from './eventUploader.js';
import { FaceDetector } from './faceDetector.js';
import { ObjectDetector } from './objectDetector.js';
import { attachBrowserEvents } from './browserEvents.js';
import { DETECTION_INTERVALS } from './proctorConfig.js';
import { api } from '../api.js';

/**
 * Runs the whole browser-side proctoring pipeline for a live session.
 *
 *   camera -> detectors -> detection state -> event engine -> uploader -> API
 *
 * Two loops at different speeds, because the two models cost very different
 * amounts: face landmarking runs ~5x a second, object detection once a second.
 * Neither blocks the other, and object detection skips a tick if the previous
 * one is still running rather than queueing up work.
 *
 * `withBoxes` is the only thing the interview mode changes here, and it changes
 * nothing about detection: it asks the two detectors to keep the bounding boxes
 * they already computed, so the DEMO overlay has something to draw. Thresholds,
 * intervals, the event engine and the uploader are identical in both modes -
 * NORMAL records exactly what DEMO records, it just does not draw it.
 */
export function useProctoring({ videoRef, sessionId, active, withBoxes = false }) {
  const [status, setStatus] = useState({
    modelsReady: false,
    loadingMessage: 'Loading detection models...',
    error: null,
    faceCount: 0,
    headDirection: null,
    personCount: 0,
    phoneVisible: false,
    phoneScore: null,
    activeTypes: [],
    eventCounts: {},
    pendingUploads: 0,
    // Empty unless withBoxes; the DEMO overlay is the only consumer.
    faceBoxes: [],
    personBoxes: [],
    phoneBox: null,
    // Frame size the boxes are relative to, so the overlay can map them onto
    // however large the preview happens to be rendered.
    frame: { width: 0, height: 0 },
  });

  const engineRef = useRef(null);
  const uploaderRef = useRef(null);
  // Readable from finalise(), which runs outside the effect and so cannot see
  // the effect's own local. Same fact as status.modelsReady, without the render.
  const modelsReadyRef = useRef(false);

  useEffect(() => {
    if (!active || !sessionId) return undefined;

    let cancelled = false;
    let faceTimer = null;
    let objectTimer = null;
    let detachBrowser = null;
    let objectBusy = false;

    const faceDetector = new FaceDetector({ withBoxes });
    const objectDetector = new ObjectDetector({ withBoxes });
    const counts = {};
    // Mirrors status.modelsReady, but readable from the callbacks below without
    // going through React state, which they cannot see synchronously.
    let modelsLoaded = false;

    // Tells the server what THIS pipeline managed to do - never anything about
    // the candidate. Without it, a session where the models never loaded is
    // indistinguishable from a session where nothing happened, and the report
    // reads as clean either way.
    //
    // Failing to report coverage must never disturb the interview, so every
    // call swallows its own error: the worst case is the server keeping the
    // more cautious of the two views, which is the right way round to fail.
    const reportCoverage = (ready, note, options) =>
      api
        .reportMonitoringStatus(
          sessionId,
          { ready, droppedEvents: uploader.dropped, note },
          options,
        )
        .catch(() => {});

    const uploader = new EventUploader(
      (events, options) => api.uploadProctorEvents(sessionId, events, options),
      {
        // Report a loss the moment it happens rather than only at the end: an
        // interview that dies before finalise would otherwise take the news
        // with it.
        onDrop: () => reportCoverage(modelsLoaded, 'Some observations could not be uploaded'),
      },
    );
    uploaderRef.current = uploader;

    const engine = new EventEngine((event) => {
      counts[event.type] = (counts[event.type] ?? 0) + 1;
      uploader.enqueue(event);
      setStatus((s) => ({
        ...s,
        eventCounts: { ...counts },
        activeTypes: engine.activeTypes(),
        pendingUploads: uploader.pending,
      }));
    });
    engineRef.current = engine;

    // Latest object-detection result, refreshed on the slower loop and folded
    // into every face-loop update so the engine always sees a full picture.
    let latestObjects = { personCount: 0, personScore: null, phone: null, personBoxes: [] };

    (async () => {
      try {
        setStatus((s) => ({ ...s, loadingMessage: 'Loading face model...' }));
        await faceDetector.load();
        if (cancelled) return;

        setStatus((s) => ({ ...s, loadingMessage: 'Loading object model...' }));
        await objectDetector.load();
        if (cancelled) return;

        modelsLoaded = true;
        modelsReadyRef.current = true;
        setStatus((s) => ({ ...s, modelsReady: true, loadingMessage: null }));
        // The interview is now genuinely being watched. Say so, so that a
        // later absence of observations can honestly be read as "nothing was
        // detected" rather than "nothing was looking".
        reportCoverage(true, null);

        detachBrowser = attachBrowserEvents(engine, {
          onChange: () => setStatus((s) => ({ ...s, activeTypes: engine.activeTypes() })),
        });

        uploader.start();

        faceTimer = setInterval(() => {
          const video = videoRef.current;
          if (!video) return;

          const face = faceDetector.detect(video);
          const now = Date.now();

          engine.update(now, {
            faceCount: face.faceCount,
            headDirection: face.headDirection,
            personCount: latestObjects.personCount,
            personScore: latestObjects.personScore,
            phone: latestObjects.phone,
          });
          engine.tickBrowserEvents(now);

          setStatus((s) => ({
            ...s,
            faceCount: face.faceCount,
            headDirection: face.headDirection,
            personCount: latestObjects.personCount,
            phoneVisible: latestObjects.phone != null,
            phoneScore: latestObjects.phone?.score ?? null,
            activeTypes: engine.activeTypes(),
            pendingUploads: uploader.pending,
            // Both are already empty arrays when withBoxes is off, so NORMAL
            // carries nothing extra through state.
            faceBoxes: face.boxes ?? [],
            personBoxes: latestObjects.personBoxes ?? [],
            phoneBox: withBoxes ? (latestObjects.phone?.bbox ?? null) : null,
            frame: { width: video.videoWidth, height: video.videoHeight },
          }));
        }, DETECTION_INTERVALS.faceMs);

        objectTimer = setInterval(async () => {
          const video = videoRef.current;
          // Skip rather than queue: object detection can take longer than its
          // own interval on a slow machine.
          if (!video || objectBusy) return;
          objectBusy = true;
          try {
            latestObjects = await objectDetector.detect(video);
          } finally {
            objectBusy = false;
          }
        }, DETECTION_INTERVALS.objectMs);
      } catch (e) {
        if (!cancelled) {
          setStatus((s) => ({
            ...s,
            error: `Could not start monitoring: ${e.message}`,
            loadingMessage: null,
          }));
          // The failure used to live only in this component's state, so an
          // interview whose detectors never loaded still produced a report
          // that read as clean. Tell the server instead: the interview carries
          // on either way, but the report can now say "not observed".
          reportCoverage(false, `Detection models failed to load: ${e.message}`);
        }
      }
    })();

    // The queue is flushed every 10 seconds, so closing the tab could take up
    // to that much with it - plus whatever event was still open. This is the
    // last chance to save any of it. `pagehide` rather than `beforeunload`
    // because it also fires when a mobile browser backgrounds the page, and it
    // is not blocked by the bfcache.
    const onPageHide = () => {
      engine.closeAll(Date.now());
      uploader.drainOnUnload();
      reportCoverage(modelsLoaded, null, { keepalive: true });
    };
    window.addEventListener('pagehide', onPageHide);

    return () => {
      cancelled = true;
      window.removeEventListener('pagehide', onPageHide);
      if (faceTimer) clearInterval(faceTimer);
      if (objectTimer) clearInterval(objectTimer);
      detachBrowser?.();
      // Close anything still open so a mid-event exit is still recorded.
      engine.closeAll(Date.now());
      uploader.flushAll();
      faceDetector.close();
      objectDetector.dispose();
    };
  }, [active, sessionId, videoRef, withBoxes]);

  /**
   * Called when the interview finishes, to make sure nothing is left queued.
   *
   * The coverage report goes last, after the flush, so it carries the final
   * dropped count rather than one taken before the last batch had its chance.
   */
  async function finalise() {
    engineRef.current?.closeAll(Date.now());
    const uploader = uploaderRef.current;
    await uploader?.flushAll();
    if (uploader && sessionId) {
      await api
        .reportMonitoringStatus(sessionId, {
          ready: modelsReadyRef.current,
          droppedEvents: uploader.dropped,
          note: null,
        })
        .catch(() => {});
    }
  }

  return { status, finalise };
}
