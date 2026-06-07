(function () {
  'use strict';

  // High-impact motivational quotes
  const MOTIVATIONAL_QUOTES = [
    "Is this cheap 15-second escape really worth your dreams?",
    "Your focus is being monetized. Take back control of your mind.",
    "Stop consuming someone else's highlight reel. Go build your own life.",
    "You opened this browser to do something else. What was it?",
    "Every short you watch is a trade: your potential in exchange for flashing lights.",
    "Break the loop. Step away.",
    "Your future self is watching you right now. Make them proud.",
    "Success is built on what you do when you are bored. Don't scroll.",
    "This feed is designed to keep you trapped. Escape now."
  ];

  let currentOverlay = null;
  let lastCheckedUrl = "";

  // Check storage if blocking is enabled for a platform
  function checkBlockerEnabled(platform, callback) {
    chrome.storage.local.get({
      youtubeEnabled: true,
      instagramEnabled: true
    }, function (items) {
      if (platform === 'youtube' && items.youtubeEnabled) {
        callback(true);
      } else if (platform === 'instagram' && items.instagramEnabled) {
        callback(true);
      } else {
        callback(false);
      }
    });
  }

  // Check URL against block lists
  function detectBlockedPlatform() {
    const url = window.location.href;
    if (url.includes('youtube.com/shorts/')) {
      return 'youtube';
    }
    if (url.includes('instagram.com/reels/') || (url.includes('instagram.com/p/') && url.includes('/reels/'))) {
      return 'instagram';
    }
    return null;
  }

  // Get a random quote
  function getRandomQuote() {
    const index = Math.floor(Math.random() * MOTIVATIONAL_QUOTES.length);
    return MOTIVATIONAL_QUOTES[index];
  }

  // Increment blocked count in local storage
  function incrementBlockCounter() {
    chrome.storage.local.get({ blockedCount: 0 }, function (items) {
      chrome.storage.local.set({ blockedCount: items.blockedCount + 1 });
    });
  }

  // Create and inject the overlay DOM structure
  function injectBlockOverlay(platform) {
    if (currentOverlay) return; // Blocker is already showing

    // Create container
    const overlay = document.createElement('div');
    overlay.className = 'focusguard-overlay';

    // Build the SVG icon markup
    const lockIconSvg = `
      <svg viewBox="0 0 24 24">
        <rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect>
        <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
      </svg>
    `;

    // Setup card content
    const randomQuote = getRandomQuote();
    overlay.innerHTML = `
      <div class="focusguard-card">
        <div class="focusguard-icon-container">
          ${lockIconSvg}
        </div>
        <h1 class="focusguard-title">Focus Restored</h1>
        <p class="focusguard-quote">"${randomQuote}"</p>
        <button class="focusguard-button" id="focusguard-goback-btn">Go Back to Work</button>
      </div>
    `;

    // Wait for body to be available
    const targetBody = document.body || document.documentElement;
    if (targetBody) {
      // Lock scroll at document/body level
      document.documentElement.classList.add('focusguard-blocked-scroll');
      document.body.classList.add('focusguard-blocked-scroll');

      targetBody.appendChild(overlay);
      currentOverlay = overlay;

      // Prevent scroll event bubbling on the overlay itself
      overlay.addEventListener('wheel', e => e.preventDefault(), { passive: false });
      overlay.addEventListener('touchmove', e => e.preventDefault(), { passive: false });
      overlay.addEventListener('keydown', e => {
        const keys = ['Space', 'ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End'];
        if (keys.includes(e.code)) {
          e.preventDefault();
        }
      });

      // Trigger fade-in animation
      requestAnimationFrame(() => {
        overlay.classList.add('active');
      });

      // Handle video audio pausing (YouTube and Instagram have video tags running in background)
      pauseAllMedia();

      // Setup Button Event
      const btn = overlay.querySelector('#focusguard-goback-btn');
      if (btn) {
        btn.addEventListener('click', () => {
          redirectHome(platform);
        });
      }

      // Record block statistic
      incrementBlockCounter();
    }
  }

  // Pause background videos/audio elements to stop dopamine trigger sounds
  function pauseAllMedia() {
    try {
      const videos = document.querySelectorAll('video');
      videos.forEach(v => {
        v.pause();
        v.src = ""; // Force source clear to stop streaming buffer
        v.load();
      });
      const audios = document.querySelectorAll('audio');
      audios.forEach(a => a.pause());
    } catch (e) {
      console.warn("FocusGuard media pause error:", e);
    }
  }

  // Perform redirection back to main homepage
  function redirectHome(platform) {
    // Unlock scrolling
    document.documentElement.classList.remove('focusguard-blocked-scroll');
    document.body.classList.remove('focusguard-blocked-scroll');

    // Fade out overlay first
    if (currentOverlay) {
      currentOverlay.classList.remove('active');
      setTimeout(() => {
        if (currentOverlay && currentOverlay.parentNode) {
          currentOverlay.parentNode.removeChild(currentOverlay);
        }
        currentOverlay = null;

        // Route to home
        if (platform === 'youtube') {
          window.location.href = 'https://www.youtube.com/';
        } else if (platform === 'instagram') {
          window.location.href = 'https://www.instagram.com/';
        }
      }, 400);
    } else {
      if (platform === 'youtube') {
        window.location.href = 'https://www.youtube.com/';
      } else if (platform === 'instagram') {
        window.location.href = 'https://www.instagram.com/';
      }
    }
  }

  // Remove blocker overlay if present
  function removeBlockOverlay() {
    // Unlock scrolling
    document.documentElement.classList.remove('focusguard-blocked-scroll');
    document.body.classList.remove('focusguard-blocked-scroll');

    if (currentOverlay) {
      currentOverlay.classList.remove('active');
      const temp = currentOverlay;
      currentOverlay = null;
      setTimeout(() => {
        if (temp && temp.parentNode) {
          temp.parentNode.removeChild(temp);
        }
      }, 400);
    }
  }

  // Run URL scanner
  function runUrlCheck() {
    const currentUrl = window.location.href;
    if (currentUrl === lastCheckedUrl) return;
    lastCheckedUrl = currentUrl;

    const platform = detectBlockedPlatform();
    if (platform) {
      checkBlockerEnabled(platform, function (isEnabled) {
        if (isEnabled) {
          // If body is not yet ready, poll until it is
          if (!document.body) {
            const bodyPoll = setInterval(() => {
              if (document.body) {
                clearInterval(bodyPoll);
                injectBlockOverlay(platform);
              }
            }, 10);
          } else {
            injectBlockOverlay(platform);
          }
        } else {
          removeBlockOverlay();
        }
      });
    } else {
      removeBlockOverlay();
    }
  }

  // Monitor SPA URL updates using event listeners + polling fallback
  window.addEventListener('popstate', runUrlCheck);
  window.addEventListener('locationchange', runUrlCheck);

  // Poll window location for SPA redirects that don't trigger events (e.g. YouTube's custom routing)
  setInterval(runUrlCheck, 150);

  // Initial check
  runUrlCheck();
})();
