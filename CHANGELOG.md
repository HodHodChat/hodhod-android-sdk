# Changelog

## 1.0.0-beta04

* Inbox announcements: the public widget config's `announcements` (up to two, already filtered by the server for enabled + schedule) are shown at the very top of Home (above the flow runner, start cards and "My tickets"), of the ticket panel when it acts as Home, and of the pre-chat form; incident notices follow them there. `notice` = yellow information banner, `alert` = red warning banner with icon (light + dark, RTL, font scale and tablet width safe). Rich text with bold and clickable links (`http`/`https`/`mailto`/`tel` only, opened with `ACTION_VIEW`, never a WebView, no auto-linking), optional https image (fixed max height, alt text, optional link, hidden when it fails to load), dismiss button for dismissible ones (TalkBack «Dismiss», all six locales). New core API (additive): models `Announcement`, `AnnouncementBlock` (`Text`, `Image`), `AnnouncementKind`, `TextSegment`; `WidgetConfig.announcements`; `HodhodRepository.announcements: StateFlow<List<Announcement>>` (minus dismissed) and `dismissAnnouncement(id)`. Dismissal is remembered per `(websiteToken, id, updated_at)` in the session store, so an edited announcement is shown again. Tolerant parsing: a missing/invalid list is empty, unknown block types are ignored, only allow-listed link schemes and https images survive. Sample: `--es fake announcements`.

* Security/privacy: the session file is now the fixed-name `hodhod_session` (one-time automatic migration from the hashed per-scope files; entries are key-prefixed by scope) so host apps can exclude it from Auto Backup; `hodhod_backup_rules.xml` and `hodhod_data_extraction_rules.xml` are shipped for that (see README "Privacy"). An undecryptable preferences file (restored backup) is recreated instead of silently falling back to plain storage. Picked/captured files are deleted from the cache after upload and on `logout()`. `HodhodChatActivity` finishes quietly when the SDK is not configured.

## 1.0.0-beta03

* My tickets: visitors see all their tickets (open and closed) in every contact mode, including tickets an agent converted from a chat. Home always shows a compact "My tickets" row with the open-count badge when there is at least one ticket (also during a live chat). The list has Open / Closed / All filter chips with counts, per-filter empty states, relative times, "From chat" tag, pull-to-refresh, pagination and skeleton/error states; tapping the ticket of the active chat opens the chat. Chat-only inboxes hide "New ticket" but keep the list; ticket-only inboxes show the list as Home.
* `hodhod-core` (additive): `HodhodRepository.ticketSummary: StateFlow<TicketSummaryCounts>` (refreshed on foreground, after creating a ticket / replying / ending a conversation and on websocket activity), `loadTickets(status: TicketFilter, page, perPage): Result<TicketList>`, `TicketSummary.source` / `conversationDisplayId` / `isOpen` / `lastAgentReplyAt`, `TicketFilter`, `TicketCounts`, `TicketList`. Older servers without `meta` or `/tickets/summary` are counted and paginated on the client.
* Needs a server with the widget tickets list (`status`, `page`, `per_page`, `meta` counters) and `/api/v1/widget/tickets/summary`.
* Sample app: `--es fake chat-tickets|ticket-tickets|both-tickets` demo data and `--es hash <hmac>` launch extra.

## 1.0.0-beta02

First public beta.

* Distribution: JitPack, `com.github.HodHodChat.hodhod-android-sdk:hodhod-ui:1.0.0-beta02` (and `:hodhod-core`), source at <https://github.com/HodHodChat/hodhod-android-sdk>; requires the git tag `1.0.0-beta02`. MIT license, CI workflow, `docs/RELEASING.md`.

* `hodhod-core`: widget API client, ActionCable client, repository with optimistic sending, tickets, pre-chat, CSAT, contact modes (chat, ticket, both, ticket when offline), session storage in EncryptedSharedPreferences, chatbot flow engine (17 node kinds, handoff to a live agent or a ticket, flow analytics, `require_flow`), i18n helper for six languages.
* `hodhod-ui`: Compose UI (home, chat, tickets, pre-chat, survey with emoji/star/number styles, flow runner), RTL support, light/dark, six languages (fa, en, ar, de, es, fr), `HodhodChatActivity`, `HodhodChat`, `HodhodBubble`.
* No push notifications; the unread count updates while the app is open.
* Known limits: beta API; instrumented tests cover the UI only on API 34; minSdk 24 verified by lint and bytecode inspection, not on an API 24 device.
