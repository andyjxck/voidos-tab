# Void Hub setup

The tablet never holds a store or API key. A small server (two Firebase Cloud Functions in
`functions/`) pulls every number, writes one summary to Firestore, and the tablet reads that one
summary with its own device token. The app holds no made-up numbers: until the server is set up,
every hub screen says **Nothing linked yet** and the standby ring stays plain.

Each source is optional. A source with no key shows **Not connected** in Settings › Hub › Sources,
and a metric it would feed names it (for example "No crashes yet · Link Crashlytics in
Settings › Hub"). The rest carry on. If a source fails later, the tablet keeps its last good numbers
and shows when they were last updated.

## 1. Pick the Firebase project

Use a project of its own (for example `voidos-hub`) rather than the Staff Hub's, so nothing here can
touch the Staff Hub's data or rules. In the Firebase console:

1. Create the project, then switch it to the **Blaze** plan (scheduled functions and Secret Manager
   need it). The hourly run and a few wake refreshes a day are small.
2. **Build › Firestore Database › Create database**, production mode, region `europe-west2`
   (London). The functions write only to the `hub` collection.

On your Mac, once:

```
npm install -g firebase-tools
firebase login
```

## 2. The device token (required)

This is the password the tablet uses to read the summary. Make one:

```
openssl rand -hex 32
```

Keep it for step 5 and for the tablet (step 7).

## 3. The keys, source by source

Every key goes into Secret Manager with `firebase functions:secrets:set NAME --project <your-project-id>`
(it asks for the value; paste it and press Enter). All ten secret names must exist before the first
deploy, so for a source you are not connecting yet, enter the word `unset`. Change it later the same
way and the next refresh picks it up.

| Secret | What it is |
|---|---|
| `HUB_TOKEN` | the device token from step 2 |
| `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_PRIVATE_KEY` | App Store Connect API key |
| `ADMOB_CLIENT_ID`, `ADMOB_CLIENT_SECRET`, `ADMOB_REFRESH_TOKEN` | AdMob API access |
| `GITHUB_TOKEN` | GitHub read-only token |
| `BREVO_API_KEY` | Brevo API key |
| `ELEVENLABS_API_KEY` | ElevenLabs API key |

The other settings are not secret, and live in the Firestore document **hub/config** (step 4).

### App Store Connect (phase 1: apps, names, icons; phase 2: downloads, sales, payouts, review status, reviews)

1. App Store Connect › **Users and Access › Integrations › App Store Connect API › Team Keys**.
   Generate a key. The **Admin** role covers everything the hub reads and the review replies; a
   narrower role works for the app list but not for sales reports or replies.
2. Download the `.p8` file (Apple lets you download it once). Note the **Key ID** beside the key and
   the **Issuer ID** above the list.
3. Set the secrets. For the private key, paste the whole file including the BEGIN and END lines:

   ```
   firebase functions:secrets:set ASC_KEY_ID
   firebase functions:secrets:set ASC_ISSUER_ID
   firebase functions:secrets:set ASC_PRIVATE_KEY --data-file AuthKey_XXXXXXXXXX.p8
   ```
4. For downloads, sales and the payout estimate, add your **vendor number** to hub/config as
   `appstore.vendorNumber`. It is at the top left of **Payments and Financial Reports**.

Apple posts each day's sales report the next morning, so "today" on the tablet is the newest day
Apple has published, usually yesterday. The payout date and amount are an estimate from the last
seven days; set `appstore.nextPayment` (YYYY-MM-DD) in hub/config to show Apple's real date.

### AdMob (phase 2: earnings, impressions, eCPM)

AdMob's API only accepts your own Google sign-in, so this makes a long-lived refresh token once.

1. Google Cloud console, same project: **APIs & Services › Library**, enable **AdMob API**.
2. **APIs & Services › OAuth consent screen**: External, add yourself as a test user, then
   **Publish app** so the token doesn't expire after seven days.
3. **Credentials › Create credentials › OAuth client ID**, type **Web application**, with
   `https://developers.google.com/oauthplayground` as an authorised redirect URI. Note the client ID
   and secret.
4. Open the OAuth 2.0 Playground (developers.google.com/oauthplayground). In the cog menu tick
   **Use your own OAuth credentials** and enter them. Authorise the scope
   `https://www.googleapis.com/auth/admob.readonly`, sign in with your AdMob Google account, then
   **Exchange authorization code for tokens** and copy the **refresh token**.
5. Set `ADMOB_CLIENT_ID`, `ADMOB_CLIENT_SECRET` and `ADMOB_REFRESH_TOKEN`.
6. In hub/config add `admob.publisherId`: your `pub-…` number from AdMob › Settings › Account
   information.

AdMob apps linked to an App Store listing are matched to your apps automatically. For one that
isn't linked, add `admob.map` with `{ "ca-app-pub-…~…": "asc:<Apple ID>" }`.

### Firebase Analytics (phase 2: active users)

1. Enable **Google Analytics Data API** in the hub's Google Cloud project.
2. For each app's Firebase project, open its Google Analytics property › **Admin › Property access
   management**, and add the functions' service account as a **Viewer**. That account is
   `<project-number>-compute@developer.gserviceaccount.com` for the hub project (the number is in
   Project settings).
3. In hub/config add `firebase.properties`, one entry per app:
   `{ "propertyId": "123456789", "bundleId": "com.andysocial.voided" }`. The property ID is under
   Admin › Property details.

### Crashlytics (phase 3: crashes and crash groups)

1. Each app needs the Firebase Crashlytics SDK, set up as Firebase's iOS guide shows.
2. In each app's Firebase project: **Project settings › Integrations › BigQuery › Link**, and turn
   on Crashlytics export. Data starts arriving the next day.
3. Give the hub's service account **BigQuery Data Viewer** on that project's
   `firebase_crashlytics` dataset, and **BigQuery Job User** on the project you name below.
4. In hub/config add `crashlytics.project` (the Google Cloud project that holds the dataset). If
   your apps' tables aren't named the usual way, list them in `crashlytics.apps` as
   `{ "bundleId": "…", "table": "…_IOS" }`.

### Your sites (phase 3: uptime, SSL and domain expiry)

No key needed. Either list them in Settings › Hub › Sites to check on the tablet (it sends the list
with each request), or put them in hub/config as `sites`, which then takes priority:

```
[ { "name": "Staff Hub", "url": "https://tws-rota.co.uk" },
  { "name": "White Swan", "url": "https://<the White Swan site's address>" } ]
```

Each refresh loads the page, reads the certificate's expiry and the domain's renewal date (RDAP).
A site glows coral when it's down or its certificate has run out, and amber with 14 days to go.

### Brevo (phase 3: bounces and blocks)

1. Brevo › **SMTP & API › API keys › Generate a new API key**. Set `BREVO_API_KEY`.
2. In hub/config add `brevo.appId`: which site the emails belong to, for example
   `site:staff-hub` (the site's name in lower case, spaces as dashes).

### GitHub (phase 3: latest commits and builds)

1. GitHub › Settings › Developer settings › **Fine-grained tokens**. Read-only access to
   **Contents**, **Actions** and **Metadata**, on the repos you want. Set `GITHUB_TOKEN`.
2. Optional: `github.repos` in hub/config, like `["andyjxck/voidos-tab"]`. Without it, the hub
   shows your six most recently pushed repos.

### Google Cloud and Firebase spend (phase 4)

1. Cloud console › **Billing › Billing export › BigQuery export**, turn on **Standard usage cost**
   into a dataset in the hub project.
2. Give the service account BigQuery Data Viewer on that dataset.
3. In hub/config add `billing.table`: the full table name,
   `<project>.<dataset>.gcp_billing_export_v1_XXXXXX_XXXXXX_XXXXXX`. Optional `billing.budget`
   (a number) turns the line amber once spend reaches it.

### ElevenLabs balance (phase 4)

ElevenLabs › profile › **API Keys**, a key with read access to your user and subscription. Set
`ELEVENLABS_API_KEY`.

### Apple Developer Program renewal (phase 4)

No key: Apple has no API for it. Add `apple.renewal` (YYYY-MM-DD, from developer.apple.com ›
Account › Membership details), and optionally `apple.fee` and `apple.currency`. It turns amber 30
days before.

### Cloudflare

Not needed. The SSL and domain checks above read the sites directly, so no Cloudflare token is
used.

## 4. hub/config

In the Firebase console › Firestore, create the collection **hub** with a document **config**.
Add only the fields for the sources you use. A full example:

```
currency: "GBP"
appstore:    { vendorNumber: "85xxxxxx", country: "gb" }
admob:       { publisherId: "pub-xxxxxxxxxxxxxxxx" }
firebase:    { properties: [ { propertyId: "123456789", bundleId: "com.andysocial.voided" } ] }
crashlytics: { project: "voided-12345" }
sites:       [ { name: "Staff Hub", url: "https://tws-rota.co.uk" } ]
brevo:       { appId: "site:staff-hub" }
github:      { repos: ["andyjxck/voidos-tab"] }
billing:     { table: "voidos-hub.billing.gcp_billing_export_v1_XXXXXX_XXXXXX_XXXXXX", budget: 10 }
apple:       { renewal: "2027-03-01", fee: 79, currency: "GBP" }
```

`appstore.hide` (a list of Apple IDs or bundle IDs) leaves an app off the drawer and the board.

## 5. Deploy (one command)

From the voidos-tab folder:

```
cd functions && npm install && cd .. && firebase deploy --only functions --project <your-project-id>
```

This deploys only the hub's two functions. It doesn't change Firestore rules or anything else in
the project. The output ends with the URL of the **hub** function; keep it for step 7.

## 6. Check it

```
curl -H "Authorization: Bearer <HUB_TOKEN>" "<hub URL>/summary?wake=1"
```

You should get JSON with `"apps"` and `"sources"`. Each source says `connected` and `ok`, and an
`error` if it failed.

## 7. The tablet

Settings › Hub › **Your hub server**: the hub URL from step 5, and the HUB_TOKEN from step 2. Save.
The Status line then shows "updated X ago", or the last error in words.

The tablet refreshes when the screen comes on (the server skips it if it refreshed under ten
minutes ago) and the server refreshes every hour on its own.

## Running the server's tests

```
cd functions && npm test
```

They use fake replies for every service, so they need no keys and send nothing anywhere.
