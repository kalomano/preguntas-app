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
    [/^territorio\s+cam(?:\s*,\s*carreteras)?\b/i, 'Tema 08. Territorio y Red de infraestructuras de la CM.pdf'],
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
      '.question-tema-prefix, [data-topic-name], [data-tema], [class*="tema-prefix"], [class*="topic-prefix"], .header-title'
    );
    if (explicit) return topicFrom(text(explicit));
    const q = text(questionNode);
    const prefix = q.match(/^(.{3,180}?)\s+(?:-|–|—)\s+/);
    return prefix ? topicFrom(prefix[1]) : '';
  }

  function questionNode() {
    return document.querySelector(
      '#questionText, [data-question-text], .question-header .question-text, .question-text, .question.title-md, [class~="pregunta"]'
    );
  }

  function optionNodes() {
    const container = document.querySelector('#optionsContainer, [data-options], .options, .options-container');
    if (!container) return [];
    let nodes = Array.from(container.querySelectorAll(
      'button.option, [role="option"], .option, label.option-label'
    ));
    if (nodes.length !== 4) {
      nodes = Array.from(container.querySelectorAll('.texto-opcion, [data-option]'));
    }
    nodes = nodes.map(node => node.closest('.option') || node);
    return Array.from(new Set(nodes)).filter(node => text(node)).slice(0, 4);
  }

  function correctAnswerText() {
    const nodes = Array.from(document.querySelectorAll(
      '#moreOptionsPopup p, #comentariosPopup .explicacion-pregunta-body p.fuente, [class*="respuesta"], [data-correct-answer]'
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

    let texts = candidates.map(node => text(node).replace(/^[a-d][).]\s*/i, ''));
    const prefixNode = question.querySelector('.question-tema-prefix');
    let prompt = text(question).replace(/^\d+\.\s*/, '');
    if (prefixNode) prompt = clean(prompt.slice(text(prefixNode).length));
    if (!prompt) throw new Error('La pregunta aparece vacía.');

    const right = candidates.findIndex(node =>
      node.matches('.correct, .is-correct, .option-label-correct, [data-correct="true"], [aria-label*="correcta" i]')
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
      box.style.cssText = 'position:fixed;z-index:2147483647;right:18px;bottom:18px;max-width:360px;padding:10px 14px;border-radius:10px;color:#fff;background:#166534;box-shadow:0 4px 18px #0004;font:600 14px/1.4 system-ui,sans-serif;';
      document.body.appendChild(box);
    }
    box.style.background = error ? '#991b1b' : '#166534';
    box.textContent = message;
    setTimeout(() => box.remove(), 5000);
  }

  function saveAnchor() {
    return document.querySelector('#btn-save-question')
      || document.querySelector('.report-wrapper button')
      || document.querySelector('.navigation-container .btn-report-icon');
  }

  function addButton() {
    const anchor = saveAnchor();
    if (!document.body || !anchor) return;

    let button = document.getElementById(BUTTON_ID);
    if (!button) {
      button = document.createElement('button');
      button.id = BUTTON_ID;
      button.type = 'button';
      button.title = 'Guardar esta pregunta en Mis Preguntas';
      button.setAttribute('aria-label', 'Guardar pregunta en Mis Preguntas');
      const isBombeaControl = anchor.id === 'btn-save-question';
      button.className = anchor.className;
      button.innerHTML = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 4.75A1.75 1.75 0 0 1 7.75 3h8.5A1.75 1.75 0 0 1 18 4.75V21l-6-3.5L6 21z"></path><path d="M12 7v6m-3-3h6"></path></svg>';
      button.style.cssText = isBombeaControl
        ? 'display:inline-flex!important;align-items:center!important;justify-content:center!important;width:44px!important;min-width:44px!important;height:44px!important;flex:0 0 44px!important;padding:0!important;margin:0 0 0 8px!important;position:relative!important;visibility:visible!important;opacity:1!important;cursor:pointer!important;z-index:2!important;box-sizing:border-box!important;'
        : 'display:inline-flex!important;align-items:center!important;justify-content:center!important;width:38px!important;min-width:38px!important;height:38px!important;flex:0 0 38px!important;padding:0!important;margin:0 0 0 8px!important;border:1px solid #e5e7eb!important;border-radius:8px!important;background:#f5f5f7!important;color:#4b5563!important;position:relative!important;visibility:visible!important;opacity:1!important;cursor:pointer!important;z-index:2!important;box-sizing:border-box!important;';

      button.dataset.originalHtml = button.innerHTML;
      button.dataset.originalText = button.textContent;
      button.addEventListener('click', () => {
        try {
          const data = extract();
          const encoded = encodeURIComponent(JSON.stringify(data));
          if (encoded.length > MAX_IMPORT_LENGTH) {
            throw new Error('Esta pregunta es demasiado larga para importarla.');
          }
          button.disabled = true;
          button.dataset.originalTitle = button.title;
          button.title = 'Guardando en Mis Preguntas…';
          chrome.runtime.sendMessage({
            type: 'open-import',
            url: APP_URL + '#mp-import=' + encoded
          }, () => {
            if (chrome.runtime.lastError) {
              button.disabled = false;
              notify('No se pudo iniciar el guardado.', true);
            }
          });
          notify('Guardando en Mis Preguntas…');
        } catch (error) {
          notify(error.message || 'No se pudo preparar la pregunta.', true);
        }
      });
      document.body.appendChild(button);
    }

    if (button.previousElementSibling !== anchor) {
      anchor.insertAdjacentElement('afterend', button);
    }
  }

  chrome.runtime.onMessage.addListener(message => {
    if (message?.type !== 'save-result') return;
    const button = document.getElementById(BUTTON_ID);
    if (!button) return;
    button.disabled = false;
    button.title = button.dataset.originalTitle || 'Guardar esta pregunta en Mis Preguntas';
    if (message.success) {
      button.innerHTML = '✓';
      button.setAttribute('aria-label', 'Pregunta guardada en Mis Preguntas');
      setTimeout(() => {
        button.innerHTML = button.dataset.originalHtml;
        button.setAttribute('aria-label', 'Guardar pregunta en Mis Preguntas');
      }, 2500);
    } else {
      notify('No se pudo guardar. Revisa la pestaña de Mis Preguntas.', true);
    }
  });

  addButton();
  new MutationObserver(addButton).observe(document.documentElement, {
    childList: true,
    subtree: true
  });
})();