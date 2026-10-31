// Run with: node --test tools/apps-script/report_service.test.cjs
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, 'Code.gs'), 'utf8');
const COUNTER = 'report_email_window_v1';

function service() {
  const state = {now: 1000000000, quota: 100, properties: {}, mails: [], reservations: [], locked: false, releases: 0};
  const sandbox = {
    Date: {now: () => state.now},
    Utilities: {newBlob: value => ({getBytes: () => Buffer.from(value, 'utf8')})},
    ContentService: {MimeType: {JSON: 'application/json'}, createTextOutput: text => ({
      setMimeType(type) {assert.equal(type, 'application/json'); return JSON.parse(text);}
    })},
    PropertiesService: {getScriptProperties: () => ({
      getProperty: key => state.properties[key] ?? null,
      setProperty(key, value) {
        assert.equal(state.locked, true);
        if (state.propertyError) throw Error('private property failure');
        state.properties[key] = value;
        state.reservations.push(JSON.parse(value));
      }
    })},
    LockService: {getScriptLock: () => ({
      tryLock() {if (state.busy) return false; assert.equal(state.locked, false); state.locked = true; return true;},
      releaseLock() {state.locked = false; state.releases++;}
    })},
    MailApp: {
      getRemainingDailyQuota() {if (state.quotaError) throw Error('private quota failure'); return state.quota;},
      sendEmail(message) {
        assert.equal(state.locked, true);
        assert.ok(JSON.parse(state.properties[COUNTER]).count > 0);
        assert.equal(JSON.parse(state.properties[COUNTER]).count, state.reservations.at(-1).count);
        if (state.mailError) throw Error('private message failure');
        state.mails.push(JSON.parse(JSON.stringify(message)));
      }
    },
    Logger: {log() {assert.fail('No logging allowed');}},
    console: {log() {assert.fail('No logging allowed');}, error() {assert.fail('No logging allowed');}}
  };
  vm.createContext(sandbox); vm.runInContext(source, sandbox);
  return {state, post: event => sandbox.doPost(event), get: () => sandbox.doGet()};
}
const valid = () => ({version: 1, reason: 'harmful', response: 'Synthetic test only.', app_version: '0.3.0-beta3'});
function event(value = valid(), raw = JSON.stringify(value)) {
  return {contentLength: Buffer.byteLength(raw), postData: {type: 'application/json; charset=utf-8', length: Buffer.byteLength(raw), contents: raw}};
}
function failure(result, error) {assert.deepEqual(result, {version: 1, ok: false, error});}

test('valid reports send plain text only to fixed recipient/subject; diagnostics are optional', () => {
  const app = service();
  assert.deepEqual(app.post(event()), {version: 1, ok: true});
  const mail = app.state.mails[0];
  assert.deepEqual(Object.keys(mail).sort(), ['body', 'subject', 'to']);
  assert.equal(mail.to, 'lumieitalia@gmail.com');
  assert.equal(mail.subject, '[SuperMens] Segnalazione risposta AI');
  assert.ok(mail.body.includes('Synthetic test only.'));
  assert.ok(mail.body.includes('0.3.0-beta3'));
  assert.ok(!mail.body.includes('Diagnostica facoltativa'));
  const report = {...valid(), reason: 'child_safety', diagnostics: {android_sdk: 35, model: 'Synthetic', model_ready: false, load_ms: -1}};
  assert.equal(app.post(event(report)).ok, true);
  assert.ok(app.state.mails[1].body.includes('"model": "Synthetic"'));
  assert.equal(app.state.releases, 2);
  assert.deepEqual(Object.keys(app.state.properties), [COUNTER]);
  assert.ok(!app.state.properties[COUNTER].includes('Synthetic'));
});

test('invalid, missing and unexpected fields cannot send mail or reserve quota', () => {
  const app = service();
  const cases = [null, [], {}, {...valid(), version: '1'}, {...valid(), reason: 'arbitrary'},
    {...valid(), response: '  '}, {...valid(), response: 42}, {...valid(), app_version: 'x\r\nBcc: other@example.com'},
    {...valid(), app_version: ''}, {...valid(), app_version: 'a'.repeat(81)},
    {...valid(), prompt: 'private'}, {...valid(), archive: []}, {...valid(), url: 'https://example.com'},
    {...valid(), to: 'other@example.com'}, {...valid(), subject: 'other'}, {...valid(), diagnostics: null},
    {...valid(), diagnostics: []}, {...valid(), diagnostics: {android_id: 'private'}},
    {...valid(), diagnostics: {model: {nested: 'private'}}}, {...valid(), diagnostics: {model: 'x'.repeat(201)}},
    {...valid(), diagnostics: {android_sdk: '35'}}, {...valid(), diagnostics: {android_sdk: 35.5}},
    {...valid(), diagnostics: {model_ready: 1}}, {...valid(), diagnostics: {constructor: 'unexpected'}}];
  for (const value of cases) failure(app.post(event(value)), 'invalid_report');
  failure(app.post(event(null, '{broken JSON')), 'invalid_report');
  failure(app.post(undefined), 'invalid_report');
  failure(app.post({...event(), queryString: 'callback=bad'}), 'invalid_report');
  failure(app.post({...event(), pathInfo: 'extra'}), 'invalid_report');
  const wrongType = event(); wrongType.postData.type = 'text/plain';
  failure(app.post(wrongType), 'invalid_report');
  assert.equal(app.state.mails.length, 0); assert.equal(app.state.reservations.length, 0);
});

test('text limits count UTF-8 bytes and request limits include JSON escaping', () => {
  const app = service();
  assert.equal(app.post(event({...valid(), response: 'x'.repeat(16000)})).ok, true);
  assert.equal(app.post(event({...valid(), response: '😀'.repeat(4000)})).ok, true);
  failure(app.post(event({...valid(), response: 'x'.repeat(16001)})), 'invalid_report');
  failure(app.post(event({...valid(), response: '😀'.repeat(4001)})), 'invalid_report');
  failure(app.post(event({...valid(), response: '\u0000'.repeat(6000)})), 'invalid_report');
  const base = JSON.stringify(valid());
  const exact = base + ' '.repeat(32768 - Buffer.byteLength(base));
  assert.equal(app.post(event(null, exact)).ok, true);
  failure(app.post(event(null, exact + ' ')), 'invalid_report');
  const lied = event(null, exact + ' '); lied.contentLength = 1; lied.postData.length = 1;
  failure(app.post(lied), 'invalid_report');
  const declared = event(); declared.contentLength = 32769;
  failure(app.post(declared), 'invalid_report');
});

test('persistent cap is 80 reservations in a 24-hour window, independent of calendar midnight', () => {
  const app = service();
  for (let n = 0; n < 80; n++) assert.equal(app.post(event()).ok, true);
  failure(app.post(event()), 'quota_exceeded'); assert.equal(app.state.mails.length, 80);
  app.state.now += 24 * 3600 * 1000 - 1;
  failure(app.post(event()), 'quota_exceeded');
  app.state.now++;
  assert.equal(app.post(event()).ok, true);
  assert.equal(JSON.parse(app.state.properties[COUNTER]).count, 1);
});

test('Google quota, busy service and mail failures reject explicitly, without automatic retries', () => {
  const app = service(); app.state.quota = 0;
  failure(app.post(event()), 'quota_exceeded'); assert.equal(app.state.reservations.length, 0);
  app.state.quota = 100; app.state.busy = true;
  failure(app.post(event()), 'busy'); assert.equal(app.state.reservations.length, 0);
  app.state.busy = false; app.state.mailError = true;
  failure(app.post(event()), 'email_failed'); assert.equal(app.state.reservations.length, 1);
  assert.equal(app.state.mails.length, 0); assert.equal(app.state.locked, false);
  app.state.mailError = false;
  assert.equal(app.post(event()).ok, true);
  assert.equal(JSON.parse(app.state.properties[COUNTER]).count, 2);
});

test('damaged quota storage or unavailable Google services fail closed and release the lock', () => {
  for (const stored of ['broken', 'null', '{}', '{"start":1000000001,"count":0}', '{"start":1,"count":-1}']) {
    const app = service(); app.state.properties[COUNTER] = stored;
    failure(app.post(event()), 'unavailable'); assert.equal(app.state.mails.length, 0); assert.equal(app.state.locked, false);
  }
  for (const fault of ['quotaError', 'propertyError']) {
    const app = service(); app.state[fault] = true;
    failure(app.post(event()), 'unavailable'); assert.equal(app.state.mails.length, 0); assert.equal(app.state.locked, false);
  }
});

test('GET never sends; manifest grants only mail sending, without inbox access', () => {
  const app = service(); failure(app.get(), 'method_not_allowed'); assert.equal(app.state.mails.length, 0);
  const manifest = JSON.parse(fs.readFileSync(path.join(__dirname, 'appsscript.json'), 'utf8'));
  assert.deepEqual(manifest.oauthScopes, ['https://www.googleapis.com/auth/script.send_mail']);
  assert.equal(manifest.exceptionLogging, 'NONE');
  assert.deepEqual(manifest.webapp, {executeAs: 'USER_DEPLOYING', access: 'ANYONE_ANONYMOUS'});
});
