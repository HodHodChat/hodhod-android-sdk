# Changelog

## 1.0.0-beta01

First public beta.

* Distribution: JitPack, `com.github.HodHodChat.hodhod-android-sdk:hodhod-ui:1.0.0-beta01` (and `:hodhod-core`), source at <https://github.com/HodHodChat/hodhod-android-sdk>; requires the git tag `1.0.0-beta01`. MIT license, CI workflow, `docs/RELEASING.md`.

* `hodhod-core`: widget API client, ActionCable client, repository with optimistic sending, tickets, pre-chat, CSAT, contact modes (chat, ticket, both, ticket when offline), session storage in EncryptedSharedPreferences, chatbot flow engine (17 node kinds, handoff to a live agent or a ticket, flow analytics, `require_flow`), i18n helper for six languages.
* `hodhod-ui`: Compose UI (home, chat, tickets, pre-chat, survey with emoji/star/number styles, flow runner), RTL support, light/dark, six languages (fa, en, ar, de, es, fr), `HodhodChatActivity`, `HodhodChat`, `HodhodBubble`.
* No push notifications; the unread count updates while the app is open.
* Known limits: beta API; instrumented tests cover the UI only on API 34; minSdk 24 verified by lint and bytecode inspection, not on an API 24 device.
