# Farming Calendar Sync

A RuneLite plugin that puts your farming patch timers on a Google Calendar. It reads the timers that RuneLite's
Time Tracking plugin already keeps, and a small Google Apps Script that you run under your own account adds the
entries to a calendar called "OSRS Farming". Each entry starts when the patch is ready and, by default, alerts your
phone at exactly that moment.

**Setup: see [SETUP.md](SETUP.md).** The script to copy is in [google-script/Code.gs](google-script/Code.gs).

## Features

* A side panel lists every patch Time Tracking knows (trees, fruit trees, hardwood, calquat, Hespori, herbs,
  allotments, flowers, bushes, hops, cactus, belladonna, mushroom, seaweed, coral, grapes, spirit trees, redwood,
  celastrus, anima, crystal tree and compost bins). Each has a **Calendar** switch (make an entry) and an **Alert**
  switch (notify me when it is ready).
* A **Contract** row follows your current Farming Guild contract and the Guild patch growing it.
* Quick select, search, and collapsible groups per patch type.
* Settings: alert minutes before (0 is exactly on time, several allowed), phone alert and/or email, entry length, when
  to remove entries, whether to include fruit and berry regrowth.

## How entries behave

| In game | Calendar |
|---|---|
| You plant something | An entry appears at the time it will be ready |
| You replant, or the estimate moves | The same entry is updated, never duplicated |
| It finishes and you have not checked it | The entry stays after its time has passed |
| You check health, harvest or clear it | The entry is removed (setting: "Once checked or harvested") |
| It dies | The entry is removed |
| It becomes diseased | The title says so (growth is paused, so the time is the old estimate) |
| Fruit tree or bush after picking | An entry for when it has regrown (optional) |
| You switch the patch off | Its entry is removed |

## Limits

* The time is only as exact as Time Tracking's. Until RuneLite has learned your farming tick offset, a time can be
  early or late by up to one growth tick. Such entries say "Approximate".
* Timers update only when RuneLite has seen the patch, so changes made on another client or a phone are not known.
* Alerts and calendar syncing are Google's, and depend on your phone's notification settings.

## Privacy and data

The plugin makes network requests only to the `script.google.com` address you enter, and only after you have entered
one. It sends the patch names and ready times for the patches you switched on, a short id for your account (a hash,
not your name or any key), and your secret. It does not touch the game: no menu entries, no input, no reflection.
Settings, including the address and secret, are kept in RuneLite's normal config file.

## For developers

`core/` has no RuneLite imports: `FarmingData` (patch table and crop data), `Predictor` (Time Tracking's timer
arithmetic), `Planner` (what goes on the calendar and when), `PatchTypes`. `FarmingCalendarPlugin` reads Time Tracking's
stored config, plans and sends; `CalendarClient` is the only network code; `FarmingCalendarPanel` is the side panel.

Time Tracking's prediction classes are package-private, so the patch table, crop numbers and varbit decoding in
`src/main/resources/com/farmingcalendar/farming-data.json` were generated from RuneLite's Time Tracking classes at build
time and are read as plain data. They need regenerating after a RuneLite update that adds patches or crops.

```
./gradlew test      unit tests for the planner and timer arithmetic
```

BSD 2-Clause licence, see [LICENSE](LICENSE). The farming data is derived from RuneLite (BSD 2-Clause).
