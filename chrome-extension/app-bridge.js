(() => {
  'use strict';

  const NOTICE_ID = 'mp-import-notice';
  let reported = false;

  function inspectNotice() {
    if (reported) return;
    const notice = document.getElementById(NOTICE_ID);
    if (!notice) return;
    const message = (notice.textContent || '').replace(/\s+/g, ' ').trim();
    let success = false;

    if (/^✓ (?:Pregunta guardada|Esa pregunta ya estaba guardada)/i.test(message)) {
      success = true;
    } else if (/No se pudo importar|No se pudo guardar|Selecciona un usuario|Pregunta lista para guardar/i.test(message)) {
      success = false;
    } else {
      return;
    }

    reported = true;
    chrome.runtime.sendMessage({ type: 'import-result', success });
  }

  inspectNotice();
  new MutationObserver(inspectNotice).observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true
  });
})();
