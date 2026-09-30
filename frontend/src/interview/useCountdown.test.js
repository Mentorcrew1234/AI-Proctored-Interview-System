import { describe, it, expect } from 'vitest';
import { formatClock, urgencyOf } from './useCountdown.js';

/**
 * The countdown's display logic.
 *
 * The countdown itself is seeded and resynced from the server, so whether a
 * refresh resumes correctly is decided server-side and is tested there
 * (InterviewDurationTest). What is left here is formatting and the urgency
 * thresholds - the parts that only exist in the browser.
 */
describe('formatClock', () => {
  it('formats mm:ss with padding', () => {
    expect(formatClock(0)).toBe('00:00');
    expect(formatClock(9)).toBe('00:09');
    expect(formatClock(65)).toBe('01:05');
    expect(formatClock(1799)).toBe('29:59');
  });

  it('grows to h:mm:ss past an hour', () => {
    expect(formatClock(3600)).toBe('1:00:00');
    expect(formatClock(3661)).toBe('1:01:01');
  });

  /** Never shows a negative time, however late a response arrives. */
  it('floors at zero rather than showing negative time', () => {
    expect(formatClock(-30)).toBe('00:00');
  });

  /** Before the first sync there is no figure to show, and none is invented. */
  it('shows a placeholder when the remaining time is not known yet', () => {
    expect(formatClock(null)).toBe('--:--');
    expect(formatClock(undefined)).toBe('--:--');
  });
});

describe('urgencyOf', () => {
  it('is normal with plenty of time left', () => {
    expect(urgencyOf(1800)).toBe('normal');
    expect(urgencyOf(301)).toBe('normal');
  });

  it('escalates at five minutes and again at one', () => {
    expect(urgencyOf(300)).toBe('low');
    expect(urgencyOf(61)).toBe('low');
    expect(urgencyOf(60)).toBe('critical');
    expect(urgencyOf(0)).toBe('critical');
  });

  it('stays normal when the remaining time is unknown', () => {
    expect(urgencyOf(null)).toBe('normal');
  });
});
