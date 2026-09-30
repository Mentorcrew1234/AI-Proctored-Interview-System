import { useEffect, useRef, useState } from 'react';
import { useFullscreen } from '../proctor/useFullscreen.js';

/**
 * Camera and microphone check, run before the session exists.
 *
 * The point is to surface problems here rather than mid-interview: a denied
 * permission, a dark room that would produce false NO_FACE events, or a browser
 * with no speech recognition. Fullscreen joins that list: it was requested on
 * the previous screen, and a permission prompt can drop it, so it is checked
 * here and re-asserted from the Start click - which is a user gesture, the only
 * thing a browser will accept.
 */
export default function DeviceCheckScreen({ onReady, onStreamReady }) {
  const { isFullscreen, supported: fullscreenSupported, enter: enterFullscreen } = useFullscreen();
  const videoRef = useRef(null);
  const streamRef = useRef(null);
  const [camera, setCamera] = useState('pending');
  const [mic, setMic] = useState('pending');
  const [micLevel, setMicLevel] = useState(0);
  const [brightness, setBrightness] = useState(null);
  const [error, setError] = useState(null);

  /**
   * Stops the microphone track and hands the remaining video-only stream to the
   * interview. Called when the candidate starts, so the recogniser has sole use
   * of the microphone.
   */
  function releaseAudio() {
    const stream = streamRef.current;
    if (stream) {
      stream.getAudioTracks().forEach((track) => track.stop());
      onStreamReady?.(stream);
    }
  }

  const speechSupported =
    typeof window !== 'undefined' &&
    ('SpeechRecognition' in window || 'webkitSpeechRecognition' in window);

  useEffect(() => {
    let stream = null;
    let audioContext = null;
    let rafId = null;
    let brightnessTimer = null;
    let cancelled = false;

    async function start() {
      // Camera and microphone are requested separately so a refused microphone
      // does not also cost us the camera.
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { width: { ideal: 640 }, height: { ideal: 480 } },
          audio: true,
        });
      } catch {
        try {
          stream = await navigator.mediaDevices.getUserMedia({ video: true });
        } catch (e) {
          if (!cancelled) {
            setCamera('denied');
            setMic('denied');
            setError(
              'Camera access was refused. The interview cannot start without a camera. ' +
                'Allow camera access in your browser and reload this page.',
            );
          }
          return;
        }
      }
      if (cancelled) return;

      streamRef.current = stream;
      const hasVideo = stream.getVideoTracks().length > 0;
      const hasAudio = stream.getAudioTracks().length > 0;
      setCamera(hasVideo ? 'ok' : 'denied');
      setMic(hasAudio ? 'ok' : 'denied');

      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        await videoRef.current.play().catch(() => {});
      }

      if (hasAudio) {
        audioContext = new (window.AudioContext || window.webkitAudioContext)();
        const source = audioContext.createMediaStreamSource(stream);
        const analyser = audioContext.createAnalyser();
        analyser.fftSize = 512;
        source.connect(analyser);
        const data = new Uint8Array(analyser.frequencyBinCount);

        const tick = () => {
          analyser.getByteTimeDomainData(data);
          // Peak deviation from silence, as a rough 0-1 level.
          let peak = 0;
          for (const v of data) peak = Math.max(peak, Math.abs(v - 128));
          setMicLevel(Math.min(1, peak / 64));
          rafId = requestAnimationFrame(tick);
        };
        tick();
      }

      // Sample average brightness: a very dark room is the most common cause
      // of spurious "no face" events.
      brightnessTimer = setInterval(() => {
        const video = videoRef.current;
        if (!video || video.readyState < 2) return;
        const canvas = document.createElement('canvas');
        canvas.width = 64;
        canvas.height = 48;
        const ctx = canvas.getContext('2d');
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        const { data } = ctx.getImageData(0, 0, canvas.width, canvas.height);
        let sum = 0;
        for (let i = 0; i < data.length; i += 4) {
          sum += (data[i] + data[i + 1] + data[i + 2]) / 3;
        }
        setBrightness(Math.round(sum / (data.length / 4)));
      }, 1000);
    }

    start();

    return () => {
      cancelled = true;
      if (rafId) cancelAnimationFrame(rafId);
      if (brightnessTimer) clearInterval(brightnessTimer);
      audioContext?.close?.();
      // The stream is handed to the exam screen, so it is deliberately not
      // stopped here.
    };
  }, [onStreamReady]);

  const canStart = camera === 'ok';
  const dark = brightness != null && brightness < 60;

  return (
    <div className="centre-wrap">
      <div className="card">
        <h1>Device check</h1>
        <p className="muted">Make sure everything works before the interview starts.</p>

        {error && <div className="alert error">{error}</div>}

        <video ref={videoRef} className="preview" muted playsInline />

        <div className="check-row">
          <span className={`dot ${camera === 'ok' ? 'ok' : camera === 'pending' ? 'wait' : 'bad'}`} />
          <span>Camera</span>
          <span className="muted">
            {camera === 'ok' ? 'Working' : camera === 'pending' ? 'Requesting access...' : 'Not available'}
          </span>
        </div>

        <div className="check-row">
          <span className={`dot ${mic === 'ok' ? 'ok' : mic === 'pending' ? 'wait' : 'bad'}`} />
          <span>Microphone</span>
          <span className="muted">
            {mic === 'ok' ? 'Working - say something to test' : mic === 'pending' ? 'Requesting access...' : 'Not available'}
          </span>
        </div>
        {mic === 'ok' && (
          <div className="meter">
            <div className="meter-fill" style={{ width: `${Math.round(micLevel * 100)}%` }} />
          </div>
        )}

        <div className="check-row">
          <span className={`dot ${speechSupported ? 'ok' : 'bad'}`} />
          <span>Speech recognition</span>
          <span className="muted">
            {speechSupported ? 'Supported by this browser' : 'Not supported - use Chrome or Edge'}
          </span>
        </div>

        <div className="check-row">
          <span className={`dot ${!fullscreenSupported ? 'bad' : isFullscreen ? 'ok' : 'wait'}`} />
          <span>Fullscreen</span>
          <span className="muted">
            {!fullscreenSupported
              ? 'Not available in this browser - the interview will run in a window'
              : isFullscreen
                ? 'Active'
                : 'Will be re-entered when you start'}
          </span>
        </div>

        <div className="check-row">
          <span className={`dot ${brightness == null ? 'wait' : dark ? 'bad' : 'ok'}`} />
          <span>Lighting</span>
          <span className="muted">
            {brightness == null
              ? 'Measuring...'
              : dark
                ? 'Too dark - this may cause false "no face" observations'
                : 'Good'}
          </span>
        </div>

        {mic !== 'ok' && camera === 'ok' && (
          <div className="alert info">
            The microphone is unavailable, so you will type your answers instead of
            speaking them. The interview can still go ahead.
          </div>
        )}

        <button
          disabled={!canStart}
          onClick={async () => {
            // Re-assert fullscreen from this click. The camera permission prompt
            // drops fullscreen in some browsers, and this is the last real user
            // gesture before the interview begins. A refusal is not fatal here -
            // the interview proceeds and the exit is recorded, exactly as it
            // would be if the candidate left fullscreen a minute later.
            if (fullscreenSupported && !isFullscreen) {
              await enterFullscreen();
            }
            // Release the microphone before the interview starts. It was only
            // needed for the level meter, and Chrome's speech recognition opens
            // its own capture - a second live audio track competes with it and
            // commonly yields "audio-capture" or silent no-results.
            // Proctoring needs video only.
            releaseAudio();
            onReady({
              cameraGranted: camera === 'ok',
              micGranted: mic === 'ok',
              browserInfo: navigator.userAgent,
            });
          }}
        >
          {canStart ? 'Start interview' : 'Camera required to continue'}
        </button>
      </div>
    </div>
  );
}
