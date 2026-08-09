# `com.tripletriad:core`, as a package

The one dependency this server has that Maven Central cannot supply, and the reason CI could not
build an image until now. This document is written from the consumer's side — it is the contract
this repository depends on, kept here so that a change to it is visible to the people it breaks.

---

## The problem it solves

The Phase 5 design has the server verify a match by **replaying it with the real engine**. That is
only meaningful if there is exactly one engine, linkable from both the client and the server, which
is why `:core` was extracted in the first place.

Extracted is not the same as available. While `:core` existed only in `AS3-Triple-Triad`, published
by `./gradlew :core:publishToMavenLocal` into a developer's `~/.m2`, this repository could be built
on exactly the machines that had also built the client. A GitHub runner is not one of them — so the
image build reached out of its own context for a mounted `~/.m2`, and CI, which has none, could not
build the server at all. No automated deployment is possible from a build that only one laptop can
perform.

---

## The arrangement

A repository, `korobetski/tto-core`, holding the module and publishing it to GitHub Packages.

```
tto-core (publishes)  ──►  ghcr / GitHub Packages  ──►  tto-server (CI, image build)
        │                                                   AS3-Triple-Triad (client)
        └──────────────────────────────────────────────────►
```

Both consumers read the same artifact. That is the property worth protecting: the moment the client
builds `:core` from source and the server reads a published copy, the two can drift, and the whole
reason for the extraction is gone.

### What the client repository does with it

Two options were open, and they were not equally good:

- **The client keeps `include(":core")` and the new repository holds a copy of the same sources.**
  Cheap to set up and wrong in the long run: two engines that are equal only as long as somebody
  keeps them equal.
- **The sources move to `tto-core`, and `AS3-Triple-Triad` consumes the published artifact exactly
  as this repository does.** More disruptive — the client's `:shared` depended on
  `project(":core")` — and the only version where "there is exactly one engine" stays true without
  discipline.

The second one was taken. `include(":core")` is gone from the client's `settings.gradle.kts`, its
`:shared` depends on `libs.tripletriad.core`, and the module's four commits went to `tto-core`
through `git subtree split` rather than arriving as a single import.

### Versioning

`0.1.0`, and deliberately not a SNAPSHOT any more. A release pinned to a moving version is not
pinned: `verify` re-run on the same tag can resolve a different engine than the one that was
released. This repository was insulated in practice — the image is built once and deployed by
digest, so what runs is fixed — but the insulation was accidental rather than designed.

The version lives in exactly one place per repository, and raising it is three edits in a fixed
order: tag `tto-core`, then the client's `libs.versions.toml`, then this repository's. The order is
not a style preference — a client submitting a transcript that its server replays with a different
engine is the one bug the extraction exists to make impossible.

---

## Publishing, from `tto-core`

A tag, and the same shape as this repository's `release.yml`: the full gate re-run on the tagged
commit, then the upload, in one job so that no green run on `main` is taken on trust for a commit
that may never have been on `main`.

```
git tag -a v0.2.0 -m "What changed" && git push origin v0.2.0
```

Two details in that workflow are load-bearing and easy to get wrong:

- **It runs on macOS.** Kotlin/Native cross-compiles nothing. A `publish` on a Linux runner
  succeeds while silently omitting the `iosArm64` and `iosSimulatorArm64` klibs, and the resulting
  module metadata advertises a library with no Apple variants — a failure that surfaces in the
  client repository, on the one target only ever built in CI.
- **The version comes from the tag** (`-PcoreVersion=${GITHUB_REF_NAME#v}`), not from a constant in
  the build file. Nothing in `tto-core` states a released version, so the tag and the artifact
  cannot come to disagree about what `v0.2.0` is.

**Publishing is one-way.** GitHub Packages will not overwrite a released version, and a version
resolved once is in somebody's cache regardless. There is no un-publishing a bad build; the remedy
is another version.

---

## Reading it, from here

Already wired: see `settings.gradle.kts`. Two things about it are worth knowing before they
surprise someone.

**GitHub Packages requires authentication even for a public package.** An anonymous request gets a
401, not a 200. So every consumer — every developer, CI, and the image build — needs a token with
`read:packages`. There is no configuration in which this repository builds without one.

**`mavenLocal()` is listed first, on purpose.** A developer only has a `:core` there because they
ran `publishToMavenLocal` in the client repository, which is an explicit act with one purpose:
trying an engine change against this server before releasing it. The published copy first would
silently defeat that. The cost is the mirror image — a local install that is no longer wanted keeps
shadowing the real artifact, and the fix is `rm -rf ~/.m2/repository/com/tripletriad`.

---

## Related

- `deployment.md` — where the token is configured, in CI and on the VPS
- `../settings.gradle.kts` — the resolution order, with the reasoning inline
