import { useCallback, useEffect, useState } from 'react';
import { api, getToken, clearToken } from './api.js';
import LoginScreen from './screens/LoginScreen.jsx';
import InstructionsScreen from './screens/InstructionsScreen.jsx';
import DeviceCheckScreen from './screens/DeviceCheckScreen.jsx';
import ExamScreen from './screens/ExamScreen.jsx';
import { resolveMode, MODE } from './interviewMode.js';

/** Reads the invite token out of /exam/{token}. */
function inviteTokenFromUrl() {
  const parts = window.location.pathname.split('/').filter(Boolean);
  const index = parts.indexOf('exam');
  return index >= 0 && parts.length > index + 1 ? parts[index + 1] : null;
}

const STAGE = {
  LOADING: 'loading',
  LOGIN: 'login',
  INSTRUCTIONS: 'instructions',
  DEVICE_CHECK: 'device_check',
  EXAM: 'exam',
  DONE: 'done',
  ERROR: 'error',
};

export default function App() {
  const [stage, setStage] = useState(STAGE.LOADING);
  const [info, setInfo] = useState(null);
  const [session, setSession] = useState(null);
  const [stream, setStream] = useState(null);
  const [devices, setDevices] = useState(null);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);
  const token = inviteTokenFromUrl();

  /*
   * The interview mode, decided once from the server's answer and passed down.
   *
   * Resolved here rather than read wherever it happens to be needed, so there
   * is a single value for the whole interview and no component can disagree
   * about which mode it is in. Anything unrecognised - including an older
   * server that does not send the field - becomes NORMAL.
   */
  const mode = info ? resolveMode(info) : MODE.NORMAL;

  const loadInfo = useCallback(async () => {
    try {
      const data = await api.examInfo(token);
      setInfo(data);

      if (data.status === 'COMPLETED' || data.sessionStatus === 'COMPLETED') {
        setError('This interview has already been completed.');
        setStage(STAGE.DONE);
        return;
      }
      if (data.status === 'CANCELLED') {
        setError('This interview was cancelled. Contact your recruiter.');
        setStage(STAGE.ERROR);
        return;
      }
      setStage(STAGE.INSTRUCTIONS);
    } catch (e) {
      if (e.status === 401) {
        clearToken();
        setStage(STAGE.LOGIN);
        return;
      }
      // 404 covers both an unknown link and someone else's link - the backend
      // deliberately does not distinguish them.
      setError(
        e.status === 404
          ? 'This interview link is not valid for your account.'
          : e.message,
      );
      setStage(STAGE.ERROR);
    }
  }, [token]);

  useEffect(() => {
    if (!token) {
      setError('No interview link was supplied.');
      setStage(STAGE.ERROR);
      return;
    }
    if (!getToken()) {
      setStage(STAGE.LOGIN);
      return;
    }
    loadInfo();
  }, [token, loadInfo]);

  async function startExam(deviceCheck) {
    try {
      const started = await api.startExam(token, deviceCheck);
      // Kept so the interview screen can report the real microphone state: the
      // start response does not echo it back, and the audio track is stopped
      // before the interview begins.
      setDevices(deviceCheck);
      setSession(started);
      setStage(STAGE.EXAM);
    } catch (e) {
      setError(e.message);
      setStage(STAGE.ERROR);
    }
  }

  if (stage === STAGE.LOADING) {
    return <div className="centre-wrap"><div className="card narrow">Loading...</div></div>;
  }

  if (stage === STAGE.LOGIN) {
    return <LoginScreen onSignedIn={() => { setStage(STAGE.LOADING); loadInfo(); }} />;
  }

  if (stage === STAGE.ERROR) {
    return (
      <div className="centre-wrap">
        <div className="card narrow">
          <h1>Cannot start</h1>
          <div className="alert error">{error}</div>
        </div>
      </div>
    );
  }

  if (stage === STAGE.INSTRUCTIONS) {
    return (
      <InstructionsScreen
        info={info}
        mode={mode}
        onContinue={() => setStage(STAGE.DEVICE_CHECK)}
      />
    );
  }

  if (stage === STAGE.DEVICE_CHECK) {
    return <DeviceCheckScreen onReady={startExam} onStreamReady={setStream} />;
  }

  if (stage === STAGE.EXAM) {
    return (
      <ExamScreen
        session={{ ...session, micGranted: devices?.micGranted }}
        stream={stream}
        mode={mode}
        onFinish={(completion) => {
          setResult(completion);
          setStage(STAGE.DONE);
        }}
      />
    );
  }

  return (
    <div className="centre-wrap">
      <div className="card narrow">
        <h1>{result?.completionReason === 'TIME_EXPIRED' ? 'Time expired' : 'Interview finished'}</h1>

        {/* A candidate whose time ran out did not choose to stop, and should not
            be told they submitted. Saying it was submitted automatically also
            answers the question they would otherwise be left with: whether the
            answers they had already given still count. */}
        {result?.completionReason === 'TIME_EXPIRED' ? (
          <p className="muted">
            Your interview time has expired. The interview has been submitted
            automatically, and the answers you had already given were saved.
            You can close this window.
          </p>
        ) : (
          <p className="muted">
            {error ?? 'Your answers have been submitted. You can close this window.'}
          </p>
        )}

        {/* The result is shown only when the recruiter allowed it. */}
        {result?.resultVisible && (
          <>
            <div className="overall-value" style={{ fontSize: 38, fontWeight: 700 }}>
              {result.overallScore}%
            </div>
            <p className="muted">
              Outcome: <strong>{result.recommendation?.replace(/_/g, ' ')}</strong>
            </p>
            <div className="alert info">
              This is an automated assessment and advisory only. Your recruiter makes
              the actual decision.
            </div>
          </>
        )}

        {result && !result.resultVisible && (
          <p className="muted small">
            Your recruiter will share the outcome with you.
          </p>
        )}
      </div>
    </div>
  );
}
