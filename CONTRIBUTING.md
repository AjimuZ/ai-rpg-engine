# Contributing

AI RPG Engine is currently a documentation-only project. Start with [the documentation index](docs/README.md), [requirements](docs/03-requirements-and-scope.md), and [roadmap](docs/16-development-roadmap.md). Application folders, schemas, tests, and installation commands will be added during authorized implementation.

## Principles

- Keep engine behavior independent of a particular adventure or protagonist.
- Keep deterministic rules and canonical writes in the backend.
- Treat Codex output and imported content as untrusted proposals.
- Preserve campaign identity, knowledge boundaries, idempotency, and recoverability.
- Use English for public technical content; story text follows its declared locale.
- Make small coherent changes with relevant evidence and updated documentation.
- Preserve attribution and avoid introducing private saves, credentials, or unlicensed assets.

## Licensing contributions

Contributions to project software and documentation must be available under the [MIT License](LICENSE). Preserve existing copyright and permission notices. Identify the origin, license, and redistribution permission of any separately licensed asset or third-party material. Do not submit material you are not authorized to distribute. Using the engine does not require adventure authors to license their own packs under MIT.

## Changes

Describe the concrete problem, resulting behavior, affected contracts, verification, and any migration requirements. Changes to architecture, content compatibility, rules, AI authority, or security require a corresponding decision record. Distinguish a supported feature from a proposed extension.

Do not add a new AI provider, executable pack scripting, distributed service, or setting-specific engine constant as incidental implementation work. Raise a scoped proposal and explain its effect on the current design.

## Verification

For documentation, check links, terminology, examples, formulas, status, and scope. For future code, run checks appropriate to the affected layer and report actual results. Ordinary CI must run without a live Codex account. Account-dependent smoke checks are separately opted in and use neutral temporary content.

Do not report planned tests or predecessor reports as newly passed checks. Browser behavior, accessibility, provider integration, and deterministic mechanics need distinct evidence.

## Documentation synchronization

After each completed work item or project change, immediately update [the roadmap](docs/16-development-roadmap.md) and every affected document before beginning the next item, committing, or reporting completion. This includes changes to code, configuration, examples, rules, architecture, and documentation itself. A read-only review does not require artificial edits.

Record partial progress and actual verification. Mark an item complete only when its implementation and exit criteria are satisfied; completing repository housekeeping does not complete a runtime milestone. Keep affected specifications, README status, worked examples, risk records, and changelog consistent. Update relevant content rather than making unrelated edits just to satisfy a check.

Stage the roadmap and affected documentation together with their change. Local checkout checks can reject missing updates, but file-change checks cannot prove semantic correctness and do not replace review or continuous integration. Local assistant configuration stays ignored; private-only configuration changes do not require publishing those settings.

## Git workflow

The initial solo-work default follows the primary project: small changes on `main`, with commits and pushes controlled by the owner. A future collaborative PR workflow can be adopted explicitly. No tool or contributor should interpret a suggested commit message as authorization to publish.

Suggested initial commit message: `Initialize project documentation and repository setup`.

Commit messages must be in English, use imperative mood, and describe one coherent change with a concise subject. Use neutral wording focused on the result, without assistant/provider/model references, generation notices, or co-author trailers. This applies to the subject, body, and trailers; use `engine` or `project` when product branding would violate the convention. Use the owner's configured Git identity.

Before committing, inspect `git status` and `git diff --cached`, then run `git diff --cached --check`. The initial commit contains design documents, decision records, the roadmap, and the labeled UI concept; it does not deliver a runnable application.

Local assistant skills and configuration are excluded by `.gitignore` and are not part of a clone. Environment files, private runtime output, and generated build artifacts are also excluded. Share only sanitized environment examples; keep private campaigns and credentials outside tracked source.

## Reporting issues

Include engine version, pack/schema/ruleset versions, adapter/runtime version if relevant, supported OS, expected behavior, actual behavior, and minimal redacted reproduction steps. Attach a neutral fixture where possible. Do not publish a private campaign archive, raw prompt trace, account identifier, or credential.

Use separate reports for engine faults, pack authoring errors, balance feedback, and feature proposals. See [SECURITY.md](SECURITY.md) for sensitive reports.
