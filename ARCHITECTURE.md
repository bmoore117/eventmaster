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
- **Classifier failures are atomic.** If the Hermes API call throws, the
  queued post IDs are *not* added to `processedIds`, so the same posts retry
  next run. Conversely, on success every post in the batch is marked seen
  even if Hermes returned `isEvent=false` for it — desired behaviour to avoid
  re-classifying non-event posts.
- **The 7-day window is asymmetric.** `DateFilters.isInNextNDays` keeps events
  with unparseable/missing dates (returns `true`); `DateFilters.isPast`
  *drops* them from "past" classification (returns `false`). So unknown-date
  events bias toward staying in `upcoming` — the right tradeoff for a
  notification system but a footgun if you change either method.
- **Webhook delivery gates state commits.** On the success path
  `HermesClient.notify` is called *before* anything is persisted. If it
  returns `false` (non-2xx, network error, missing prompt file, etc.),
  `ConnectorRun` throws `WebhookDeliveryException`, which the catch block
  treats as a run failure: `consecutive_failures` is incremented and
  `processedIds`, `instagramBootstrapped`, `upcoming_events.json`,
  `past_events.json`, and the zeroed failure counter are all left unsaved,
  so the next run re-scrapes and retries the same notification. The catch
  block intentionally skips the follow-up error notification in this case to
  avoid hammering a webhook that's already known to be down.
- **Webhook only fires when there's something to say.** On a successful run
  with zero `newlyAdded`, Hermes is *not* notified (and state still
  commits). On any other failure path, Hermes *is* notified with
  `hasErrors=true` and the prior-failure counter incremented — the only
  signal of consecutive-failure escalation to the agent.
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
