document.addEventListener('DOMContentLoaded', () => {
  const ytToggle = document.getElementById('yt-toggle');
  const igToggle = document.getElementById('ig-toggle');
  const blockedCountEl = document.getElementById('blocked-count');
  const resetBtn = document.getElementById('reset-btn');

  // Load saved preferences and metrics
  chrome.storage.local.get({
    youtubeEnabled: true,
    instagramEnabled: true,
    blockedCount: 0
  }, (items) => {
    ytToggle.checked = items.youtubeEnabled;
    igToggle.checked = items.instagramEnabled;
    blockedCountEl.textContent = formatNumber(items.blockedCount);
  });

  // Listen for changes on YouTube Toggle
  ytToggle.addEventListener('change', () => {
    chrome.storage.local.set({ youtubeEnabled: ytToggle.checked });
  });

  // Listen for changes on Instagram Toggle
  igToggle.addEventListener('change', () => {
    chrome.storage.local.set({ instagramEnabled: igToggle.checked });
  });

  // Handle Reset Stats button
  resetBtn.addEventListener('click', () => {
    if (confirm('Are you sure you want to reset the blocked count?')) {
      chrome.storage.local.set({ blockedCount: 0 }, () => {
        blockedCountEl.textContent = '0';
      });
    }
  });

  // Format large numbers (e.g. 1000 -> 1.0k)
  function formatNumber(num) {
    if (num >= 1000000) {
      return (num / 1000000).toFixed(1) + 'M';
    }
    if (num >= 1000) {
      return (num / 1000).toFixed(1) + 'k';
    }
    return num.toString();
  }
});
