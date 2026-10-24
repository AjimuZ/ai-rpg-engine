# ADR 0018: Protected staging and immutable pack registration

**Status:** Accepted and implemented in P04.4. **Date:** 24 October 2026.

## Context

Parser/compiler output alone cannot establish safe admission of hostile packs.
Archives can present conflicting names/lengths to different readers, while
concurrent imports or late file insertion can invalidate an apparently immutable
revision. Registration must preserve exactly the bytes that were validated.

## Decision

Use a separate bounded in-memory staging boundary with a privately constructed,
defensively copied inventory. Portable ASCII paths reject traversal, device
names, file/directory aliasing and case conflicts, including directory prefixes.
Directory input rejects static links, reparse points and special files, but must
be owner-controlled and quiescent because portable JDK path operations cannot
exclude intermediate-directory replacement by another local process. ZIP input
never extracts to disk and is the distribution boundary for untrusted content.

Accept the stored/deflate, single-disk, unencrypted, non-ZIP64 subset of
[PKWARE APPNOTE 6.3.10](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT).
Validate central/local names, flags, methods, CRCs, sizes and descriptors, require
contiguous local records and full compressed-stream consumption, and enforce
budgets on actual bytes. Ignore extra-field metadata rather than treating it as
an alternate filesystem name. Reject unsupported structures instead of repairing
them or delegating to an external extractor. This avoids adding a dependency
and keeps the accepted ZIP subset explicit; expansion of that subset requires
new security regression cases.

Allowlisted asset signatures and bounded image header dimensions are admission
checks, not a complete codec validator, decoder sandbox or rights audit. Persist
the declared license/authorship, but require later playback/export workflows to
check allocation/frame/duration limits, audience scope and provenance/rights.

Store original files as PostgreSQL `bytea` rows alongside immutable revision
coordinates, file digests and one JSONB report/export index. This keeps admission
atomic and existing database backups complete without coordinating filesystem
renames with database commits. The cost is database size and memory pressure;
defaults cap each import at 250 MiB expanded, while an eventual import endpoint
must also bound concurrent requests. Future managed-file storage requires an
explicit migration preserving identities and atomic visibility.

Serialize registry writes on the owning adventure. Dependencies use only sealed
revisions in that adventure. Identical ID/version/hash returns the existing
revision; conflicting bytes require a new release. Legacy metadata-only revisions
remain readable but cannot masquerade as admitted imports (`UNSEALED_REVISION`).
No REST endpoint, activation or gameplay loading is introduced here.

The final report seals the file set. File and seal triggers share a parent-row
lock, accept only READ COMMITTED writes (to refresh lookups after waiting), and
reject mutations/late inserts. Database checks recompute each file's SHA-256 and
length and match report count/total. A failed seal rolls back everything; reload
recomputes the normalized pack hash. Administrative database bypass is outside
these application integrity guarantees.

Report version 1 excludes timestamps, source paths and archive packaging details.
JSONB key ordering is not significant. The provisional ruleset digest frames
contract version, ruleset ID/version and sorted policy profiles; P07 must define
the mechanics identity before gameplay can rely on that digest.

## Verification and consequences

The staging corpus covers malformed local/central views, streaming descriptors,
traversal/aliases/links, CRC/truncation, amplification, media types/dimensions and
defensive copies. PostgreSQL tests cover directory/ZIP equivalence, preview,
idempotency/conflicts, adventure-local dependencies, immutable bytes/reports,
reload tampering, concurrent retries, concurrent direct insertion/sealing,
isolation-level rejection and final-seal rollback. Full verification evidence is
recorded in [the roadmap](../16-development-roadmap.md). Cross-platform malicious
directory races and full media decoding are deliberately not claimed as covered.
