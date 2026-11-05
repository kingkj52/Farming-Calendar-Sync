/**
 * Farming Calendar Sync: the Google Apps Script half.
 *
 * The RuneLite plugin posts a list of "make this entry" / "remove this entry" operations to this web app.
 * The script runs as YOU, so it can put entries on your own Google Calendar. Everything is idempotent: every
 * entry has an id derived from the patch, so sending the same thing twice changes nothing, and replanting a
 * patch moves its one entry instead of adding another.
 *
 * Uses the Calendar *advanced service* (Calendar API v3), not the simpler CalendarApp, because CalendarApp
 * cannot set an alert at exactly 0 minutes (its minimum is 5), and an on-time alert is the whole point.
 *
 * Setup steps are in SETUP.md next to this folder. In short: add the Google Calendar API service (v3, name "Calendar"),
 * put your own secret in TOKEN below and the same one in the plugin, run authorize() once, then deploy as a web app
 * (Execute as: Me, Who has access: Anyone). After later edits, deploy a New version.
 */

// The shared secret. Anyone who knows the web app address AND this can add entries to your calendar, so make it long.
const TOKEN = 'put-a-long-random-secret-here';

// Event colour (Google Calendar colour id 1-11; 10 is green). Use '' for the calendar's own colour.
const COLOR_ID = '10';

function doGet() {
  return json_({ status: 'ok', message: 'Farming Calendar Sync is running. The RuneLite plugin talks to this address.' });
}

function doPost(e) {
  const lock = LockService.getScriptLock();
  let locked = false;
  try {
    lock.waitLock(30000);
    locked = true;

    let msg;
    try {
      msg = JSON.parse(e.postData.contents);
    } catch (err) {
      return json_({ status: 'error', message: 'The request was not valid JSON.' });
    }
    if (TOKEN === 'put-a-long-random-secret-here') {
      return json_({ status: 'error', message: 'Set TOKEN in the script first (it still has the example value).' });
    }
    if (!sameText_(String(msg.token || ''), TOKEN)) {
      return json_({ status: 'error', message: 'Wrong secret. It must match TOKEN in the script.' });
    }

    const calendarId = calendarId_(String(msg.calendar || 'OSRS Farming'));

    if (msg.action === 'ping') {
      const cal = Calendar.Calendars.get(calendarId);
      return json_({
        status: 'ok',
        message: 'Calendar "' + cal.summary + '" is ready (time zone ' + cal.timeZone + ').'
      });
    }

    if (msg.action === 'sync' || msg.action === 'test') {
      const results = [];
      (msg.ops || []).forEach(function (op) {
        try {
          if (op.op === 'delete') {
            remove_(calendarId, op);
          } else {
            upsert_(calendarId, op);
          }
          results.push({ id: op.id, status: 'ok' });
        } catch (err) {
          results.push({ id: op.id, status: 'error', error: String(err) });
        }
      });
      return json_({ status: 'ok', results: results });
    }

    return json_({ status: 'error', message: 'Unknown action: ' + msg.action });
  } catch (err) {
    return json_({ status: 'error', message: String(err) });
  } finally {
    if (locked) {
      lock.releaseLock();
    }
  }
}

/** Create or update the entry for op.id. The id is stable, so the same patch always lands on the same entry. */
function upsert_(calendarId, op) {
  const eventId = eventId_(op.id);
  const start = new Date(op.start * 1000);
  const end = new Date(start.getTime() + Math.max(1, op.minutes || 15) * 60000);
  const resource = {
    summary: op.title,
    description: op.description || '',
    start: { dateTime: start.toISOString() },
    end: { dateTime: end.toISOString() },
    // useDefault:false with an explicit list means exactly these alerts and no others (an empty list means none)
    reminders: {
      useDefault: false,
      overrides: (op.reminders || []).map(function (r) {
        return { method: r.method, minutes: r.minutes };
      })
    },
    // show as "free" so a farming reminder never makes you look busy
    transparency: 'transparent',
    status: 'confirmed'
  };
  if (COLOR_ID) {
    resource.colorId = COLOR_ID;
  }

  let exists = true;
  try {
    Calendar.Events.get(calendarId, eventId);
  } catch (err) {
    exists = false;
  }
  if (exists) {
    // also brings back an entry that was deleted earlier: a deleted id cannot be inserted again, only patched
    Calendar.Events.patch(resource, calendarId, eventId);
  } else {
    resource.id = eventId;
    try {
      Calendar.Events.insert(resource, calendarId);
    } catch (err) {
      // an id that was used before and deleted is "already in use" until it is patched back to life
      if (/already exists|identifier|duplicate|409/i.test(String(err))) {
        delete resource.id;
        Calendar.Events.patch(resource, calendarId, eventId);
      } else {
        throw err;
      }
    }
  }
}

function remove_(calendarId, op) {
  try {
    Calendar.Events.remove(calendarId, eventId_(op.id));
  } catch (err) {
    // already gone is fine
    if (!/not found|deleted|404|410/i.test(String(err))) {
      throw err;
    }
  }
}

/** Finds the calendar by name among the ones you own, or makes it. The id is remembered. */
function calendarId_(name) {
  const props = PropertiesService.getScriptProperties();
  const key = 'calendar:' + name;
  const known = props.getProperty(key);
  if (known) {
    try {
      Calendar.Calendars.get(known);
      return known;
    } catch (err) {
      props.deleteProperty(key);
    }
  }
  let pageToken;
  do {
    const page = Calendar.CalendarList.list({ minAccessRole: 'owner', maxResults: 250, pageToken: pageToken });
    const items = page.items || [];
    for (let i = 0; i < items.length; i++) {
      if (items[i].summary === name && !items[i].primary) {
        props.setProperty(key, items[i].id);
        return items[i].id;
      }
    }
    pageToken = page.nextPageToken;
  } while (pageToken);

  const created = Calendar.Calendars.insert({ summary: name, timeZone: Session.getScriptTimeZone() });
  props.setProperty(key, created.id);
  return created.id;
}

/** Google event ids may only use 0-9 and a-v; a hex digest of the patch id fits and cannot collide in practice. */
function eventId_(id) {
  const bytes = Utilities.computeDigest(Utilities.DigestAlgorithm.MD5, id);
  let hex = '';
  for (let i = 0; i < bytes.length; i++) {
    hex += ((bytes[i] & 0xff) + 0x100).toString(16).substring(1);
  }
  return 'osrs' + hex;
}

/** Compares without stopping at the first difference. */
function sameText_(a, b) {
  if (a.length !== b.length) {
    return false;
  }
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

/** Run this once from the editor so Google asks you to approve calendar access. */
function authorize() {
  Calendar.CalendarList.list({ maxResults: 1 });
  Logger.log('Calendar access is approved. Now deploy the web app.');
}
