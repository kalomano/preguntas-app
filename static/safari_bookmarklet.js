(() => {
  const clean = s => String(s || '').replace(/\s+/g, ' ').trim();
  if (location.hostname !== 'app.testazo.com') return alert('Abre primero una pregunta de Testazo.');
  const header = document.querySelector('.question-header .question-text');
  const form = header?.closest('form');
  const labels = Array.from(form?.querySelectorAll('.options-container label.option-label') || []);
  const topicNode = document.querySelector('.question-tema-prefix, [class*="tema-prefix"]')
    || document.querySelector('[data-topic-name], [data-tema], [class*="topic-prefix"]');
  const topicHint = clean(topicNode?.innerText || topicNode?.textContent
    || topicNode?.getAttribute('data-topic-name') || topicNode?.getAttribute('data-tema'));
  const rawQuestion = clean(header?.innerText || header?.textContent);
  const topicPrefix = topicNode && header?.contains(topicNode) ? clean(topicNode.innerText || topicNode.textContent) : '';
  const question = clean(topicPrefix ? rawQuestion.replace(topicPrefix, '') : rawQuestion).replace(/^\d+[.)]\s*/, '');
  if (!question || labels.length !== 4) return alert('Abre un test con una pregunta de cuatro opciones.');
  if (header.querySelector('img, canvas, video, svg') || labels.some(el => el.querySelector('img, canvas, video, svg'))) {
    return alert('Esta pregunta contiene imágenes y no puede importarse con el marcador.');
  }
  const options = labels.map((label, i) => ({
    letter: 'abcd'[i],
    text: clean(label.querySelector(':scope > div')?.children?.[1]?.innerText || label.querySelector(':scope > div')?.children?.[1]?.textContent)
  }));
  const correct = labels.flatMap((label, i) => label.classList.contains('option-label-correct') ? [i] : []);
  if (options.some(o => !o.text)) return alert('No puedo leer una de las cuatro respuestas.');
  if (correct.length !== 1) return alert('Primero responde en modo estudio y espera a ver la respuesta correcta en verde.');
  const payload = {v: 1, question, options, correct_index: correct[0], source_url: location.href, created_at: Date.now()};
  const encoded = encodeURIComponent(JSON.stringify(payload));
  if (encoded.length > 22000) return alert('Esta pregunta es demasiado larga para este método.');
  const url = 'https://preguntas-app.onrender.com/users#mp-import=' + encoded;
  const tab = window.open(url, '_blank');
  if (!tab) {
    let link = document.getElementById('mp-import-fallback');
    if (!link) {
      link = document.createElement('a');
      link.id = 'mp-import-fallback';
      link.target = '_blank';
      link.rel = 'noopener';
      link.textContent = '↗ Toca aquí para guardar la pregunta';
      link.style.cssText = 'position:fixed;z-index:2147483647;bottom:40px;right:14px;background:#0f766e;color:#fff;border-radius:12px;padding:16px;font:700 16px system-ui;box-shadow:0 6px 22px #0005';
      document.body.appendChild(link);
    }
    link.href = url;
    alert('Safari ha bloqueado la nueva pestaña. Pulsa el botón verde que ha aparecido en Testazo.');
  }
})()