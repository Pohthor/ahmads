// Injected into Instagram and TikTok pages
// Listens for SCROLL messages from the background service worker

let overlay = null;
let overlayTimeout = null;

chrome.runtime.onMessage.addListener((msg) => {
  if (msg.type === 'SCROLL' && msg.direction === 'next') {
    scrollToNext();
    showScrollFeedback();
  }
});

function scrollToNext() {
  const url = location.href;

  if (url.includes('tiktok.com')) {
    // TikTok: keyboard ArrowDown works on the feed
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', keyCode: 40, bubbles: true }));
    // Also try scroll as fallback
    window.scrollBy({ top: window.innerHeight, behavior: 'smooth' });
    return;
  }

  if (url.includes('instagram.com')) {
    // Instagram Reels: try clicking the Next button first
    const nextBtn = document.querySelector(
      '[aria-label="Next"], [aria-label="next"], button[class*="next"]'
    );
    if (nextBtn) { nextBtn.click(); return; }

    // Instagram feed / explore: standard scroll
    window.scrollBy({ top: window.innerHeight * 0.92, behavior: 'smooth' });
  }
}

// ── Subtle corner overlay so the user knows it scrolled ──────────────────────
function showScrollFeedback() {
  if (!overlay) {
    overlay = document.createElement('div');
    overlay.style.cssText = `
      position: fixed; bottom: 24px; right: 24px; z-index: 999999;
      background: rgba(196,169,122,0.92); color: #0e0e0d;
      padding: 8px 14px; border-radius: 20px;
      font-size: 13px; font-family: sans-serif; font-weight: 600;
      letter-spacing: 0.03em; pointer-events: none;
      transition: opacity 0.3s;
    `;
    document.body.appendChild(overlay);
  }

  overlay.textContent = '👁 Next';
  overlay.style.opacity = '1';

  clearTimeout(overlayTimeout);
  overlayTimeout = setTimeout(() => {
    overlay.style.opacity = '0';
  }, 1200);
}
