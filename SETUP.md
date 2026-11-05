# Setup guide

Farming Calendar Sync needs a small Google Apps Script of your own. It runs under your Google account, so it can add
entries to your calendar, and nobody else can use it without your secret. Setup takes about five minutes and is done
once.

What the plugin sends to it: the names and ready times of the patches you switch on in the side panel, plus your secret.
Nothing else is sent, and nothing is sent until you have entered the script's address in the plugin.

## 1. Make a secret

The secret is a long random password that the plugin and the script share. Make one with a password manager, or run
this and keep the output:

```
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

## 2. Create the script

1. Open <https://script.google.com> signed in to the Google account whose calendar you want to use. Choose
   **New project** and name it, for example, "Farming Calendar Sync".
2. Open [google-script/Code.gs](google-script/Code.gs) on GitHub, use the **Copy raw file** button, and paste it over
   everything in the editor's `Code.gs`.
3. Near the top, replace `put-a-long-random-secret-here` with your secret from step 1.
4. In the left sidebar choose **Services +**, pick **Google Calendar API**, keep version **v3** and the identifier
   `Calendar`, and choose **Add**. (The simpler built-in calendar service cannot set an alert at exactly 0 minutes, which
   is why this one is needed.)
5. Save. In the toolbar's function dropdown pick **authorize** and choose **Run**. Google asks for permission:
   **Review permissions**, pick your account, **Advanced**, **Go to Farming Calendar Sync (unsafe)**, **Allow**. The
   "unsafe" wording only means Google has not reviewed a script you wrote yourself.
6. Choose **Deploy > New deployment**, click the gear and pick **Web app**. Set **Execute as** to **Me** and
   **Who has access** to **Anyone**, then **Deploy**.
7. Copy the **Web app URL**. It ends in `/exec`.

"Anyone" only means anyone who has both the address and your secret can reach it. A request without the right secret is
refused and does nothing.

## 3. Connect the plugin

1. In RuneLite, open the Farming Calendar panel (calendar icon in the sidebar) and expand **Settings**.
2. Paste the web app URL and your secret, then **Save settings**.
3. Choose **Test connection**. The status line should say the calendar "OSRS Farming" is ready. The calendar is created
   on your account if it does not exist.
4. Choose **Send a test alert in 2 minutes** and check that your phone alerts at that time.

On your phone, open the Google Calendar app and make sure the OSRS Farming calendar is ticked, and that Calendar is
allowed to send notifications. A new secondary calendar sometimes needs that tick once.

## 4. Pick patches

Tick **Calendar** for the patches you want on the calendar and **Alert** for the ones that should notify you. The
**Quick select** menu has a "My usual" choice for trees, fruit trees, hardwood, calquat, Hespori and the farming
contract. Hover a patch name to see what Time Tracking currently knows about it.

Patches only update once RuneLite's Time Tracking has seen them, so visit a patch (or log in near it) the first time.

## Changing the script later

If you edit the script after deploying, publish the change: **Deploy > Manage deployments > Edit (pencil) >
Version: New version > Deploy**. Otherwise the old version keeps running. The web app address stays the same.

## Removing everything

Delete the OSRS Farming calendar in Google Calendar, delete the script project at script.google.com, and clear the
settings in the plugin. Nothing is stored anywhere else.

## Troubleshooting

* **"Wrong secret"**: the secret in the plugin differs from `TOKEN` in the script. Re-paste both, and redeploy a new
  version of the script if you changed it.
* **"The script did not answer with data"**: the deployment is not set to *Anyone*, or the address is the editor link
  instead of the `/exec` link.
* **No phone alert**: check the calendar is ticked in the phone app, notifications are allowed for Google Calendar, and
  the phone does not restrict the app in the background.
* **A time is wrong by a while**: Time Tracking has not learned your farming tick offset yet. The entry says
  "Approximate" until it has.
