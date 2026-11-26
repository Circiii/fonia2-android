# Google Play: Data safety

Answers for Play Console → App content → Data safety, for the app built from `:legacy:app`. They have to stay true to the code; each line says where.

## What leaves the phone

One thing: the install code (`legacy/features/.../app/InstallCount.kt`). A random UUID made on the phone, sent once with the word "android" to the `installs` table of the Supabase project `fonia` (Frankfurt, eu-central-1), a day after the first profile, so the owner can count installs. It is tied to no profile and the request carries nothing else (no Authorization header; a fixed User-Agent "Fonia" and Accept-Language "ro" instead of Android's, which name the phone model, its Android version and its languages). It goes at the first look with a profile a day old, at a launch or at a return from the background; an adult can switch it off in Settings ("Numără instalarea") until it has gone; debug builds never send it (`InstallCount.IS_ENABLED`); "Șterge tot" clears the code and the switch from the phone, and a wipe while the request is out does not mark the next install as sent. The table keeps `id`, `platform` and `created_at`, no IP address; Supabase's technical request logs keep the IP for a few days. With the publishable key the app can only insert: row-level security plus column grants refuse select, update and delete.

Nothing else uses the network. INTERNET is in the manifest for this alone. ACCESS_NETWORK_STATE stays removed, so the model fetchers (Whisper, ZIPA) find no network and download nothing, and `check<Variant>OnnxRuntime` fails the build if it comes back. Links in Settings open in the browser, which fetches the page, not Fonia.

## Answers

- Does your app collect or share any of the required user data types? **Yes.**
- Is all of the user data collected by your app encrypted in transit? **Yes** (HTTPS to `pqhwircymgqvxtamvjno.supabase.co`).
- Do you provide a way for users to request that their data is deleted? The row can't be tied to a person, and "Șterge tot" removes the code from the phone, so nobody can later point to their row. Answer **No** unless the owner decides to offer deletion by email (`stdulgh@gmail.com`) for someone who still has the code, which the app does not show.

Data types: only **Device or other IDs** (section "Device or other IDs"). Every other type: not collected.

| Question | Answer |
|---|---|
| Collected | **Yes** |
| Shared | **No** (Supabase processes it for the owner, as a service provider) |
| Processed ephemerally | **No** (the row is kept, to be counted) |
| Required or optional | **Optional**: users can choose (the switch "Numără instalarea" in Settings, on by default, works until the code has gone) |
| Purpose | **Analytics** only |

Why each other type is "not collected":

| Data | What the code does |
|---|---|
| Audio, voice | Analysed on the phone, never sent. Takes stay in the app's files (`Fonia/Recordings`), excluded from backup (`allowBackup="false"`) |
| Photos, camera, face data | The mirror reads face points in memory, one frame at a time; nothing is stored or sent (`facecamera`) |
| Profiles, attempts, notes, progress, practice log | Local SQLite and files only |
| App activity, app info and performance, crash logs | None: no analytics SDK, no crash reporter, no advertising ID. ONNX Runtime's 1DS telemetry provider is removed from the manifest and the build fails if it comes back |
| Files the adult shares (the export for the speech therapist, the PDF report) | Made on the phone and shared by the adult through Android's share sheet; nothing goes to Fonia |

## Notes for the owner

- Families policy: the app is for children with an adult. The install code is a random app-made UUID, not one of the persistent device identifiers the policy names (advertising ID, IMEI, MAC, serial, SSID and the like), and it is not used for ads.
- The in-app privacy text says the same (`legacy/features/.../grownups/PrivacyText.kt`, sections "Internetul" and "Numărul de instalări"), and the hosted policy at getfonia.com/confidentialitate must too.
- The iOS answer is in `fonia-app/docs/app-store.md` §7 (Identifiers → Device ID, Analytics, not linked, no tracking).
