## [2.0.0](https://github.com/amine2233/kotlin-cordis/compare/v1.0.0...v2.0.0) (2026-11-15)

### ⚠ BREAKING CHANGES

* **loader:** PluginRegistry is renamed to PluginFactoryRegistry (no alias).
Plan and registry keys are PluginKey, Entry.config is a JsonElement, LoadOutcome
is sealed (Loaded with the PluginHandle, Invalid, NotLoaded), LoaderAction returns
PluginHandle<*>, LoaderError.UnknownKey/InvalidConfig keys are PluginKey, and
kotlinx-serialization-json is now an api dependency of cordis-loader.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011hCzH3L9Lmi1r5PE5B21Aw
* **kernel:** PluginInfo gains key, inject and missing; SlotInfo gains
providerKey; PluginStatusChanged, PluginFailed and TeardownFailed gain a
trailing key property (default null), changing their constructors and
componentN/copy shapes; the lambda Context.plugin(name, inject, key, apply)
overload gains a key parameter; Plugin gains the pluginKey member.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011hCzH3L9Lmi1r5PE5B21Aw

* docs: describe PluginKey, snapshotFlow and PluginInfo.missing

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011hCzH3L9Lmi1r5PE5B21Aw
* a timer whose clock throws is now reported as TaskFailed instead of stopping silently.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011hCzH3L9Lmi1r5PE5B21Aw

* refactor(testing)!: make RecordedEvents.all a copy-on-write snapshot
* RecordedEvents.all is now a read-only List<CordisEvent> snapshot.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011hCzH3L9Lmi1r5PE5B21Aw

* refactor(kernel): type Registry owners as Context and share child construction

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011hCzH3L9Lmi1r5PE5B21Aw

* test(kernel): give UpdateSemanticsTests per-test logs and dispose apps

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011hCzH3L9Lmi1r5PE5B21Aw

* chore: drop redundant coroutines deps and set plugin.json version to 1.0.0

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_011hCzH3L9Lmi1r5PE5B21Aw
* Registry (with Registry.Entry and Registry.Listener),
Context.registry and the Fork constructor are now internal to
cordis-kernel and no longer part of the public API.


Claude-Session: https://claude.ai/code/session_011hCzH3L9Lmi1r5PE5B21Aw

Co-authored-by: simone <simone@Mac-mini-de-simone.local>
Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
* **kernel:** `Scope` now requires a `CoroutineScope` (the default and the
`Dispatchers.Default` fallback are gone), and plugin lifecycle operations
(`plugin`, `provide`, `dispose`, `PluginHandle.update`/`dispose`) no longer
follow the caller's cancellation: cancelling the caller only ends its wait.


Claude-Session: https://claude.ai/code/session_011hCzH3L9Lmi1r5PE5B21Aw

Co-authored-by: simone <simone@Mac-mini-de-simone.local>
Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>
* **kernel:** Fork.reconfigure, Fork.dispose and Fork.refresh are now
internal; use PluginHandle.update/dispose instead. events() and
serviceFlow() collectors now complete when their context is disposed.


Claude-Session: https://claude.ai/code/session_011hCzH3L9Lmi1r5PE5B21Aw

Co-authored-by: simone <simone@Mac-mini-de-simone.local>
Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>

### Features

* **android:** add opt-in cordis-android module with foreground bridge and DataStore store ([#40](https://github.com/amine2233/kotlin-cordis/issues/40)) ([2024b27](https://github.com/amine2233/kotlin-cordis/commit/2024b2737899847a54d09d5a7f6c15f5ab6954b4))
* **kernel:** add contribution points ([#35](https://github.com/amine2233/kotlin-cordis/issues/35)) ([dbd9402](https://github.com/amine2233/kotlin-cordis/commit/dbd9402f13e27f91d220acf0b8875bcaac1d6b77))
* **kernel:** add PluginKey, snapshotFlow and dependency info in PluginInfo ([#34](https://github.com/amine2233/kotlin-cordis/issues/34)) ([2f92073](https://github.com/amine2233/kotlin-cordis/commit/2f92073e042335a2d005829b11712c10b4bb2668))
* **kernel:** add SnapshotVisitor and RuntimeSnapshot.accept traversal ([b3c4538](https://github.com/amine2233/kotlin-cordis/commit/b3c4538dcbcbdf2b9d1935cf302371655706c336))
* **kernel:** add TextTreeVisitor, MermaidVisitor and HealthVisitor ([4492bff](https://github.com/amine2233/kotlin-cordis/commit/4492bffb383119dfb132493ebfa4a439fb85eda1))
* **ktor:** add cordis-ktor Ktor 3 server integration module ([#39](https://github.com/amine2233/kotlin-cordis/issues/39)) ([e1b2a6c](https://github.com/amine2233/kotlin-cordis/commit/e1b2a6c4be1d745fd4f72cefba2196eb7f5c4c3c))
* **ktor:** add cordisSnapshot tree, Mermaid and health routes ([64abfcf](https://github.com/amine2233/kotlin-cordis/commit/64abfcfb375ad485079b3eaff25eae4639000794))
* **loader:** serializable LoadPlan keyed by PluginKey with handle-carrying outcomes ([#37](https://github.com/amine2233/kotlin-cordis/issues/37)) ([12aa2c7](https://github.com/amine2233/kotlin-cordis/commit/12aa2c7d1796085c57d0d54fedef4ea3379253f8))
* **runtime:** add cordis-runtime module to enable and disable plugins by key ([#38](https://github.com/amine2233/kotlin-cordis/issues/38)) ([07817a7](https://github.com/amine2233/kotlin-cordis/commit/07817a743772b6475868e78a32384745a873d681))
* **samples:** add Android Compose app with plugin drawer and Settings ([#43](https://github.com/amine2233/kotlin-cordis/issues/43)) ([59a4dab](https://github.com/amine2233/kotlin-cordis/commit/59a4dabdde263285e7c702cbd0d618bb0b462104))
* **samples:** add Ktor server sample with runtime plugin toggling ([#42](https://github.com/amine2233/kotlin-cordis/issues/42)) ([ca357b9](https://github.com/amine2233/kotlin-cordis/commit/ca357b9159aa6d24aa89cc18774c64f1de74baac))
* **samples:** add shared sample plugins and catalog build ([#41](https://github.com/amine2233/kotlin-cordis/issues/41)) ([e885bb7](https://github.com/amine2233/kotlin-cordis/commit/e885bb717f48858360920bca15518f7386a6a3a1))
* **samples:** add the Android Debug screen with the plugin tree and health ([dad3746](https://github.com/amine2233/kotlin-cordis/commit/dad37463b13df30c5e4b5b1ba72499aa1ffb562c))
* **samples:** declare each sample plugin's catalog key as its pluginKey ([1d42dea](https://github.com/amine2233/kotlin-cordis/commit/1d42dea33fe51000f995cef52672d2c43457f93a))
* **samples:** map the runtime snapshot to an Android DebugState and DebugViewModel ([355c227](https://github.com/amine2233/kotlin-cordis/commit/355c2275fac09caac9dcbdb4c1979ea70e6fc548))
* **samples:** show health and the plugin graph on the Ktor admin page ([24879da](https://github.com/amine2233/kotlin-cordis/commit/24879da60dc40b2b48010fe9023d73094c00be08))
* **testing:** deterministic withTestApp on a test dispatcher ([#36](https://github.com/amine2233/kotlin-cordis/issues/36)) ([8fa0a1e](https://github.com/amine2233/kotlin-cordis/commit/8fa0a1eab675a26734d74359d7214a6e8b3dfe5c))

### Bug Fixes

* **build:** declare Kover dependencies only when a Kover task is requested ([82d529e](https://github.com/amine2233/kotlin-cordis/commit/82d529ea308dda4f044a9fa7cc4e2839cbf6bae4))
* **kernel:** confine all graph mutations to the graph dispatcher and make listener/scope APIs thread-safe ([#23](https://github.com/amine2233/kotlin-cordis/issues/23)) ([d0c85f0](https://github.com/amine2233/kotlin-cordis/commit/d0c85f0a8c8fae83616ba123d503827cdcfe0d92))
* **kernel:** DefaultClock.delay recurses infinitely ([#21](https://github.com/amine2233/kotlin-cordis/issues/21)) ([9c57d69](https://github.com/amine2233/kotlin-cordis/commit/9c57d69bde693f8753ac79b90d0d59bf4f661e6c))
* **kernel:** isolate listener and background-job failures ([#24](https://github.com/amine2233/kotlin-cordis/issues/24)) ([d57cb2e](https://github.com/amine2233/kotlin-cordis/commit/d57cb2ed25a1b8e0494526d445a8619515e942cc))
* **kernel:** make plugin lifecycle independent of caller cancellation and fix scope leaks ([#25](https://github.com/amine2233/kotlin-cordis/issues/25)) ([c630b33](https://github.com/amine2233/kotlin-cordis/commit/c630b331dceccbc97de559ccf9af2c2005adbd58))
* **kernel:** validate plugin config on initial load ([#26](https://github.com/amine2233/kotlin-cordis/issues/26)) ([0058ead](https://github.com/amine2233/kotlin-cordis/commit/0058ead08b7543035a806036aff35673dab695eb))

### Code Refactoring

* cleanup pass on kernel, timers and tests ([#33](https://github.com/amine2233/kotlin-cordis/issues/33)) ([5af2f83](https://github.com/amine2233/kotlin-cordis/commit/5af2f83af92fd48ef904b80ecb7a66f5c34d4de3))
* enable explicit API mode and hide kernel internals ([#32](https://github.com/amine2233/kotlin-cordis/issues/32)) ([6ba255a](https://github.com/amine2233/kotlin-cordis/commit/6ba255a46ab296531355aa1c73512ad46aaf2ecb))

## 1.0.0 (2026-10-24)

### Features

* complete Kotlin Multiplatform port of Cordis ([512dfa4](https://github.com/amine2233/kotlin-cordis/commit/512dfa41c20141bc2cccce5834ed035cdb6494bb))
