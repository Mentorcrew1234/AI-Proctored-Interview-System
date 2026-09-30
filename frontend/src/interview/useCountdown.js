import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * The interview countdown.
 *
 * **Seeded from the server, never from the browser clock.** The server sends
 * how many seconds are left; this ticks that value down locally between
 * requests and resyncs to a fresh server figure whenever one arrives (on load,
 * and after every submitted answer).
 *
 * That is what makes a refresh behave correctly: reloading fetches the session
 * again and gets the real remaining time, because the server computes it from
 * the session's own `startedAt`. A timer written as "30 minutes from page load"
 * would restart on every refresh and hand out unlimited time.
 *
 * Local ticking is a display convenience only. It is measured with elapsed
 * wall-clock deltas rather than by assuming the interval fired exactly on time,
 * so a throttled background tab does not drift far - and even if it did, the
 * server rejects work past the deadline regardless of what this shows.
 *
 * @param initialSeconds seconds remaining, from the server
 * @param onExpire       called once, when the countdown first reaches zero
 */
export function useCountdown(initialSeconds, onExpire) {
  const [remaining, setRemaining] = useState(
    typeof initialSeconds === 'number' ? Math.max(0, initialSeconds) : null,
  );

  // The wall-clock instant the current value was true at, so ticking measures
  // real elapsed time instead of counting interval fires.
  const syncedAt = useRef(Date.now());
  const expiredRef = useRef(false);
  const onExpireRef = useRef(onExpire);
  onExpireRef.current = onExpire;

  /** Accepts a fresh figure from the server and restarts local ticking from it. */
  const sync = useCallback((seconds) => {
    if (typeof seconds !== 'number') return;
    syncedAt.current = Date.now();
    setRemaining(Math.max(0, seconds));
    if (seconds > 0) {
      // A later sync can only re-arm expiry, never un-expire a finished
      // interview - that decision belongs to the server.
      expiredRef.current = false;
    }
  }, []);

  useEffect(() => {
    sync(initialSeconds);
    // Only when a genuinely new starting value arrives.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialSeconds]);

  useEffect(() => {
    if (remaining === null) return undefined;

    const base = remaining;
    const from = syncedAt.current;

    const id = setInterval(() => {
      const elapsed = Math.floor((Date.now() - from) / 1000);
      const left = Math.max(0, base - elapsed);
      setRemaining(left);

      if (left === 0 && !expiredRef.current) {
        expiredRef.current = true;
        onExpireRef.current?.();
      }
    }, 1000);

    return () => clearInterval(id);
    // Re-armed on each sync; `remaining` changing every second is fine because
    // the interval is cheap and the base is captured from the last sync.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [syncedAt.current, remaining === null]);

  return { remaining, sync };
}

/** mm:ss, or h:mm:ss once over an hour. */
export function formatClock(totalSeconds) {
  if (typeof totalSeconds !== 'number') return '--:--';
  const safe = Math.max(0, totalSeconds);
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  const seconds = safe % 60;
  const pad = (n) => String(n).padStart(2, '0');
  return hours > 0
    ? `${hours}:${pad(minutes)}:${pad(seconds)}`
    : `${pad(minutes)}:${pad(seconds)}`;
}

/**
 * How urgent the remaining time is, for styling only.
 *
 * Thresholds are generous rather than dramatic: the point is to be noticed in
 * peripheral vision while someone is thinking, not to alarm them.
 */
export function urgencyOf(remaining) {
  if (typeof remaining !== 'number') return 'normal';
  if (remaining <= 60) return 'critical';
  if (remaining <= 300) return 'low';
  return 'normal';
}
