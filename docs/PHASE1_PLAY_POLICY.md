# Phase 1 — Google Play policy check (in-process app virtualization)

Written 2026-10-14. Question: can an app that runs *other* installed apps inside itself
(in-process virtualization, the BlackBox approach) be published on Google Play for our use?
Each point is marked **[confirmed from source]** (quoted/paraphrased from an official Play
policy page) or **[my reading]** (my judgement, not policy text).

## Short answer

**Conditionally yes, but with one hard, non-negotiable obligation and real suspension
risk.** App virtualization is not banned outright — app cloners have historically shipped —
but Google added a 2023 rule aimed squarely at our category, and enforcement of the
Device & Network Abuse policy against cloners has happened with little warning.

## 1. "On-device Android Containers" rule — the one that governs us (REQUIRE_SECURE_ENV)

**[confirmed from source]** Google's 12 July 2023 policy update: *"all apps running
On-device Android Containers, which simulate all or portions of the Android OS, must respect
the new REQUIRE_SECURE_ENV flag."* Another app *"can add this flag to their manifest to
indicate they don't want to be run in an On-device Android Container,"* and a container app
*"must not load flagged apps into their containers."*

**[my reading]** Running a guest app in-process on a virtual display we own is "simulating
portions of the Android OS," so we are an on-device container and this rule applies to us.
The hard obligation: **before launching any guest, we must check that guest's manifest for
`REQUIRE_SECURE_ENV` and refuse to run it if set.** Banking, DRM/streaming and some
enterprise apps are the likely setters. This must be built into the engine, not bolted on.
Source: <https://support.google.com/googleplay/android-developer/answer/13678785>

## 2. Device and Network Abuse policy

**[confirmed from source]** *"We don't allow apps that interfere with, disrupt, damage, or
access in an unauthorized manner the user's device."* Listed examples include *"Apps that
install other apps on a device without the user's prior consent."* The VM/interpreter
carve-out (*"This restriction does not apply to code that runs in a virtual machine or an
interpreter"*) is about **running executable code downloaded from outside Play** — it is not
a blanket licence to run other apps. The Device & Network Abuse page itself does not directly
address running other apps; that case is handled by the on-device container rule in §1.
Source: <https://support.google.com/googleplay/android-developer/answer/9888379>

**[my reading]** We stay on the right side of this only if: the user explicitly chooses
which of *their own* installed apps to mirror; we never install apps for them silently; we
don't bypass another app's security or ads. We run apps the user already has — we don't
download or sideload them — so the "install without consent" and "code from outside Play"
examples should not apply.

## 3. QUERY_ALL_PACKAGES (listing installed apps)

**[confirmed from source]** `QUERY_ALL_PACKAGES` is a **restricted permission**. Play treats
the list of installed apps as personal/sensitive data. It is allowed only when broad
visibility is the app's core, user-facing purpose; the developer must justify why a less
intrusive method won't work, document the feature prominently in the store listing, and
complete the Permissions Declaration Form. Listed permitted cases: device search,
antivirus, file managers, browsers. Misuse → removal or account termination.
Source: <https://support.google.com/googleplay/android-developer/answer/10158779>

**[my reading] We should try to ship WITHOUT `QUERY_ALL_PACKAGES`.** We likely do not need
the full inventory: a `<queries>` element with `<intent><action MAIN/><category LAUNCHER/>`
returns exactly the user-launchable apps — which is the set a user would ever want to mirror
to a desktop. That is the "less intrusive method" Google expects. If a reviewer ever asks,
"a desktop/remote-display tool that mirrors the user's chosen apps" is a plausible core
purpose, but the launcher-intent query keeps us out of the restricted-permission process
entirely. **Decision for the manager: default to `<queries>`, do not request
`QUERY_ALL_PACKAGES` unless a real feature forces it.**

## 4. Hidden-API use

**[my reading, not a Play *policy* point]** Google Play policy does not forbid reflection
onto hidden/greylisted framework APIs. The risk is **technical, not policy**: hidden-API
restrictions can break the engine on a new Android version, and aggressive reflection can
trip Play Integrity / SafetyNet-style attestation that *other* apps (banking) rely on — which
loops back to §1 (those apps will set `REQUIRE_SECURE_ENV`). No store-listing declaration is
required for hidden-API use itself.

## 5. Precedent

**[my reading]** App cloners (Parallel Space, Island, work-profile tools) have shipped on
Play for years, which shows the category is not per-se banned. But I could not confirm from
an official source how Google treated Parallel Space specifically, and there is at least one
secondhand account of a cloner being pulled under Device & Network Abuse with a failed
appeal. So precedent supports feasibility, not safety.

## Risks, ranked (all [my reading])

1. **Not honoring `REQUIRE_SECURE_ENV` → removal.** This is the clearest, most specific rule
   against us. Must be engineered in from Phase 1 onward.
2. **Restricted-permission rejection** if we ship `QUERY_ALL_PACKAGES` without a strong,
   documented core-purpose justification. Avoidable by using `<queries>`.
3. **Discretionary Device & Network Abuse enforcement**, which can be sudden and hard to
   appeal, as the cloner precedent shows.
4. **Technical breakage** from hidden APIs across Android versions (not a policy risk).

## Bottom line for the manager

Shippable to Play *if* we (a) build `REQUIRE_SECURE_ENV` checking into the engine and refuse
flagged guests, (b) avoid `QUERY_ALL_PACKAGES` by using the launcher-intent `<queries>`,
and (c) frame the store listing around the user mirroring their own chosen apps. None of
this blocks the Phase 1 *technical* bet; it shapes how the engine must behave. Needs a
DECISIONS.md entry before anything ships.
