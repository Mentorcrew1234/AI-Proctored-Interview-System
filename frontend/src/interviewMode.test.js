import { describe, expect, it } from 'vitest';
import { MODE, isDemo, resolveMode } from './interviewMode.js';

/**
 * The client half of the same rule the server enforces: only an explicit,
 * valid DEMO turns the monitoring panel on. Everything else is NORMAL.
 *
 * Worth testing on both sides because they fail differently. The server
 * protects against a mistyped deployment variable; this protects against a
 * response that is older, truncated or simply missing the field - none of which
 * should ever put live model output in front of a real candidate.
 */
describe('resolveMode', () => {
  it('defaults to NORMAL when the field is missing', () => {
    expect(resolveMode({})).toBe(MODE.NORMAL);
    expect(resolveMode({ interviewMode: undefined })).toBe(MODE.NORMAL);
  });

  it('defaults to NORMAL for a null or undefined response', () => {
    expect(resolveMode(null)).toBe(MODE.NORMAL);
    expect(resolveMode(undefined)).toBe(MODE.NORMAL);
  });

  it('defaults to NORMAL for anything unrecognised', () => {
    expect(resolveMode({ interviewMode: 'INVALID' })).toBe(MODE.NORMAL);
    expect(resolveMode({ interviewMode: '' })).toBe(MODE.NORMAL);
    expect(resolveMode({ interviewMode: 'demonstration' })).toBe(MODE.NORMAL);
  });

  it('does not treat a non-string as a mode', () => {
    // A truthy value must not read as "demo on".
    expect(resolveMode({ interviewMode: true })).toBe(MODE.NORMAL);
    expect(resolveMode({ interviewMode: 1 })).toBe(MODE.NORMAL);
    expect(resolveMode({ interviewMode: {} })).toBe(MODE.NORMAL);
  });

  it('honours DEMO, forgiving case and surrounding space', () => {
    expect(resolveMode({ interviewMode: 'DEMO' })).toBe(MODE.DEMO);
    expect(resolveMode({ interviewMode: 'demo' })).toBe(MODE.DEMO);
    expect(resolveMode({ interviewMode: '  Demo  ' })).toBe(MODE.DEMO);
  });

  it('isDemo is true only for the resolved DEMO mode', () => {
    expect(isDemo(resolveMode({ interviewMode: 'DEMO' }))).toBe(true);
    expect(isDemo(resolveMode({ interviewMode: 'NORMAL' }))).toBe(false);
    expect(isDemo(resolveMode({ interviewMode: 'INVALID' }))).toBe(false);
    expect(isDemo(undefined)).toBe(false);
  });
});
