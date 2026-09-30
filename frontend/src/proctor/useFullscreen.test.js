// @vitest-environment jsdom
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, act, cleanup } from '@testing-library/react';
import { useFullscreen } from './useFullscreen.js';

/**
 * Fullscreen state for the interface only.
 *
 * The distinction this hook exists to make - and the one most worth pinning -
 * is between "not in fullscreen" and "fullscreen is not available". A candidate
 * who pressed Escape can be asked to go back; one whose browser forbids
 * fullscreen outright would be trapped on a screen with a button that can never
 * work, which docs/system/quality/LIMITATIONS.md says explicitly must not happen.
 *
 * It records nothing. `FULLSCREEN_EXIT` comes from `attachBrowserEvents`, and
 * keeping the two apart is what stops a change to the on-screen message from
 * altering the proctoring record.
 */
describe('useFullscreen', () => {
  const setFullscreenElement = (element) => {
    Object.defineProperty(document, 'fullscreenElement', {
      configurable: true,
      get: () => element,
    });
  };

  const setFullscreenEnabled = (enabled) => {
    Object.defineProperty(document, 'fullscreenEnabled', {
      configurable: true,
      get: () => enabled,
    });
  };

  beforeEach(() => {
    setFullscreenElement(null);
    setFullscreenEnabled(true);
    document.documentElement.requestFullscreen = vi.fn().mockResolvedValue(undefined);
    document.exitFullscreen = vi.fn().mockResolvedValue(undefined);
  });

  afterEach(() => {
    cleanup();
    delete document.documentElement.requestFullscreen;
    delete document.exitFullscreen;
  });

  // ---- reading the state --------------------------------------------------

  it('starts out reflecting whether the document is already fullscreen', () => {
    setFullscreenElement(document.documentElement);

    const { result } = renderHook(() => useFullscreen());

    expect(result.current.isFullscreen).toBe(true);
  });

  it('follows the browser when fullscreen is left', () => {
    setFullscreenElement(document.documentElement);
    const { result } = renderHook(() => useFullscreen());

    act(() => {
      setFullscreenElement(null);
      document.dispatchEvent(new Event('fullscreenchange'));
    });

    expect(result.current.isFullscreen).toBe(false);
  });

  it('follows the browser when fullscreen is re-entered', () => {
    const { result } = renderHook(() => useFullscreen());
    expect(result.current.isFullscreen).toBe(false);

    act(() => {
      setFullscreenElement(document.documentElement);
      document.dispatchEvent(new Event('fullscreenchange'));
    });

    expect(result.current.isFullscreen).toBe(true);
  });

  // ---- "not now" versus "never" -------------------------------------------

  it('reports fullscreen as supported when the browser offers it', () => {
    const { result } = renderHook(() => useFullscreen());

    expect(result.current.supported).toBe(true);
  });

  it('reports it unsupported when the API is missing', () => {
    delete document.documentElement.requestFullscreen;

    const { result } = renderHook(() => useFullscreen());

    expect(result.current.supported).toBe(false);
  });

  it('reports it unsupported when a policy forbids it', () => {
    // An embedded frame, or an enterprise policy. Retrying can never succeed,
    // so the candidate must be told rather than offered a dead button.
    setFullscreenEnabled(false);

    const { result } = renderHook(() => useFullscreen());

    expect(result.current.supported).toBe(false);
  });

  it('does not even ask the browser when unsupported', async () => {
    setFullscreenEnabled(false);
    const { result } = renderHook(() => useFullscreen());

    let granted;
    await act(async () => {
      granted = await result.current.enter();
    });

    expect(granted).toBe(false);
    expect(document.documentElement.requestFullscreen).not.toHaveBeenCalled();
  });

  // ---- entering -----------------------------------------------------------

  it('reports success only after reading the real state back', async () => {
    const { result } = renderHook(() => useFullscreen());
    document.documentElement.requestFullscreen = vi.fn().mockImplementation(async () => {
      setFullscreenElement(document.documentElement);
    });

    let granted;
    await act(async () => {
      granted = await result.current.enter();
    });

    expect(granted).toBe(true);
    expect(result.current.isFullscreen).toBe(true);
  });

  // The promise resolving is not the same as fullscreen having happened, and
  // claiming to be in fullscreen when the page is not would be exactly the kind
  // of overstatement this system avoids elsewhere.
  it('does not claim success when the call resolves but nothing happened', async () => {
    const { result } = renderHook(() => useFullscreen());
    document.documentElement.requestFullscreen = vi.fn().mockResolvedValue(undefined);

    let granted;
    await act(async () => {
      granted = await result.current.enter();
    });

    expect(granted).toBe(false);
    expect(result.current.isFullscreen).toBe(false);
  });

  it('reports failure rather than throwing when the browser refuses', async () => {
    // Browsers grant fullscreen only from a user gesture.
    document.documentElement.requestFullscreen = vi.fn()
      .mockRejectedValue(new Error('Permissions check failed'));
    const { result } = renderHook(() => useFullscreen());

    let granted;
    await act(async () => {
      granted = await result.current.enter();
    });

    expect(granted).toBe(false);
    expect(result.current.isFullscreen).toBe(false);
  });

  // ---- leaving ------------------------------------------------------------

  it('exits when the document is in fullscreen', async () => {
    setFullscreenElement(document.documentElement);
    const { result } = renderHook(() => useFullscreen());

    await act(async () => {
      await result.current.exit();
    });

    expect(document.exitFullscreen).toHaveBeenCalled();
  });

  it('does nothing when already out', async () => {
    const { result } = renderHook(() => useFullscreen());

    await act(async () => {
      await result.current.exit();
    });

    expect(document.exitFullscreen).not.toHaveBeenCalled();
  });

  it('swallows a refused exit rather than failing the way out', async () => {
    // Called as the interview closes; a throw here would break finishing.
    setFullscreenElement(document.documentElement);
    document.exitFullscreen = vi.fn().mockRejectedValue(new Error('not allowed'));
    const { result } = renderHook(() => useFullscreen());

    await expect(act(async () => {
      await result.current.exit();
    })).resolves.not.toThrow();
  });

  // ---- cleanup ------------------------------------------------------------

  it('stops listening once unmounted', () => {
    const remove = vi.spyOn(document, 'removeEventListener');
    const { unmount } = renderHook(() => useFullscreen());

    unmount();

    expect(remove).toHaveBeenCalledWith('fullscreenchange', expect.any(Function));
    remove.mockRestore();
  });
});
