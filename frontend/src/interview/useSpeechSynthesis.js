import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Reads text aloud through the browser's built-in speech synthesis.
 *
 * The counterpart to `useSpeechRecognition`, and deliberately kept separate:
 * that one turns the candidate's voice into text, this one turns the question
 * into sound. They are different Web Speech APIs with different failure modes,
 * and the two must not be confused - see the note about interference below.
 *
 * <b>No library and no service.</b> `window.speechSynthesis` is part of the
 * browser; nothing is sent anywhere and nothing new is bundled. A browser
 * without it simply reports `supported: false` and the question stays on screen
 * as text, which is how it has always been read.
 *
 * <h2>Overlapping speech is impossible by construction</h2>
 *
 * Every {@link speak} cancels whatever is already playing before it starts.
 * That is the whole duplicate-suppression strategy, and it is deliberately not
 * a "have I already said this?" flag:
 *
 * React runs effects twice in StrictMode - mount, clean up, mount again - and a
 * flag that remembered the question had been spoken would skip the second run
 * after the first had already been cancelled, leaving silence. Cancel-then-speak
 * survives that, because the last call always wins and there is only ever one
 * utterance in flight.
 *
 * <h2>Interference with answer recording</h2>
 *
 * Speech synthesis plays through the speakers; speech recognition listens
 * through the microphone. If the question is still being read when the
 * candidate starts answering, the microphone hears it and transcribes the
 * question into their answer. Callers must therefore stop playback before
 * starting recognition - `QuestionPanel` does this.
 */
export function useSpeechSynthesis() {
  const supported =
    typeof window !== 'undefined' &&
    typeof window.speechSynthesis !== 'undefined' &&
    typeof window.SpeechSynthesisUtterance !== 'undefined';

  const [speaking, setSpeaking] = useState(false);
  const [error, setError] = useState(null);
  /** The utterance currently in flight, so a late event from an old one is ignored. */
  const currentRef = useRef(null);

  /** Stops immediately. Safe to call when nothing is playing. */
  const stop = useCallback(() => {
    if (!supported) return;
    currentRef.current = null;
    try {
      window.speechSynthesis.cancel();
    } catch {
      // Some browsers throw if the engine is not ready. Nothing useful to do:
      // the point of this call is that speech is not playing afterwards.
    }
    setSpeaking(false);
  }, [supported]);

  /**
   * Speaks `text`, cancelling anything already playing.
   *
   * @returns {boolean} whether speech was actually started - false when the
   *   browser has no support, so a caller can tell "said it" from "could not".
   */
  const speak = useCallback(
    (text) => {
      if (!supported || typeof text !== 'string' || !text.trim()) {
        return false;
      }

      try {
        // Never layer one question over another.
        window.speechSynthesis.cancel();

        const utterance = new window.SpeechSynthesisUtterance(text);
        // Slightly under normal pace: these are long technical sentences being
        // heard once, not read.
        utterance.rate = 0.95;
        utterance.pitch = 1;
        utterance.volume = 1;
        utterance.lang = 'en-US';

        utterance.onend = () => {
          if (currentRef.current === utterance) {
            currentRef.current = null;
            setSpeaking(false);
          }
        };

        utterance.onerror = (event) => {
          // "interrupted" and "canceled" are what our own cancel() produces, so
          // they are the normal path rather than a fault to report.
          const reason = event?.error;
          if (currentRef.current === utterance) {
            currentRef.current = null;
            setSpeaking(false);
            if (reason && reason !== 'interrupted' && reason !== 'canceled') {
              setError('The question could not be read aloud.');
            }
          }
        };

        currentRef.current = utterance;
        setError(null);
        setSpeaking(true);
        window.speechSynthesis.speak(utterance);
        return true;
      } catch {
        currentRef.current = null;
        setSpeaking(false);
        setError('The question could not be read aloud.');
        return false;
      }
    },
    [supported],
  );

  // Leaving the interview must not leave a voice talking to an empty room.
  useEffect(() => {
    if (!supported) return undefined;
    return () => {
      try {
        window.speechSynthesis.cancel();
      } catch {
        // As above: best effort on the way out.
      }
    };
  }, [supported]);

  return { supported, speaking, error, speak, stop };
}
