'use strict';

chrome.runtime.onMessage.addListener((message, sender) => {
  if (message?.type !== 'open-import' || typeof message.url !== 'string') return;
  try {
    const url = new URL(message.url);
    if (url.origin !== 'https://preguntas-app.onrender.com'
      || !url.pathname.endsWith('/')) return;
    chrome.tabs.create({ url: url.href });
  } catch (_) {
    // Ignore malformed page data; never navigate to an untrusted URL.
  }
});