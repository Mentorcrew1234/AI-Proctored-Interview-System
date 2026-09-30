/**
 * Bridges browser-level signals into the event engine.
 *
 * Documented limitations - these are advisory signals, not security controls:
 *
 * - `visibilitychange` fires when the tab is hidden, which includes switching
 *   tabs, minimising, and on some systems locking the screen. It cannot tell
 *   those apart, and it does not fire when the candidate simply moves another
 *   window on top of this one.
 * - `blur` catches losing focus (including alt-tab to another application) but
 *   also fires for a devtools window or an OS dialog.
 * - Fullscreen can be exited with Escape, which the page cannot prevent. Nor
 *   can the page detect a second monitor, a phone beside the keyboard, or a
 *   person off-camera.
 *
 * A determined candidate can defeat all of this. It is included because it
 * documents obvious environment changes, not because it is airtight.
 */
export function attachBrowserEvents(engine, { onChange } = {}) {
  const notify = () => onChange?.();

  const onVisibilityChange = () => {
    const now = Date.now();
    if (document.hidden) {
      engine.browserEventStart('TAB_SWITCH', now, { reason: 'visibilitychange' });
    } else {
      engine.browserEventEnd('TAB_SWITCH', now);
    }
    notify();
  };

  const onBlur = () => {
    // A tab switch already covers the hidden case; recording both would
    // double-count one action.
    if (document.hidden) return;
    engine.browserEventStart('WINDOW_BLUR', Date.now(), { reason: 'blur' });
    notify();
  };

  const onFocus = () => {
    engine.browserEventEnd('WINDOW_BLUR', Date.now());
    notify();
  };

  const onFullscreenChange = () => {
    const now = Date.now();
    if (document.fullscreenElement) {
      engine.browserEventEnd('FULLSCREEN_EXIT', now);
    } else {
      engine.browserEventStart('FULLSCREEN_EXIT', now, { reason: 'fullscreenchange' });
    }
    notify();
  };

  document.addEventListener('visibilitychange', onVisibilityChange);
  window.addEventListener('blur', onBlur);
  window.addEventListener('focus', onFocus);
  document.addEventListener('fullscreenchange', onFullscreenChange);

  return function detach() {
    document.removeEventListener('visibilitychange', onVisibilityChange);
    window.removeEventListener('blur', onBlur);
    window.removeEventListener('focus', onFocus);
    document.removeEventListener('fullscreenchange', onFullscreenChange);
  };
}

export async function requestFullscreen(element) {
  try {
    await (element ?? document.documentElement).requestFullscreen();
    return true;
  } catch {
    return false;
  }
}
