# Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Chat screen shows an error | Read `Hodhod.state` (`Failed(code, message)`). `not_found`: wrong `websiteToken`/`baseUrl`. `network`: unreachable server. `suspended`: the Hodhod account is suspended. `server`: server error. `cleartext`: an `http://` URL without `allowCleartext`. `config`: invalid `baseUrl`. |
| Emulator cannot reach the local server | Use `http://10.0.2.2:<port>`, pass `allowCleartext = true` and add a debug-only `network_security_config` allowing cleartext for `10.0.2.2` (see `sample/src/debug`). Never in release builds. |
| Release build crashes or loses messages (R8) | The libraries ship consumer rules (`consumer-rules.pro`): serializers of the models, `HodhodChatActivity`, OkHttp/Tink warnings. Make sure you did not exclude them (`-dontusemetadata`/custom `-assumenosideeffects`). The `sample` `release`/`qa` builds are minified with exactly these rules. |
| Messages do not arrive live ("no connection" banner) | WebSocket path `/cable` blocked by a proxy/CDN. The SDK reconnects with backoff; after a network drop it recovers by itself and keeps the cached messages visible. |
| Unread badge stays at zero | Call `Hodhod.start()` (or open the chat once) after `configure`. The counter only updates while the app is in the foreground (no push). |
| Wrong language | Pass `locale` in `HodhodConfig`; otherwise inbox language, then device language (fa, en, ar, de, es, fr, else English). |
| `identify` fails | `identifierHash` must be the hex HMAC-SHA256 of the identifier with the inbox HMAC key, computed on your backend. |
| Chatbot flow does not show | The inbox needs an active flow whose triggers match; the flow must be saved/enabled in the dashboard. With `require_flow` the direct start card is hidden while the flow is available. |
| Build: `PermittedSubclasses requires ASM9` in `javaDocReleaseGeneration` | The UI module disables the AGP Dokka task (empty javadoc jar). |
| Duplicate FileProvider authority | The authority is `${applicationId}.hodhod.fileprovider`; a clash means two modules use the same applicationId. |
| `Could not find com.github.HodHodChat.hodhod-android-sdk:hodhod-ui:1.0.0-beta02` | Add `maven { url = uri("https://jitpack.io") }` to the `repositories` of `dependencyResolutionManagement` in `settings.gradle.kts`, and make sure the git tag `1.0.0-beta02` exists in <https://github.com/HodHodChat/hodhod-android-sdk> (see `docs/RELEASING.md`). |
| JitPack shows a red build for a tag | Open the build log at <https://jitpack.io/#HodHodChat/hodhod-android-sdk>. The build needs JDK 17 (`jitpack.yml`); after fixing, push a new tag (JitPack caches failed builds per tag) or use "Get it" on the Commits tab. |
