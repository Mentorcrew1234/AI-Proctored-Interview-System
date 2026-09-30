import { useCallback, useEffect, useRef, useState } from 'react';
import { useSpeechRecognition } from './useSpeechRecognition.js';
import { useSpeechSynthesis } from './useSpeechSynthesis.js';

/**
 * One question, the candidate's answer, and submission.
 *
 * The transcript is shown and stays editable on purpose. Speech recognition
 * makes mistakes, and the candidate should be able to see and correct what will
 * actually be graded rather than being marked on a mis-transcription. Typing is
 * always allowed, so a browser without speech support is inconvenient but never
 * blocking.
 *
 * Control state is derived from one `phase` value rather than several
 * independent booleans. The previous version left a `submitting` flag set after
 * a successful submit - nothing reset it when the next question arrived, so
 * every control stayed disabled until the page was reloaded.
 */
const PHASE = {
  READY: 'READY',
  SUBMITTING: 'SUBMITTING',
  FAILED: 'FAILED',
};

export default function QuestionPanel({
  question, index, total, onSubmitted, onFinish, onListeningChange,
}) {
  const speech = useSpeechRecognition();
  const reader = useSpeechSynthesis();
  const [text, setText] = useState('');
  const [phase, setPhase] = useState(PHASE.READY);
  const [error, setError] = useState(null);
  const startedAt = useRef(Date.now());
  // Belt and braces against a double-click landing before React re-renders.
  const inFlight = useRef(false);

  // Reset for a new question. `phase` is included deliberately: forgetting it
  // here is exactly what left the controls disabled after a successful submit.
  useEffect(() => {
    setText('');
    setError(null);
    setPhase(PHASE.READY);
    inFlight.current = false;
    startedAt.current = Date.now();
    speech.reset();
    speech.stop();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [question.id]);

  /*
   * Read each question aloud as it appears.
   *
   * Keyed on `question.id` alone, so an ordinary re-render - a keystroke in the
   * answer box, an interim transcript arriving - does not restart the audio.
   * Only a genuinely different question does.
   *
   * There is deliberately no "already spoken" flag. `speak` cancels whatever is
   * playing before it starts, which makes overlap impossible on its own, and a
   * flag would break under StrictMode: React mounts, cleans up and mounts again,
   * and the second run would skip after the first had already been cancelled.
   * See useSpeechSynthesis.
   */
  useEffect(() => {
    reader.speak(question.text);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [question.id]);

  // Moving on, or leaving, must not leave a voice reading to nobody.
  useEffect(() => () => reader.stop(), [reader.stop]);

  /**
   * Starts the microphone, silencing the reader first.
   *
   * Without this the speakers are still reading the question while the
   * microphone opens, and the recogniser transcribes the question into the
   * candidate's own answer.
   */
  const startAnswering = useCallback(() => {
    reader.stop();
    speech.start();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reader.stop, speech.start]);

  // Fold recognised speech into the editable box.
  useEffect(() => {
    if (speech.finalText) setText(speech.finalText);
  }, [speech.finalText]);

  /*
   * Reports whether the microphone is actually capturing right now.
   *
   * Only the DEMO monitor uses this, and it exists because "microphone active"
   * would otherwise be a guess: the audio track from the device check is
   * stopped on purpose, so the microphone is live only while the recogniser is
   * listening. Reporting the real state beats displaying a plausible one.
   */
  useEffect(() => {
    onListeningChange?.(speech.listening);
  }, [speech.listening, onListeningChange]);

  // Leaving this question must not leave the indicator stuck on.
  useEffect(() => () => onListeningChange?.(false), [onListeningChange]);

  async function submit() {
    if (inFlight.current) return;

    const answer = text.trim();
    if (!answer) {
      setError('Please answer before continuing, either by speaking or typing.');
      return;
    }

    inFlight.current = true;
    setPhase(PHASE.SUBMITTING);
    setError(null);
    speech.stop();
    reader.stop();

    try {
      const durationSeconds = Math.round((Date.now() - startedAt.current) / 1000);
      await onSubmitted(question.id, answer, durationSeconds);
      // On success the parent advances to the next question, which remounts
      // this panel's state through the effect above. Nothing to reset here.
    } catch (e) {
      // Never leave the UI stuck: go to a state the candidate can act on.
      setError(e.message || 'Could not submit your answer.');
      setPhase(PHASE.FAILED);
      inFlight.current = false;
    }
  }

  const busy = phase === PHASE.SUBMITTING;
  const words = text.trim() ? text.trim().split(/\s+/).length : 0;
  const isLast = index + 1 === total;

  return (
    <div className="card">
      <p className="muted small">
        Question {index + 1} of {total} &middot; {question.difficulty}
      </p>
      {/* The question stays on screen as text regardless of whether it can be
          read aloud - the audio is an addition, never a replacement. */}
      <h1 className="question-text">{question.text}</h1>

      {reader.supported && (
        <div className="button-row" style={{ marginTop: 0, marginBottom: 12 }}>
          {/* Named "Repeat question" and "Stop reading" rather than anything
              with "speaking" in it: on this same panel "Start speaking" and
              "Stop speaking" already control the MICROPHONE, and reusing the
              word for the speakers would invert its meaning mid-screen. */}
          <button type="button" className="secondary" onClick={() => reader.speak(question.text)}>
            {reader.speaking ? 'Repeat question' : 'Read question aloud'}
          </button>
          {reader.speaking && (
            <button type="button" className="secondary" onClick={reader.stop}>
              Stop reading
            </button>
          )}
        </div>
      )}

      {/* Stated, not hidden: a candidate who hears nothing should know why. */}
      {!reader.supported && (
        <div className="alert info">
          This browser cannot read the question aloud. The question is shown above
          and the interview is unaffected.
        </div>
      )}
      {reader.error && <div className="alert info">{reader.error}</div>}

      {error && (
        <div className="alert error">
          {error}
          {phase === PHASE.FAILED && (
            <>
              {' '}
              <button type="button" className="secondary" onClick={submit}
                      style={{ marginTop: 8 }}>
                Try again
              </button>
            </>
          )}
        </div>
      )}

      {/* Speech problems are shown but never block: typing still works. */}
      {speech.error && <div className="alert info">{speech.error}</div>}

      {!speech.supported && (
        <div className="alert info">
          Voice input is not supported in this browser. Please use Google Chrome or
          Microsoft Edge, or type your answer below.
        </div>
      )}
      {speech.supported && !speech.secureContext && (
        <div className="alert info">
          Voice input needs a secure connection (https or localhost). Please type your
          answer below.
        </div>
      )}

      <label htmlFor="answer">Your answer</label>
      <textarea
        id="answer"
        rows={8}
        value={text + (speech.interimText ? ` ${speech.interimText}` : '')}
        onChange={(e) => {
          setText(e.target.value);
          speech.setFinalText(e.target.value);
        }}
        placeholder={
          speech.supported
            ? 'Press "Start speaking" and answer out loud, or type here.'
            : 'Type your answer here.'
        }
        disabled={busy}
      />
      <p className="muted small">
        {words} word{words === 1 ? '' : 's'}
        {speech.starting && ' · starting microphone...'}
        {speech.listening && ' · listening...'}
        {' · you can edit this text before submitting'}
      </p>

      <div className="button-row">
        {speech.supported && speech.secureContext && !speech.listening && (
          <button type="button" onClick={startAnswering} disabled={busy || speech.starting}>
            {speech.starting ? 'Starting...' : 'Start speaking'}
          </button>
        )}
        {speech.listening && (
          <button type="button" className="secondary" onClick={speech.stop}>
            Stop speaking
          </button>
        )}

        <button type="button" onClick={submit} disabled={busy}>
          {busy ? 'Submitting answer...' : isLast ? 'Submit final answer' : 'Submit and continue'}
        </button>

        {/* Always available: a candidate may need to stop at any point. */}
        <button type="button" className="danger" onClick={onFinish} disabled={busy}>
          Finish test
        </button>
      </div>

      <p className="muted small">
        Once submitted you cannot return to this question.
      </p>
    </div>
  );
}
