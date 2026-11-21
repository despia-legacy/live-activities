# Live Activities

Source preview for Despia V3. This snapshot requires the runtime update currently awaiting release and an approved extension-enabled native build. Uploading these files does not change an installed app.

Live Activities on iOS and Live Updates on Android, from your page or OneSignal.

This ready-made extension is part of Despia V3 Extensions. First-party V4 support comes through the new package registry, with a different package shape.

[Extension documentation](https://setup.despia.com/experiments/samples/live-activities), [V3 extension documentation](https://setup.despia.com/experiments/v3-extensions) and [extension skill](https://github.com/despia-legacy/extension-skill).

## Web frameworks and app builders

Use the Despia SDK with React, Vue, Svelte, Next.js, TanStack or HTML5. Projects built with Lovable, Base44, Replit and other app builders need custom JavaScript and static file hosting. Claude Code, Codex and Cursor can edit the same extension files.

## What is included

- `manifest.json`: the extension's routes, parameters, events and native files.
- `ios/` and `android/`: the Swift and Kotlin or Java sources and resources the manifest names.
- `extensions.example.json`: the entry for `public/despia/extensions.json`.
- `liveactivity.policy.json`: an empty policy example. No additional approval fields are needed by this fixture. A policy example is not an approval.

## Requirements

- A Despia V3 app with V3 Extensions enabled, and a web app that serves static files from its own HTTPS origin.
- The `despia-native` JavaScript SDK (`npm install despia-native`).
- iOS 16.2 or later for Live Activities.
- A OneSignal account if you update activities from your server.

## Install

1. Copy `manifest.json`, `ios/` and `android/` into `public/despia/liveactivity/` in your web app. The folder name is the extension id, so pages call `extension://liveactivity/<route>`. Keep every path inside the folder unchanged, because the manifest names them. (`public/` is the folder your site serves from its root; in SvelteKit, use `static/`.)
2. Add `"liveactivity"` to the `extensions` list in `public/despia/extensions.json`. `extensions.example.json` shows the entry. Despia takes app identity from the build configuration.
3. Set the values listed under "Values to set before you build". Vendor keys and hosts in this repository are placeholders only. Never put a server secret in the manifest or anywhere in your web app.
4. Request approval and a new native build in Despia. Despia reviews and approves these exact files before a build includes them, and any change to the manifest or to a native file needs a new approval and a new native build. It cannot be applied over the air.

## JavaScript SDK

Install the SDK in your web app:

```sh
npm install despia-native
```

Call the published SDK directly and watch the exact answer variable declared in `manifest.json`. The SDK resolves to an object keyed by that variable; the native envelope is inside it. Check both a missing reply and `ok: false` before using `data`. Native errors resolve through the SDK; they do not reject its promise. A single watched variable becomes `undefined` after 30 seconds without a reply.

Await calls that use the same answer variable sequentially. The SDK's command queue does not correlate concurrent responses. Encode every query value with `encodeURIComponent`, including JSON text. Spaces become `%20`; a literal plus becomes `%2B`. Do not use unmodified `URLSearchParams` form encoding, which uses plus for spaces.

Importing the SDK is safe during server rendering. Run native calls only in browser click handlers or mounted client hooks, including client components in Next.js. The extension must be included in the installed Despia V3 app; a browser preview has no native extension features. Optional event handlers use the declared callback on `globalThis`, not an SDK subscription API. Save and restore any callback your app already owns when a component mounts and unmounts.

## Calling it

```javascript
import despia from 'despia-native';

const answerReply = await despia('extension://liveactivity/list', ['liveActivityList']);
const answer = answerReply.liveActivityList;
if (answer === undefined) throw new Error('No native reply within 30 seconds');
if (!answer.ok) throw new Error(answer.code + ': ' + answer.error);

console.log(answer.data);
```

Calls use `extension://liveactivity/<route>?<params>`. Each route answers once, in the page that made the call: the runtime sets the route's answer variable in the page, then calls the route's callback. Only declared parameters are accepted. Keep large data on the native side and pass identifiers through the bridge. A call URL is at most 8192 characters.

Every answer has the same shape: `ok`, `provider`, `platform`, `runtime` (`3`), `bridge` (`1`), `extension`, `route`, `data` (your handler's data on success, `null` on error), and on error `error` (a message) and `code`.

Standard error codes: `bad_request` (an undeclared or missing parameter, or a value of the wrong type), `not_registered` (this build does not contain the extension or route), `forbidden_frame` (the call did not come from the top-level page), `forbidden_origin` (an origin that is not allowed), `rate_limited` (more than 20 calls per second to one extension), `timeout` (no answer within the route's `timeout_ms`), `unavailable`, `platform_limit` (this platform or OS version has no such capability), `handler_error`, plus the codes this extension lists below.

---

## About

This extension shows a live card for something in progress: a Live Activity on the iOS Lock Screen and Dynamic Island, and a Live Update on Android 16 and later (an ongoing notification on older Android). Your page starts, updates and ends it, and OneSignal can do the same from your server, also while the app is closed.

Three templates are included, and each is a file you edit: `delivery` (order tracking with steps), `score` (a live score) and `timer` (a countdown).

Your app must already use OneSignal through Despia. Do not add a second OneSignal SDK and do not initialize OneSignal yourself: the app already does both.

---

## Values to set before you build

Despia takes app identity from the existing build configuration. If your app has other extensions, list `"liveactivity"` beside them.

What to edit:

- **Edit these:** `DeliveryActivity.swift`, `ScoreActivity.swift` and `TimerActivity.swift` (the data and the Lock Screen and Dynamic Island views), and on Android `DeliveryTemplate.java`, `ScoreTemplate.java` and `TimerTemplate.java`.
- **Optional:** the three `nx_live_*.xml` status-bar icons and `nx_live_strings.xml` (the channel name and the Remove button).
- **Add one line per template you add:** `LiveTemplateRegistry.swift`, `NXLiveActivityBundle.swift` and `LiveTemplates.java`.
- **Leave alone:** every other file.

Every file must be named in the manifest: each Swift file by its path, and the Android files by their module folder (`android/liveactivity`). The manifest may not name a file that is missing. When you add or rename a Swift file, change the manifest too. Every change needs a new approval from Despia.

---

## Using it from your web app

```javascript
import despia from 'despia-native';

const attributes = { orderNumber: '1042', merchant: 'Corner Bakery' };
const state = { status: 'Preparing', step: 1, stepCount: 4, etaEpoch: Date.now() / 1000 + 1800 };

const startedReply = await despia(`extension://liveactivity/start?template=${encodeURIComponent('delivery')}&activity_id=${encodeURIComponent('order-1042')}&attributes=${encodeURIComponent(JSON.stringify(attributes))}&state=${encodeURIComponent(JSON.stringify(state))}&stale_minutes=${encodeURIComponent(60)}`, ['liveActivityStart']);
const started = startedReply.liveActivityStart;
if (started === undefined) throw new Error('No native reply within 30 seconds');
if (!started.ok) throw new Error(started.code + ': ' + started.error);

const updatedReply = await despia(`extension://liveactivity/update?activity_id=${encodeURIComponent('order-1042')}&state=${encodeURIComponent(JSON.stringify({ status: 'Out for delivery', step: 3, stepCount: 4, etaEpoch: state.etaEpoch }))}`, ['liveActivityUpdate']);
const updated = updatedReply.liveActivityUpdate;
if (updated === undefined) throw new Error('No native reply within 30 seconds');
if (!updated.ok) throw new Error(updated.code + ': ' + updated.error);

const endedReply = await despia(`extension://liveactivity/end?activity_id=${encodeURIComponent('order-1042')}&state=${encodeURIComponent(JSON.stringify({ status: 'Delivered', step: 4, stepCount: 4 }))}&dismiss=${encodeURIComponent('after')}&dismiss_minutes=${encodeURIComponent(30)}`, ['liveActivityEnd']);
const ended = endedReply.liveActivityEnd;
if (ended === undefined) throw new Error('No native reply within 30 seconds');
if (!ended.ok) throw new Error(ended.code + ': ' + ended.error);

const previousLiveActivityState = globalThis.onLiveActivityState;
const handleLiveActivityState = event => {
  try {
    if (typeof previousLiveActivityState === 'function') previousLiveActivityState(event);
  } finally {
    console.log(event.activityId, event.state, event.origin)
  }
};
globalThis.onLiveActivityState = handleLiveActivityState;
const cleanupLiveActivityState = () => {
  if (globalThis.onLiveActivityState !== handleLiveActivityState) return;
  if (previousLiveActivityState === undefined) delete globalThis.onLiveActivityState;
  else globalThis.onLiveActivityState = previousLiveActivityState;
};
// Call cleanupLiveActivityState() when this component unmounts.

// Remove or restore your event callback on unmount.
```

Times are Unix seconds (`Date.now() / 1000`), never date strings. Attributes and state together must stay under 4 KB.

### Routes

| Route        | Parameters                                                                                              | Answer `data`                                                       | Answer variable | Callback |
| ------------ | ------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- | --- | --- |
| `start`      | `template` (required), `activity_id`, `attributes` (required JSON), `state` (required JSON), `stale_minutes` (0 to 720), `relevance` (0 to 100) | `activityId`, `template`, `state`                                   | `liveActivityStart` | `onLiveActivityStart` |
| `update`     | `activity_id` (required), `state` (required JSON), `stale_minutes`, `relevance`, `alert_title` (up to 120), `alert_body` (up to 240) | the activity                                                        | `liveActivityUpdate` | `onLiveActivityUpdate` |
| `end`        | `activity_id` (required), `state`, `dismiss` (`default`, `immediate` or `after`), `dismiss_minutes` (0 to 240) | the activity                                                        | `liveActivityEnd` | `onLiveActivityEnd` |
| `list`       | none                                                                                                    | `enabled`, `templates`, `activities`, `pushToStart` (token or `null` per template) | `liveActivityList` | `onLiveActivityList` |
| `permission` | none                                                                                                    | The authorization state. On Android 13 and later it asks for `POST_NOTIFICATIONS`. iOS has no prompt for Live Activities | `liveActivityPermission` | `onLiveActivityPermission` |
| `settings`   | none                                                                                                    | Opens where a person turns them on: the app's Settings page on iOS, the promotion settings on Android 16 | `liveActivitySettings` | `onLiveActivitySettings` |

- `activity_id` is 1 to 64 characters of `A-Z a-z 0-9 . _ -`. It is the id your page, your server and OneSignal share. It is generated when you leave it out of `start`.
- `attributes` and `state` are the template's JSON. An `update` without `stale_minutes` keeps the stale date it had.
- `stale_minutes`: on iOS the system marks the activity stale. Android has no stale state, so the activity turns `stale` at that time on its own, and an update with a later date makes it `active` again. A push sets it with `stale_date` (epoch seconds).
- `relevance` is passed to ActivityKit on iOS. An Android notification has no relevance score, so `start` and `update` there answer with `platformDifferences: {relevance: "..."}` when you send it.
- `list` is how a page catches up after a relaunch. Events are dropped while no page is loaded.

### Events

| Event       | Callback                    | Scope | Data                                                                         |
| ----------- | --------------------------- | ----- | ---------------------------------------------------------------------------- |
| `token`     | `onLiveActivityToken`       | `app` | `kind` (`update` or `push_to_start`), `activityId` or `template`, `token`    |
| `state`     | `onLiveActivityState`       | `app` | `activityId`, `template`, `state` (`active`, `stale`, `ended` or `dismissed`), `origin` (`app`, `push` or `restored`) |
| `dismissed` | `onLiveActivityDismissed`   | `app` | `activityId`, `by` (`user` or `end`)                                         |

### Error codes

On both platforms: `unknown_template`, `activities_disabled`, `exists`, `bad_value`, `too_large`, `unknown_activity`, `timeout` and `unavailable`. Only on iOS: `activity_failed`, `not_foreground`, `too_many` and `unsupported`. Only on Android: `storage_failed`, and `busy` (a notification prompt is already showing). See also the standard codes.

---

## Adding a template

1. **iOS: copy the timer.** Copy `TimerActivity.swift` to a new file and rename the type, the name and the fields. Keep `activityId: String`, and use `Double` seconds for times.
2. **iOS: register it.** Add `.of(YourAttributes.self)` to `LiveTemplateRegistry.swift` and `YourLiveActivity()` to `NXLiveActivityBundle.swift`.
3. **Android: copy the timer.** Copy `TimerTemplate.java`, set `name()` to the same name, read the same JSON keys, and add it to `LiveTemplates.java`.
4. **Update the manifest.** List the new Swift file in `manifest.json` under both `ios.app.sources` and `ios.targets[0].shared_sources`. The Java file needs no entry, because it sits in the module folder.

---

## From your server through OneSignal

The activity id, the template's attribute keys and its state keys are the same on both platforms. For endpoints and authentication, read OneSignal's REST API reference for Live Activities and its Android Live Updates guide on the day you integrate. This page does not copy them.

- **iOS, update or end** a running activity: target its `activity_id`. Send `event: "update"` or `"end"`, and `event_updates` holding the full new state, for example `{"status": "Out for delivery", "step": 3, "stepCount": 4, "etaEpoch": 1795000000}`.
- **iOS, start from a push** (iOS 17.2 and later): the activity type is the Swift type name, for example `DeliveryAttributes`. Send `event_attributes` with the attributes, including `"activityId"`, and `event_updates` with the first state.
- **Android:** send a data notification with `data.live_notification`:

```json
{
  "live_notification": {
    "key": "order-1042",
    "template": "delivery",
    "event": "update",
    "event_attributes": { "orderNumber": "1042", "merchant": "Corner Bakery" },
    "event_updates": { "status": "Out for delivery", "step": 3, "stepCount": 4, "etaEpoch": 1795000000 }
  }
}
```

`key` is the activity id. `template` is this template's own field; when you leave it out, `key` is used as the template name. `event_attributes` is needed on `start`, and on an `update` for an id the phone has not seen. Use the same `collapse_id` for every push of one activity. A push after the person swiped the notification away is skipped until the next `start`.

---

## Permissions and capabilities

### iOS

- **App keys.** `NSSupportsLiveActivities` and `NSSupportsLiveActivitiesFrequentUpdates`, set by the manifest.
- **Widget target.** `NXLiveActivity`, a WidgetKit target with a deployment target of iOS 16.6. Live Activities and the Dynamic Island are SwiftUI and WidgetKit only.
- **OneSignal.** Only `OneSignalLiveLink.swift` names OneSignal, and it only hands ActivityKit's tokens to the OneSignal SDK your app already has.

### Android

- **Notifications.** `POST_NOTIFICATIONS` on Android 13 and later, asked by `permission`.
- **Live Updates.** On Android 16 each activity is one ongoing notification, and the system promotes it to a Live Update when it is eligible.
- **OneSignal.** `OneSignalLiveUpdates.java` is OneSignal's notification service extension, declared once by the module manifest. It uses the OneSignal SDK your app already has.
- **Libraries.** `androidx.activity:activity:1.13.0` and `androidx.annotation:annotation:1.9.1`, the versions your app's base build already resolves.

---

## Platform limits

- **iOS.**
  - Up to 8 hours active, then up to 4 more on the Lock Screen. An ended activity stays up to 4 hours.
  - Push-to-start needs iOS 17.2. iOS rations push updates per hour, and priority-5 pushes do not count against the budget.
  - A person can turn Live Activities off in Settings. `list` shows `enabled`, and `settings` opens the page.
- **Android.** Promotion to a Live Update needs Android 16 and is the system's choice: it needs an ongoing notification with a title, no custom views, and a person who has not turned promotion off. When the system does not promote it, the same notification still shows and updates. Swiping the notification away or tapping Remove ends it for good until the next `start`.
- **Both.** Attributes and state together are at most 4 KB.

## License

[Despia V3 Extension Source-Available Commercial License](LICENSE).
You may inspect the source. Using or modifying this extension requires a valid
Despia commercial license and a valid Despia project for the particular app.
The standard price is **USD 249 per application per operating system**.
iOS and Android require separate licenses. See [Despia](https://despia.com).
Third-party SDKs keep their own licenses. Experimental V3 extensions do not
include a commitment to maintenance or a V4 port.

## Source and licensing

Browse, clone or fork this source to inspect it. Public GitHub forks remain public. For private development, clone into a private workspace or private repository. Modification, building, execution and deployment require a valid Despia commercial license and a valid Despia project for the application. The standard price is USD 249 per application per operating system. Licensed users may customize the extension or use it as a reference for new functionality in that licensed app. Keep the license and applicable notices. See [LICENSE](LICENSE) for the terms.
