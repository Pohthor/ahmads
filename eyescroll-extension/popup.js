const toggle      = document.getElementById('toggle');
const statusLabel = document.getElementById('statusLabel');
const sensSlider  = document.getElementById('sensitivity');
const dwellSlider = document.getElementById('dwell');
const sensVal     = document.getElementById('sensVal');
const dwellVal    = document.getElementById('dwellVal');
const eyeCanvas   = document.getElementById('eye');
const ctx         = eyeCanvas.getContext('2d');

let irisY = 0; // animated -1..1

// ── Restore saved prefs ───────────────────────────────────────────────────────
chrome.storage.local.get(['enabled', 'sensitivity', 'dwell'], (s) => {
  toggle.checked   = !!s.enabled;
  sensSlider.value = s.sensitivity ?? 60;
  dwellSlider.value = s.dwell ?? 10;
  updateLabels();
  updateStatus(s.enabled ? 'Tracking...' : 'Off');
});

// ── Toggle ────────────────────────────────────────────────────────────────────
toggle.addEventListener('change', () => {
  const enabled = toggle.checked;
  chrome.runtime.sendMessage({ type: 'TOGGLE', enabled });
  updateStatus(enabled ? 'Starting...' : 'Off');
  if (!enabled) animateIris(0);
});

// ── Sliders ───────────────────────────────────────────────────────────────────
sensSlider.addEventListener('input', () => {
  updateLabels();
  chrome.storage.local.set({ sensitivity: +sensSlider.value });
});
dwellSlider.addEventListener('input', () => {
  updateLabels();
  chrome.storage.local.set({ dwell: +dwellSlider.value });
});

function updateLabels() {
  sensVal.textContent  = sensSlider.value + '%';
  const ms = Math.round(dwellSlider.value * 10); // 0-100 → 0-1000ms
  dwellVal.textContent = ms === 0 ? 'Instant' : (ms / 1000).toFixed(1) + 's';
}

// ── Receive gaze state from background ───────────────────────────────────────
chrome.runtime.onMessage.addListener((msg) => {
  if (msg.type !== 'STATE_UPDATE') return;
  const s = msg.state;
  if (s.error) { updateStatus('Error — check camera'); return; }

  if (!toggle.checked) return;

  if (!s.faceDetected) {
    updateStatus('Looking for face...');
    animateIris(0);
    return;
  }

  if (s.isLookingUp) {
    updateStatus('Hold... ' + Math.round(s.dwellProgress * 100) + '%');
    animateIris(-0.6);
  } else {
    updateStatus('Watching 👁');
    animateIris(0);
  }
});

function updateStatus(text) { statusLabel.textContent = text; }

// ── Eye animation ─────────────────────────────────────────────────────────────
let targetIrisY = 0;
let currentIrisY = 0;

function animateIris(target) { targetIrisY = target; }

function drawEye() {
  const W = eyeCanvas.width, H = eyeCanvas.height;
  const cx = W / 2, cy = H / 2;
  const rx = W * 0.42, ry = H * 0.35;
  const irisR = ry * 0.55;
  const pupilR = irisR * 0.42;

  currentIrisY += (targetIrisY - currentIrisY) * 0.12;

  ctx.clearRect(0, 0, W, H);

  // Sclera
  ctx.beginPath();
  ctx.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
  ctx.fillStyle = '#2a2a27';
  ctx.fill();

  // Iris
  const irisActualY = cy + currentIrisY * ry * 0.55;
  const isDwell = statusLabel.textContent.startsWith('Hold');
  ctx.beginPath();
  ctx.arc(cx, irisActualY, irisR, 0, Math.PI * 2);
  ctx.fillStyle = isDwell ? '#c4a97a' : '#6e6e68';
  ctx.fill();

  // Pupil
  ctx.beginPath();
  ctx.arc(cx, irisActualY, pupilR, 0, Math.PI * 2);
  ctx.fillStyle = '#0e0e0d';
  ctx.fill();

  requestAnimationFrame(drawEye);
}

drawEye();
