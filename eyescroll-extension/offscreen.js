// ── Config ──────────────────────────────────────────────────────────────────
const MP_VISION_URL  = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/vision_bundle.js';
const MP_WASM_PATH   = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/wasm';
const MODEL_URL      = 'https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task';

const LOOK_UP_THRESHOLD = 0.30;
const DWELL_MS          = 0;
const COOLDOWN_MS       = 2500;

// ── State ────────────────────────────────────────────────────────────────────
let faceLandmarker = null;
let stream = null;
let tracking = false;
let rafId = null;

let smoothedUp = 0;
let lookUpStart = 0;
let lastScroll  = 0;

const video  = document.getElementById('video');
const canvas = document.getElementById('canvas');

// ── Script loader (fetch → blob → <script>) ──────────────────────────────────
function loadScript(url) {
  return fetch(url)
    .then(r => r.text())
    .then(code => new Promise((resolve, reject) => {
      const blob = new Blob([code], { type: 'application/javascript' });
      const blobUrl = URL.createObjectURL(blob);
      const s = document.createElement('script');
      s.src = blobUrl;
      s.onload  = () => { URL.revokeObjectURL(blobUrl); resolve(); };
      s.onerror = () => reject(new Error('Failed to load: ' + url));
      document.head.appendChild(s);
    }));
}

// ── Initialise MediaPipe ──────────────────────────────────────────────────────
async function initMediaPipe() {
  if (faceLandmarker) return;

  await loadScript(MP_VISION_URL);

  // tasks-vision UMD exports FilesetResolver and FaceLandmarker to window
  const { FilesetResolver, FaceLandmarker } = window;

  const vision = await FilesetResolver.forVisionTasks(MP_WASM_PATH);

  faceLandmarker = await FaceLandmarker.createFromOptions(vision, {
    baseOptions: {
      modelAssetPath: MODEL_URL,
      delegate: 'GPU'
    },
    outputFaceBlendshapes: true,
    runningMode: 'VIDEO',
    numFaces: 1
  });
}

// ── Camera ───────────────────────────────────────────────────────────────────
async function startCamera() {
  if (stream) return;
  stream = await navigator.mediaDevices.getUserMedia({
    video: { width: 320, height: 240, facingMode: 'user', frameRate: { ideal: 15 } }
  });
  video.srcObject = stream;
  await new Promise(r => video.onloadedmetadata = r);
  video.play();
  canvas.width  = video.videoWidth;
  canvas.height = video.videoHeight;
}

function stopCamera() {
  stream?.getTracks().forEach(t => t.stop());
  stream = null;
  video.srcObject = null;
}

// ── Gaze processing ───────────────────────────────────────────────────────────
function avgBlendshape(cats, ...names) {
  let sum = 0;
  for (const name of names) sum += cats.find(c => c.categoryName === name)?.score ?? 0;
  return sum / names.length;
}

function processFrame() {
  if (!tracking || !faceLandmarker) return;

  const result = faceLandmarker.detectForVideo(video, performance.now());
  const cats = result?.faceBlendshapes?.[0]?.categories ?? [];

  const rawUp   = avgBlendshape(cats, 'eyeLookUpLeft', 'eyeLookUpRight');
  smoothedUp = smoothedUp * 0.5 + rawUp * 0.5;

  const isUp  = smoothedUp > LOOK_UP_THRESHOLD;
  const now   = Date.now();

  if (isUp) {
    if (!lookUpStart) lookUpStart = now;
    const elapsed  = now - lookUpStart;
    const progress = Math.min(elapsed / DWELL_MS, 1);

    sendState({ faceDetected: true, isLookingUp: true, dwellProgress: progress });

    if (elapsed >= DWELL_MS && (now - lastScroll) >= COOLDOWN_MS) {
      lastScroll  = now;
      lookUpStart = 0;
      chrome.runtime.sendMessage({ type: 'SCROLL', direction: 'next' });
    }
  } else {
    lookUpStart = 0;
    sendState({ faceDetected: cats.length > 0, isLookingUp: false, dwellProgress: 0 });
  }

  rafId = requestAnimationFrame(processFrame);
}

function sendState(state) {
  chrome.runtime.sendMessage({ type: 'STATE_UPDATE', state }).catch(() => {});
}

// ── Start / Stop ──────────────────────────────────────────────────────────────
async function startTracking() {
  if (tracking) return;
  tracking = true;
  try {
    await startCamera();
    await initMediaPipe();
    processFrame();
  } catch (e) {
    tracking = false;
    sendState({ error: e.message });
  }
}

function stopTracking() {
  tracking = false;
  if (rafId) cancelAnimationFrame(rafId);
  stopCamera();
  smoothedUp = 0;
  lookUpStart = 0;
  sendState({ faceDetected: false, isLookingUp: false, dwellProgress: 0 });
}

// ── Message listener ──────────────────────────────────────────────────────────
chrome.runtime.onMessage.addListener((msg) => {
  if (msg.type === 'START') startTracking();
  if (msg.type === 'STOP')  stopTracking();
  if (msg.type === 'SET_THRESHOLD') LOOK_UP_THRESHOLD = msg.value;
});
