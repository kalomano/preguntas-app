'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const sent = [];
const byId = new Map();
const handlers = new Map();

class Element {
  constructor(text = '', className = '') {
    this.textContent = text;
    this.innerHTML = '';
    this.dataset = {};
    this.style = {};
    this.attributes = {};
    this.children = [];
    this.id = '';
    this.className = className;
    this.disabled = false;
    this.previousElementSibling = null;
  }
  setAttribute(name, value) { this.attributes[name] = value; }
  addEventListener(name, fn) {
    if (name === 'click') handlers.set(this.id, fn);
  }
  querySelector() { return null; }
  querySelectorAll(selector) {
    return selector.includes('button.option') ? options : [];
  }
  closest() { return null; }
  matches(selector) {
    return selector.includes('.option-label-correct')
      && this.className.split(/\\s+/).includes('option-label-correct');
  }
  insertAdjacentElement(_position, element) { element.previousElementSibling = this; }
  appendChild(element) {
    this.children.push(element);
    if (element.id) byId.set(element.id, element);
    return element;
  }
  remove() { if (this.id) byId.delete(this.id); }
}

const topic = new Element('24. Construcción II');
const question = new Element(
  '1. Respecto a las herramientas utilizadas para labores de BREC, ¿qué tipo de herramienta es la amoladora?'
);
const options = [
  new Element('a) Neumática', 'option-label'),
  new Element('b) De impacto', 'option-label'),
  new Element('c) Eléctrica, con cable o inalámbrica con batería', 'option-label option-label-correct'),
  new Element('d) Hidráulica', 'option-label')
];
const optionsContainer = new Element();
optionsContainer.querySelectorAll = selector =>
  selector.includes('label.option-label') ? options : [];
const reportButton = new Element('', 'btn-report-icon px-6 py-2');
const body = new Element();

const document = {
  body,
  documentElement: new Element(),
  querySelector(selector) {
    if (selector === '#btn-save-question') return null;
    if (selector === '.report-wrapper button') return reportButton;
    if (selector.includes('#questionText')) return question;
    if (selector.includes('#optionsContainer')) return optionsContainer;
    if (selector.includes('.question-tema-prefix')) return topic;
    return null;
  },
  querySelectorAll() { return []; },
  getElementById(id) { return byId.get(id) || null; },
  createElement() { return new Element(); }
};

const chrome = {
  runtime: {
    lastError: null,
    onMessage: { addListener() {} },
    sendMessage(message, callback) {
      sent.push(message);
      if (callback) callback();
    }
  }
};
const location = {
  href: 'https://app.testazo.com/app/tests/123',
  hostname: 'app.testazo.com'
};
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
assert.ok(button, 'extension save control should be created on Testazo');
assert.equal(button.previousElementSibling, reportButton, 'control should sit beside the report button');
const buttonStyle = String(button.style.cssText || '');
assert.match(buttonStyle, /display:inline-flex!important/, 'save control should stay visible inline');
assert.match(buttonStyle, /width:38px!important/, 'Testazo control should be compact');
assert.match(button.innerHTML, /<svg/, 'Testazo control should use a bookmark icon');
assert.ok(!button.textContent, 'Testazo control should not show an oversized text label');
assert.ok(!buttonStyle.includes('position:fixed'), 'save control should not float over the page');

handlers.get(button.id)();
assert.equal(sent.length, 1, 'click should start one import');
const importUrl = new URL(sent[0].url);
const payload = JSON.parse(decodeURIComponent(importUrl.hash.slice('#mp-import='.length)));
assert.match(payload.question, /amoladora/);
assert.deepEqual(Array.from(payload.options, option => option.text), [
  'Neumática',
  'De impacto',
  'Eléctrica, con cable o inalámbrica con batería',
  'Hidráulica'
]);
assert.equal(payload.correct_index, 2);
assert.equal(payload.topic_hint, '24. Construcción II');
assert.equal(payload.source_url, location.href);

console.log('Testazo capture passed: compact inline control, prompt, options, correct answer, topic and source URL.');
