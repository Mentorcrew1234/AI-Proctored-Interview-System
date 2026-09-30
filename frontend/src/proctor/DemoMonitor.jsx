/**
 * The DEMO monitoring panel.
 *
 * Every value here is read straight from the running pipeline - the same
 * numbers the event engine is deciding on, at the same moment. Nothing is
 * simulated, smoothed or invented, and the panel deliberately shows raw states
 * rather than a summary, because the point of a demonstration is to watch the
 * detector change its mind in real time.
 *
 * What is NOT shown, because the prototype does not compute it: attention,
 * emotion, gaze, eye or lip tracking, liveness, or any accuracy figure. Those
 * models do not exist here, so no panel of ours will claim them.
 *
 * This component is never rendered in NORMAL mode - the exam screen does not
 * import it into the tree at all, so there is nothing to un-hide with devtools.
 */

const EVENT_LABELS = {
  NO_FACE: 'No face',
  MULTIPLE_FACES: 'Multiple faces',
  MULTIPLE_PERSONS: 'Multiple persons',
  PHONE_DETECTED: 'Phone detected',
  HEAD_TURN: 'Head turned',
  TAB_SWITCH: 'Tab switch',
  WINDOW_BLUR: 'Window blur',
  FULLSCREEN_EXIT: 'Fullscreen exit',
};

function Dot({ on, warn }) {
  const state = warn ? 'warn' : on ? 'ok' : 'bad';
  return <span className={`dot ${state === 'ok' ? 'ok' : state === 'warn' ? 'wait' : 'bad'}`} />;
}

/** mm:ss from a seconds count, or a placeholder before the first sync. */
function clock(totalSeconds) {
  if (typeof totalSeconds !== 'number') return '--:--';
  const safe = Math.max(0, totalSeconds);
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(Math.floor(safe / 60))}:${pad(safe % 60)}`;
}

function formatMinutes(minutes) {
  if (typeof minutes !== 'number') return '-';
  return `${minutes} min`;
}

/** Derived, not separately tracked: allocated minus what is left. */
function elapsedText(durationMinutes, remainingSeconds) {
  if (typeof durationMinutes !== 'number' || typeof remainingSeconds !== 'number') return '--:--';
  return clock(Math.max(0, durationMinutes * 60 - remainingSeconds));
}

function Row({ label, value, on, warn }) {
  return (
    <div className="monitor-row">
      <span className="monitor-label">
        {on !== undefined && <Dot on={on} warn={warn} />}
        {label}
      </span>
      <strong className="monitor-value">{value}</strong>
    </div>
  );
}

export default function DemoMonitor({
  status,
  cameraActive,
  micGranted,
  listening,
  isFullscreen,
  durationMinutes,
  remainingSeconds,
  answeredCount,
  totalQuestions,
}) {
  const totalEvents = Object.values(status.eventCounts ?? {}).reduce((a, b) => a + b, 0);
  const active = new Set(status.activeTypes ?? []);

  const detectionState = status.error
    ? 'Failed'
    : status.modelsReady
      ? 'Monitoring'
      : 'Loading models';

  return (
    <section className="demo-monitor" aria-label="Proctoring monitor (demonstration)">
      <header className="demo-monitor-head">
        <span className="demo-badge">DEMO</span>
        <h3>Proctoring monitor</h3>
      </header>

      <p className="muted small demo-monitor-note">
        Live output of the detection pipeline. Shown for demonstration only &mdash; a real
        candidate never sees this panel.
      </p>

      {/*
        Timing, for the demonstration only. The candidate's own screen shows
        just the remaining time; this adds what it was measured against, so an
        audience can see the countdown is driven by a configured duration and a
        real start time rather than a number picked at page load.
      */}
      <h4>Timing</h4>
      <Row label="Allocated" value={formatMinutes(durationMinutes)} />
      <Row label="Elapsed" value={elapsedText(durationMinutes, remainingSeconds)} />
      <Row label="Remaining" value={clock(remainingSeconds)} />
      <Row label="Answered" value={`${answeredCount ?? 0} / ${totalQuestions ?? 0}`} />

      <h4>Devices</h4>
      <Row label="Camera" on={cameraActive} value={cameraActive ? 'Active' : 'Inactive'} />
      {/*
        Honest about the microphone: the audio track is stopped before the
        interview begins so Chrome's speech recognition can own the microphone.
        "Granted" is what the device check recorded; "Listening" is the
        recogniser actually capturing right now.
      */}
      <Row
        label="Microphone"
        on={micGranted}
        warn={micGranted && !listening}
        value={!micGranted ? 'Not granted' : listening ? 'Listening' : 'Idle (granted)'}
      />
      <Row
        label="Fullscreen"
        on={isFullscreen}
        value={isFullscreen ? 'Active' : 'Exited'}
      />

      <h4>Detection</h4>
      <Row
        label="Face"
        on={status.faceCount > 0}
        warn={status.faceCount > 1}
        value={status.faceCount > 0 ? `Detected (${status.faceCount})` : 'Not detected'}
      />
      <Row
        label="Persons"
        on={status.personCount === 1}
        warn={status.personCount > 1}
        value={status.personCount}
      />
      <Row
        label="Objects"
        on={!status.phoneVisible}
        warn={status.phoneVisible}
        value={
          status.phoneVisible
            ? `Phone${status.phoneScore != null ? ` (${Math.round(status.phoneScore * 100)}%)` : ''}`
            : 'None'
        }
      />
      <Row label="Head" value={status.headDirection ?? '-'} />
      <Row label="Detection status" value={detectionState} />

      {status.error && <div className="alert error">{status.error}</div>}
      {status.loadingMessage && <div className="alert info">{status.loadingMessage}</div>}

      <h4>Conditions being held</h4>
      {/*
        A rule can be "open" - the condition is currently true and the engine is
        timing it - without having become an event yet. Watching that transition
        is the most useful thing in a demonstration, so it is shown separately
        from the recorded totals.
      */}
      <div className="chips">
        {active.size === 0 && <span className="chip">None</span>}
        {[...active].map((type) => (
          <span key={type} className="chip chip-active">
            {EVENT_LABELS[type] ?? type}
          </span>
        ))}
      </div>

      <h4>Events recorded</h4>
      <Row label="Total" value={totalEvents} />
      {Object.entries(status.eventCounts ?? {}).map(([type, count]) => (
        <Row key={type} label={EVENT_LABELS[type] ?? type} value={count} />
      ))}
      {status.pendingUploads > 0 && (
        <Row label="Awaiting upload" value={status.pendingUploads} />
      )}

      <p className="muted small">
        Observations, not conclusions. These are recorded identically in normal mode;
        the only difference is that this panel is not drawn.
      </p>
    </section>
  );
}
