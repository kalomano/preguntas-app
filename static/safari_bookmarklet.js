(() => {
  const clean = s => String(s || '').replace(/\s+/g, ' ').trim();
  if (location.hostname !== 'app.testazo.com') return alert('Abre primero una pregunta de Testazo.');
  const header = document.querySelector('.question-header .question-text');
  const form = header?.closest('form');
  const labels = Array.from(form?.querySelectorAll('.options-container label.option-label') || []);
  const question = clean(header?.innerText || header?.textContent).replace(/^\d+[.)]\s*/, '');
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
  if (!tab) alert('Safari ha bloqueado la pestaña nueva. Permite ventanas emergentes y vuelve a pulsar Guardar pregunta.');
})()