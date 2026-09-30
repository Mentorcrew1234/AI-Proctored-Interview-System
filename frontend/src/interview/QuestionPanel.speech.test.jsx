// @vitest-environment jsdom
import { StrictMode } from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent, act } from '@testing-library/react';
import QuestionPanel from './QuestionPanel.jsx';

/**
 * Reading the question aloud.
 *
 * `window.speechSynthesis` does not exist in jsdom, so it is installed here as a
 * recording double. That is the right level for these tests: what matters is
 * *when* the panel asks the browser to speak and to stop, not what the browser's
 * audio pipeline then does with it.
 *
 * Speech recognition is mocked out entirely. It is the other Web Speech API and
 * has its own behaviour; mixing the two here would make a failure ambiguous.
 */

vi.mock('./useSpeechRecognition.js', () => ({
  useSpeechRecognition: () => ({
    supported: true,
    secureContext: true,
    listening: false,
    starting: false,
    finalText: '',
    interimText: '',
    setFinalText: vi.fn(),
    error: null,
    start: recognitionStart,
    stop: vi.fn(),
    reset: vi.fn(),
  }),
}));

const recognitionStart = vi.fn();

/** Records every call, and lets a test end an utterance on demand. */
function installSpeechSynthesis() {
  const spoken = [];
  const cancel = vi.fn();
  const utterances = [];

  class FakeUtterance {
    constructor(text) {
      this.text = text;
      utterances.push(this);
    }
  }

  window.SpeechSynthesisUtterance = FakeUtterance;
  window.speechSynthesis = {
    speak: vi.fn((utterance) => spoken.push(utterance.text)),
    cancel,
  };

  return {
    spoken,
    cancel,
    utterances,
    get speakCalls() {
      return window.speechSynthesis.speak.mock.calls.length;
    },
    /** Fires the browser's end event for the utterance in flight. */
    endCurrent() {
      utterances.at(-1)?.onend?.();
    },
    errorCurrent(error) {
      utterances.at(-1)?.onerror?.({ error });
    },
  };
}

const QUESTION_ONE = { id: 1, text: 'Describe a race condition you have debugged.', difficulty: 'MEDIUM' };
const QUESTION_TWO = { id: 2, text: 'How would you index this query?', difficulty: 'HARD' };

function renderPanel(question = QUESTION_ONE, extra = {}) {
  return render(
    <QuestionPanel
      question={question}
      index={0}
      total={2}
      onSubmitted={vi.fn()}
      onFinish={vi.fn()}
      {...extra}
    />,
  );
}

describe('QuestionPanel — reading the question aloud', () => {
  let tts;

  beforeEach(() => {
    recognitionStart.mockClear();
    tts = installSpeechSynthesis();
  });

  afterEach(() => {
    cleanup();
    delete window.speechSynthesis;
    delete window.SpeechSynthesisUtterance;
  });

  // ---- 1. speaks automatically -------------------------------------------

  it('speaks the question as soon as it is displayed', () => {
    renderPanel();

    expect(tts.spoken).toEqual([QUESTION_ONE.text]);
  });

  it('speaks the question text, not the difficulty or the position', () => {
    renderPanel();

    expect(tts.spoken[0]).toBe('Describe a race condition you have debugged.');
    expect(tts.spoken[0]).not.toContain('MEDIUM');
    expect(tts.spoken[0]).not.toContain('Question 1');
  });

  // ---- 2. repeat -----------------------------------------------------------

  it('speaks again when the candidate asks to repeat', () => {
    renderPanel();
    expect(tts.speakCalls).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: /read question aloud|repeat question/i }));

    expect(tts.speakCalls).toBe(2);
    expect(tts.spoken).toEqual([QUESTION_ONE.text, QUESTION_ONE.text]);
  });

  it('cancels before repeating, so a repeat never layers over itself', () => {
    renderPanel();
    tts.cancel.mockClear();

    fireEvent.click(screen.getByRole('button', { name: /read question aloud|repeat question/i }));

    expect(tts.cancel).toHaveBeenCalled();
  });

  // ---- 3. stop -------------------------------------------------------------

  it('offers a stop control only while speaking, and it cancels', () => {
    renderPanel();

    // The panel is speaking, so the stop control is offered.
    const stop = screen.getByRole('button', { name: /stop reading/i });
    tts.cancel.mockClear();

    fireEvent.click(stop);

    expect(tts.cancel).toHaveBeenCalled();
    // Once stopped it is no longer offered.
    expect(screen.queryByRole('button', { name: /stop reading/i })).toBeNull();
  });

  it('stops offering the stop control once the browser reports the end', () => {
    renderPanel();
    expect(screen.getByRole('button', { name: /stop reading/i })).toBeTruthy();

    // The utterance finishes on its own - no click, the browser just ends it.
    act(() => tts.endCurrent());

    expect(screen.queryByRole('button', { name: /stop reading/i })).toBeNull();
  });

  it('treats our own cancellation as normal rather than as a fault', () => {
    // cancel() makes the browser fire onerror with "interrupted". Reporting
    // that would put an error on screen every time a question changed.
    renderPanel();

    act(() => tts.errorCurrent('interrupted'));

    expect(screen.queryByText(/could not be read aloud/i)).toBeNull();
  });

  it('reports a genuine synthesis failure', () => {
    renderPanel();

    act(() => tts.errorCurrent('synthesis-failed'));

    expect(screen.getByText(/could not be read aloud/i)).toBeTruthy();
  });

  // ---- 4. moving to the next question -------------------------------------

  it('cancels the previous question and speaks the new one', () => {
    const { rerender } = renderPanel();
    expect(tts.spoken).toEqual([QUESTION_ONE.text]);
    tts.cancel.mockClear();

    rerender(
      <QuestionPanel
        question={QUESTION_TWO}
        index={1}
        total={2}
        onSubmitted={vi.fn()}
        onFinish={vi.fn()}
      />,
    );

    expect(tts.cancel).toHaveBeenCalled();
    expect(tts.spoken).toEqual([QUESTION_ONE.text, QUESTION_TWO.text]);
  });

  // ---- 5. no duplicate speech from re-renders -----------------------------

  it('does not re-speak when the component re-renders with the same question', () => {
    const { rerender } = renderPanel();
    expect(tts.speakCalls).toBe(1);

    // Three re-renders with the same question - a keystroke, an interim
    // transcript, a parent state change. None is a new question.
    for (let i = 0; i < 3; i++) {
      rerender(
        <QuestionPanel
          question={QUESTION_ONE}
          index={0}
          total={2}
          onSubmitted={vi.fn()}
          onFinish={vi.fn()}
        />,
      );
    }

    expect(tts.speakCalls).toBe(1);
  });

  it('never overlaps two utterances, even under StrictMode double-mounting', () => {
    // The application really does run in StrictMode (src/main.jsx), so in
    // development React mounts, cleans up and mounts again. The guarantee that
    // matters is not "speak was called once" - it is that the browser is told
    // to cancel before every speak, so only one utterance is ever audible.
    render(
      <StrictMode>
        <QuestionPanel
          question={QUESTION_ONE}
          index={0}
          total={2}
          onSubmitted={vi.fn()}
          onFinish={vi.fn()}
        />
      </StrictMode>,
    );

    const speaks = window.speechSynthesis.speak.mock.invocationCallOrder;
    const cancels = window.speechSynthesis.cancel.mock.invocationCallOrder;

    // Every speak is preceded by a cancel, so nothing can layer.
    speaks.forEach((speakAt) => {
      expect(cancels.some((cancelAt) => cancelAt < speakAt)).toBe(true);
    });
    // And it is the same question throughout - never two different ones at once.
    expect(new Set(tts.spoken)).toEqual(new Set([QUESTION_ONE.text]));
  });

  it('does not re-speak when the candidate types an answer', () => {
    renderPanel();
    expect(tts.speakCalls).toBe(1);

    fireEvent.change(screen.getByLabelText(/your answer/i), {
      target: { value: 'A deadlock between two locks taken in different orders.' },
    });

    expect(tts.speakCalls).toBe(1);
  });

  // ---- the microphone must not hear the question --------------------------

  it('stops reading before the microphone opens', () => {
    // Otherwise the recogniser transcribes the question into the answer.
    renderPanel();
    tts.cancel.mockClear();

    fireEvent.click(screen.getByRole('button', { name: /start speaking/i }));

    expect(tts.cancel).toHaveBeenCalled();
    expect(recognitionStart).toHaveBeenCalled();
  });

  // ---- unsupported browsers ------------------------------------------------

  describe('when the browser cannot speak', () => {
    beforeEach(() => {
      delete window.speechSynthesis;
      delete window.SpeechSynthesisUtterance;
      cleanup();
    });

    it('still shows the question and says why there is no audio', () => {
      renderPanel();

      expect(screen.getByText(QUESTION_ONE.text)).toBeTruthy();
      expect(screen.getByText(/cannot read the question aloud/i)).toBeTruthy();
    });

    it('offers no playback controls it cannot honour', () => {
      renderPanel();

      expect(screen.queryByRole('button', { name: /read question aloud/i })).toBeNull();
      expect(screen.queryByRole('button', { name: /stop reading/i })).toBeNull();
    });

    it('leaves answering entirely unaffected', () => {
      renderPanel();

      fireEvent.change(screen.getByLabelText(/your answer/i), {
        target: { value: 'Typed instead.' },
      });

      expect(screen.getByRole('button', { name: /submit/i })).toBeTruthy();
      expect(screen.getByLabelText(/your answer/i).value).toContain('Typed instead.');
    });
  });
});
