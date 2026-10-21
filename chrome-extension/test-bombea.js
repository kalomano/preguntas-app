'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const sent = [];
const listeners = [];
const byId = new Map();
const clickHandlers = new Map();

class Element {
  constructor(text = '') {
    this.textContent = text;
    this.innerHTML = '';
    this.dataset = {};
    this.style = {};
    this.attributes = {};
    this.children = [];
    this.id = '';
    this.className = '';
    this.disabled = false;
    this.previousElementSibling = null;
  }
  setAttribute(name, value) { this.attributes[name] = value; }
  addEventListener(name, fn) {
    if (name === 'click') clickHandlers.set(this.id, fn);
  }
  querySelector(selector) {
    return selector === '.question-tema-prefix' ? topic : null;
  }
  querySelectorAll() { return []; }
  closest() { return null; }
  matches() { return false; }
  insertAdjacentElement(_position, element) {
    element.previousElementSibling = this;
  }
  appendChild(element) {
    this.children.push(element);
    if (element.id) byId.set(element.id, element);
    return element;
  }
  remove() {
    if (this.id) byId.delete(this.id);
  }
}

const topic = new Element('Territorio CAM, Carreteras -');
const question = new Element(
  '1. Territorio CAM, Carreteras - ¿Dónde termina la carretera M-215 según la descripción de la red secundaria?'
);
question.querySelector = selector => selector === '.question-tema-prefix' ? topic : null;

const options = [
  new Element('En Villalbilla'),
  new Element('En Guadalajara'),
  new Element('En Ambite'),
  new Element('En Villarejo de Salvanés')
];
const explanationQuestion = new Element(
  'Según el tema de territorio de la Comunidad de Madrid, ¿Dónde termina la carretera M-215?'
);
const explanationAnswer = new Element('Respuesta correcta: En Guadalajara');
const favoriteButton = new Element();
favoriteButton.id = 'btn-save-question';
favoriteButton.className = 'rounded-button';

const optionsContainer = new Element();
optionsContainer.querySelectorAll = selector =>
  selector.includes('button.option') ? options : [];
const body = new Element();

const document = {
  body,
  documentElement: new Element(),
  querySelector(selector) {
    if (selector === '#btn-save-question') return favoriteButton;
    if (selector.includes('#questionText')) return question;
    if (selector.includes('#optionsContainer')) return optionsContainer;
    if (selector.includes('.question-tema-prefix')) return topic;
    return null;
  },
  querySelectorAll(selector) {
    return selector.includes('#comentariosPopup')
      ? [explanationQuestion, explanationAnswer]
      : [];
  },
  getElementById(id) { return byId.get(id) || null; },
  createElement() { return new Element(); }
};

const chrome = {
  runtime: {
    lastError: null,
    onMessage: { addListener(listener) { listeners.push(listener); } },
    sendMessage(message, callback) {
      sent.push(message);
      if (callback) callback();
    }
  }
};
const location = { href: 'https://bombea.madrid/generador/?test=1' };
const context = {
  document,
  chrome,
  location,
  URL,
  Date,
  Array,
  Set,
  JSON,
  encodeURIComponent,
  MutationObserver: class { observe() {} },
  setTimeout() {}
};

vm.runInNewContext(fs.readFileSync(process.argv[2], 'utf8'), context);

const button = byId.get('mis-preguntas-chrome-save');
assert.ok(button, 'extension save control should be created');
assert.equal(
  button.previousElementSibling,
  favoriteButton,
  'extension control should sit beside Bombea favorite control'
);
assert.ok(
  !String(button.style.cssText || '').includes('position:fixed'),
  'save control should not float over the page'
);

clickHandlers.get(button.id)();
assert.equal(sent.length, 1, 'click should start one import');
const importUrl = new URL(sent[0].url);
assert.equal(importUrl.origin, 'https://preguntas-app.onrender.com');

const payload = JSON.parse(
  decodeURIComponent(importUrl.hash.slice('#mp-import='.length))
);
assert.equal(
  payload.question,
  '¿Dónde termina la carretera M-215 según la descripción de la red secundaria?'
);
assert.deepEqual(
  Array.from(payload.options, option => option.text),
  ['En Villalbilla', 'En Guadalajara', 'En Ambite', 'En Villarejo de Salvanés']
);
assert.equal(payload.correct_index, 1);
assert.equal(
  payload.topic_hint,
  'Tema 08. Territorio y Red de infraestructuras de la CM.pdf'
);
assert.equal(payload.source_url, location.href);

console.log('Bombea capture passed: placement, prompt, four options, correct answer, topic 08 and source URL.');
