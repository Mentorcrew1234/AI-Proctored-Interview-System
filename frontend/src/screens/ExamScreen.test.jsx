// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup, act, fireEvent } from '@testing-library/react';
import ExamScreen from './ExamScreen.jsx';

/**
 * The interview's two ways to end, and the guarantee they share.
 *
 * The countdown and the Finish button are separate triggers that deliberately
 * run the *same* completion path, so an interview that ran out of time and one
 * the candidate closed themselves produce a report the same way. What must
 * never happen either way is completing twice: the second call would be a
 * duplicate POST against a session the server has already closed.
 *
 * These are the only tests that render the exam screen, so they mock the two
 * things that would otherwise reach outside the browser - the proctoring
 * pipeline (which loads MediaPipe and COCO-SSD) and the API. Everything else,
 * including the countdown, the phase machine and the confirmation panel, is the
 * real implementation.
 *
 * Whether the countdown is *correct* is decided by the server and tested there
 * (InterviewDurationTest); what is tested here is what the browser does when it
 * reaches zero.
 */

const finalise = vi.fn();

vi.mock('../proctor/useProctoring.js', () => ({
  useProctoring: () => ({
    status: {
      modelsReady: true, loadingMessage: null, error: null,
      faceCount: 1, headDirection: 'CENTER', personCount: 1,
      phoneVisible: false, phoneScore: null, activeTypes: [], eventCounts: {},
      pendingUploads: 0, faceBoxes: [], personBoxes: [], phoneBox: null,
      frame: { width: 640, height: 480 },
    },
    finalise,
  }),
}));

vi.mock('../api.js', () => ({
  api: {
    questions: vi.fn(),
    submitAnswer: vi.fn(),
    completeSession: vi.fn(),
  },
}));

const { api } = await import('../api.js');

/** Two questions, both already answered unless told otherwise. */
function questionSet({ answered }) {
  return [
    { id: 1, text: 'Describe a race condition you have debugged.', difficulty: 'MEDIUM', answered },
    { id: 2, text: 'How would you index this query?', difficulty: 'MEDIUM', answered },
  ];
}

/** A camera stream that can report whether it was actually released. */
function fakeStream() {
  const track = { readyState: 'live', stop: vi.fn() };
  return { getTracks: () => [track], getVideoTracks: () => [track], track };
}

function renderExam({ remainingSeconds, answered = false, stream = fakeStream() }) {
  api.questions.mockResolvedValue({
    questions: questionSet({ answered }),
    aiGenerated: true,
    mockMode: false,
    // The server's figure wins over the seeded one - this is the refresh path.
    remainingSeconds,
  });
  api.completeSession.mockResolvedValue({ sessionId: 42, recommendation: 'FURTHER_REVIEW' });

  const onFinish = vi.fn();
  const session = {
    sessionId: 42, remainingSeconds, durationMinutes: 30, micGranted: true, questionCount: 2,
  };
  const result = render(
    <ExamScreen session={session} stream={stream} onFinish={onFinish} mode="NORMAL" />,
  );
  return { ...result, onFinish, stream };
}

/** Lets the mocked API promises settle while fake timers are installed. */
async function settle() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}

/**
 * Answers the currently displayed question through the real QuestionPanel UI
 * - typing and clicking, not calling a callback directly - so these tests
 * exercise exactly what a candidate does.
 */
async function answerCurrentQuestion(text) {
  const textarea = screen.getByLabelText('Your answer');
  fireEvent.change(textarea, { target: { value: text } });
  const submit = screen.getByRole('button', { name: /^Submit (and continue|final answer)$/ });
  await act(async () => {
    submit.click();
    await vi.advanceTimersByTimeAsync(0);
  });
}

/** The "Question X of Y" text, split across elements by the <strong> tags. */
function progressText(container) {
  return container.querySelector('.exam-progress-meta span')?.textContent ?? null;
}

beforeEach(() => {
  vi.clearAllMocks();
  finalise.mockResolvedValue(undefined);
  // jsdom has no media stack; the screen only ever calls play() to show a preview.
  HTMLMediaElement.prototype.play = vi.fn().mockResolvedValue(undefined);
  vi.useFakeTimers({ shouldAdvanceTime: false });
});

afterEach(() => {
  vi.useRealTimers();
  cleanup();
});

describe('running out of time', () => {
  it('finishes the interview through the normal completion path when the clock reaches zero', async () => {
    const { onFinish } = renderExam({ remainingSeconds: 5 });
    await settle();

    // An interview genuinely in progress: a question is on screen and nothing
    // has been completed yet.
    expect(screen.getByText('Describe a race condition you have debugged.')).toBeTruthy();
    expect(api.completeSession).not.toHaveBeenCalled();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });

    // Open observations are flushed before the report is built, then the same
    // completion endpoint the Finish button uses is called.
    expect(finalise).toHaveBeenCalledTimes(1);
    expect(api.completeSession).toHaveBeenCalledTimes(1);
    expect(api.completeSession).toHaveBeenCalledWith(42);
    expect(onFinish).toHaveBeenCalledTimes(1);
  });

  it('tells the candidate the time ran out rather than that they finished', async () => {
    // Completion is held open so the screen stays on its finishing state.
    let release;
    api.completeSession.mockReturnValue(new Promise((resolve) => { release = resolve; }));

    renderExam({ remainingSeconds: 3 });
    await settle();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000);
    });

    expect(screen.getByText('Time is up')).toBeTruthy();
    expect(screen.queryByText('Finishing your interview')).toBeNull();

    await act(async () => { release({ sessionId: 42 }); });
  });

  it('completes once even though the countdown keeps ticking past zero', async () => {
    const { onFinish } = renderExam({ remainingSeconds: 2 });
    await settle();

    // Well past the deadline: the interval fires many more times.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(30000);
    });

    expect(api.completeSession).toHaveBeenCalledTimes(1);
    expect(onFinish).toHaveBeenCalledTimes(1);
  });

  it('releases the camera when time runs out', async () => {
    const { stream } = renderExam({ remainingSeconds: 2 });
    await settle();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });

    expect(stream.track.stop).toHaveBeenCalled();
  });
});

describe('finishing manually', () => {
  /** Opens the confirmation panel and returns its confirm button. */
  async function openConfirm() {
    const trigger = screen.getAllByRole('button', { name: 'Finish test' })[0];
    await act(async () => { trigger.click(); });
    expect(screen.getByText('Finish interview?')).toBeTruthy();
    // The confirmation panel's own button is the last one on screen.
    const buttons = screen.getAllByRole('button', { name: 'Finish test' });
    return buttons[buttons.length - 1];
  }

  it('completes the interview when the candidate confirms', async () => {
    const { onFinish } = renderExam({ remainingSeconds: 600, answered: true });
    await settle();

    // Every question answered, so the screen offers the finishing card.
    expect(screen.getByText('All questions answered')).toBeTruthy();

    const confirm = await openConfirm();
    await act(async () => { confirm.click(); });

    expect(finalise).toHaveBeenCalledTimes(1);
    expect(api.completeSession).toHaveBeenCalledTimes(1);
    expect(api.completeSession).toHaveBeenCalledWith(42);
    expect(onFinish).toHaveBeenCalledTimes(1);
  });

  it('does not complete twice when the confirm button is clicked repeatedly', async () => {
    let release;
    api.completeSession.mockReturnValue(new Promise((resolve) => { release = resolve; }));

    const { onFinish } = renderExam({ remainingSeconds: 600, answered: true });
    await settle();

    const confirm = await openConfirm();
    // Three clicks landing before the first completion resolves.
    await act(async () => {
      confirm.click();
      confirm.click();
      confirm.click();
    });

    expect(api.completeSession).toHaveBeenCalledTimes(1);

    await act(async () => { release({ sessionId: 42 }); });
    expect(onFinish).toHaveBeenCalledTimes(1);
  });

  it('does not complete again if the clock runs out while finishing', async () => {
    let release;
    api.completeSession.mockReturnValue(new Promise((resolve) => { release = resolve; }));

    const { onFinish } = renderExam({ remainingSeconds: 3, answered: true });
    await settle();

    const confirm = await openConfirm();
    await act(async () => { confirm.click(); });
    expect(api.completeSession).toHaveBeenCalledTimes(1);

    // The deadline passes while the completion request is still in flight.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10000);
    });

    expect(api.completeSession).toHaveBeenCalledTimes(1);

    await act(async () => { release({ sessionId: 42 }); });
    expect(onFinish).toHaveBeenCalledTimes(1);
  });

  it('lets the candidate cancel without completing anything', async () => {
    const { onFinish } = renderExam({ remainingSeconds: 600, answered: true });
    await settle();

    const trigger = screen.getAllByRole('button', { name: 'Finish test' })[0];
    await act(async () => { trigger.click(); });

    const cancel = screen.getByRole('button', { name: 'Cancel' });
    await act(async () => { cancel.click(); });

    expect(screen.queryByText('Finish interview?')).toBeNull();
    expect(api.completeSession).not.toHaveBeenCalled();
    expect(onFinish).not.toHaveBeenCalled();
  });
});

/**
 * Phase 6.4: the same screen working correctly against ADAPTIVE mode's
 * backend contract, where `GET /questions` only ever returns what has been
 * generated so far, and `POST /answers` generates the next one server-side
 * without returning its text. Nothing here is mode-aware code in the
 * component - `submitAnswer` decides whether to fetch based only on whether
 * the next question is already loaded locally, which is what makes FIXED
 * mode's existing behaviour (asserted throughout this file already) keep
 * working unchanged.
 */
describe('fixed mode regression under the same submitAnswer logic', () => {
  it('advances through locally-loaded questions without fetching again', async () => {
    renderExam({ remainingSeconds: 1800 });
    await settle();

    expect(screen.getByText('Describe a race condition you have debugged.')).toBeTruthy();
    expect(api.questions).toHaveBeenCalledTimes(1);

    api.submitAnswer.mockResolvedValueOnce({
      answerId: 1, sequenceNo: 1, cleanTranscript: 'x', fillerCount: 0, wordCount: 5,
      answeredCount: 1, totalQuestions: 2, complete: false, remainingSeconds: 1750,
    });
    await answerCurrentQuestion('A real transaction-based answer.');

    expect(screen.getByText('How would you index this query?')).toBeTruthy();
    // Both questions were already loaded from the first GET - no extra fetch.
    expect(api.questions).toHaveBeenCalledTimes(1);
  });
});

/**
 * Phase 8: the server can end up with an answer recorded whose response never
 * reached the browser - a dropped connection, or (in ADAPTIVE mode) a failure
 * later in the same request after the answer had already been saved. The
 * server correctly refuses a second save of the same question with a 409, but
 * before this fix the question panel's own retry just resubmitted the same
 * answer and hit the same 409 forever, with no way to reach the next
 * question short of a full page reload.
 */
describe('recovering when the server already has the answer', () => {
  function conflict(message) {
    return Object.assign(new Error(message), { status: 409 });
  }

  it('resyncs from the server instead of looping on a 409, without showing an error', async () => {
    renderExam({ remainingSeconds: 1800 });
    await settle();

    expect(screen.getByText('Describe a race condition you have debugged.')).toBeTruthy();

    api.submitAnswer.mockRejectedValueOnce(conflict('This question has already been answered'));
    // What loadQuestions finds when it resyncs: the server already has the
    // first answer recorded and is waiting on the second question.
    api.questions.mockResolvedValueOnce({
      questions: questionSet({ answered: false }).map((q, i) => (i === 0 ? { ...q, answered: true } : q)),
      aiGenerated: true, mockMode: false, remainingSeconds: 1750,
    });

    await answerCurrentQuestion('A real transaction-based answer.');
    await settle();

    // Recovered to the true next question - no dead-end error, no stuck retry.
    expect(screen.queryByText('Something went wrong')).toBeNull();
    expect(screen.queryByText('This question has already been answered')).toBeNull();
    expect(screen.getByText('How would you index this query?')).toBeTruthy();
    expect(api.questions).toHaveBeenCalledTimes(2);
  });

  it('still shows a real retry for an ordinary submission failure', async () => {
    renderExam({ remainingSeconds: 1800 });
    await settle();

    api.submitAnswer.mockRejectedValueOnce(new Error('Network error'));
    await answerCurrentQuestion('A real transaction-based answer.');

    // Not a 409, so this is the panel's own retry (resubmitting is correct
    // here - the server never saved anything), not a resync.
    expect(screen.getByText('Network error')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
    expect(api.questions).toHaveBeenCalledTimes(1);
  });
});

describe('adaptive question flow', () => {
  function adaptiveSession(overrides = {}) {
    return {
      sessionId: 42, remainingSeconds: 1800, durationMinutes: 30, micGranted: true,
      questionCount: 3, ...overrides,
    };
  }

  function renderAdaptive({ firstResponse, session = adaptiveSession(), stream = fakeStream() }) {
    api.questions.mockResolvedValueOnce(firstResponse);
    api.completeSession.mockResolvedValue({ sessionId: 42, recommendation: 'FURTHER_REVIEW' });
    const onFinish = vi.fn();
    const result = render(
      <ExamScreen session={session} stream={stream} onFinish={onFinish} mode="NORMAL" />,
    );
    return { ...result, onFinish, stream, session };
  }

  const q1 = { id: 101, text: 'Describe a time you tracked down a memory leak.',
    difficulty: 'MEDIUM', answered: false };
  const q2 = { id: 102, text: 'How would you design a rate limiter?',
    difficulty: 'HARD', answered: false };
  const q3 = { id: 103, text: 'Explain how you would review a risky pull request.',
    difficulty: 'HARD', answered: false };

  it('shows the single initial question, not "1 of 1"', async () => {
    const { container } = renderAdaptive({
      firstResponse: { questions: [q1], aiGenerated: true, mockMode: false, remainingSeconds: 1800 },
    });
    await settle();

    expect(screen.getByText(q1.text)).toBeTruthy();
    // The interview's real total (3), not how many exist so far (1) - fixing
    // what would otherwise mislabel every adaptive question as the final one.
    expect(progressText(container)).toBe('Question 1 of 3');
    expect(screen.getByRole('button', { name: 'Submit and continue' })).toBeTruthy();
  });

  it('fetches and displays the next question after answering, without a page reload', async () => {
    const { container } = renderAdaptive({
      firstResponse: { questions: [q1], aiGenerated: true, mockMode: false, remainingSeconds: 1800 },
    });
    await settle();

    api.submitAnswer.mockResolvedValueOnce({
      answerId: 1, sequenceNo: 1, cleanTranscript: 'x', fillerCount: 0, wordCount: 6,
      answeredCount: 1, totalQuestions: 2, complete: false, remainingSeconds: 1750,
    });
    api.questions.mockResolvedValueOnce({
      questions: [{ ...q1, answered: true }, q2],
      aiGenerated: true, mockMode: false, remainingSeconds: 1750,
    });

    await answerCurrentQuestion('I used a heap dump and found a growing cache.');
    await settle();

    expect(screen.getByText(q2.text)).toBeTruthy();
    expect(screen.queryByText(q1.text)).toBeNull();
    // Exactly one refetch happened - the initial GET, plus the one triggered
    // by this answer. "Without a page reload" means no re-mount, so no third
    // call from loadQuestions firing again.
    expect(api.questions).toHaveBeenCalledTimes(2);
    // Progress reflects the real total throughout, and advances correctly.
    expect(progressText(container)).toBe('Question 2 of 3');
  });

  it('completes on the final adaptive question without an extra fetch', async () => {
    renderAdaptive({
      firstResponse: {
        questions: [{ ...q1, answered: true }, q2],
        aiGenerated: true, mockMode: false, remainingSeconds: 1750,
      },
    });
    await settle();

    expect(screen.getByText(q2.text)).toBeTruthy();

    api.submitAnswer.mockResolvedValueOnce({
      answerId: 2, sequenceNo: 2, cleanTranscript: 'x', fillerCount: 0, wordCount: 6,
      answeredCount: 2, totalQuestions: 2, complete: true, remainingSeconds: 1700,
    });
    await answerCurrentQuestion('A token bucket with a background refill.');

    expect(screen.getByText('All questions answered')).toBeTruthy();
    // The interview is over - nothing left to fetch.
    expect(api.questions).toHaveBeenCalledTimes(1);
  });

  it('recovers the current backend question on a refresh mid-interview', async () => {
    // A reload: the browser calls GET fresh, exactly as the initial mount
    // does. The backend already knows q1 is answered and q2 is not.
    const { container } = renderAdaptive({
      firstResponse: {
        questions: [{ ...q1, answered: true }, q2],
        aiGenerated: true, mockMode: false, remainingSeconds: 1700,
      },
    });
    await settle();

    expect(screen.getByText(q2.text)).toBeTruthy();
    expect(screen.queryByText(q1.text)).toBeNull();
    expect(progressText(container)).toBe('Question 2 of 3');
  });

  it('does not duplicate a question in client state after fetching the next one', async () => {
    renderAdaptive({
      firstResponse: { questions: [q1], aiGenerated: true, mockMode: false, remainingSeconds: 1800 },
    });
    await settle();

    api.submitAnswer.mockResolvedValueOnce({
      answerId: 1, sequenceNo: 1, cleanTranscript: 'x', fillerCount: 0, wordCount: 6,
      answeredCount: 1, totalQuestions: 2, complete: false, remainingSeconds: 1750,
    });
    // The fetch REPLACES local state with the server's own list; a naive
    // append bug would instead end up with q1 appearing twice.
    api.questions.mockResolvedValueOnce({
      questions: [{ ...q1, answered: true }, q2],
      aiGenerated: true, mockMode: false, remainingSeconds: 1750,
    });
    await answerCurrentQuestion('I used a heap dump and found a growing cache.');
    await settle();

    expect(screen.queryAllByText(q1.text)).toHaveLength(0);
    expect(screen.getAllByText(q2.text)).toHaveLength(1);
  });

  it('shows a loading state while fetching the next question', async () => {
    renderAdaptive({
      firstResponse: { questions: [q1], aiGenerated: true, mockMode: false, remainingSeconds: 1800 },
    });
    await settle();

    api.submitAnswer.mockResolvedValueOnce({
      answerId: 1, sequenceNo: 1, cleanTranscript: 'x', fillerCount: 0, wordCount: 6,
      answeredCount: 1, totalQuestions: 2, complete: false, remainingSeconds: 1750,
    });
    let releaseNextQuestion;
    api.questions.mockReturnValueOnce(new Promise((resolve) => { releaseNextQuestion = resolve; }));

    await answerCurrentQuestion('I used a heap dump and found a growing cache.');

    expect(screen.getByText('Preparing your next question')).toBeTruthy();
    expect(screen.queryByText(q1.text)).toBeNull();

    await act(async () => {
      releaseNextQuestion({
        questions: [{ ...q1, answered: true }, q2],
        aiGenerated: true, mockMode: false, remainingSeconds: 1750,
      });
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(screen.getByText(q2.text)).toBeTruthy();
    expect(screen.queryByText('Preparing your next question')).toBeNull();
  });

  it('shows an error and offers a retry if fetching the next question fails', async () => {
    renderAdaptive({
      firstResponse: { questions: [q1], aiGenerated: true, mockMode: false, remainingSeconds: 1800 },
    });
    await settle();

    api.submitAnswer.mockResolvedValueOnce({
      answerId: 1, sequenceNo: 1, cleanTranscript: 'x', fillerCount: 0, wordCount: 6,
      answeredCount: 1, totalQuestions: 2, complete: false, remainingSeconds: 1750,
    });
    api.questions.mockRejectedValueOnce(new Error('Network error'));

    await answerCurrentQuestion('I used a heap dump and found a growing cache.');

    expect(screen.getByText('Something went wrong')).toBeTruthy();
    expect(screen.getByText('Network error')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();

    // The answer itself was not lost - only the fetch of the next question
    // failed, and the candidate is offered a real retry.
    api.questions.mockResolvedValueOnce({
      questions: [{ ...q1, answered: true }, q2],
      aiGenerated: true, mockMode: false, remainingSeconds: 1750,
    });
    await act(async () => {
      screen.getByRole('button', { name: 'Try again' }).click();
      await vi.advanceTimersByTimeAsync(0);
    });

    expect(screen.getByText(q2.text)).toBeTruthy();
  });

  it('correctly handles a three-question adaptive interview end to end', async () => {
    const { container } = renderAdaptive({
      firstResponse: { questions: [q1], aiGenerated: true, mockMode: false, remainingSeconds: 1800 },
    });
    await settle();
    expect(progressText(container)).toBe('Question 1 of 3');

    api.submitAnswer.mockResolvedValueOnce({
      answerId: 1, sequenceNo: 1, cleanTranscript: 'x', fillerCount: 0, wordCount: 6,
      answeredCount: 1, totalQuestions: 2, complete: false, remainingSeconds: 1700,
    });
    api.questions.mockResolvedValueOnce({
      questions: [{ ...q1, answered: true }, q2],
      aiGenerated: true, mockMode: false, remainingSeconds: 1700,
    });
    await answerCurrentQuestion('First answer.');
    await settle();
    expect(screen.getByText(q2.text)).toBeTruthy();
    expect(progressText(container)).toBe('Question 2 of 3');

    api.submitAnswer.mockResolvedValueOnce({
      answerId: 2, sequenceNo: 2, cleanTranscript: 'x', fillerCount: 0, wordCount: 6,
      answeredCount: 2, totalQuestions: 3, complete: false, remainingSeconds: 1600,
    });
    api.questions.mockResolvedValueOnce({
      questions: [{ ...q1, answered: true }, { ...q2, answered: true }, q3],
      aiGenerated: true, mockMode: false, remainingSeconds: 1600,
    });
    // The button label reflects the true total too, not just "1 loaded".
    expect(screen.getByRole('button', { name: 'Submit and continue' })).toBeTruthy();
    await answerCurrentQuestion('Second answer.');
    await settle();
    expect(screen.getByText(q3.text)).toBeTruthy();
    expect(progressText(container)).toBe('Question 3 of 3');
    expect(screen.getByRole('button', { name: 'Submit final answer' })).toBeTruthy();

    api.submitAnswer.mockResolvedValueOnce({
      answerId: 3, sequenceNo: 3, cleanTranscript: 'x', fillerCount: 0, wordCount: 6,
      answeredCount: 3, totalQuestions: 3, complete: true, remainingSeconds: 1500,
    });
    await answerCurrentQuestion('Third answer.');

    expect(screen.getByText('All questions answered')).toBeTruthy();
    expect(api.questions).toHaveBeenCalledTimes(3);
  });
});
