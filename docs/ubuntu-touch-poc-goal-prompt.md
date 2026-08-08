# Goal prompt — Ubuntu Touch X11/Libertine PoC

Paste this into a fresh session (ideally a dedicated worktree/branch — this is exploratory,
keep it off `master`) to drive the build. Work phase by phase; do not skip a phase's exit
criteria to get to the next one, and stop at any Phase 0 "no" rather than pushing forward.

---

You are building a proof-of-concept to determine whether CoreApp (this repo) can ship on
Ubuntu Touch via a Compose Desktop UI running over Xwayland inside a Libertine container,
backed by a headless core service. Full context and rationale is in
`docs/ubuntu-touch-poc-plan.md` — read it first.

This is exploratory and separate from the supported Android/iOS product. Do not modify
existing Android/iOS code paths, do not touch the Ring recording pipeline
(`queueLocalAudioProcessing` and everything downstream of it), and keep all new code isolated
to new modules/targets so nothing here can regress the shipping app. Follow the repo's
existing conventions (DI via Koin, expect/actual placement, no `init {}` blocks, minimal
comments) for any shared Kotlin code you add or extend.

Work through `docs/ubuntu-touch-poc-plan.md` phase by phase:

1. **Phase 0 spikes first, in isolation, throwaway code.** Each spike has an explicit go/no-go
   in the plan. Report the result of each honestly, including partial/ugly successes (e.g.
   "renders but input is laggy") — don't round up to a clean pass. If a spike hard-fails, stop
   and report that QML is the recommended path instead of continuing.
2. Only after all four Phase 0 spikes pass, proceed to Phase 1 (core service skeleton), then
   Phase 2 (audio capture into the existing pipeline), then Phase 3 (thin UI client).
3. Stop at Phase 4 and report back rather than making the ship/iterate/pivot-to-QML call
   yourself — that decision needs a human looking at the device, not just a summary of the
   PoC's own progress.

As you complete each phase, update `docs/ubuntu-touch-poc-plan.md`'s status and record what
was actually learned (especially spike results and any surprises) — the doc should stay an
accurate account of what's proven, not just the original intent.

Keep commits/PRs single-purpose per the repo's guidelines even though this is exploratory
work — one phase's worth of work per PR, not one giant PoC branch dumped at the end.
