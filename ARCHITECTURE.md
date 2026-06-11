# eventmaster — runtime architecture

## Diagrams

The control flow lives as standalone Mermaid files so they pan/zoom in a
proper viewer instead of getting shrunk to fit a Markdown column:

- [`docs/architecture.mmd`](docs/architecture.mmd) — full connector run
  (`ConnectorRun.execute()`): Gmail + Luma calendars + Instagram → dedup +
  rotation → Hermes webhook.
- [`docs/test-command.mmd`](docs/test-command.mmd) — synthetic webhook test
  path (`java -jar eventmaster.jar test [--error] [--dry-run]`).

To view, either:

- Paste the file into <https://mermaid.live>, or
- Install the **Mermaid Preview** extension in Cursor/VS Code and open the
  file (it gives you a zoomable, pannable canvas), or
- Render to SVG/PNG with the Mermaid CLI:
  `npx -p @mermaid-js/mermaid-cli mmdc -i docs/architecture.mmd -o docs/architecture.svg`.

Update the `.mmd` files whenever the control flow in `ConnectorRun`,
`EventStore`, or the source adapters (`EmailParser`, `LumaScraper`,
`InstagramPostClassifier`) changes meaningfully.

## Invariants worth preserving

A few correctness-relevant rules the diagram encodes — easy to break by
accident during refactors:

- **Single dedup file for two source types.** Gmail message IDs and Instagram
  post IDs both live in `processed_ids.txt` (`StateStore.loadProcessedIds`,
  `ConnectorRun.processGmail` / `processInstagram`). Collisions are unlikely
  in practice but worth remembering when changing ID formats.
- **Instagram "bootstrap" is one-shot per handle.** The first run for a new
  handle marks every currently-visible post seen *without* classifying —
  preventing a huge backfill into Hermes. Removing the entry from
  `instagram_bootstrapped` (in `connector-state.json`) re-arms the bootstrap.
  See `ConnectorRun.processInstagram`.
- **Instagram fetch throttling is global, not per-handle.** ScrapeCreators has
  no `since_id` parameter — every call returns the latest page — so the only
  credit-saving lever is fetching less often. `INSTAGRAM_FETCH_INTERVAL_HOURS`
  (default 6) gates the entire Instagram phase: when the connector runs more
  frequently (e.g. hourly cron), intermediate runs skip ScrapeCreators
  entirely. The timestamp lives in `connector-state.json` as
  `instagram_last_fetched_at` and advances when a fetch cycle completes
  (whether or not individual handles returned errors). Set the interval to
  `0` to disable throttling and fetch on every run. When a throttled run
  skips the Instagram phase, any Instagram-related codes in
  `last_warning_codes` are carried forward in `WarningDiff` rather than
  reported as resolved — "didn't check" must not look like "recovered".
- **Classifier failures are atomic.** If the Hermes API call throws, the
  queued post IDs are *not* added to `processedIds`, so the same posts retry
  next run. Conversely, on success every post in the batch is marked seen
  even if Hermes returned `isEvent=false` for it — desired behaviour to avoid
  re-classifying non-event posts.
- **Storage and notification use separate rules.** All non-past events
  are kept in `upcoming_events.json`. The 7-day window
  (`DateFilters.isInNextNDays`) gates the events webhook only: an event
  is included in `toNotify` when it falls within the window and
  `notifiedAt` is unset. Early Instagram discoveries are stored silently
  and notified when the window opens; `EventStore.markNotified` stamps
  `notifiedAt` after a successful webhook so cron ticks don't re-fire.
  Unknown-date events still pass `isInNextNDays` (returns `true`) and
  notify on first store, same as before.
- **Events and warnings are separate webhook calls.** A successful run can
  fire up to two webhooks, in order: events (`WebhookPayload`) and warnings
  (`WarningsPayload`). The agent receives "what's new" and "what's degraded"
  as distinct messages; an events payload never carries warning material and
  vice versa.
- **Events webhook gates state commits; warnings webhook is best-effort.**
  `hermes.notifyEvents` runs *before* any state writes. If it returns
  `false`, `ConnectorRun` throws `WebhookDeliveryException` and the catch
  block treats it as a run failure: `processedIds`, `instagramBootstrapped`,
  `upcoming_events.json`, `past_events.json`, and the zeroed failure counter
  are all left unsaved, so the next run re-scrapes and retries the same
  notification. The catch intentionally skips the follow-up error
  notification in this case to avoid hammering a webhook that's already
  known to be down. The warnings webhook runs *after* events succeeds but
  is best-effort: a failure logs loudly but does not undo the committed
  events delivery — instead, `last_warning_codes` is left unchanged so the
  next run sees the same warning diff and retries.
- **Warnings webhook fires on warning-set change, not every run.**
  `ConnectorRun` computes `WarningDiff.compute(currentWarnings,
  loadLastWarningCodes())`. The warnings webhook only fires when the
  dedup-key set differs from the previous run — a new failure appearing, a
  previously firing failure recovering, or one code swapping for another at
  the same source. Hourly crons against a chronically degraded source
  therefore produce one notification when it breaks and one when it
  recovers, not 24 per day. The payload carries `current` (full sorted
  warning list) and `resolved` (codes that were firing last run and now
  aren't), so the agent can announce recoveries.
- **Events webhook only fires when there's something to say.** On a
  successful run with zero `toNotify`, the events webhook is *not*
  notified (state still commits, warnings webhook may still fire if the
  warning set changed). On any top-level exception, the events channel is
  used for the error notification (`hasErrors=true`, prior-failure counter
  incremented). Warnings are not sent in the error path — they'll catch up
  on the next successful run.
- **`consecutive_failures` tracks run-level failures only.** A source-level
  degradation (Gmail IMAP down, ScrapeCreators 402, a Luma slug returning
  500) becomes a `RunWarning` and does *not* bump `consecutive_failures`.
  Only a top-level exception (state write failure, unexpected code path) or
  an events webhook delivery failure increments it. This keeps the 8-run
  escalation alarm meaningful — it fires when the connector itself is
  broken end-to-end, not when one of three sources is having a bad day.
  Source-level escalation policy lives in the Hermes agent's reaction to
  the warnings channel.
- **The Hermes agent prompt knows about both payload shapes.**
  `hermes/agent-prompt.txt` branches on which fields are populated: events
  payloads carry `newEvents` + `hasErrors` + `health`; warnings payloads
  carry `current[]` + `resolved[]`. The single agent prompt file serves
  both channels (the `instructions` field on both payload records reads
  from the same `agentPromptPath`). Keep this branch logic in sync with
  the `WebhookPayload` and `WarningsPayload` record shapes — changing a
  field name on either record without updating the prompt will silently
  produce useless agent replies.
- **Source phases are isolated.** `processGmailSafely`,
  `processLumaCalendarsSafely`, and `processInstagramSafely` each wrap
  their work in try/catch. A failure in one source never prevents the
  others from running. `ScrapeCreatorsClient.fetchUserPosts` and
  `LumaScraper.fetchCalendar` throw typed exceptions
  (`ScrapeCreatorsException`, `LumaFetchException`) on hard failure so the
  caller can map them to `RunWarning`s without having to disambiguate
  "empty because broken" from "empty because nothing new". An empty list
  from either method now strictly means "API said nothing matched".
- **Dedup uses hint-set intersection, not a single key.** `EventStore.eventKeys`
  emits up to three identity hints per event — `luma:<normalised-url>` (only
  for real Luma hosts), `td:<normalised-title>|<date>`, and
  `dl:<date>|<normalised-location>`. Two events are duplicates if any pair of
  hints overlap. This catches cross-source matches (same event scraped from
  email + Luma calendar, or same gathering posted on Instagram and announced
  via ICS) that a single-key approach would miss.
- **Instagram permalinks are not identity hints.** Unmatched Instagram-derived
  events still get their permalink in the `lumaUrl` slot so the agent has a
  clickable link, but `EventStore.eventKeys` only emits a `luma:` hint when
  the host is actually `lu.ma` or `luma.com`. IG permalinks fall through to
  `td:` / `dl:` hints derived from the LLM-extracted title/date/location.
- **The classifier matches by per-call id, not by Luma URL.**
  `InstagramPostClassifier.classifyPosts` assigns each upcoming event a
  sequential `u0`/`u1`/… id and asks the LLM to echo it back as
  `matchedUpcomingEventId`. This lets the LLM signal a match against
  ICS-sourced or body-text-sourced existing events that have no Luma URL to
  point at. Matched posts are dropped entirely (no separate Event emitted);
  unknown ids are treated as unmatched with a warning, guarding against
  hallucinated ids.
- **Classifier context spans the whole run, not just on-disk state.**
  `ConnectorRun.processInstagram` hands the LLM the on-disk `upcoming` list
  *plus* the non-past events scraped from Gmail and Luma earlier in the same
  run. Without this, an Instagram post about an event we just discovered via
  email or a Luma calendar would not match any `upcomingEvents` entry, fall
  back to `post.permalink()` as its URL, and slip past the lumaUrl-based
  dedup in `rotateAndClassify` — producing a near-duplicate notification.
- **Classifier context is pre-deduped by hint-set.** Before sending,
  `ConnectorRun.processInstagram` runs `EventStore.dedupByHints` over the
  merged context so the LLM doesn't see two copies of the same event (the
  on-disk record and a fresh Luma re-scrape of it, which is the dominant
  case). Same dedup function as `rotateAndClassify`, just applied earlier —
  pure token / clarity savings, no semantic change.
