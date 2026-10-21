(() => {
  'use strict';

  const BUTTON_ID = 'mis-preguntas-chrome-save';
  const APP_URL = 'https://preguntas-app.onrender.com/';
  const MAX_IMPORT_LENGTH = 21000;

  const clean = value => String(value || '').replace(/\s+/g, ' ').trim();
  const text = node => clean(node?.textContent);
  const TOPIC_ALIASES = [
    [/^decreto legislativo 1\s*\/\s*2006\b/i, 'Tema 04. DL 1 2006.pdf'],
    [/^dl\s*1\s*\/\s*2006\b/i, 'Tema 04. DL 1 2006.pdf'],
    [/^soporte vital b[aá]sico\s*2\b/i, 'Tema 13. SVB II.pdf'],
    [/^svb\s*ii\b/i, 'Tema 13. SVB II.pdf'],
    [/^svb\s*i\b/i, 'Tema 12. SVB I (trampeado de Cecop).pdf']
  ];

  function topicFrom(raw) {
    const value = clean(raw).replace(/[–—-]+\s*$/, '').trim();
    if (!value) return '';
    for (const [pattern, label] of TOPIC_ALIASES) {
      if (pattern.test(value)) return label;
    }
    return value.slice(0, 300);
  }

  function extractTopic(questionNode) {
    const explicit = document.querySelector(
      '.question-tema-prefix, [data-topic-name], [data-tema], [class*="tema-prefix"], [class*="topic-prefix"]'
    );
    if (explicit) return topicFrom(text(explicit));
    const q = text(questionNode);
    const prefix = q.match(/^(.{3,180}?)\s+(?:-|–|—)\s+/);
    return prefix ? topicFrom(prefix[1]) : '';
  }

  function questionNode() {
    return document.querySelector(
      '#questionText, [data-question-text], .question-text, .question.title-md, [class~="pregunta"]'
    );
  }

  function optionNodes() {
    const container = document.querySelector('#optionsContainer, [data-options], .options');
    if (!container) return [];
    let nodes = Array.from(container.querySelectorAll(
      'button.option, [role="option"], .option'
    ));
    if (nodes.length !== 4) {
      nodes = Array.from(container.querySelectorAll('.texto-opcion, [data-option]'));
    }
    nodes = nodes.map(node => node.closest('.option') || node);
    return Array.from(new Set(nodes)).filter(node => text(node)).slice(0, 4);
  }

  function correctAnswerText() {
    const nodes = Array.from(document.querySelectorAll(
      '#moreOptionsPopup p, [class*="respuesta"], [data-correct-answer]'
    ));
    for (const node of nodes) {
      const value = text(node);
      const match = value.match(/respuesta correcta\s*:\s*(.+)$/i);
      if (match) return clean(match[1]);
      if (node.matches('[data-correct-answer]')) {
        return clean(node.getAttribute('data-correct-answer'));
      }
    }
    return '';
  }

  function extract() {
    const question = questionNode();
    const candidates = optionNodes();
    if (!question || candidates.length !== 4) {
      throw new Error('No pude identificar la pregunta y sus cuatro respuestas.');
    }

    let texts = candidates.map(text);
    const prefixNode = question.querySelector('.question-tema-prefix');
    let prompt = text(question);
    if (prefixNode) prompt = clean(prompt.slice(text(prefixNode).length));
    if (!prompt) throw new Error('La pregunta aparece vacía.');

    const right = candidates.findIndex(node =>
      node.matches('.correct, .is-correct, [data-correct="true"], [aria-label*="correcta" i]')
    );
    let correctIndex = right;
    if (correctIndex < 0) {
      const answer = correctAnswerText();
      if (answer) {
        correctIndex = texts.findIndex(option =>
          clean(option).toLocaleLowerCase('es') === answer.toLocaleLowerCase('es')
        );
      }
    }
    if (correctIndex < 0) {
      throw new Error('No encuentro la respuesta correcta. Corrige o revela la pregunta y vuelve a pulsar.');
    }

    return {
      v: 1,
      question: prompt,
      options: texts.map((value, index) => ({
        letter: 'abcd'[index],
        text: value
      })),
      correct_index: correctIndex,
      source_url: location.href,
      topic_hint: extractTopic(question),
      created_at: Date.now()
    };
  }

  function notify(message, error = false) {
    let box = document.getElementById(BUTTON_ID + '-status');
    if (!box) {
      box = document.createElement('div');
      box.id = BUTTON_ID + '-status';
      box.setAttribute('role', 'status');
      box.style.cssText = 'position:fixed;z-index:2147483647;right:18px;bottom:76px;max-width:360px;padding:12px 16px;border-radius:10px;color:#fff;background:#166534;box-shadow:0 4px 18px #0004;font:600 14px/1.4 system-ui,sans-serif;';
      document.body.appendChild(box);
    }
    box.style.background = error ? '#991b1b' : '#166534';
    box.textContent = message;
    setTimeout(() => box.remove(), 6500);
  }

  function addButton() {
    if (!document.body || document.getElementById(BUTTON_ID)) return;
    const button = document.createElement('button');
    button.id = BUTTON_ID;
    button.type = 'button';
    button.textContent = 'Guardar en Mis Preguntas';
    button.title = 'Guarda esta pregunta corregida, su tema y enlace';
    button.setAttribute('aria-label', 'Guardar pregunta de Testazo o Bombea en Mis Preguntas');
    button.style.cssText = 'position:fixed;z-index:2147483646;right:18px;bottom:18px;padding:12px 18px;border:0;border-radius:12px;background:#0f766e;color:white;font:700 15px system-ui,sans-serif;box-shadow:0 4px 18px #0004;cursor:pointer;';
    button.addEventListener('click', () => {
      try {
        const data = extract();
        const encoded = encodeURIComponent(JSON.stringify(data));
        if (encoded.length > MAX_IMPORT_LENGTH) {
          throw new Error('Esta pregunta es demasiado larga para importarla.');
        }
        chrome.runtime.sendMessage({
          type: 'open-import',
          url: APP_URL + '#mp-import=' + encoded
        });
        notify(data.topic_hint
          ? 'Abriendo Mis Preguntas · ' + data.topic_hint
          : 'Abriendo Mis Preguntas · Sin tema detectado');
      } catch (error) {
        notify(error.message || 'No se pudo preparar la pregunta.', true);
      }
    });
    document.body.appendChild(button);
  }

  addButton();
  new MutationObserver(addButton).observe(document.documentElement, {
    childList: true,
    subtree: true
  });
})();