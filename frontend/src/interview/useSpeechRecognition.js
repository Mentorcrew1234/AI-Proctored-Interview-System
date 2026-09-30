import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Speech-to-text via the browser's Web Speech API.
 *
 * Documented limitations, all of which the UI accounts for:
 * - Chrome and Edge only. Firefox has no implementation at all.
 * - Chrome streams audio to Google's servers, so it needs internet. It is not
 *   on-device despite running "in the browser".
 * - It needs a secure context: https, or localhost.
 * - Recognition stops on its own after a pause, so it is restarted while the
 *   candidate is still holding the floor.
 * - Accuracy drops with accents, background noise and technical vocabulary.
 *
 * Because none of that is under our control, the transcript stays editable and
 * typing is always available - a candidate is never blocked by their browser.
 *
 * Every failure is surfaced. Silently doing nothing is the one behaviour this
 * hook must never have: a candidate who presses the microphone and sees no
 * reaction cannot tell a broken feature from their own mistake.
 */
export function useSpeechRecognition({ lang = 'en-US' } = {}) {
  const SpeechRecognition =
    typeof window !== 'undefined' &&
    (window.SpeechRecognition || window.webkitSpeechRecognition);

  const supported = Boolean(SpeechRecognition);

  // A secure context is required; without it start() fails immediately and,
  // on some builds, without ever firing onerror.
  const secureContext =
    typeof window === 'undefined' ||
    window.isSecureContext ||
    window.location.hostname === 'localhost' ||
    window.location.hostname === '127.0.0.1';

  const [listening, setListening] = useState(false);
  const [starting, setStarting] = useState(false);
  const [finalText, setFinalText] = useState('');
  const [interimText, setInterimText] = useState('');
  const [error, setError] = useState(null);

  const recognitionRef = useRef(null);
  // Distinguishes "the API stopped by itself" from "the candidate pressed stop".
  const wantListeningRef = useRef(false);
  // Guards the auto-restart loop: if restarting keeps failing, stop trying.
  const restartCountRef = useRef(0);

  useEffect(() => {
    if (!supported || !secureContext) return undefined;

    const recognition = new SpeechRecognition();
    recognition.lang = lang;
    recognition.continuous = true;
    recognition.interimResults = true;

    recognition.onstart = () => {
      restartCountRef.current = 0;
      setStarting(false);
      setListening(true);
      setError(null);
    };

    recognition.onresult = (event) => {
      let interim = '';
      let appended = '';
      for (let i = event.resultIndex; i < event.results.length; i += 1) {
        const result = event.results[i];
        if (result.isFinal) {
          appended += result[0].transcript;
        } else {
          interim += result[0].transcript;
        }
      }
      if (appended) {
        setFinalText((prev) => (prev ? `${prev} ${appended.trim()}` : appended.trim()));
      }
      setInterimText(interim);
    };

    recognition.onerror = (event) => {
      // Every branch either reports something or is genuinely routine and
      // recoverable. Nothing falls through silently.
      switch (event.error) {
        case 'no-speech':
          // Routine: the pause timer fired. onend will restart it.
          return;
        case 'aborted':
          // We stopped it ourselves.
          return;
        case 'not-allowed':
        case 'service-not-allowed':
          setError(
            'Microphone access is required for voice answers. Please allow microphone '
              + 'access in your browser and try again. You can type your answer instead.',
          );
          break;
        case 'audio-capture':
          setError(
            'No microphone could be used. Check that one is connected and not in use by '
              + 'another application, then try again. You can type your answer instead.',
          );
          break;
        case 'network':
          setError(
            'Speech recognition needs an internet connection and could not reach the '
              + 'service. Please type your answer instead.',
          );
          break;
        case 'language-not-supported':
          setError('This language is not supported for voice input. Please type your answer.');
          break;
        default:
          setError(`Voice input stopped: ${event.error}. You can type your answer instead.`);
      }
      // Any reported error ends the session; do not fight it in onend.
      wantListeningRef.current = false;
      setStarting(false);
      setListening(false);
    };

    recognition.onend = () => {
      setStarting(false);
      if (!wantListeningRef.current) {
        setListening(false);
        return;
      }
      // Chrome ends the session after a silence; resume if we still want it.
      // Bounded, so a permanently failing start cannot spin.
      restartCountRef.current += 1;
      if (restartCountRef.current > 20) {
        wantListeningRef.current = false;
        setListening(false);
        setError('Voice input kept stopping. Please type your answer instead.');
        return;
      }
      try {
        recognition.start();
      } catch {
        wantListeningRef.current = false;
        setListening(false);
      }
    };

    recognitionRef.current = recognition;

    return () => {
      wantListeningRef.current = false;
      // Detach first: a stop() during unmount would otherwise fire onend and
      // set state on an unmounted component.
      recognition.onstart = null;
      recognition.onresult = null;
      recognition.onerror = null;
      recognition.onend = null;
      try {
        recognition.abort();
      } catch {
        /* already stopped */
      }
      recognitionRef.current = null;
    };
  }, [SpeechRecognition, supported, secureContext, lang]);

  /**
   * Starts listening.
   *
   * Asks for microphone permission explicitly first. The Web Speech API would
   * request it itself, but doing it here means a denial produces a clear,
   * immediate message instead of a recognition session that quietly never
   * yields a result.
   */
  const start = useCallback(async () => {
    if (!supported) {
      setError(
        'Voice input is not supported in this browser. Please use Google Chrome or '
          + 'Microsoft Edge, or type your answer.',
      );
      return;
    }
    if (!secureContext) {
      setError(
        'Voice input needs a secure connection (https or localhost). Please type your answer.',
      );
      return;
    }
    if (!recognitionRef.current) return;

    setError(null);
    setStarting(true);

    if (navigator.mediaDevices?.getUserMedia) {
      try {
        // Released immediately - this is a permission probe, not a capture.
        // Holding it open would compete with the recogniser for the device.
        const probe = await navigator.mediaDevices.getUserMedia({ audio: true });
        probe.getTracks().forEach((track) => track.stop());
      } catch (e) {
        setStarting(false);
        setError(
          e && e.name === 'NotFoundError'
            ? 'No microphone was found. Please connect one, or type your answer instead.'
            : 'Microphone access is required for voice answers. Please allow microphone '
              + 'access in your browser. You can type your answer instead.',
        );
        return;
      }
    }

    wantListeningRef.current = true;
    restartCountRef.current = 0;
    try {
      recognitionRef.current.start();
      setListening(true);
    } catch {
      // start() throws if it is already running - harmless.
      setStarting(false);
      setListening(true);
    }
  }, [supported, secureContext]);

  const stop = useCallback(() => {
    wantListeningRef.current = false;
    setInterimText('');
    setStarting(false);
    try {
      recognitionRef.current?.stop();
    } catch {
      /* already stopped */
    }
    setListening(false);
  }, []);

  const reset = useCallback(() => {
    setFinalText('');
    setInterimText('');
    setError(null);
  }, []);

  return {
    supported,
    secureContext,
    listening,
    starting,
    finalText,
    interimText,
    setFinalText,
    error,
    start,
    stop,
    reset,
  };
}
