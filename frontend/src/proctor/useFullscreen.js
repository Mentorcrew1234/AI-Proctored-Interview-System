import { useCallback, useEffect, useState } from 'react';

/**
 * Fullscreen state, for the interface only.
 *
 * <b>This does not record anything.</b> `FULLSCREEN_EXIT` is recorded by
 * `attachBrowserEvents`, which feeds the event engine exactly as before and is
 * untouched by this hook. Both listen to the same browser event for different
 * reasons: that one produces the proctoring record, this one decides whether to
 * show the candidate a prompt. Keeping them apart means a change to the on-screen
 * message can never alter what is recorded.
 *
 * `fullscreenEnabled` is reported separately from `isFullscreen` because they
 * fail differently: a candidate who pressed Escape can be asked to go back, but
 * a browser or embedding policy that forbids fullscreen entirely will refuse
 * every retry, and pretending otherwise would trap them on a screen with a
 * button that cannot work.
 */
export function useFullscreen() {
  const [isFullscreen, setIsFullscreen] = useState(
    () => typeof document !== 'undefined' && Boolean(document.fullscreenElement),
  );

  // False when the browser or an embedding policy forbids fullscreen outright,
  // which no amount of retrying will change.
  const supported =
    typeof document !== 'undefined' &&
    Boolean(document.documentElement?.requestFullscreen) &&
    document.fullscreenEnabled !== false;

  useEffect(() => {
    const sync = () => setIsFullscreen(Boolean(document.fullscreenElement));
    document.addEventListener('fullscreenchange', sync);
    sync();
    return () => document.removeEventListener('fullscreenchange', sync);
  }, []);

  /**
   * Asks for fullscreen. Must be called from a real user gesture - browsers
   * refuse otherwise, and that restriction is deliberately not worked around.
   *
   * @returns {Promise<boolean>} whether the browser actually granted it
   */
  const enter = useCallback(async () => {
    if (!supported) return false;
    try {
      await document.documentElement.requestFullscreen();
      // Read the real state back rather than assuming the call succeeded.
      const now = Boolean(document.fullscreenElement);
      setIsFullscreen(now);
      return now;
    } catch {
      setIsFullscreen(Boolean(document.fullscreenElement));
      return false;
    }
  }, [supported]);

  /** Used on the way out, so the candidate is not left stuck in fullscreen. */
  const exit = useCallback(async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
    } catch {
      /* already out, or refused - nothing useful to do */
    }
  }, []);

  return { isFullscreen, supported, enter, exit };
}
