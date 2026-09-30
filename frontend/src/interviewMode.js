/**
 * Interview execution mode, resolved once and passed down.
 *
 * The mode comes from the server (`app.interview.mode` -> `ExamInfo.interviewMode`)
 * and decides one thing only: how much of the proctoring machinery the screen
 * shows. It never changes the interview, the questions, the detection pipeline
 * or what is recorded.
 *
 * This module exists so there is exactly one definition of "are we
 * demonstrating?". Components take a `mode` prop and ask `isDemo(mode)`, rather
 * than each reaching for configuration and drifting apart.
 *
 * Anything unrecognised - including a missing field from an older server -
 * resolves to NORMAL. Showing a candidate less than intended is a cosmetic
 * problem; showing them the monitoring panel because a value failed to parse is
 * the failure worth preventing. The server applies the same rule, so both ends
 * fail the same way.
 */

export const MODE = Object.freeze({
  NORMAL: 'NORMAL',
  DEMO: 'DEMO',
});

/**
 * @param {{interviewMode?: string} | null | undefined} examInfo
 * @returns {'NORMAL' | 'DEMO'}
 */
export function resolveMode(examInfo) {
  const raw = examInfo?.interviewMode;
  if (typeof raw !== 'string') return MODE.NORMAL;
  const value = raw.trim().toUpperCase();
  return value === MODE.DEMO ? MODE.DEMO : MODE.NORMAL;
}

/** @param {string} mode */
export function isDemo(mode) {
  return mode === MODE.DEMO;
}
