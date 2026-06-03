let offscreenReady = false;

chrome.runtime.onMessage.addListener((msg, sender, respond) => {
  if (msg.type === 'TOGGLE') {
    handleToggle(msg.enabled).then(respond);
    return true;
  }
  if (msg.type === 'SCROLL') {
    forwardToActiveTab(msg);
  }
  if (msg.type === 'STATE_UPDATE') {
    // relay gaze state to popup if open
    chrome.runtime.sendMessage(msg).catch(() => {});
  }
});

async function handleToggle(enabled) {
  if (enabled) {
    await ensureOffscreen();
    chrome.runtime.sendMessage({ type: 'START' }).catch(() => {});
  } else {
    chrome.runtime.sendMessage({ type: 'STOP' }).catch(() => {});
  }
  chrome.storage.local.set({ enabled });
  return { ok: true };
}

async function ensureOffscreen() {
  if (await chrome.offscreen.hasDocument()) return;
  await chrome.offscreen.createDocument({
    url: 'offscreen.html',
    reasons: ['USER_MEDIA'],
    justification: 'Front camera access for eye gaze detection'
  });
  offscreenReady = true;
}

async function forwardToActiveTab(msg) {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (tab?.id) {
    chrome.tabs.sendMessage(tab.id, msg).catch(() => {});
  }
}
