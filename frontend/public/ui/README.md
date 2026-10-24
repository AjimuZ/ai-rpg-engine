# Interface graphic kit — P05.5

All assets in this inventory are original project vector/CSS geometry, copyright
2026 Azim Ben Othman, distributed under the accompanying `LICENSE.txt` (MIT).
No third-party artwork, icon package, font, reference image or raster generator
was used. There are no generated raster assets, prompts, seeds or remote jobs to
register. Editable sources are the runtime exports compiled by Angular; the
source revision is the repository commit containing each file. No export step
or external dependency is required. System fonts use the device installation.

| Family | Editable source (relative to frontend/) | Integration and states |
| --- | --- | --- |
| Buttons, reply chips, links/tabs, fields, checkbox toggles | `src/styles.scss`, `src/_ui-kit.scss` | Preview screens; native normal/hover/pressed/focus/disabled, selected navigation and pressed toggle, invalid field, busy button |
| Resource and progress bars | `src/app/preview/ui-kit.ts` (`UiMeter`), `src/_ui-kit.scss` | Sheet uses unavailable bars until canonical values exist; review page shows health/focus/XP examples, determinate/indeterminate progress; invalid numbers show unavailable text |
| Navigation/action/category/rarity/status icons | `src/app/preview/ui-kit.ts` (`ICONS`, `UiIcon`) | 20 original 24-unit SVG paths, fixed allowlist; navigation and review catalog; decorative beside visible labels; unknown icon uses neutral diamond |
| Dialogue/nameplate/portrait frame | `src/styles.scss` | Story and review page; live attribution, missing portrait initial, text-only reading |
| Item/inventory frame and independent crystal illustration | `src/app/preview/ui-kit.ts` (`UiItem`), `src/_ui-kit.scss` | Inventory empty slot; review-only sample crystal with explicit sample rarity; no implied owned item |
| Tooltip/help surface | `src/_ui-kit.scss` (`kit-help`) | Native persistent disclosure, usable on keyboard/touch, no hover-only information |
| Save/loading/error | `src/app/preview/ui-kit.ts`, `src/_ui-kit.scss` | Review page icons plus text; story retains scoped live connection/draft messages |
| Sample scenes | `public/scenes/*.svg`, `public/scenes/README.md` | Two editable original MIT environments, image-error fallback |
| Sample portraits | `public/portraits/*.svg`, `public/portraits/README.md` | Two editable original MIT portraits, scoped speaker IDs and image-error fallback |

Review `/interface-kit` (linked in Settings) for all sample families. Interactive
controls expose real native hover/pressed/focus states rather than flattened
screenshots. Example controls affect only their local review state. Selected
navigation retains `aria-current`; toggles use native checked/`aria-pressed`.
Symbols never replace required visible text. Numeric meters expose bounded
`role=meter` ARIA values; absent/nonfinite data cannot fabricate zero balances.
Indeterminate progress is labeled, and errors use explanatory text.

The shared palette is plum #141522/#202032, gold #e6c783, text #f0edf5,
secondary text #c0bbd0 and cyan #94d6de. Native focus rings, responsive wrapping,
increased contrast, forced colours and reduced motion are covered by theme CSS.
No translated label or interactive hit area is baked into artwork. English is
the current preview language; reviewed Italian resources remain P05.6. Runtime
art generation, persistent campaign visual identities and their provenance
records remain P17; this kit does not establish those capabilities.
