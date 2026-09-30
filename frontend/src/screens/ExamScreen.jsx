import { useCallback, useEffect, useRef, useState } from 'react';
import { useProctoring } from '../proctor/useProctoring.js';
import { useFullscreen } from '../proctor/useFullscreen.js';
import DemoMonitor from '../proctor/DemoMonitor.jsx';
import DetectionOverlay from '../proctor/DetectionOverlay.jsx';
import QuestionPanel from '../interview/QuestionPanel.jsx';
import { isDemo } from '../interviewMode.js';
import { useCountdown, formatClock, urgencyOf } from '../interview/useCountdown.js';
import { api } from '../api.js';

/**
 * Phases of the interview, kept explicit so the controls can never end up in a
 * state nobody chose. The earlier version tracked this with separate booleans
 * and left one of them set after a successful submit, which disabled every
 * button until the page was reloaded.
 */
const PHASE = {
  LOADING_QUESTIONS: 'LOADING_QUESTIONS',
  IN_PROGRESS: 'IN_PROGRESS',
  LOADING_NEXT: 'LOADING_NEXT',
  ALL_ANSWERED: 'ALL_ANSWERED',
  FINISHING: 'FINISHING',
  LOAD_FAILED: 'LOAD_FAILED',
};

/**
 * The live interview screen.
 *
 * One screen, two presentations, decided by `mode`:
 *
 * - NORMAL is the real candidate experience: question, answer, progress,
 *   finish. No detection state, no counters, no model status, no camera
 *   preview - a candidate should be thinking about the question, not watching
 *   a face counter.
 * - DEMO is the same interview with the monitoring panel and detection overlay
 *   beside it, so a viva audience can see what is actually being detected.
 *
 * **The pipeline itself does not know which mode it is in.** `useProctoring`
 * runs with the same thresholds and records the same events either way; the
 * mode only decides whether any of it is drawn. That is what makes the
 * demonstration honest - it shows the real system, not a version of it staged
 * for the audience.
 *
 * This reverses an earlier decision to show monitoring state to every
 * candidate. That was defensible - you can fix what you can see - but it puts
 * live model output in front of someone being assessed by it, which is neither
 * professional nor calming. The instructions screen still says plainly what is
 * monitored, so nothing is hidden about *what* is recorded; only the live
 * readout is gone.
 */
export default function ExamScreen({ session, stream, onFinish, mode }) {
  const demo = isDemo(mode);
  const videoRef = useRef(null);

  const { status, finalise } = useProctoring({
    videoRef,
    sessionId: session.sessionId,
    active: true,
    // The one thing mode changes in the pipeline: keep the boxes the detectors
    // already computed, so the overlay has something to draw.
    withBoxes: demo,
  });

  // UI state only. FULLSCREEN_EXIT continues to be recorded by the proctoring
  // pipeline regardless of what this shows.
  const { isFullscreen, supported: fullscreenSupported, enter: enterFullscreen, exit: exitFullscreen } =
    useFullscreen();

  const [questions, setQuestions] = useState(null);
  const [current, setCurrent] = useState(0);
  const [aiGenerated, setAiGenerated] = useState(false);
  const [mockMode, setMockMode] = useState(false);
  const [phase, setPhase] = useState(PHASE.LOADING_QUESTIONS);
  const [loadError, setLoadError] = useState(null);
  const [confirmFinish, setConfirmFinish] = useState(false);
  const [listening, setListening] = useState(false);
  const [expired, setExpired] = useState(false);
  const finishing = useRef(false);
  // Lets the countdown call doFinish, which is declared further down. A ref
  // rather than reordering, so the finish logic stays next to the UI it drives.
  const doFinishRef = useRef(null);

  useEffect(() => {
    if (videoRef.current && stream) {
      videoRef.current.srcObject = stream;
      videoRef.current.play().catch(() => {});
    }
  }, [stream]);

  /**
   * Runs out of time.
   *
   * Finishes through exactly the same path as the Finish button, so the
   * answers already submitted are kept and the report is produced by the
   * existing pipeline. The server decides the reason it records - this only
   * asks it to close - so an interview that expired is labelled TIME_EXPIRED
   * even though the request came from the same call Finish makes.
   */
  const handleExpiry = useCallback(() => {
    setExpired(true);
    doFinishRef.current?.();
  }, []);

  const { remaining, sync: syncCountdown } = useCountdown(session.remainingSeconds, handleExpiry);

  // Questions are generated server-side on first request and fixed thereafter,
  // so a reload resumes the same interview at the first unanswered question.
  const loadQuestions = useCallback(async () => {
    setPhase(PHASE.LOADING_QUESTIONS);
    setLoadError(null);
    try {
      const data = await api.questions(session.sessionId);
      // The refresh path: a reload lands here and picks up the real remaining
      // time from the server rather than restarting the clock.
      syncCountdown(data.remainingSeconds);
      setQuestions(data.questions);
      setAiGenerated(data.aiGenerated);
      setMockMode(Boolean(data.mockMode));
      const firstUnanswered = data.questions.findIndex((q) => !q.answered);
      const next = firstUnanswered === -1 ? data.questions.length : firstUnanswered;
      setCurrent(next);
      setPhase(next >= data.questions.length ? PHASE.ALL_ANSWERED : PHASE.IN_PROGRESS);
    } catch (e) {
      setLoadError(e.message || 'Could not load your questions.');
      setPhase(PHASE.LOAD_FAILED);
    }
  }, [session.sessionId, syncCountdown]);

  useEffect(() => {
    loadQuestions();
  }, [loadQuestions]);

  /**
   * Submits one answer and advances.
   *
   * `result.complete` and `result.totalQuestions` are the server's own word on
   * whether the interview is finished and how many questions exist now - the
   * same fields FIXED mode has always returned, reused rather than computed
   * locally so this works identically for both modes without knowing which
   * one is active. In FIXED mode every question was already loaded on the
   * first GET, so the "fetch the next one" branch below is never taken - it
   * exists for ADAPTIVE mode, where this same request generated the next
   * question server-side but did not hand back its text, and the existing
   * `GET /questions` (already safe to call repeatedly - see `loadQuestions`)
   * is what already knows how to return it.
   *
   * Errors are rethrown so the question panel can show them and offer a retry;
   * swallowing them here is what would leave the candidate staring at a dead
   * button.
   *
   * One error is deliberately NOT rethrown: a 409 on this exact call means the
   * server already has this answer - most likely this same request succeeded
   * once already and only its response was lost (a dropped connection, or a
   * failure elsewhere in the same request after the answer had already been
   * saved). The question panel's own retry resubmits the same question, which
   * would only repeat the same 409 forever; recovering through `loadQuestions`
   * (the same path a manual page reload already takes) is what actually gets
   * the candidate unstuck.
   */
  const submitAnswer = useCallback(
    async (questionId, rawTranscript, durationSeconds) => {
      let result;
      try {
        result = await api.submitAnswer(session.sessionId, questionId, rawTranscript, durationSeconds);
      } catch (e) {
        if (e.status === 409) {
          await loadQuestions();
          return null;
        }
        throw e;
      }
      // Correct any local drift against the server after every answer.
      syncCountdown(result.remainingSeconds);
      setQuestions((prev) =>
        prev ? prev.map((q) => (q.id === questionId ? { ...q, answered: true } : q)) : prev);

      if (result.complete) {
        setCurrent((i) => i + 1);
        setPhase(PHASE.ALL_ANSWERED);
        return result;
      }

      const haveNextLocally = Boolean(questions) && questions.length > current + 1;
      if (haveNextLocally) {
        setCurrent((i) => i + 1);
        setPhase(PHASE.IN_PROGRESS);
        return result;
      }

      // ADAPTIVE mode: the next question exists server-side but not here yet.
      setPhase(PHASE.LOADING_NEXT);
      try {
        const data = await api.questions(session.sessionId);
        syncCountdown(data.remainingSeconds);
        setQuestions(data.questions);
        setAiGenerated(data.aiGenerated);
        setMockMode(Boolean(data.mockMode));
        setCurrent((i) => i + 1);
        setPhase(PHASE.IN_PROGRESS);
      } catch (e) {
        setLoadError(e.message || 'Could not load your next question.');
        setPhase(PHASE.LOAD_FAILED);
      }
      return result;
    },
    [session.sessionId, questions, current, syncCountdown, loadQuestions],
  );

  /**
   * Releases every media resource this interview holds, and drops out of
   * fullscreen so the candidate is not left in it after finishing.
   *
   * Runs on any exit path, including finishing early. Speech recognition stops
   * itself when the question panel unmounts; this handles the camera track that
   * the device check opened.
   */
  const releaseMedia = useCallback(() => {
    try {
      stream?.getTracks().forEach((track) => track.stop());
    } catch {
      /* already released */
    }
    if (videoRef.current) {
      videoRef.current.srcObject = null;
    }
    exitFullscreen();
  }, [stream, exitFullscreen]);

  // Also release if the component goes away for any other reason.
  useEffect(() => releaseMedia, [releaseMedia]);

  async function doFinish() {
    if (finishing.current) return;
    finishing.current = true;
    setConfirmFinish(false);
    setPhase(PHASE.FINISHING);
    try {
      // Flush open observations first, so the report is built from the whole
      // picture rather than whatever happened to be uploaded already.
      await finalise();
      const result = await api.completeSession(session.sessionId);
      releaseMedia();
      onFinish(result);
    } catch (e) {
      setLoadError(e.message || 'Could not finish the interview.');
      setPhase(PHASE.LOAD_FAILED);
      finishing.current = false;
    }
  }

  doFinishRef.current = doFinish;

  const answeredCount = questions ? questions.filter((q) => q.answered).length : 0;
  // session.questionCount, not questions.length: in ADAPTIVE mode a
  // not-yet-generated question is still one the candidate would be skipping
  // by finishing now, even though it does not exist in local state yet.
  const unanswered = questions ? session.questionCount - answeredCount : 0;
  const cameraActive = Boolean(
    stream?.getVideoTracks?.().some((t) => t.readyState === 'live'),
  );

  /*
   * The one thing the candidate is told about monitoring during the interview.
   *
   * Fullscreen is the only condition they can act on directly, and it is a
   * deterministic browser fact rather than a model's opinion - so asking them
   * to fix it is fair in a way that "your face was not detected" would not be.
   * The exit is recorded either way; this is a nudge, not a punishment, and it
   * never blocks answering.
   */
  const fullscreenNotice =
    !isFullscreen && fullscreenSupported && phase !== PHASE.FINISHING ? (
      <div className="alert warn fullscreen-notice">
        <span>Please return to fullscreen mode to continue the interview.</span>
        <button type="button" onClick={enterFullscreen}>Return to fullscreen</button>
      </div>
    ) : null;

  const main = (
    <main className="exam-main">
      {fullscreenNotice}

      {/* Development notice - not a proctoring metric, and equally relevant in
          both modes, since it changes where the questions came from. */}
      {mockMode && (
        <div className="alert info">
          <strong>Development mode</strong> &mdash; AI question generation is disabled.
          Questions come from the built-in set.
        </div>
      )}

      {phase === PHASE.LOAD_FAILED && (
        <div className="card">
          <h1>Something went wrong</h1>
          <div className="alert error">{loadError}</div>
          <p className="muted">
            Your submitted answers are safe. You can try again without losing them.
          </p>
          <button onClick={loadQuestions}>Try again</button>
          <button className="danger" onClick={() => setConfirmFinish(true)}>
            Finish test
          </button>
        </div>
      )}

      {phase === PHASE.LOADING_QUESTIONS && (
        <div className="card">
          <h1>Preparing your interview</h1>
          <p className="muted">
            Loading your questions. You can continue once the next question is ready.
          </p>
        </div>
      )}

      {phase === PHASE.LOADING_NEXT && (
        <div className="card">
          <h1>Preparing your next question</h1>
          <p className="muted">Your answer was submitted. The next question is being prepared.</p>
        </div>
      )}

      {phase === PHASE.FINISHING && (
        <div className="card">
          <h1>{expired ? 'Time is up' : 'Finishing your interview'}</h1>
          <p className="muted">
            {expired
              ? 'The time allowed for this interview has run out. Saving the answers you submitted and closing the session...'
              : 'Saving your answers and closing the session...'}
          </p>
        </div>
      )}

      {phase === PHASE.IN_PROGRESS && questions && current < questions.length && (
        <>
          <div className="exam-progress">
            <div className="progress-row">
              {/* session.questionCount, not questions.length: the interview's
                  real total, already in StartExamResponse. In FIXED mode the
                  two are always equal - every question is loaded from the
                  start - but in ADAPTIVE mode only what has been generated so
                  far is loaded, and a dot per loaded question (or "final
                  answer" once the one loaded question happened to be the
                  last one asked) would misrepresent how much of the
                  interview remains. Nothing about a future question's
                  content is shown - only that a step for it exists. */}
              {Array.from({ length: session.questionCount }).map((_, i) => (
                <span
                  key={i}
                  className={`step ${i < current ? 'done' : i === current ? 'current' : ''}`}
                />
              ))}
            </div>
            <div className="exam-progress-meta">
              <span>
                Question <strong>{current + 1}</strong> of <strong>{session.questionCount}</strong>
              </span>

              {/* Counting down to the server's deadline. The value is seeded and
                  resynced from the server, so a refresh resumes rather than
                  restarts. Styling escalates as time runs low; it does not
                  flash, because someone is trying to think. */}
              <span className={`time-remaining time-${urgencyOf(remaining)}`}>
                <span className="time-label">Time remaining</span>
                <strong
                  role="timer"
                  aria-live={urgencyOf(remaining) === 'critical' ? 'assertive' : 'off'}
                >
                  {formatClock(remaining)}
                </strong>
              </span>
            </div>
          </div>

          <QuestionPanel
            question={questions[current]}
            index={current}
            total={session.questionCount}
            onSubmitted={submitAnswer}
            onFinish={() => setConfirmFinish(true)}
            onListeningChange={setListening}
          />

          {!aiGenerated && !mockMode && (
            <p className="muted small">
              These questions came from the offline question bank rather than the
              language model.
            </p>
          )}
        </>
      )}

      {phase === PHASE.ALL_ANSWERED && (
        <div className="card">
          <h1>All questions answered</h1>
          <p className="muted">
            Your answers have been submitted. Finish below to close the session and
            send any remaining monitoring observations.
          </p>
          <button className="danger" onClick={() => setConfirmFinish(true)}>Finish test</button>
        </div>
      )}

      {/* Confirmation. Finishing is deliberate, never a single stray click. */}
      {confirmFinish && (
        <div className="card confirm-panel">
          <h3>Finish interview?</h3>
          {unanswered > 0 ? (
            <p>
              You still have <strong>{unanswered}</strong> unanswered question
              {unanswered === 1 ? '' : 's'}. They will be left unanswered and cannot be
              completed later. Are you sure you want to finish?
            </p>
          ) : (
            <p>Are you sure you want to finish the interview?</p>
          )}
          <p className="muted small">
            Answers you have already submitted are saved either way.
          </p>
          <div className="button-row">
            <button className="secondary" onClick={() => setConfirmFinish(false)}>
              Cancel
            </button>
            <button className="danger" onClick={doFinish}>Finish test</button>
          </div>
        </div>
      )}
    </main>
  );

  /*
   * NORMAL: a single column and nothing else on screen.
   *
   * The camera element still has to exist - the detectors read frames from it -
   * so it is present but visually hidden rather than removed. Removing it would
   * stop proctoring, which is exactly what must not happen in normal mode.
   */
  if (!demo) {
    return (
      <div className="exam-layout exam-layout-plain">
        <video ref={videoRef} className="preview preview-hidden" muted playsInline />
        {main}
      </div>
    );
  }

  /* DEMO: the same interview, with the monitor and overlay beside it. */
  return (
    <div className="exam-layout">
      <aside className="exam-side">
        <div className="demo-banner">DEMO MODE &mdash; proctoring monitoring visible</div>

        <div className="preview-wrap">
          <video ref={videoRef} className="preview" muted playsInline />
          <DetectionOverlay videoRef={videoRef} status={status} />
        </div>

        <DemoMonitor
          status={status}
          cameraActive={cameraActive}
          micGranted={Boolean(session.micGranted)}
          listening={listening}
          isFullscreen={isFullscreen}
          durationMinutes={session.durationMinutes}
          remainingSeconds={remaining}
          answeredCount={answeredCount}
          totalQuestions={session.questionCount}
        />
      </aside>
      {main}
    </div>
  );
}
