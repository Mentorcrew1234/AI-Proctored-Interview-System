# Third-party components

Every third-party component is used through its **published package**. No source
code was copied from any project. Versions are those resolved in this build.

---

## AI models and libraries

### MediaPipe Tasks Vision — Face Landmarker

| | |
|---|---|
| Package | `@mediapipe/tasks-vision` 0.10.35 |
| Source | https://github.com/google-ai-edge/mediapipe |
| Licence | Apache-2.0 |
| Model | `face_landmarker.task` (float16), from Google's model repository |
| Used for | face presence, face count, head pose |

**Why:** one model covers three of the five vision features. A plain face
detector would give presence and count but no orientation, requiring a second
model.

**Limitations:** treats a partially occluded face region as a separate face
(measured — see [LIMITATIONS.md](../quality/LIMITATIONS.md)); degrades in poor lighting;
needs a ~3.8 MB model plus a ~33 MB WASM runtime.

Used only for **orientation**. The API also exposes blendshapes for expression
analysis; that is switched off deliberately, as expression and emotion analysis
are out of scope.

### TensorFlow.js

| | |
|---|---|
| Package | `@tensorflow/tfjs` 4.22.0 |
| Source | https://github.com/tensorflow/tfjs |
| Licence | Apache-2.0 |
| Used for | the runtime executing COCO-SSD in the browser (WebGL) |

### COCO-SSD

| | |
|---|---|
| Package | `@tensorflow-models/coco-ssd` 2.2.3 |
| Source | https://github.com/tensorflow/tfjs-models |
| Licence | Apache-2.0 |
| Model | `ssdlite_mobilenet_v2`, trained on COCO |
| Used for | detecting `person` and `cell phone` |

**Why:** a pre-trained detector whose 80 classes already include mobile phones,
small enough to run in a browser. Training a custom detector was explicitly out
of scope.

**Limitations:** only 2 of its 80 classes are used; requires the whole object in
frame (measured); confuses phones with other small dark rectangles; markedly
slower than face landmarking, hence its separate 1 fps loop.

### Google Gemini API

| | |
|---|---|
| Service | `gemini-3.1-flash-lite` via Google AI Studio (configurable; `gemini-2.5-flash` was the original default and has since been withdrawn for new keys) |
| Docs | https://ai.google.dev/gemini-api/docs |
| Terms | https://ai.google.dev/gemini-api/terms |
| Used for | question generation and answer evaluation |

**Why:** a free tier with no card required, and native schema-constrained JSON
output, which removes the need to parse prose. Called over plain REST — no SDK
dependency.

**Limitations:** ~10 requests/minute and ~250/day on the free tier; **free-tier
prompts may be used by Google to improve their products**; not deterministic.
The system runs without it.

### Web Speech API

Browser platform APIs, not dependencies — nothing is bundled and no service is
called by this project. Both directions are used:

| | API | Support | Note |
|---|---|---|---|
| Answer → text | `SpeechRecognition` | Chrome and Edge only | **Chrome routes audio through Google's servers**, so it is not on-device |
| Question → speech | `speechSynthesis` + `SpeechSynthesisUtterance` | widely supported | voices come from the operating system; nothing is sent anywhere |

See [LIMITATIONS.md](../quality/LIMITATIONS.md) for what each cannot do.

### Vendored model files

`frontend/public/models/` contains the Face Landmarker model and the COCO-SSD
weights, downloaded from Google's public model storage and committed so an
interview runs without internet access. They are redistributed under the
Apache-2.0 terms of their respective projects and are unmodified.

The MediaPipe WASM runtime is **not** committed — it is copied out of
`node_modules` at build time by `frontend/scripts/copy-wasm.mjs`.

---

## Backend

| Component | Version | Licence |
|---|---|---|
| Spring Boot | 4.0.7 | Apache-2.0 |
| Spring Framework / Security | 7.x | Apache-2.0 |
| Hibernate ORM | 7.2.19 | LGPL-2.1 / Apache-2.0 (dual, via Spring Data JPA) |
| Thymeleaf | 3.1.5 | Apache-2.0 |
| Flyway | 11.14.1 | Apache-2.0 |
| Jackson | 3.1.4 | Apache-2.0 |
| jjwt | 0.12.6 | Apache-2.0 |
| Project Lombok | 1.18.46 | MIT |
| Apache POI (`poi-ooxml`) | 5.4.1 | Apache-2.0 |
| Jakarta Mail, via `spring-boot-starter-mail` | (Boot-managed) | EPL-2.0 / GPL-2.0 with Classpath Exception |
| **OpenPDF** | **2.0.3** | **LGPL-2.1 / MPL-2.0 (dual)** |
| **MySQL Connector/J** | **9.7.0** | **GPL-2.0 with the Universal FOSS Exception** |
| H2 (test only) | 2.4.240 | MPL-2.0 / EPL-1.0 |
| JUnit 5, AssertJ, Mockito (test) | — | EPL-2.0 / Apache-2.0 / MIT |

> **Note on OpenPDF:** used unmodified, as a library, to render the detailed
> interview report as a PDF. It is dual-licensed LGPL-2.1 / MPL-2.0, so using it
> as an unmodified dependency carries no obligation on this project's own source.
> It was chosen over iText 7, which is **AGPL** — that would place requirements
> on anything served over a network. Apache PDFBox (Apache-2.0) was considered
> and rejected: it has no layout engine, so a report of this shape would mean
> hand-computing coordinates and page breaks.

> **Note on MySQL Connector/J:** it is GPL-licensed, not permissive. Oracle's
> Universal FOSS Exception permits use in open-source software, and it is used
> here unmodified as a JDBC driver for an academic project. Any closed-source
> distribution would need either a commercial Oracle licence or a different
> driver (MariaDB Connector/J, LGPL, is a drop-in alternative). This is the one
> dependency whose licence would need attention beyond a prototype.

## Frontend

| Component | Version | Licence |
|---|---|---|
| React / React DOM | 19.2.8 | MIT |
| Vite | 6.4.3 | MIT |
| Vitest | 2.1.9 | MIT |

---

## Reference material

Consulted for understanding; **no code was taken from any of them**:

- MediaPipe Face Landmarker web guide — model configuration and the facial
  transformation matrix
- TensorFlow.js COCO-SSD README — model loading and output shape
- MDN Web Speech API documentation — continuous recognition and interim results,
  and the utterance lifecycle used to read questions aloud
- Gemini API structured-output documentation — `responseSchema` usage
- Spring Boot 4 and Spring Security 7 reference documentation

Public proctoring projects (openproctor, ProctorAI, Aankh and others) were
surveyed during Phase 0 to compare approaches. Their common architecture —
browser-side detection with event upload — informed the design. No
implementation was reused, and the event engine, thresholds and debounce logic
here are original.

---

## Originality

Written specifically for this project:

- the event engine and its state machine (`eventEngine.js`)
- all detection thresholds and the debounce/dedup design
- the batching, retry and idempotency scheme
- the transcript cleaner
- score aggregation, the recommendation engine and the proctoring cap rule
- the offline question bank and heuristic scorer
- all prompts
- the entire backend, schema and UI
- all 591 backend tests and 140 JavaScript tests

Third-party contribution is limited to the two pre-trained models, the LLM
service, and standard framework libraries.
