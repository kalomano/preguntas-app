(() => {
  'use strict';
  const PREFIX = '#mp-import=';
  const STORAGE = 'mp_import_bookmark_1';
  const clean = s => String(s || '').replace(/\s+/g, ' ').trim();
  const validate = data => data && data.v === 1 && typeof data.question === 'string'
    && data.question.length > 0 && data.question.length <= 12000
    && Array.isArray(data.options) && data.options.length === 4
    && data.options.every((opt, i) => opt && opt.letter === 'abcd'[i]
      && typeof opt.text === 'string' && opt.text.length > 0 && opt.text.length <= 12000)
    && Number.isInteger(data.correct_index) && data.correct_index >= 0 && data.correct_index < 4
    && typeof data.source_url === 'string' && data.source_url.startsWith('https://app.testazo.com/')
    && typeof data.created_at === 'number' && Date.now() >= data.created_at
    && Date.now() - data.created_at < 3600000;
  let pending = null;
  try {
    if (location.hash.startsWith(PREFIX)) {
      const encoded = location.hash.slice(PREFIX.length);
      history.replaceState(null, '', location.pathname + location.search);
      if (encoded.length > 22000) throw new Error('Pregunta demasiado larga.');
      const candidate = JSON.parse(decodeURIComponent(encoded));
      if (!validate(candidate)) throw new Error('La pregunta está incompleta o ha caducado.');
      sessionStorage.setItem(STORAGE, JSON.stringify(candidate));
    }
    const stored = sessionStorage.getItem(STORAGE);
    if (!stored) return;
    pending = JSON.parse(stored);
    if (!validate(pending)) throw new Error('La pregunta ha caducado. Vuelve a Testazo.');
  } catch (err) {
    sessionStorage.removeItem(STORAGE);
    show('No se pudo importar: ' + (err.message || 'datos no válidos'), true);
    return;
  }
  function show(message, error=false) {
    let notice = document.getElementById('mp-import-notice');
    if (!notice) {
      notice = document.createElement('div');
      notice.id = 'mp-import-notice';
      notice.setAttribute('role', 'status');
      notice.style.cssText = 'position:fixed;z-index:2147483647;bottom:20px;left:12px;right:12px;max-width:620px;margin:auto;padding:16px 19px;border-radius:14px;color:white;box-shadow:0 5px 25px #0005;font:600 16px/1.4 system-ui,sans-serif;';
      document.body.appendChild(notice);
    }
    notice.style.background = error ? '#991b1b' : '#0f766e';
    notice.textContent = message;
    return notice;
  }
  if (!document.querySelector('nav a[href="/users"]')) {
    show('Pregunta lista para guardar. Selecciona tu usuario y se importará automáticamente.');
    return;
  }
  const notice = show('Guardando la pregunta en tu usuario…');
  async function save() {
    const form = new FormData();
    form.set('question', pending.question);
    form.set('options_json', JSON.stringify(pending.options));
    form.set('correct_index', String(pending.correct_index));
    try {
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), 90000);
      let res;
      try {
        res = await fetch('/confirm-save', {method: 'POST', body: form, credentials: 'same-origin', signal: ctrl.signal});
      } finally { clearTimeout(timer); }
      if (!res.ok) throw new Error('Error HTTP ' + res.status);
      if (new URL(res.url).pathname === '/users') {
        show('Selecciona un usuario de Mis Preguntas y vuelve a intentarlo.', true);
        return;
      }
      const doc = new DOMParser().parseFromString(await res.text(), 'text/html');
      const flashes = Array.from(doc.querySelectorAll('.flash'));
      const success = flashes.find(el => el.classList.contains('success') && el.textContent.includes('Guardada correctamente'));
      const duplicate = flashes.find(el => el.textContent.includes('ya estaba guardada'));
      if (success || duplicate) {
        sessionStorage.removeItem(STORAGE);
        notice.style.background = '#166534';
        notice.textContent = success ? '✓ Pregunta guardada en Mis Preguntas.' : '✓ Esa pregunta ya estaba guardada.';
        const link = document.createElement('a');
        link.href = '/questions'; link.textContent = ' Ver preguntas';
        link.style.cssText = 'display:inline-block;margin-left:8px;color:white;text-decoration:underline';
        notice.appendChild(link);
        return;
      }
      const err = flashes.find(el => el.classList.contains('error'));
      throw new Error(clean(err?.textContent) || 'Render no confirmó el guardado.');
    } catch(err) {
      show('No se pudo guardar: ' + (err?.message || 'servidor no disponible') + '. Reintenta.', true);
      const retry = document.createElement('button');
      retry.textContent = 'Reintentar guardado';
      retry.style.cssText = 'display:block;background:white;color:#991b1b;border:0;border-radius:8px;margin-top:12px;padding:10px;font-weight:bold';
      retry.onclick = () => {retry.remove(); show('Reintentando…'); void save();};
      notice.appendChild(retry);
    }
  }
  void save();
})()