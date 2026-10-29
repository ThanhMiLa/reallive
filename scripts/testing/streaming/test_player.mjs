import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('./player.js', import.meta.url), 'utf8');

function player() {
  const backendSession = '11111111-1111-4111-8111-111111111111';
  const fixtureSession = '22222222-2222-4222-8222-222222222222';
  const elements = new Map();
  const observations = [];
  const requests = [];
  function element(id) {
    if (!elements.has(id)) elements.set(id, {
      value: '', checked: false, handlers: {}, readyState: 0,
      addEventListener(event, handler) { this.handlers[event] = handler; },
      pause() {}, load() {}, removeAttribute() {},
    });
    return elements.get(id);
  }
  class Hls {
    static isSupported() { return true; }
    static Events = { MANIFEST_LOADED: 'manifest', LEVEL_LOADED: 'level', FRAG_LOADED: 'fragment',
      ERROR: 'error', MANIFEST_PARSED: 'parsed' };
    on() {} destroy() {} attachMedia() {} loadSource() {}
  }
  const context = vm.createContext({
    document: { getElementById: element, hidden: false },
    window: { Hls, addEventListener() {} }, Hls, URL, URLSearchParams,
    setInterval() { return 1; }, clearInterval() {},
    async fetch(path, options) {
      requests.push(path);
      let value = {};
      if (path.startsWith('/fixture/status')) value = { sessionId: fixtureSession };
      if (path === '/fixture/backend-access') value = {
        sessionId: backendSession, path: `live/${backendSession}`,
        playbackUrl: `http://localhost:8898/live/${backendSession}/index.m3u8`, readToken: 'test-only',
      };
      if (path === '/fixture/credentials') value = {
        path: `live/${fixtureSession}`,
        playbackUrl: `http://localhost:8898/live/${fixtureSession}/index.m3u8`, readToken: 'test-only',
      };
      if (path === '/fixture/observation') observations.push(JSON.parse(options.body));
      return { ok: true, json: async () => value };
    },
  });
  vm.runInContext(source, context);
  element('backend-session-id').value = backendSession;
  return { element, observations, requests, backendSession, fixtureSession };
}

test('backend evidence stays bound to granted session when inputs change before saving', async () => {
  const fixture = player();
  fixture.element('access-source').value = 'backend';
  await fixture.element('play').handlers.click();
  fixture.element('backend-session-id').value = fixture.fixtureSession;
  fixture.element('access-source').value = 'fixture';
  await fixture.element('record-browser').handlers.click();
  assert.equal(fixture.observations[0].sessionId, fixture.backendSession);
  assert.equal(fixture.observations[0].accessSource, 'backend');
});

test('fixture evidence uses the session in the granted media path', async () => {
  const fixture = player();
  fixture.element('access-source').value = 'fixture';
  fixture.element('read-case').value = 'valid';
  await fixture.element('play').handlers.click();
  await fixture.element('record-browser').handlers.click();
  assert.equal(fixture.observations[0].sessionId, fixture.fixtureSession);
  assert.equal(fixture.observations[0].accessSource, 'fixture');
});

test('OBS evidence and acceptance select the backend session before opening the player', async () => {
  const fixture = player();
  fixture.element('access-source').value = 'backend';
  await fixture.element('obs-publish').handlers.click();
  assert.equal(fixture.observations[0].sessionId, fixture.backendSession);
  assert.equal(fixture.observations[0].accessSource, 'backend');
  const route = fixture.requests.find(path => path.startsWith('/fixture/acceptance?accessSource=backend'));
  assert.equal(new URL(route, 'http://localhost').searchParams.get('sessionId'), fixture.backendSession);
  assert.ok(fixture.requests.some(path => path.startsWith('/fixture/status?accessSource=backend')));
});

test('legacy OBS evidence uses the fixture session', async () => {
  const fixture = player();
  fixture.element('access-source').value = 'fixture';
  await fixture.element('obs-publish').handlers.click();
  assert.equal(fixture.observations[0].sessionId, fixture.fixtureSession);
  assert.equal(fixture.observations[0].accessSource, 'fixture');
});

test('backend mode disables legacy path and key actions', async () => {
  const fixture = player();
  fixture.element('access-source').value = 'backend';
  await fixture.element('access-source').handlers.change();
  for (const id of ['create', 'delete', 'copy-server', 'copy-key']) {
    assert.equal(fixture.element(id).disabled, true);
  }
  fixture.element('access-source').value = 'fixture';
  await fixture.element('access-source').handlers.change();
  assert.equal(fixture.element('delete').disabled, false);
});
