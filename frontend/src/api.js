/**
 * Thin wrapper around the backend REST API.
 *
 * The JWT lives in sessionStorage rather than localStorage so it does not
 * outlive the browser session, and it is only ever sent in the Authorization
 * header.
 */

const TOKEN_KEY = 'proctor.jwt';

export function getToken() {
  return sessionStorage.getItem(TOKEN_KEY);
}

export function setToken(token) {
  sessionStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  sessionStorage.removeItem(TOKEN_KEY);
}

export class ApiError extends Error {
  constructor(status, message, body) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

/**
 * `keepalive` lets a request outlive the page that started it, which is what
 * makes the final drain on `pagehide` possible. `navigator.sendBeacon` cannot
 * be used for that: it has no way to set an Authorization header, and every
 * endpoint here needs the bearer token.
 *
 * The browser caps all in-flight keepalive bodies at 64 KB. A proctoring event
 * is a few hundred bytes, so a full queue is comfortably inside that, but it is
 * the reason the unload drain sends what is queued rather than retrying.
 */
async function request(path, { method = 'GET', body, auth = true, keepalive = false } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (auth) {
    const token = getToken();
    if (token) headers.Authorization = `Bearer ${token}`;
  }

  const response = await fetch(path, {
    method,
    headers,
    keepalive,
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  const text = await response.text();
  const payload = text ? safeParse(text) : null;

  if (!response.ok) {
    throw new ApiError(
      response.status,
      payload?.message ?? `Request failed (${response.status})`,
      payload,
    );
  }
  return payload;
}

function safeParse(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export const api = {
  login: (email, password) =>
    request('/api/auth/login', { method: 'POST', body: { email, password }, auth: false }),

  me: () => request('/api/auth/me'),

  examInfo: (token) => request(`/api/exam/${encodeURIComponent(token)}`),

  startExam: (token, deviceCheck) =>
    request(`/api/exam/${encodeURIComponent(token)}/start`, { method: 'POST', body: deviceCheck }),

  uploadProctorEvents: (sessionId, events, { keepalive = false } = {}) =>
    request(`/api/interview-sessions/${sessionId}/proctor-events`, {
      method: 'POST',
      body: { events },
      keepalive,
    }),

  /**
   * Reports on the monitoring itself - whether the detectors loaded and how
   * many observations were lost - so a session nobody watched cannot be
   * mistaken for a session where nothing happened.
   *
   * Never carries anything about the candidate.
   */
  reportMonitoringStatus: (sessionId, { ready, droppedEvents, note }, { keepalive = false } = {}) =>
    request(`/api/interview-sessions/${sessionId}/monitoring-status`, {
      method: 'POST',
      body: { ready, droppedEvents, note: note ?? null },
      keepalive,
    }),

  questions: (sessionId) => request(`/api/interview-sessions/${sessionId}/questions`),

  submitAnswer: (sessionId, questionId, rawTranscript, durationSeconds) =>
    request(`/api/interview-sessions/${sessionId}/answers`, {
      method: 'POST',
      body: { questionId, rawTranscript, durationSeconds },
    }),

  completeSession: (sessionId) =>
    request(`/api/interview-sessions/${sessionId}/complete`, { method: 'POST' }),
};
