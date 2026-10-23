# Contributing

AI RPG Engine currently contains design documentation, neutral [Angular](frontend/README.md) and [Spring Boot](backend/README.md) development shells, and a developer-only [runtime compatibility probe](tools/runtime_probe/README.md). Start with [the documentation index](docs/README.md), [requirements](docs/03-requirements-and-scope.md), and [roadmap](docs/16-development-roadmap.md). There is no runnable game application; canonical schemas, gameplay features, and packaged installation remain future work.

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

## Formatting and local configuration

Use the root [`.editorconfig`](.editorconfig): UTF-8, LF line endings, a final newline, spaces, two-space indentation by default, and four spaces for Java and Python. Trim trailing whitespace except in Markdown, where intentional hard line breaks are preserved. The root [`.gitattributes`](.gitattributes) normalizes text to LF across supported hosts and treats PNG assets as binary. Avoid unrelated bulk reformatting. Language-specific formatters and CI enforcement belong to the later workspace/quality tasks; these defaults do not claim a formatter is installed.

The root [`.env.example`](.env.example) contains only neutral, non-secret labels for future local tooling. No current service consumes it. Copy it to an ignored local `.env` only when the implementation documents that flow. Keep database passwords, account state, tokens, and personal paths out of examples; use private secret files and the documented owner-controlled sign-in flow. Verify new local data and build outputs against [`.gitignore`](.gitignore) before staging; an example-file exception does not authorize publishing secrets.

## Verification

For documentation, check links, terminology, examples, formulas, status, and scope. For the neutral shells, follow [frontend checks](frontend/README.md) and [backend checks](backend/README.md), including a disposable PostgreSQL database for backend tests; run checks appropriate to the affected layer and report actual results. Ordinary CI must run without a live Codex account. Account-dependent smoke checks are separately opted in and use neutral temporary content.

Do not report planned tests or predecessor reports as newly passed checks. Browser behavior, accessibility, provider integration, and deterministic mechanics need distinct evidence.

## Documentation synchronization

After each completed work item or project change, immediately update [the roadmap](docs/16-development-roadmap.md) and every affected document before beginning the next item, committing, or reporting completion. This includes changes to code, configuration, examples, rules, architecture, and documentation itself. A read-only review does not require artificial edits.

Record partial progress and actual verification. Mark an item complete only when its implementation and exit criteria are satisfied; completing repository housekeeping does not complete a runtime milestone. Keep affected specifications, README status, worked examples, risk records, and changelog consistent. Update relevant content rather than making unrelated edits just to satisfy a check.

Stage the roadmap and affected documentation together with their change. Local checkout checks can reject missing updates, but file-change checks cannot prove semantic correctness and do not replace review or continuous integration. Local assistant configuration stays ignored; private-only configuration changes do not require publishing those settings.

## Git workflow

`main` is the project's single branch. Implementation work is committed to `main` in small, verified commits and pushed only after its checks pass. Published history is never rewritten or force-pushed. A future collaborative PR workflow can be adopted explicitly. No tool or contributor should interpret a suggested commit message as authorization to publish a release.

Suggested initial commit message: `Initialize project documentation and repository setup`.

Commit messages must be in English, use imperative mood, and describe one coherent change with a concise subject. Use neutral wording focused on the result, without assistant/provider/model references, generation notices, or co-author trailers. This applies to the subject, body, and trailers; use `engine` or `project` when product branding would violate the convention. Use the owner's configured Git identity. A commit that implements a roadmap item ends its body with a line naming that item, for example `Roadmap: P00.1`.

Before committing, inspect `git status` and `git diff --cached`, then run `git diff --cached --check`. The initial commit contains design documents, decision records, the roadmap, and the labeled UI concept; it does not deliver a runnable application.

Local assistant skills and configuration are excluded by `.gitignore` and are not part of a clone. Environment files, private runtime output, and generated build artifacts are also excluded. Share only sanitized environment examples; keep private campaigns and credentials outside tracked source.

## Reporting issues

Include engine version, pack/schema/ruleset versions, adapter/runtime version if relevant, supported OS, expected behavior, actual behavior, and minimal redacted reproduction steps. Attach a neutral fixture where possible. Do not publish a private campaign archive, raw prompt trace, account identifier, or credential.

Use separate reports for engine faults, pack authoring errors, balance feedback, and feature proposals. See [SECURITY.md](SECURITY.md) for sensitive reports.
