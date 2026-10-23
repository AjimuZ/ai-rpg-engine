# Managed image capability (P01.6)

Verified scope: runtime **0.164.0**, the existing managed narration profile in
[`docker/codex-runtime`](../../docker/codex-runtime), on the developer platform in
[the compatibility matrix](COMPATIBILITY.md). This is explicit unavailability,
not a claim that the upstream product or every account lacks image generation.

| Boundary | Recorded result |
| --- | --- |
| Capability | `unavailable`, reason `managed-profile-tool-free` |
| Restricted invocation | None (`invocation: null`); the current profile offers no image tool |
| Supported generation output formats | Empty list; no PNG, JPEG, WebP, SVG, base64 image payload, file path or remote URL is accepted as generated artwork |
| Account entitlement | `unverified`; neither text success, account presence, catalog entries nor development-client tools establish runtime image access |
| Cost / allowance | `unverified`; no free, included, unlimited, price, quota or budget guarantee |
| Actual generation | Not run; `generationVerified: false` |
| Fallback | Text and existing registered/catalog/category assets; no image readiness prerequisite for text discovery |
| Paid access | No API key, paid overage, alternate service or broader tools enabled |

## Evidence and refusal

[`evidence/images-0.164.0.json`](evidence/images-0.164.0.json) is the neutral offline
projection returned by `contract.image_capability()`, also included under
`imageGeneration` in discovery reports. It is a policy record, not a live account
probe or an image-generation transcript. Reproduce it without Docker, sign-in,
network access, inference, prompts or output-path arguments:

```powershell
py -B tools/runtime_probe/probe.py images
py -B tools/runtime_probe/probe.py images --generate
py -B -m unittest discover -s tools/runtime_probe -p test_images.py
```

The first command exits zero with the record. The second exits **1** with
`image-generation-unavailable`, before launching or connecting to a runtime.
`contract.require_image_generation(policy=..., output_format=...)` always refuses,
including `bounded` opt-in and every requested format. Unknown options are CLI
errors, never account, endpoint or output-path configuration. These are developer
probe boundaries; the production job/asset pipeline remains P17 work.

The managed configuration disables `image_generation` and `view_image`; the
catalog supplies no tools or image inputs. Docker isolation checks inspect actual
upstream `tools: []` requests and inject hostile function calls named
`image_generation` and `image_gen` alongside the six existing host-tool probes.
The pinned runtime refuses all eight and completes a valid neutral text turn.
This checks tool omission and function dispatch, not a remote image backend or
built-in image-result protocol. The existing turn validator rejects non-text or
unknown items. No runtime-account volume is needed or mounted for these checks.

## Requalification before enablement

Changing owner policy alone cannot enable this capability. P17.6 must first
qualify a supported invocation with the exact runtime pin and an explicitly
authorized dedicated runtime account, recording actual entitlement, usage/cost
boundaries, output MIME/container formats, transparency/dimensions/size limits,
terminal/cancellation behavior and failure handling. Sign-in remains human-only;
never inherit development credentials or configure API billing as a fallback.

Any image-capable profile needs a separate reviewed isolation decision and
negative tests. Preserve tool-free narration and canonical storage isolation;
use public appearance inputs, managed temporary/output roots and validated asset
registration. Require explicit owner enablement and finite request, queue,
concurrency and account budgets before any call. Retain text/catalog fallback
when unsupported, disabled, quota-limited, failed or stale. P01.6 does not deliver
the P17 job pipeline, UI fallback, asset validation, saved artwork or QA-31.

The official [API image-tool documentation](https://developers.openai.com/api/docs/guides/tools-image-generation)
describes a distinct API interface (reviewed 23 October 2026); its options and
formats are not evidence of support or account entitlement in this pinned
app-server profile. No API client or dependency is added.
