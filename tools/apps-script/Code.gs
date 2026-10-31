// Copyright (C) 2026 BBNSS. GPL-3.0-only.
// No report contents, addresses, or exceptions are logged or stored in Properties.
const REPORT_RECIPIENT = 'lumieitalia@gmail.com';
const REPORT_SUBJECT = '[SuperMens] Segnalazione risposta AI';
const REPORT_MAX_TEXT_BYTES = 16000;
const REPORT_MAX_REQUEST_BYTES = 32768;
const REPORT_MAX_EMAILS = 80;
const REPORT_WINDOW_MS = 24 * 60 * 60 * 1000;
const REPORT_COUNTER_KEY = 'report_email_window_v1';
const REPORT_DIAGNOSTICS = {
  app_version: 'string', version_code: 'number', manufacturer: 'string',
  model: 'string', soc: 'string', android_sdk: 'number',
  ram_total_bytes: 'number', ram_available_bytes: 'number', model_label: 'string',
  model_ready: 'boolean', backend: 'string', vision: 'boolean', load_ms: 'number',
  generation_ms: 'number', first_token_ms: 'number', output_chars: 'number',
  thermal_status: 'number', foreground_failed: 'boolean', device_certified: 'boolean'
};

function reportJson_(value) {
  return ContentService.createTextOutput(JSON.stringify(Object.assign({version: 1}, value)))
    .setMimeType(ContentService.MimeType.JSON);
}

function reportBytes_(text) {
  return Utilities.newBlob(text).getBytes().length;
}

function reportObject_(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function validateReport_(event) {
  if (!event || event.queryString || event.pathInfo || !event.postData ||
      !/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(event.postData.type || '') ||
      typeof event.postData.contents !== 'string' ||
      event.contentLength > REPORT_MAX_REQUEST_BYTES ||
      event.postData.length > REPORT_MAX_REQUEST_BYTES) throw new Error('invalid_report');
  const raw = event.postData.contents;
  if (!raw.length || raw.length > REPORT_MAX_REQUEST_BYTES ||
      reportBytes_(raw) > REPORT_MAX_REQUEST_BYTES) throw new Error('invalid_report');
  const value = JSON.parse(raw);
  const fields = ['version', 'reason', 'response', 'app_version', 'diagnostics'];
  if (!reportObject_(value) || Object.keys(value).some(key => !fields.includes(key)) ||
      value.version !== 1 || !['child_safety', 'harmful', 'deceptive', 'other'].includes(value.reason) ||
      typeof value.response !== 'string' || !value.response.trim() ||
      reportBytes_(value.response) > REPORT_MAX_TEXT_BYTES ||
      typeof value.app_version !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9.+_-]{0,79}$/.test(value.app_version)) {
    throw new Error('invalid_report');
  }
  if (Object.prototype.hasOwnProperty.call(value, 'diagnostics')) {
    if (!reportObject_(value.diagnostics)) throw new Error('invalid_report');
    Object.keys(value.diagnostics).forEach(key => {
      const item = value.diagnostics[key];
      if (!Object.prototype.hasOwnProperty.call(REPORT_DIAGNOSTICS, key) ||
          typeof item !== REPORT_DIAGNOSTICS[key] ||
          (typeof item === 'string' && item.length > 200) ||
          (typeof item === 'number' && !Number.isSafeInteger(item))) throw new Error('invalid_report');
    });
  }
  return value;
}

function reportEmailBody_(value) {
  let body = 'SuperMens — segnalazione volontaria di una risposta AI\n' +
    'Motivo: ' + value.reason + '\nVersione app: ' + value.app_version +
    '\n\nRisposta selezionata e modificabile dall’utente:\n' + value.response;
  if (value.diagnostics) body += '\n\nDiagnostica facoltativa visualizzata e autorizzata:\n' +
    JSON.stringify(value.diagnostics, null, 2);
  return body + '\n\nGestione: eliminare tutte le copie sotto il controllo del titolare entro 30 giorni, compreso il cestino.\n';
}

function doPost(event) {
  let value;
  try {
    value = validateReport_(event);
  } catch (_) {
    return reportJson_({ok: false, error: 'invalid_report'});
  }
  let lock;
  let locked = false;
  try {
    lock = LockService.getScriptLock();
    locked = lock.tryLock(1000);
    if (!locked) return reportJson_({ok: false, error: 'busy'});
    const now = Date.now();
    const properties = PropertiesService.getScriptProperties();
    const stored = properties.getProperty(REPORT_COUNTER_KEY);
    let counter = stored === null ? {start: now, count: 0} : JSON.parse(stored);
    if (!reportObject_(counter) || !Number.isSafeInteger(counter.start) || counter.start < 0 ||
        counter.start > now || !Number.isSafeInteger(counter.count) || counter.count < 0) {
      throw new Error('invalid_counter');
    }
    if (now - counter.start >= REPORT_WINDOW_MS) counter = {start: now, count: 0};
    const remaining = MailApp.getRemainingDailyQuota();
    if (counter.count >= REPORT_MAX_EMAILS || !Number.isFinite(remaining) || remaining < 1) {
      return reportJson_({ok: false, error: 'quota_exceeded'});
    }
    // Reserve before sending, under the same lock. A failed/uncertain send consumes
    // a slot too: errors or execution termination must never allow excess email.
    properties.setProperty(REPORT_COUNTER_KEY, JSON.stringify({start: counter.start, count: counter.count + 1}));
    try {
      MailApp.sendEmail({to: REPORT_RECIPIENT, subject: REPORT_SUBJECT, body: reportEmailBody_(value)});
    } catch (_) {
      return reportJson_({ok: false, error: 'email_failed'});
    }
    // Success means MailApp accepted the send; mailbox delivery is checked by the owner.
    return reportJson_({ok: true});
  } catch (_) {
    return reportJson_({ok: false, error: 'unavailable'});
  } finally {
    if (locked) {
      try { lock.releaseLock(); } catch (_) { /* Never log raw errors. */ }
    }
  }
}

function doGet() {
  return reportJson_({ok: false, error: 'method_not_allowed'});
}

// Run once in the owner's editor to authorize send_mail, without sending email.
function authorizeReportService() {
  return MailApp.getRemainingDailyQuota();
}
