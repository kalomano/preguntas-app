'use strict';

const APP_ORIGIN = 'https://preguntas-app.onrender.com';
const pendingKey = tabId => 'mis-preguntas-import-' + tabId;

chrome.runtime.onMessage.addListener((message, sender) => {
  if (message?.type === 'open-import' && typeof message.url === 'string') {
    try {
      const url = new URL(message.url);
      if (url.origin !== APP_ORIGIN || url.pathname !== '/') return;

      // Load in the background; the app page reports success and this tab closes itself.
      chrome.tabs.create({ url: 'about:blank', active: false }, tab => {
        if (chrome.runtime.lastError || !tab?.id) return;
        const sourceTabId = sender.tab?.id;
        chrome.storage.session.set({ [pendingKey(tab.id)]: sourceTabId }, () => {
          chrome.tabs.update(tab.id, { url: url.href });
        });
      });
    } catch (_) {
      // Ignore malformed page data; never navigate to an untrusted URL.
    }
    return;
  }

  if (message?.type !== 'import-result' || !sender.tab?.id) return;
  const appTabId = sender.tab.id;
  const key = pendingKey(appTabId);
  chrome.storage.session.get(key, result => {
    const sourceTabId = result[key];
    chrome.storage.session.remove(key);
    if (message.success) {
      chrome.tabs.remove(appTabId);
    } else {
      chrome.tabs.update(appTabId, { active: true });
    }
    if (sourceTabId !== undefined) {
      chrome.tabs.sendMessage(sourceTabId, {
        type: 'save-result',
        success: Boolean(message.success)
      });
    }
  });
});

chrome.tabs.onRemoved.addListener(tabId => {
  chrome.storage.session.remove(pendingKey(tabId));
});
