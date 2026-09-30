import { useState } from 'react';
import { useFullscreen } from '../proctor/useFullscreen.js';
import { isDemo } from '../interviewMode.js';

/**
 * Shown before anything is recorded. Being explicit about what is captured is
 * the point of this screen: the candidate should know monitoring is happening
 * and what it does and does not collect before they agree to start.
 *
 * This is also where the interview enters fullscreen. It has to happen here
 * because browsers only grant fullscreen from a real user gesture - a click -
 * and this screen has the click that means "I am ready to begin". That
 * restriction is respected rather than worked around; there is no way to force
 * fullscreen without the candidate choosing it, and pretending otherwise would
 * just produce a screen that lies about its own state.
 */
export default function InstructionsScreen({ info, onContinue, mode }) {
  const { isFullscreen, supported, enter } = useFullscreen();
  const [refused, setRefused] = useState(false);
  const [working, setWorking] = useState(false);

  async function enterInterview() {
    setWorking(true);
    setRefused(false);

    // If the browser cannot do fullscreen at all, retrying will never help, so
    // the candidate is let through with the limitation stated rather than
    // trapped behind a button that cannot work. Exiting fullscreen is recorded
    // throughout the interview either way, which is the honest record of it.
    if (!supported) {
      setWorking(false);
      onContinue();
      return;
    }

    const granted = await enter();
    setWorking(false);
    if (granted) {
      onContinue();
    } else {
      // Never claim fullscreen we did not get.
      setRefused(true);
    }
  }

  return (
    <div className="centre-wrap">
      <div className="card">
        {isDemo(mode) && (
          <div className="demo-banner">DEMO MODE &mdash; proctoring monitoring visible</div>
        )}

        <h1>Before you begin</h1>
        <p className="muted">
          {info.domain} &middot;{' '}
          {info.interviewType === 'TECHNICAL' ? 'Technical' : 'HR / General'} &middot;{' '}
          {info.questionCount} questions &middot; {info.language === 'ENGLISH' ? 'English' : info.language}
        </p>

        <h3>How it works</h3>
        <ul>
          <li>You will be asked {info.questionCount} questions, one at a time.</li>
          <li>Answer out loud. Your speech is converted to text in your browser.</li>
          <li>You can read your transcript before submitting each answer.</li>
          <li>Once you submit an answer you cannot go back to it.</li>
          <li>The interview runs in fullscreen. Leaving fullscreen is recorded.</li>
        </ul>

        <h3>What is monitored</h3>
        <ul>
          <li>Whether a face is visible, and whether more than one person is in frame.</li>
          <li>Whether a mobile phone is visible.</li>
          <li>Roughly which way your head is turned.</li>
          <li>Switching tabs, leaving the window, or exiting fullscreen.</li>
        </ul>

        <div className="alert info">
          <strong>Your video is never uploaded.</strong> All detection runs inside your
          browser. Only short summaries - for example &ldquo;a phone was visible for
          5 seconds&rdquo; - are sent to the server. No video, images, or audio recordings
          leave your computer.
        </div>

        <div className="alert info">
          These observations are shared with your recruiter as <em>observations</em>,
          not as accusations. A detection does not by itself mean anything is wrong -
          people look away, and detectors make mistakes.
        </div>

        <h3>To avoid false observations</h3>
        <ul>
          <li>Sit in a well-lit room, facing the camera.</li>
          <li>Ask others not to walk behind you.</li>
          <li>Keep your phone out of view of the camera.</li>
          <li>Use Google Chrome or Microsoft Edge - speech recognition needs them.</li>
        </ul>

        {refused && (
          <div className="alert error">
            <strong>Fullscreen mode is required for this interview.</strong> Please allow
            fullscreen access to continue. If your browser asked for permission, choose
            Allow and then press the button again.
          </div>
        )}

        {!supported && (
          <div className="alert info">
            This browser will not allow fullscreen, so the interview will run in a normal
            window. Everything else works as described above.
          </div>
        )}

        <button onClick={enterInterview} disabled={working}>
          {working
            ? 'Entering fullscreen...'
            : refused
              ? 'Try fullscreen again'
              : 'Enter interview'}
        </button>

        {isFullscreen && (
          <p className="muted small">Fullscreen is active. Next: your camera and microphone.</p>
        )}
      </div>
    </div>
  );
}
