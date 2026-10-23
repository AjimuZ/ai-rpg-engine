# Third-Party Notices and Heritage

Project software and documentation are distributed under the [MIT License](LICENSE), copyright 2026 Azim Ben Othman. The notices below preserve upstream attribution and do not override separate terms for third-party material.

## The World I Can Develop

AI RPG Engine's design builds primarily on the architecture and product analysis of [The World I Can Develop](https://github.com/AjimuZ/the-world-i-can-develop), supplied locally by the project owner. Preserve the following notice with distributions that reuse its licensed material. The notice applies to that upstream material and is retained alongside the project's root MIT license.

```text
MIT License

Copyright (c) 2026 Azim Ben Othman

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Local demo

The owner-supplied `the-world-i-can-develop-demo` informed the analysis of Codex lifecycle, memory renewal, request handling, knowledge projections, and media behavior. Its code, private campaign, images, audio catalog, runtime logs, and backup files are not bundled here. No standalone license file was observed in its root. Establish explicit rights before copying any of those materials into a future public release.

## Apache Maven Wrapper

`backend/mvnw` and `backend/mvnw.cmd` are the official Apache Maven Wrapper 3.3.4
only-script distribution, produced by the Apache Maven Wrapper plugin. Preserve
their upstream headers and the accompanying [Apache License 2.0](backend/.mvn/wrapper/LICENSE)
and [NOTICE](backend/.mvn/wrapper/NOTICE). The scripts are build tooling, separate
from the project's MIT-licensed application code. Source:
[Apache Maven Wrapper 3.3.4](https://github.com/apache/maven-wrapper/tree/maven-wrapper-3.3.4).

The Angular CLI 22.2.3 scaffold supplies the initial workspace configuration under
its MIT license (Copyright Google LLC). The application template has been replaced
with the neutral project shell. Downloaded npm/Maven dependencies retain their
individual licenses in their distributions; the Angular production build also
emits its third-party license inventory. The backend `packaged-ui` profile retains
that inventory as `/3rdpartylicenses.txt` alongside the compiled browser assets
in the executable jar. Preserve it with distributions. No dependency binary is
committed here.

## Other references

Official documentation is linked for technical and rights background in [the reference register](docs/19-glossary-and-references.md). No OpenAI SDK, Codex binary, D&D/SRD rule text, external stat block, third-party image, audio file, font, or model checkpoint is distributed in the tracked source tree.

Future dependencies and assets require their own license/provenance inventory. Mentioning a technology or an inspiration does not imply affiliation or transfer of rights.

## Music research references — no bundled recordings

The [dynamic music specification](docs/24-dynamic-music-and-credits.md) records two candidate works by Kevin MacLeod (incompetech.com): [Wholesome](https://commons.wikimedia.org/wiki/File:Wholesome_by_Kevin_MacLeod.ogg), declared [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), and [Darkest Child, ISRC USUAN1100783](https://commons.wikimedia.org/wiki/File:Darkest_Child_%28ISRC_USUAN1100783%29.mp3), declared [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/). Source/file-page declarations were checked on 23 October 2026. These are reference-only credits: no recording is copied, modified, installed or played by this change. Before runtime admission, retain the selected recording's complete rights evidence, exact license version, source and any processing notice. The project's MIT license does not relicense that music.
