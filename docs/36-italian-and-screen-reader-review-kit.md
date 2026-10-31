# Italian and screen-reader review kit

This document gathers everything a human reviewer needs for the two open P05.6 gates: the
independent review of the Italian interface text and the screen-reader pass of both language
variants (see [localization](29-localization.md) and QA-35 in
[quality and acceptance](15-quality-and-acceptance.md)). It prepares the review; it is not the review.

## Status (31 October 2026)

| Gate | State |
| --- | --- |
| Independent Italian review | **Not done.** One author pass by the session that prepared this kit (section 3) found and fixed two clear errors. It is not independent. |
| Screen-reader review of the English and Italian variants | **Not done.** Section 4 is the script; its evidence tables are empty. |
| Axe and accessibility-tree reports | Recorded in section 5 as **tool evidence**. Neither tool is a screen reader and neither replaces the review. |

The P05.6 roadmap box stays unchecked until a reviewer fills sections 2 and 4 and the owner signs off
(P05.6-O).

## 1. How to use this kit

- Review the Italian text in section 2 against its English source. The source is authoritative; the
  catalog is `frontend/src/locale/messages.it.xlf` (433 units at the time of writing, checked by
  `npm run check:i18n`; since P21.3-D the catalog has 515, and the 82 `models.*` units of the Settings → Models panel are
  not in the section 2 table yet and need adding before the review). Write the verdict in the last column of each row: `OK`, or the change you ask for
  with a reason. Mark a row `?` when the meaning depends on context you cannot see.
- Placeholders such as `{INTERPOLATION}`, `{path}` or `{day}` are values inserted at runtime. Their order may
  change in Italian; their number and names may not. `{VAR_PLURAL, plural, ...}` is an ICU plural. `<pc></pc>`
  and `{TAG_INPUT}` mark an inline element (a button icon or a checkbox) kept in place.
- Rows marked **(first pass)** in the last column were changed by the preparing session; confirm or reject them.
- Do not edit the catalog from this document. Send the verdicts to the owner, who changes the catalog together
  with the English source (see `frontend/src/locale/README.md`).
- Strings that are intentionally literal and never translated: adventure titles (for example `Mosslight Trail`,
  `Silent Relay`), icon identifiers, asset paths, resource keys, destinations and the user's own drafts.
- Where a string appears: a heading, label, button or message in the screen named by the section. Strings in
  section 2.9 onward belong to the saved-campaign view, which the packaged installation cannot reach today
  (no owner or saved campaign can be provisioned from the packaged setup; see the P05.6-A roadmap entry).
  Review those from text, or in the recorded-reply journey the owner can run.

## 2. Italian strings with English source and context

Columns: ID, English source, Italian target, verdict. A pipe inside a string is written `\|`.

### 2.1 Shell, navigation and tab titles

Context: skip link, header, navigation landmarks, and the document title shown in the browser tab. Every title is
`<screen> | <brand>`.

| ID | English | Italian | Verdict |
| --- | --- | --- | --- |
| shell.skip | Skip to content | Vai al contenuto | |
| shell.brand | Adventure engine | Motore di avventure | |
| shell.navigation | Application | Applicazione | |
| shell.library | Library | Biblioteca | |
| shell.settings | Settings | Impostazioni | |
| shell.libraryTitle | Library \| Adventure engine | Biblioteca \| Motore di avventure | |
| shell.settingsTitle | Settings \| Adventure engine | Impostazioni \| Motore di avventure | |
| shell.kitTitle | Interface kit \| Adventure engine | Kit dell’interfaccia \| Motore di avventure | |
| shell.storyTitle | Story \| Adventure engine | Storia \| Motore di avventure | |
| shell.createTitle | New playthrough \| Adventure engine | Nuova partita \| Motore di avventure | |
| shell.sheetTitle | Character \| Adventure engine | Personaggio \| Motore di avventure | |
| shell.inventoryTitle | Inventory \| Adventure engine | Inventario \| Motore di avventure | |
| shell.journalTitle | Journal \| Adventure engine | Diario \| Motore di avventure | |
| shell.guideTitle | Guide \| Adventure engine | Guida \| Motore di avventure | |
| shell.creativeTitle | Creative abilities \| Adventure engine | Abilità creative \| Motore di avventure | |
| shell.savedTitle | Saved adventure \| Adventure engine | Avventura salvata \| Motore di avventure | |

### 2.2 Settings

Context: `/settings`. Text size and layout selectors, the language selector and its notices, credits.
`settings.languagePending` is shown only by the unlocalized development build, not by the packaged variants.

| ID | English | Italian | Verdict |
| --- | --- | --- | --- |
| settings.title | Settings | Impostazioni | |
| settings.reading | Reading | Lettura | |
| settings.textSize | Story text size | Dimensione del testo della storia | |
| settings.pixels | {INTERPOLATION} px | {INTERPOLATION} px | |
| settings.layout | Story layout | Visualizzazione della storia | |
| settings.visualNovel | Visual novel | Visual novel | |
| settings.textFocused | Text focused (no artwork) | Solo testo (senza illustrazioni) | |
| settings.tabPreferences | Reading preferences and passage positions survive reload in this tab. | Le preferenze di lettura e le posizioni dei brani restano disponibili dopo il ricaricamento in questa scheda. | |
| settings.language | Language | Lingua | |
| settings.languagePending | Language selection is available in the English and Italian review variants. Packaged language delivery is still pending. | La selezione della lingua è disponibile nelle versioni di revisione inglese e italiana. La distribuzione delle lingue nel pacchetto è ancora da completare. | |
| settings.sound | Sound and appearance | Suono e aspetto | |
| settings.noAudio | No audio is loaded. Motion is disabled in this preview. | Non viene caricato alcun audio. Il movimento è disattivato in questa anteprima. | |
| settings.runtime | Runtime | Ambiente di esecuzione | |
| settings.noRuntime | No campaign or narration service is connected. | Non è collegato alcun servizio di campagna o narrazione. | |
| settings.credits | Credits | Crediti | |
| settings.rights | Original scene, speaker and item vector illustrations, icons, frames and control geometry. Copyright 2026 Azim Ben Othman; distributed under the project MIT license. System fonts; no external media requests. | Illustrazioni vettoriali originali di scene, personaggi e oggetti, icone, cornici e geometrie dei controlli. Copyright 2026 Azim Ben Othman; distribuiti con la licenza MIT del progetto. Caratteri di sistema; nessuna richiesta di contenuti multimediali esterni. | |
| settings.kit | Review interface graphic kit | Esamina il kit grafico dell’interfaccia | |
| settings.sources | Asset sources and provenance | Sorgenti e provenienza delle risorse | |
| settings.license | MIT license | Licenza MIT | |
| settings.readingStorage | Reading preferences could not be restored or saved. Language reload requires available tab storage. | Non è stato possibile ripristinare o salvare le preferenze di lettura. Per ricaricare la lingua è necessario consentire l’archiviazione della scheda. | |
| settings.interfaceLanguage | Interface language | Lingua dell’interfaccia | |
| settings.languageHelp | Changing language saves your preference on this device and reloads the interface. Preview drafts, selections and reading positions are kept. No action is sent. | Cambiare lingua salva la preferenza su questo dispositivo e ricarica l’interfaccia. Le bozze dell’anteprima, le selezioni e le posizioni di lettura vengono conservate. Nessuna azione viene inviata. | |
| settings.unsupportedLanguage | Saved language is unsupported. English is the default; choose English or Italian. | La lingua salvata non è supportata. L’inglese è la lingua predefinita; scegli inglese o italiano. | |
| settings.languageStorage | Language could not be saved or reloaded. Keep this tab open to preserve your work and allow device and tab storage before trying again. | Non è stato possibile salvare o ricaricare la lingua. Tieni aperta questa scheda per conservare il lavoro e consenti l’archiviazione del dispositivo e della scheda prima di riprovare. | |
| settings.languageUnsafe | Language reload is paused because drafts are not safely saved or recovery is unresolved. Keep this tab open and resolve recovery before trying again. | Il ricaricamento della lingua è sospeso perché le bozze non sono salvate in sicurezza o il ripristino è ancora in sospeso. Tieni aperta questa scheda e completa il ripristino prima di riprovare. | |

### 2.3 Sample adventures (data shown by the preview)

Context: text of the two sample adventures, `Mosslight Trail` (woodland) and `Silent Relay` (science fiction), including
character names, story passages, suggested replies, sheet labels and scene descriptions read by assistive technology.

| ID | English | Italian | Verdict |
| --- | --- | --- | --- |
| preview.woodlandDescription | A woodland exploration preview. | Un’anteprima di esplorazione nel bosco. | |
| preview.woodlandAlt | A winding trail crosses a quiet grove beneath layered green canopies. | Un sentiero sinuoso attraversa un boschetto silenzioso sotto chiome verdi sovrapposte. | |
| preview.trailhead | Trailhead | Inizio del sentiero | |
| preview.narrator | Narrator | Narratore | |
| preview.grovePassage | The trail opens into a quiet grove. Pale light settles on the path ahead. | Il sentiero si apre su un boschetto silenzioso. Una luce tenue illumina il cammino davanti a te. | |
| preview.observeGrove | Look around the grove. | Osserva il boschetto. | |
| preview.listenGrove | Listen to the sounds nearby. | Ascolta i suoni nelle vicinanze. | |
| preview.keeper | Trail keeper | Custode del sentiero | |
| preview.keeperPassage | The path continues through the trees. What would you like to know? | Il sentiero prosegue tra gli alberi. Che cosa vorresti sapere? | |
| preview.askTrail | What lies along the trail? | Che cosa si trova lungo il sentiero? | |
| preview.awareness | Awareness | Percezione | |
| preview.vigor | Vigor | Vigore | |
| preview.guide | Trail guide | Guida del sentiero | |
| preview.guideDescription | A place for public exploration guidance. | Uno spazio per le indicazioni pubbliche di esplorazione. | |
| preview.relayDescription | A science-fiction salvage preview. | Un’anteprima di recupero in un’ambientazione fantascientifica. | |
| preview.stationAlt | A blue-lit station corridor leads from the docking ring toward a silent relay. | Un corridoio della stazione illuminato di blu conduce dall’anello di attracco verso un ripetitore silenzioso. | |
| preview.dock | Dock | Attracco | |
| preview.stationPassage | Beyond the docking ring, a line of blue lamps marks the station corridor. | Oltre l’anello di attracco, una fila di lampade blu delinea il corridoio della stazione. | |
| preview.observeStation | Look along the station corridor. | Osserva il corridoio della stazione. | |
| preview.attendant | Dock attendant | Addetto all’attracco | |
| preview.attendantPassage | The relay is quiet. Where would you like to begin your survey? | Il ripetitore è silenzioso. Da dove vorresti iniziare la ricognizione? | |
| preview.askRelay | What is known about the relay? | Che cosa si sa del ripetitore? | |
| preview.crew | Crew profile | Profilo dell’equipaggio | |
| preview.cargo | Cargo | Carico | |
| preview.missionLog | Mission log | Registro di missione | |
| preview.specialist | Specialist name | Nome dello specialista | |
| preview.techniques | Techniques | Tecniche | |
| preview.equipment | Installed equipment | Equipaggiamento installato | |
| preview.assignments | Known assignments | Incarichi noti | |
| preview.logic | Logic | Logica | |
| preview.charge | Charge | Carica | |
| preview.fieldTechniques | Field techniques | Tecniche sul campo | |
| preview.fieldDescription | A place to inspect supported custom techniques. | Uno spazio per esaminare le tecniche personalizzate supportate. | |

### 2.4 Story reader and composer

Context: `/adventures/<id>/story`. Passage region, Continue button, Chronicle disclosure, suggestion chips, the draft
field and its status messages. `story.progress` is `Passage <n> of <total> · <title>`. `story.revealedCount` follows
the Chronicle heading and is an ICU plural (zero, one, other).

| ID | English | Italian | Verdict |
| --- | --- | --- | --- |
| story.sceneUnavailable | Scene illustration unavailable. {INTERPOLATION} | Illustrazione della scena non disponibile. {INTERPOLATION} | |
| story.sceneCaption | Illustrative scene · local preview | Scena illustrativa · anteprima locale | |
| story.passageLabel | Current passage | Brano corrente | |
| story.continue | <pc></pc>Continue | <pc></pc>Continua | |
| story.progress | Passage {INTERPOLATION} of {INTERPOLATION_1} · {INTERPOLATION_2} | Brano {INTERPOLATION} di {INTERPOLATION_1} · {INTERPOLATION_2} | |
| story.empty | No passages are available. | Nessun brano disponibile. | |
| story.chronicle | Chronicle | Cronologia | |
| story.revealedCount | {VAR_PLURAL, plural, =0 {No passages} =1 {One passage} other {{INTERPOLATION} passages}} | {VAR_PLURAL, plural, =0 {Nessun brano} =1 {Un brano} other {{INTERPOLATION} brani}} | |
| story.suggestionHelp | Suggestions fill an empty draft. Edit freely; clear your draft before choosing another. | I suggerimenti compilano una bozza vuota. Modifica liberamente; cancella la bozza prima di sceglierne un altro. | |
| story.suggestionsLabel | Suggestions | Suggerimenti | |
| story.dismiss | Dismiss | Nascondi | |
| story.dismissLabel | Dismiss suggestion: {reply} | Nascondi suggerimento: {reply} | |
| story.inputLabel | What do you say or do? | Che cosa dici o fai? | |
| story.draftPlaceholder | Write a draft… | Scrivi una bozza… | |
| story.send | <pc></pc>Send | <pc></pc>Invia | |
| story.clear | Clear draft | Cancella bozza | |
| story.composerHelp | Reading and suggestions never submit an action. No campaign is connected; Send is unavailable. | La lettura e i suggerimenti non inviano mai azioni. Nessuna campagna è collegata; Invia non è disponibile. | |
| story.unavailable | Draft kept in this tab only. Device storage is unavailable. | La bozza resta solo in questa scheda. L’archiviazione sul dispositivo non è disponibile. | |
| story.invalid | Saved draft could not be restored. New edits will replace it. | Impossibile ripristinare la bozza salvata. Le nuove modifiche la sostituiranno. | |
| story.saved | Draft saved on this device. | Bozza salvata su questo dispositivo. | |
| story.readingOnly | reading only | sola lettura | |
| story.end | End of available passages | Fine dei brani disponibili | |
| story.action | Action | Azione | |
| story.speech | Speech | Battuta | |
| story.question | Question | Domanda | |
| story.narration | Narration | Narrazione | |
| story.player | Player | Giocatore | |
| story.character | Character | Personaggio | |
| story.you | You | Tu | |
| story.narrator | Narrator | Narratore | |
| story.unknown | Unknown character | Personaggio sconosciuto | |
| story.status.loading | Loading current context. You can keep drafting. | Caricamento del contesto corrente. Puoi continuare a scrivere la bozza. | |
| story.status.preview | Local preview. Drafting is available; no campaign is connected. | Anteprima locale. Puoi scrivere una bozza; nessuna campagna è collegata. | |
| story.status.ready | Current context is available. No campaign transport is connected. | Il contesto corrente è disponibile. Nessun servizio di campagna è collegato. | |
| story.status.disconnected | Connection unavailable. Your last context and draft remain available. | Connessione non disponibile. L’ultimo contesto e la bozza restano disponibili. | |
| story.status.reconnecting | Connection restored. Operation status must be checked before any retry. Your draft will not be sent. | Connessione ripristinata. Prima di riprovare occorre verificare lo stato dell’operazione. La bozza non verrà inviata. | |
| story.status.error | Context could not be refreshed. Your last context and draft remain available. | Impossibile aggiornare il contesto. L’ultimo contesto e la bozza restano disponibili. | |
| story.status.staleRevision | The world has changed. Refresh current context before sending; your draft is preserved. | Il mondo è cambiato. Aggiorna il contesto prima di inviare; la bozza è conservata. | |
| story.status.clarification | Clarification is needed. Review the pending operation before sending another action. | È necessario un chiarimento. Esamina l’operazione in sospeso prima di inviare un’altra azione. | |
| story.status.cancelled | The operation was cancelled before commit. Your draft is preserved. | L’operazione è stata annullata prima del salvataggio. La bozza è conservata. | |
| story.status.narrationPending | The action was saved; narration is pending. Do not resend the action. | L’azione è stata salvata; la narrazione è in attesa. Non inviare di nuovo l’azione. | |
| story.status.restoreRequired | Campaign recovery is required before sending. Your draft is preserved. | È necessario ripristinare la campagna prima di inviare. La bozza è conservata. | |

### 2.5 Terms, creation, library, workspace

Context: shared terms used by the character, inventory and journal panels; the new-playthrough preview
(`/adventures/<id>/create`); the library (`/library`, sample adventures and installed packs); the adventure navigation.

| ID | English | Italian | Verdict |
| --- | --- | --- | --- |
| terms.story | Story | Storia | |
| terms.sheet | Character | Personaggio | |
| terms.inventory | Inventory | Inventario | |
| terms.journal | Journal | Diario | |
| terms.guide | Guide | Guida | |
| terms.creative | Creative abilities | Abilità creative | |
| terms.characterName | Character name | Nome del personaggio | |
| terms.attributes | Attributes | Attributi | |
| terms.resources | Resources | Risorse | |
| terms.skills | Skills | Competenze | |
| terms.abilities | Abilities | Abilità | |
| terms.conditions | Conditions | Condizioni | |
| terms.equipment | Equipment | Equipaggiamento | |
| terms.quests | Known quests | Missioni note | |
| creation.title | New playthrough | Nuova partita | |
| creation.intro | Character creation preview for {INTERPOLATION}. | Anteprima della creazione del personaggio per {INTERPOLATION}. | |
| creation.unavailable | Pack choices, budgets and campaign creation will appear when campaign services are connected. | Le scelte del pacchetto, i budget e la creazione della campagna saranno disponibili quando i servizi di campagna saranno collegati. | |
| creation.help | Enter a name of 1–60 characters. This preview does not save it. | Inserisci un nome da 1 a 60 caratteri. Questa anteprima non lo salva. | |
| creation.nameError | Enter a name of 1–60 characters. | Inserisci un nome da 1 a 60 caratteri. | |
| creation.preview | Preview character | Anteprima del personaggio | |
| creation.receipt | {INTERPOLATION} is ready to preview. No campaign has been created. | {INTERPOLATION} è pronto per l’anteprima. Nessuna campagna è stata creata. | |
| creation.create | Create campaign | Crea campagna | |
| library.title | Your adventures | Le tue avventure | |
| library.intro | Independent worlds, each with its own playthroughs. | Mondi indipendenti, ciascuno con le proprie partite. | |
| library.notice | Preview samples below are local to this interface and are never saved. | Gli esempi di anteprima qui sotto sono locali all’interfaccia e non vengono mai salvati. | |
| library.new | New adventure | Nuova avventura | |
| library.noSave | No saved playthrough connected | Nessuna partita salvata collegata | |
| library.open | Open story preview | Apri l’anteprima della storia | |
| library.create | New playthrough preview | Anteprima di una nuova partita | |
| library.packs | Installed packs | Pacchetti installati | |
| library.packIntro | Reusable content sources are managed separately from adventures. | Le fonti di contenuto riutilizzabili sono gestite separatamente dalle avventure. | |
| library.packUnavailable | Pack management is not connected in this preview. | La gestione dei pacchetti non è collegata in questa anteprima. | |
| library.import | Import pack | Importa pacchetto | |
| workspace.preview | Preview | Anteprima | |
| workspace.navigation | Adventure | Avventura | |
| workspace.missing | Adventure unavailable | Avventura non disponibile | |
| workspace.unknown | This preview identity is unknown. | L’identità di questa anteprima è sconosciuta. | |
| workspace.return | Return to library | Torna alla biblioteca | |
| workspace.create | New playthrough | Nuova partita | |

### 2.6 Character, inventory, journal and content panels (preview)

Context: `/adventures/<id>/sheet`, `/inventory`, `/journal`, `/guide`, `/creative`. These are empty-state texts: no campaign is connected.

| ID | English | Italian | Verdict |
| --- | --- | --- | --- |
| panels.noCharacter | No character is connected to this preview. | Nessun personaggio è collegato a questa anteprima. | |
| panels.disconnected | Not connected | Non collegato | |
| panels.noAttributes | No attribute definitions to display. | Nessuna definizione di attributo da mostrare. | |
| panels.noResources | No resource definitions to display. | Nessuna definizione di risorsa da mostrare. | |
| panels.values | Accepted character values will appear here. | I valori confermati del personaggio appariranno qui. | |
| panels.conditions | No current conditions to display. | Nessuna condizione attuale da mostrare. | |
| panels.abilities | No available abilities to display. | Nessuna abilità disponibile da mostrare. | |
| panels.noInventory | No campaign inventory is connected. | Nessun inventario di campagna è collegato. | |
| panels.items | Carried items | Oggetti trasportati | |
| panels.itemHelp | Known owned items will appear here with quantities and explicit rarity labels. | Gli oggetti posseduti noti appariranno qui con quantità e indicazioni esplicite di rarità. | |
| panels.equipment | No equipment to inspect. | Nessun equipaggiamento da esaminare. | |
| panels.equip | Equip item | Equipaggia oggetto | |
| panels.noJournal | No campaign journal is connected. | Nessun diario di campagna è collegato. | |
| panels.objectives | Discovered objectives will appear here. | Gli obiettivi scoperti appariranno qui. | |
| panels.discoveries | Discoveries and promises | Scoperte e promesse | |
| panels.noDiscoveries | No recorded discoveries or promises. | Nessuna scoperta o promessa registrata. | |
| panels.events | Event history | Cronologia degli eventi | |
| panels.eventHelp | Accepted campaign events will appear here. | Gli eventi confermati della campagna appariranno qui. | |
| panels.panelHelp | No campaign is connected. This panel previews content configuration only. | Nessuna campagna è collegata. Questo pannello mostra soltanto un’anteprima della configurazione dei contenuti. | |
| panels.create | Create ability | Crea abilità | |
| panels.missing | Panel unavailable | Pannello non disponibile | |
| panels.disabled | This content does not enable the requested panel. | Questo contenuto non abilita il pannello richiesto. | |

### 2.7 Interface graphic kit

Context: `/interface-kit`, a page of theme samples (controls, bars, frames, icons). Values and items are examples.

| ID | English | Italian | Verdict |
| --- | --- | --- | --- |
| kit.disconnected | Not connected | Non collegato | |
| kit.emptySlot | Empty slot | Spazio vuoto | |
| kit.noItem | No item | Nessun oggetto | |
| kit.title | Interface graphic kit | Kit grafico dell’interfaccia | |
| kit.intro | Theme samples only. Values and items below are examples, independent of any playthrough. | Solo esempi del tema. I valori e gli oggetti qui sotto sono esempi indipendenti da qualsiasi partita. | |
| kit.controls | Controls | Controlli | |
| kit.unavailable | Unavailable | Non disponibile | |
| kit.loading | <pc></pc>Loading | <pc></pc>Caricamento | |
| kit.reply | Reply sample | Esempio di risposta | |
| kit.field | Sample field | Campo di esempio | |
| kit.placeholder | Write here | Scrivi qui | |
| kit.invalid | Invalid field | Campo non valido | |
| kit.invalidValue | Invalid sample | Esempio non valido | |
| kit.error | Enter a supported value. | Inserisci un valore supportato. | |
| kit.disabled | Unavailable field | Campo non disponibile | |
| kit.disabledValue | Not connected | Non collegato | |
| kit.preference | {TAG_INPUT}Sample preference | {TAG_INPUT}Preferenza di esempio | |
| kit.bars | Bars | Barre | |
| kit.health | Health example | Esempio di salute | |
| kit.focus | Focus example | Esempio di concentrazione | |
| kit.xp | XP example | Esempio di esperienza | |
| kit.resource | Unavailable resource | Risorsa non disponibile | |
| kit.loadingExample | Loading example | Esempio di caricamento | |
| kit.progressName | Loading example | Esempio di caricamento | |
| kit.transfer | Transfer example: 60% | Esempio di trasferimento: 60% | |
| kit.frames | Frames and fallback | Cornici e alternative | |
| kit.item | Signal crystal example | Esempio di cristallo di segnalazione | |
| kit.itemRarity | Uncommon example | Esempio non comune | Flagged by first pass: could read as "an ordinary example"; "Esempio di rarità non comune" is unambiguous. Not changed. |
| kit.portrait | Portrait unavailable | Ritratto non disponibile | |
| kit.speaker | Speaker nameplate | Nome di chi parla | |
| kit.dialogue | Dialogue text stays selectable and resizes with reading preferences. | Il testo del dialogo resta selezionabile e si ridimensiona secondo le preferenze di lettura. | |
| kit.rarity | About item rarity | Informazioni sulla rarità degli oggetti | |
| kit.rarityHelp | Rarity is always written beside its symbol. Colour alone never conveys an item category or status. | La rarità è sempre scritta accanto al suo simbolo. Il solo colore non indica mai la categoria o lo stato di un oggetto. | |
| kit.status | Status | Stato | |
| kit.saved | <pc></pc>Saved example | <pc></pc>Esempio di salvataggio | |
| kit.loadingHelp | <pc></pc>Loading example; text remains available. | <pc></pc>Esempio di caricamento; il testo resta disponibile. | |
| kit.errorHelp | <pc></pc>Error example; keep your draft and retry when connected. | <pc></pc>Esempio di errore; conserva la bozza e riprova quando la connessione è disponibile. | |
| kit.icons | Icons | Icone | |
| kit.sources | Sources and rights | Fonti e diritti | |
| kit.rights | Original editable SVG and CSS geometry under the project MIT license. No external artwork or fonts. See Settings credits and the distributed UI source inventory. | Geometrie SVG e CSS originali e modificabili, distribuite con la licenza MIT del progetto. Nessuna illustrazione o font esterno. Consulta i crediti nelle Impostazioni e l’inventario distribuito delle risorse dell’interfaccia. | |
| kit.selected | Selected | Selezionato | |
| kit.select | Select sample | Seleziona esempio | |

### 2.8 Saved adventures (library list and adventure page)

Context: the "Saved adventures" list on `/library` and `/saved/<id>`. On the packaged installation today the list
answers `LOCAL_OWNER_UNCONFIGURED` and shows `saved.unconfigured` as an alert (the screen-reader script uses it).

| ID | English | Italian | Verdict |
| --- | --- | --- | --- |
| saved.title | Saved adventures | Avventure salvate | |
| saved.loading | Loading saved adventures… | Caricamento delle avventure salvate… | |
| saved.empty | No saved adventures yet. | Nessuna avventura salvata. | |
| saved.reference | Reference {INTERPOLATION} | Riferimento {INTERPOLATION} | |
| saved.open | Open saved adventure | Apri avventura salvata | |
| saved.more | Show more saved adventures | Mostra altre avventure salvate | |
| saved.loadingMore | Loading more… | Caricamento… | |
| saved.missing | This saved adventure is unavailable. | Questa avventura salvata non è disponibile. | |
| saved.unconfigured | The local owner is not configured, so saved adventures cannot be read. | Il proprietario locale non è configurato, quindi le avventure salvate non possono essere lette. | |
| saved.denied | This browser session was not admitted. Reload the page to try again. | Questa sessione del browser non è stata ammessa. Ricarica la pagina per riprovare. | |
| saved.invalid | The saved library sent data this interface cannot verify, so nothing was shown. | La biblioteca salvata ha inviato dati che questa interfaccia non può verificare, quindi non è stato mostrato nulla. | |
| saved.unavailable | Saved adventures are unavailable right now. | Le avventure salvate non sono disponibili al momento. | |
| saved.selected | Playthrough: {INTERPOLATION} | Partita: {INTERPOLATION} | |
| saved.noneSelected | No playthrough selected | Nessuna partita selezionata | |
| saved.unknownPlaythrough | That playthrough does not belong to this adventure. | Questa partita non appartiene a questa avventura. | |
| saved.playthroughs | Playthroughs | Partite | |
| saved.readOnly | Select a playthrough to read its current state and action results. Sending actions in the browser is not connected yet. | Seleziona una partita per leggerne lo stato attuale e i risultati delle azioni. L’invio delle azioni nel browser non è ancora collegato. | |
| saved.noPlaythroughs | This adventure has no playthroughs yet. | Questa avventura non ha ancora partite. | |
| saved.turn | Turn {INTERPOLATION} | Turno {INTERPOLATION} | |
| saved.pending | Decision pending | Decisione in sospeso | |
| saved.loadingOne | Loading saved adventure… | Caricamento dell’avventura salvata… | |

### 2.9 Campaign errors

Context: the closed table of error codes the saved-campaign view can show (screen-reader alert text). IDs are
shown without the prefix `campaign.error.`. `generic` is used for any unknown code.

| ID | English | Italian | Verdict |
| --- | --- | --- | --- |
| admissionDenied | This browser session was not admitted. Reload the page to try again. | Questa sessione del browser non è stata ammessa. Ricarica la pagina e riprova. | |
| ownerUnconfigured | The local owner is not configured, so this playthrough cannot be read. | Il proprietario locale non è configurato, quindi questa partita non può essere letta. | |
| inputMalformed | The request was not understood. Nothing was changed. | La richiesta non è stata compresa. Non è stato modificato nulla. | |
| bodyTooLarge | The request was too large. Nothing was changed. | La richiesta era troppo grande. Non è stato modificato nulla. | |
| scopeUnavailable | This playthrough is unavailable. | Questa partita non è disponibile. | |
| requestConflict | This request identity was already used for a different action. Nothing was changed. | Questa identità di richiesta è già stata usata per un’azione diversa. Non è stato modificato nulla. | |
| staleRevision | The world has changed since this action was prepared. Refresh the current context before trying again. | Il mondo è cambiato da quando questa azione è stata preparata. Aggiorna il contesto attuale prima di riprovare. | |
| busy | The playthrough is busy with another action. Wait a moment and check its status before retrying. | La partita sta elaborando un’altra azione. Attendi un momento e controlla lo stato prima di riprovare. | |
| admissionLimit | Too many actions are waiting. Wait for one to finish before sending another. | Ci sono troppe azioni in attesa. Attendi che una finisca prima di inviarne un’altra. | |
| invalid | This action is not valid. Nothing was changed. | Questa azione non è valida. Non è stato modificato nulla. | |
| modeIllegal | This action is not available in the current situation. | Questa azione non è disponibile nella situazione attuale. | |
| decisionUnavailable | There is no matching decision to answer right now. | In questo momento non c’è nessuna decisione corrispondente a cui rispondere. | |
| actorUnavailable | The acting character is unavailable. | Il personaggio che agisce non è disponibile. | |
| destinationUnavailable | That destination cannot be reached from here. | Da qui non si può raggiungere quella destinazione. | |
| abilityUnavailable | That ability is unavailable. | Quell’abilità non è disponibile. | |
| prerequisiteUnmet | A requirement for this action is not met. | Un requisito per questa azione non è soddisfatto. | |
| targetIllegal | That target is not allowed for this action. | Quel bersaglio non è consentito per questa azione. | |
| resourceInsufficient | There are not enough resources for this action. | Non ci sono risorse sufficienti per questa azione. | |
| contentUnavailable | The adventure content needed for this action is unavailable. | Il contenuto dell’avventura necessario per questa azione non è disponibile. | |
| effectUnsupported | This kind of effect is not supported yet. Nothing was changed. | Questo tipo di effetto non è ancora supportato. Non è stato modificato nulla. | |
| storageUnavailable | Storage is unavailable right now. Check the action status before retrying. | L’archiviazione non è disponibile in questo momento. Controlla lo stato dell’azione prima di riprovare. | |
| streamCapacity | Too many live updates are open. Close another tab and try again. | Sono aperti troppi aggiornamenti in diretta. Chiudi un’altra scheda e riprova. | |
| generic | Something went wrong. Nothing was lost; check the playthrough status and try again. | Qualcosa è andato storto. Non è stato perso nulla; controlla lo stato della partita e riprova. | |
| itemUnavailable | That item is not available for this action. Nothing was changed. | Questo oggetto non è disponibile per questa azione. Non è stato modificato nulla. | |
| equipmentIllegal | That item cannot be worn or taken off right now. Nothing was changed. | Questo oggetto non può essere indossato o rimosso in questo momento. Non è stato modificato nulla. | |
| quantityIllegal | That is more than the available quantity. Nothing was changed. | È più della quantità disponibile. Non è stato modificato nulla. | |
| tradeRefused | The other party does not accept this exchange. Nothing was changed. | L’altra parte non accetta questo scambio. Non è stato modificato nulla. | |
| questUnavailable | That quest is not available for this action. Nothing was changed. | Questa missione non è disponibile per questa azione. Non è stato modificato nulla. | |
| timeIllegal | That much time cannot pass at once. Nothing was changed. | Non può trascorrere così tanto tempo in una volta. Non è stato modificato nulla. | |
| restUnavailable | You cannot rest like that here. Nothing was changed. | Qui non puoi riposare in questo modo. Non è stato modificato nulla. | |
| encounterUnavailable | That encounter cannot begin here and now. Nothing was changed. | Questo scontro non può iniziare qui e ora. Non è stato modificato nulla. | |
| turnUnavailable | It is not your turn to act. Nothing was changed. | Non è il tuo turno di agire. Non è stato modificato nulla. | |
| actionPointsInsufficient | You do not have enough action points left. Nothing was changed. | Non ti restano abbastanza punti azione. Non è stato modificato nulla. | |
| zoneUnavailable | You cannot move to that zone from here. Nothing was changed. | Da qui non puoi spostarti in quella zona. Non è stato modificato nulla. | |
| speechLimitReached | You have already spoken during this turn. Nothing was changed. | Hai già parlato durante questo turno. Non è stato modificato nulla. | |
| interactionUnavailable | That action is not possible here and now. Nothing was changed. | Quell’azione non è possibile qui e ora. Non è stato modificato nulla. | |
| attackUnavailable | You cannot make that attack: the weapon is not ready or has no ammunition left. Nothing was changed. | Non puoi portare quell’attacco: l’arma non è pronta o ha finito le munizioni. Non è stato modificato nulla. | |
| guardUnavailable | You have already taken a guarded stance during this turn. Nothing was changed. | Hai già assunto una posizione di guardia durante questo turno. Non è stato modificato nulla. | |
| reactionUnavailable | You do not have that reaction. Nothing was changed. | Non disponi di quella reazione. Non è stato modificato nulla. | |
| actionPrevented | An effect on you prevents that right now. Nothing was changed. | Un effetto che ti riguarda lo impedisce in questo momento. Non è stato modificato nulla. | |
| dependenciesMissing | This playthrough is paused because adventure content it depends on is missing or damaged. Nothing was changed. | Questa partita è in pausa perché un contenuto dell’avventura da cui dipende manca o è danneggiato. Non è stato modificato nulla. | |

### 2.10 Operation statuses, narration, stream resets, language fallback, command labels

Context: the "Actions" list of the campaign view. Statuses describe one action (feminine in Italian: "azione");
narration notices follow a saved result; reset notices explain a re-checked stream; fallback notices appear when a passage
is shown in its source language; command labels name the kind of action. IDs are shown without the prefix `campaign.`.

| ID | English | Italian | Verdict |
| --- | --- | --- | --- |
| status.accepted | Received | Presa in carico | |
| status.planning | Being prepared | In preparazione | |
| status.clarification | Waiting for your clarification | In attesa di un tuo chiarimento | |
| status.validating | Being checked | In verifica | |
| status.rejected | Not allowed; nothing changed | Non consentita; nulla è cambiato | |
| status.committing | Being saved | In salvataggio | |
| status.committed | Saved | Salvata | |
| status.narrating | Saved; narration in progress | Salvata; narrazione in corso | |
| status.delivered | Saved and narrated | Salvata e narrata | |
| status.narrationPending | Saved; narration is pending | Salvata; la narrazione è in sospeso | |
| status.cancelled | Cancelled before saving; nothing changed | Annullata prima del salvataggio; nulla è cambiato | |
| status.failed | Failed before saving; nothing changed | Non riuscita prima del salvataggio; nulla è cambiato | |
| narration.unavailable | Narration is not available yet. The saved result below is complete. | La narrazione non è ancora disponibile. Il risultato salvato qui sotto è completo. | |
| narration.cancelled | Narration was stopped. The saved result below is complete. | La narrazione è stata annullata. Il risultato salvato qui sotto è completo. | |
| narration.failed | Narration could not be produced. The saved result below is complete. | Non è stato possibile produrre la narrazione. Il risultato salvato qui sotto è completo. | |
| narration.retired | Narration was discarded because the language or settings changed. The saved result below is complete. | La narrazione è stata scartata perché la lingua o le impostazioni sono cambiate. Il risultato salvato qui sotto è completo. | |
| narration.interrupted | Narration was interrupted. The saved result below is complete. | La narrazione è stata interrotta. Il risultato salvato qui sotto è completo. | |
| reset.expired | Some live updates were missed while this page was closed. Operation status was checked again. | Alcuni aggiornamenti in diretta sono andati persi mentre questa pagina era chiusa. Lo stato delle azioni è stato ricontrollato. | |
| reset.generation | The playthrough was restored from a backup. Operation status was checked again. | La partita è stata ripristinata da un backup. Lo stato delle azioni è stato ricontrollato. | |
| reset.unknown | The saved update position was not recognised. Operation status was checked again. | La posizione di aggiornamento salvata non è stata riconosciuta. Lo stato delle azioni è stato ricontrollato. | |
| fallback.italian | This text is shown in the adventure's original Italian because no reviewed translation exists for your language. | Questo testo è mostrato nell’italiano originale dell’avventura perché per la tua lingua non esiste una traduzione rivista. | |
| fallback.english | This text is shown in the adventure's original English because no reviewed translation exists for your language. | Questo testo è mostrato nell’inglese originale dell’avventura perché per la tua lingua non esiste una traduzione rivista. | |
| language.italian | Italian | italiano | Lower case on purpose: inserted into "Lingua del testo: …". |
| language.english | English | inglese | Lower case on purpose: inserted into "Lingua del testo: …". |
| command.move | Move | Spostamento | |
| command.wait | Wait | Attesa | |
| command.speak | Speak | Dialogo | |
| command.useAbility | Use an ability | Uso di un’abilità | |
| command.useItem | Use an item | Uso di un oggetto | |
| command.equipItem | Equip an item | Equipaggiamento di un oggetto | |
| command.unequipItem | Take off an item | Rimozione di un oggetto | |
| command.transferItem | Give an item | Consegna di un oggetto | |
| command.tradeItems | Trade items | Scambio di oggetti | |
| command.acceptQuest | Accept a quest | Accetta una missione | |
| command.suspendQuest | Set a quest aside | Metti da parte una missione | |
| command.resumeQuest | Resume a quest | Riprendi una missione | |

### 2.11 Action receipts

Context: lines of the "Result of the action" list. Placeholders: `path` (a rule path), `kind`, `outcome`, `total`,
`revision`, `destination`, `seconds`, `resource`, `before`/`after`, `ordinal`, `value`, `sides`. Numbers are
formatted for the compiled locale; resource names and destinations are literal identifiers from the adventure.
IDs are shown without the prefix `campaign.receipt.`.

| ID | English | Italian | Verdict |
| --- | --- | --- | --- |
| succeeded | succeeded | riuscita | |
| failed | failed | fallita | |
| check | Check {path} ({kind}): {outcome} | Prova {path} ({kind}): {outcome} | |
| total | Calculation {path} ({kind}): {total} | Calcolo {path} ({kind}): {total} | |
| revision | Saved as world revision {revision}. | Azione salvata come revisione del mondo {revision}. | |
| moved | Moved to {destination}. | Destinazione raggiunta: {destination}. | |
| elapsed | World time passed: {seconds} s. | Tempo trascorso nel mondo di gioco: {seconds} s. | |
| turnAdvanced | The game turn advanced. | Il turno di gioco è avanzato. | |
| turnHeld | The game turn did not advance. | Il turno di gioco non è avanzato. | |
| resource | {resource}: {before} → {after} | {resource}: {before} → {after} | |
| draw | Draw {ordinal}: {value} on a {sides}-sided die | Tiro {ordinal}: {value} su un dado a {sides} facce | |

### 2.12 Campaign view and "Where you are" summary

Context: the saved-campaign page. IDs are shown without the prefix `campaign.`. `resume.issue*` explain why a
playthrough is paused; `{pack}` and `{version}` are the content pack name and version.

| ID | English | Italian | Verdict |
| --- | --- | --- | --- |
| view.startingTitle | Starting situation | Situazione iniziale | |
| view.fallbackLanguage | Text language: {INTERPOLATION} | Lingua del testo: {INTERPOLATION} | |
| view.noSituation | No decision is waiting for you in this playthrough. | In questa partita non ci sono decisioni in attesa. | |
| view.loading | Loading the playthrough… | Caricamento della partita… | |
| view.operations | Actions | Azioni | |
| view.noOperations | No actions have been recorded for this playthrough in this browser. | In questo browser non sono state registrate azioni per questa partita. | |
| view.refresh | Check for updates | Cerca aggiornamenti | |
| view.inputLabel | What do you say or do? | Che cosa dici o fai? | |
| view.send | Send | Invia | |
| view.composerHelp | Your draft stays on this device and is never sent automatically, including after changing language. Sending actions is not connected yet. | La tua bozza resta su questo dispositivo e non viene mai inviata automaticamente, nemmeno dopo aver cambiato lingua. L’invio delle azioni non è ancora collegato. | |
| view.receiptLabel | Result of the action | Risultato dell’azione | |
| view.live | Connected. Waiting for pending actions to finish. | Connesso. In attesa che le azioni in sospeso si concludano. | |
| view.liveIdle | Connected. All known actions are finished. | Connesso. Tutte le azioni note sono concluse. | |
| view.disconnected | Connection unavailable. Your last known state and draft remain available; retrying automatically. | Connessione non disponibile. L’ultimo stato noto e la bozza restano disponibili; nuovo tentativo automatico in corso. | |
| view.connecting | Checking the playthrough… | Controllo della partita… | |
| resume.decisionStart | Decide how to begin, as described in the starting situation. | Decidi come iniziare, come descritto nella situazione iniziale. | |
| resume.decisionFree | Say or do what you want next. | Di’ o fai ciò che vuoi adesso. | |
| resume.decisionOther | A decision is waiting for you. | Una decisione ti aspetta. | |
| resume.narrationResumes | Saved; its narration resumes by itself. | Salvata; la sua narrazione riprende da sola. | |
| resume.issueRuleset | {pack} {version} was made for rules that this engine version does not provide. | {pack} {version} è stato creato per regole che questa versione del motore non fornisce. | |
| resume.issueCorrupt | The stored files of {pack} {version} are damaged. | I file salvati di {pack} {version} sono danneggiati. | |
| resume.issueMissing | {pack} {version} is not installed in this adventure. | {pack} {version} non è installato in questa avventura. | |
| resume.issueInvalid | {pack} {version} cannot be loaded by this engine version. | {pack} {version} non può essere caricato da questa versione del motore. | |
| resume.issueRevision | The adventure content of this playthrough is missing. | Manca il contenuto dell’avventura di questa partita. | |
| resume.noLocation | Unknown | Sconosciuto | |
| resume.nobody | Nobody you have seen here. | Nessuno che tu abbia visto qui. | |
| resume.resource | {resource} {current} of {maximum} | {resource} {current} su {maximum} | |
| resume.title | Where you are | Dove ti trovi | |
| resume.paused | This playthrough is paused: adventure content it depends on is missing or damaged. Nothing can change until the same content is restored from a backup or imported again. | Questa partita è in pausa: un contenuto dell’avventura da cui dipende manca o è danneggiato. Nulla può cambiare finché lo stesso contenuto non viene ripristinato da un backup o importato di nuovo. | |
| resume.location | Location | Luogo | |
| resume.present | Known to be here | Presenti, per quanto ne sai | |
| resume.resources | Resources | Risorse | |
| resume.encounter | Encounter | Scontro | |
| resume.noEncounter | None | Nessuno | |
| resume.decision | Next decision | Prossima decisione | |
| resume.awaiting | Saved actions awaiting narration | Azioni salvate in attesa di narrazione | |
| resume.readOnly | This summary is read from the last saved state; reading it does not advance time. | Questo riepilogo è letto dall’ultimo stato salvato; leggerlo non fa avanzare il tempo. | |

### 2.13 Campaign panels (character, belongings, journal)

Context: the panels under the campaign view. `{kind}` is one of Promise, Obligation, Agreement (feminine,
masculine, masculine in Italian); `{due}` is an in-world date. IDs are shown without the prefix `campaign.panels.`.

| ID | English | Italian | Verdict |
| --- | --- | --- | --- |
| clock | Day {day}, {time} | Giorno {day}, {time} | |
| questAvailable | Available | Disponibile | |
| questActive | In progress | In corso | |
| questSuspended | Set aside | Messa da parte | |
| questCompleted | Completed | Completata | |
| questFailed | Failed | Fallita | |
| outcomeSuccess | Success | Successo | |
| outcomeFailure | Failure | Fallimento | |
| outcomeAlternative | Alternative ending | Finale alternativo | |
| objectiveDone | Done | Fatto | |
| objectiveOptional | Optional | Facoltativo | |
| objectiveOpen | Open | Da fare | |
| attributeChanged | {effective} (base {base}) | {effective} (base {base}) | |
| condition | {name} ×{stacks}, {rounds} rounds left | {name} ×{stacks}, ancora {rounds} round | "round" kept as the tabletop term; confirm it is preferred over "turni". |
| stanceKnows | You know this | Lo sai | |
| stanceBelieves | You believe this | Lo credi | |
| stanceRumored | A rumor you heard | Una voce che hai sentito | |
| stanceSuspects | You suspect this | Lo sospetti | |
| stanceDoubts | You doubt this | Ne dubiti | |
| stanceCommon | Common knowledge | Conoscenza comune | |
| kindPromise | Promise | Promessa | |
| kindObligation | Obligation | Obbligo | |
| kindAgreement | Agreement | Accordo | |
| youOwe | {kind} · you owe {creditor} | {kind} · devi qualcosa a {creditor} | |
| owedToYou | {kind} · {debtor} owes you | {kind} · {debtor} ti deve qualcosa | |
| betweenOthers | {kind} · {debtor} owes {creditor} | {kind} · {debtor} deve qualcosa a {creditor} | |
| overdue | Overdue since {due} | Scadenza superata dal {due} | **(first pass)** Was "Scaduta dal {due}": feminine only, wrong beside "Obbligo" and "Accordo". |
| due | Due {due} | Scadenza: {due} | |
| title | Character, belongings and journal | Personaggio, oggetti e diario | |
| clockLabel | In-world time | Tempo nel mondo | |
| contentMissing | Adventure names and quests cannot be shown because the adventure content is unavailable. The numbers below are exact. | I nomi e le missioni dell’avventura non si possono mostrare perché il contenuto dell’avventura non è disponibile. I numeri qui sotto sono esatti. | |
| placeLabel | Location | Luogo | |
| attributes | Attributes | Attributi | |
| skills | Skills | Competenze | |
| derived | Derived statistics | Statistiche derivate | |
| resources | Resources | Risorse | |
| noResources | No resources to show. | Nessuna risorsa da mostrare. | |
| conditions | Conditions | Condizioni | |
| noConditions | No active conditions. | Nessuna condizione attiva. | |
| abilities | Abilities | Abilità | |
| noAbilities | No abilities learned yet. | Nessuna abilità appresa finora. | |
| inventory | Inventory | Inventario | |
| noItems | You carry nothing. | Non porti nulla con te. | |
| worn | Worn | Indossato | |
| equipment | Worn equipment | Equipaggiamento indossato | |
| noEquipment | Nothing is worn. | Non indossi nulla. | |
| quests | Quests | Missioni | |
| noQuests | No quests discovered yet. | Nessuna missione scoperta finora. | |
| knowledge | What you know | Ciò che sai | |
| noKnowledge | No recorded discoveries. | Nessuna scoperta registrata. | |
| moreKnowledge | Only the newest discoveries are listed. | Sono elencate solo le scoperte più recenti. | |
| promises | Promises and agreements | Promesse e accordi | |
| noPromises | No open promises. | Nessuna promessa aperta. | |
| history | Event history | Cronologia degli eventi | |
| noEvents | Nothing has happened yet. | Non è ancora successo nulla. | |
| older | Show older events | Mostra gli eventi precedenti | |
| rewards | Reward | Ricompensa | |

### 2.14 Scene and voice cues

Context: the "Scene and voices" region under a narrated result: text descriptions of scene, pose, expression and
spoken lines, plus optional line-audio buttons. IDs are shown without the prefix `campaign.cues.`.

| ID | English | Italian | Verdict |
| --- | --- | --- | --- |
| title | Scene and voices | Scena e voci | |
| help | These lines describe what the scene shows. The written account is complete without them, and audio plays only when you choose to start it. | Queste righe descrivono ciò che mostra la scena. Il racconto scritto è completo anche senza di esse e l’audio parte solo quando scegli di avviarlo. | **(first pass)** Straight apostrophe in `l'audio` changed to `’` like the rest of the catalog. |
| scene | Scene | Scena | |
| pose | Pose | Posa | |
| expression | Expression | Espressione | |
| line | Spoken line | Battuta parlata | |
| unnamed | (not named) | (senza nome) | |
| play | Play line audio | Riproduci l’audio della battuta | **(first pass)** Apostrophe as above. |
| stop | Stop line audio | Ferma l’audio della battuta | **(first pass)** Apostrophe as above. |
| unavailable | Audio is not available for this line. The text above is unchanged. | L’audio non è disponibile per questa battuta. Il testo qui sopra non cambia. | **(first pass)** Apostrophe as above. |
| noAudio | No audio for this line. | Nessun audio per questa battuta. | |

## 3. First-pass review by the preparing session (not independent)

Scope: the preparing session read all 433 targets against their English source once, on 31 October 2026. It is the same
author line as the earlier author pass recorded in `frontend/src/locale/README.md`, so it is **not an independent review**
and does not count towards the QA-35 gate.

Changed (clear errors only):

| ID | Before | After | Reason |
| --- | --- | --- | --- |
| campaign.panels.overdue | Scaduta dal {due} | Scadenza superata dal {due} | The feminine form disagreed with the masculine kinds "Obbligo" and "Accordo" that precede it. |
| campaign.cues.help, play, stop, unavailable | `l'audio` (straight apostrophe) | `l’audio` | Every other Italian target uses the typographic apostrophe; only these four strings did not. |

Checks on the change: placeholders and ICU branches were untouched, and the parity check, the Italian render
tests and the localized build passed (section 6). The first version of the overdue edit lost the space before the
placeholder ("dalGiorno 2, 03:46"); the parity check and render tests did not notice, a review of the diff did. It is
repaired, and `italian-variant.spec.ts` now pins the overdue text and the four apostrophes (section 6).

Left unchanged and offered to the reviewer (judgement calls, not clear errors):

- `kit.itemRarity` "Esempio non comune" is ambiguous (see row).
- `campaign.panels.condition` keeps the English loan "round"; the Italian game vocabulary elsewhere uses "turno".
- `settings.visualNovel` keeps "Visual novel" as a loan term.
- "brano/brani" renders "passage"; a reviewer may prefer "passo" or "scena".
- `campaign.command.*` labels are verbal nouns ("Spostamento") except the three quest commands, which are imperatives
  ("Accetta una missione"). A first-pass review in October 2026 already unified part of them; confirm the remaining mix.
- `campaign.panels.attributeChanged` and `settings.pixels` have no words to translate; confirm the abbreviations.
- English source, not Italian: `settings.languagePending` says packaged language delivery is "still pending" although the
  packaged installation now serves both variants. The text appears only where language selection is unavailable (the
  `@else` branch in `settings.ts`, the unlocalized development build); decide whether the English should change.
- Tone: informal "tu" is used throughout; confirm that this is the wanted register.
- Not reviewed at all: pronunciation by an Italian screen-reader voice (see section 4).

## 4. Screen-reader script (English and Italian variants)

Purpose: a person using a real screen reader confirms that both variants can be used without sight. Nothing in this section
has been performed. The tables below are the script; the right-hand columns are for the reviewer.

### 4.1 Setup

| Item | Value (reviewer fills in) |
| --- | --- |
| Reviewer name and date | |
| Is the reviewer fluent in Italian? Independent of the project? | |
| Operating system and version | |
| Browser and version | |
| Screen reader and version (for example NVDA, JAWS, VoiceOver, TalkBack) | |
| Italian speech voice installed (name) | |
| Speech rate and verbosity settings | |

Start the installation from the repository root with `./start.sh` (the origin is `http://127.0.0.1:8080`) and stop it with
`./start.sh --stop`. Without runtime sign-in it starts with gameplay not ready, which is expected for this script. Open
the English variant at `/en/library` and the Italian variant at `/it/library`. Run every step once in each variant and
fill both result columns. Use the keyboard and the screen reader only; do not look at the screen.

### 4.2 Steps

"Expected" is derived from the catalog and from the accessibility tree in section 5.2. It is what should be offered; the
reviewer records what is actually heard, which may differ.

| # | Step | Expected, English | Expected, Italian | Heard / result, EN | Heard / result, IT | Pass? EN / IT |
| --- | --- | --- | --- | --- | --- | --- |
| S1 | Load the variant's library URL. | Page title "Library \| Adventure engine"; the reader speaks English text with an English voice. | Page title "Biblioteca \| Motore di avventure"; the reader switches to or uses an Italian voice (`lang="it"`). | | | |
| S2 | Press Tab once. | "Skip to content", link. Enter moves focus past the header. | "Vai al contenuto", collegamento. | | | |
| S3 | List landmarks and headings. | Navigation "Application"; heading "Your adventures". | Navigazione "Applicazione"; titolo "Le tue avventure". | | | |
| S4 | Read the saved-adventures area. | Alert "The local owner is not configured, so saved adventures cannot be read." (the live packaged API answers 503). | Alert with the Italian text of `saved.unconfigured`. | | | |
| S5 | Move to the sample adventure "Mosslight Trail" and open its story link. | Link "Open story preview"; the story page title is "Story \| Adventure engine". | Link "Apri l’anteprima della storia"; titolo "Storia \| Motore di avventure". The adventure name stays "Mosslight Trail". | | | |
| S6 | On the story page, read the heading and the passage. | Heading "Trailhead"; region "Current passage"; passage text; progress "Passage 1 of …". | Titolo "Inizio del sentiero"; area "Brano corrente"; "Brano 1 di …". | | | |
| S7 | Find the scene image. | Image with the alternative text "A winding trail crosses a quiet grove beneath layered green canopies." | Immagine con il testo alternativo "Un sentiero sinuoso attraversa un boschetto silenzioso sotto chiome verdi sovrapposte." | | | |
| S8 | Activate Continue until the end; open Chronicle. | Button "Continue"; disclosure "Chronicle · One passage" (or the plural). | Pulsante "Continua"; "Cronologia · Un brano" (or "N brani"). | | | |
| S9 | Open the suggestions list; choose one; then dismiss one. | List "Suggestions"; buttons "Action: Look around the grove." and "Dismiss suggestion: Look around the grove." | Elenco "Suggerimenti"; "Azione: Osserva il boschetto." e "Nascondi suggerimento: Osserva il boschetto." | | | |
| S10 | Type a draft in "What do you say or do?"; check Send and Clear. | Textbox with that label; the help text is read as a description; Send is reported unavailable; the status "Draft saved on this device." is announced without stealing focus. | Campo "Che cosa dici o fai?"; testo di aiuto letto come descrizione; Invia non disponibile; "Bozza salvata su questo dispositivo." annunciato senza spostare il focus. | | | |
| S11 | Open Settings; change the text size and the story layout. | Headings "Settings", "Reading"; comboboxes "Story text size" and "Story layout"; options "16 px" … "Visual novel", "Text focused (no artwork)". | "Impostazioni", "Lettura"; "Dimensione del testo della storia" e "Visualizzazione della storia"; opzioni "Solo testo (senza illustrazioni)". Check how "px" and "Visual novel" are spoken. | | | |
| S12 | Change the language with "Interface language" (English ⇄ Italiano). | The page reloads in the other language; focus returns to the language selector, which is announced in the new language ("Lingua dell’interfaccia" / "Interface language"); the draft from S10 is still present afterwards. | Same, in the other direction. | | | |
| S13 | Open Character, Inventory, Journal, Trail guide and New playthrough from the adventure navigation. | Each page title and heading is announced; empty states such as "No character is connected to this preview." | Titoli e messaggi italiani, ad esempio "Nessun personaggio è collegato a questa anteprima." | | | |
| S14 | On New playthrough, submit an empty name, then a valid name. | Error "Enter a name of 1–60 characters." is announced (the reviewer records whether it is tied to the field); then "<name> is ready to preview. No campaign has been created." | "Inserisci un nome da 1 a 60 caratteri." annunciato (annotare se è legato al campo); poi "<nome> è pronto per l’anteprima. Nessuna campagna è stata creata." Check the gender of "pronto" with a name. | | | |
| S15 | Open the interface kit from Settings credits. | Page "Interface kit"; progress "Loading example"; bars "Health example"; rarity is written as text. | "Kit dell’interfaccia"; "Esempio di caricamento"; "Esempio di salute"; la rarità è scritta a parole. | | | |
| S16 | Switch to the "Silent Relay" adventure and repeat S6 and S13 briefly. | Station corridor image text and "Dock attendant". | Testo dell’immagine del corridoio e "Addetto all’attracco". | | | |
| S17 | Return to the library and open the saved-adventure page URL `/saved/<any-uuid>`. | The unavailable or unconfigured message is announced as an alert. | L’equivalente messaggio italiano è annunciato come avviso. | | | |
| S18 | Saved-campaign view (receipts, statuses, panels, cues). | **Cannot run on the packaged installation today** (no owner or saved campaign can be provisioned). Record "not run". | Idem. | | | |

Items to listen for in every step: wrong voice or language, spelling out of words, missing or duplicated announcements,
focus lost after an action, text read twice, a control without a name, and any text left in English in the Italian variant.

### 4.3 Reviewer evidence

| Field | Value |
| --- | --- |
| Steps run in English | |
| Steps run in Italian | |
| Steps not run, and why | |
| Defects found (step, variant, what was heard, what was expected) | |
| Recording or notes location | |
| Verdict (accepted / accepted with changes / rejected) | |
| Reviewer signature and date | |

## 5. Tool evidence: axe and accessibility tree (not a screen-reader review)

**These reports come from automated tools in headless Chrome. They show that two tools found no rule violation and what
Chrome's accessibility tree offers. They do not show how any screen reader speaks the pages, whether the Italian reads
naturally, or that the pages are usable without sight.**

### 5.1 How the evidence was produced (31 October 2026)

Commands, run from the repository root with host Chrome through `.agents/ralph/browser/browser.mjs`
(axe-core 4.14.0, the version pinned in that tool's lock file; the Chrome version was not recorded in this run):

1. `"C:/Program Files/Git/bin/bash.exe" ./start.sh`, which built the image from this tree (frontend tests, `check:i18n`
   and the locale build ran inside the build) and started the packaged installation at `http://127.0.0.1:8080`.
2. `node .local/scratch/p056c/audit.mjs`, a private script (ignored by Git; not committed) that, for `en` and `it`,
   opened 13 routes with `waitUntil: "networkidle"`, ran `axeReport` and `accessibilitySnapshot` and wrote the results
   to ignored JSON files.
3. `node .agents/ralph/browser/browser.mjs http://127.0.0.1:8080/en/settings --axe` and the same for `/it/library`,
   to read the console errors.
4. `"C:/Program Files/Git/bin/bash.exe" ./start.sh --stop`.

The routes were `library`, `settings`, `interface-kit`, `adventures/mosslight/{story,create,sheet,inventory,journal,guide,creative}`,
`adventures/relay/{story,sheet}` and `saved/123e4567-e89b-42d3-a456-426614174000?playthrough=` the same identifier.
The saved route shows the live "owner not configured" state, because the packaged API has no owner.

### 5.2 Axe results

| Locale | Routes checked | HTTP status | `html lang` | Axe violations | Tab titles |
| --- | --- | --- | --- | --- | --- |
| English | 13 | 200 on all 13 | `en` on all 13 | 0 on all 13 | English on all 13 (for example "Settings \| Adventure engine") |
| Italian | 13 | 200 on all 13 | `it` on all 13 | 0 on all 13 | Italian on all 13 (for example "Impostazioni \| Motore di avventure") |

Named nodes in the accessibility tree, per route (English / Italian): library 68 / 69, settings 81 / 83, interface-kit
158 / 161, mosslight story 93 / 94, create 56 / 56, sheet 75 / 75, inventory 59 / 59, journal 57 / 57, guide 44 / 45,
creative 42 / 42, relay story 87 / 88, relay sheet 75 / 75, saved route 22 / 23.

Console errors seen by the page: on `library` and on the saved route, two each; on every other route, one. One of the two is
the expected `503` of `/api/v1/session` (owner not configured), which the page turns into the alert text. The other error
on every route reads "Failed to load resource: ... 404"; its URL was not identified (a request to check the favicon was
refused in this session), so it is **unexplained**, probably the browser's own icon request.

### 5.3 Accessibility-tree excerpts

Chrome's tree with static text omitted, for two routes. Only nodes that have an accessible name are listed by the helper,
so unnamed landmarks (for example the `main` region) are absent from these lists; that is a limit of the helper.

English `/en/adventures/mosslight/story`: link "Skip to content"; link "Adventure engine"; navigation "Application"; links
"Library", "Settings"; navigation "Adventure"; heading "Trailhead"; region "Current passage"; button "Continue"; list
"Suggestions"; textbox "What do you say or do?"; buttons "Send", "Clear draft"; links "Story", "Character", "Inventory",
"Journal", "Trail guide", "New playthrough"; image "A winding trail crosses a quiet grove beneath layered green canopies.";
disclosure "Chronicle · One passage"; buttons "Action: Look around the grove." and "Dismiss suggestion: Look around the
grove.", "Action: Listen to the sounds nearby." and "Dismiss suggestion: Listen to the sounds nearby."

Italian `/it/adventures/mosslight/story`: link "Vai al contenuto"; link "Motore di avventure"; navigation "Applicazione";
links "Biblioteca", "Impostazioni"; navigation "Avventura"; heading "Inizio del sentiero"; region "Brano corrente"; button
"Continua"; list "Suggerimenti"; textbox "Che cosa dici o fai?"; buttons "Invia", "Cancella bozza"; links "Storia",
"Personaggio", "Inventario", "Diario", "Guida del sentiero", "Nuova partita"; image "Un sentiero sinuoso attraversa un
boschetto silenzioso sotto chiome verdi sovrapposte."; disclosure "Cronologia · Un brano"; buttons "Azione: Osserva il
boschetto." and "Nascondi suggerimento: Osserva il boschetto.", "Azione: Ascolta i suoni nelle vicinanze." and "Nascondi
suggerimento: Ascolta i suoni nelle vicinanze."

English `/en/settings`: headings "Settings", "Reading", "Language", "Sound and appearance", "Runtime", "Credits";
comboboxes "Story text size", "Story layout", "Interface language"; links "Review interface graphic kit", "Asset sources
and provenance", "MIT license"; options "15 px" to "24 px", "Visual novel", "Text focused (no artwork)", "English", "Italiano".

Italian `/it/settings`: headings "Impostazioni", "Lettura", "Lingua", "Suono e aspetto", "Ambiente di esecuzione", "Crediti";
comboboxes "Dimensione del testo della storia", "Visualizzazione della storia", "Lingua dell’interfaccia"; links "Esamina
il kit grafico dell’interfaccia", "Sorgenti e provenienza delle risorse", "Licenza MIT"; options "15 px" to "24 px",
"Visual novel", "Solo testo (senza illustrazioni)", "English", "Italiano".

### 5.4 What this evidence does not prove

- No screen reader was run. Speech output, voice selection, pronunciation and reading order are unverified.
- Axe checks only the rules it implements on the DOM at one moment; most success criteria need a human.
- Only the routes above were visited, in a fresh profile, at 1280 × 800, with default preferences. Keyboard journeys, focus
  after a language change and live announcements were not part of this run (the earlier P05.6-A journey covers part of them
  and is also not a screen-reader review).
- The saved-campaign screens (section 2.9 onward, S18) could not be reached on the packaged origin.
- The unexplained 404 in section 5.2 is open.

## 6. Checks run for this kit

| Command | Result |
| --- | --- |
| `"C:/Program Files/Git/bin/bash.exe" ./start.sh` (image build) | Frontend stage inside the build: 17 test files, 131 tests passed; `test:i18n` 5 tests passed; "Localization parity: 433 reviewed messages"; both locale variants built; `PackagedSecretsTest` 4 tests passed; installation started with gameplay not ready, as expected. |
| `node .local/scratch/p056c/audit.mjs` | 26 page loads (13 routes × 2 locales), all HTTP 200, 0 axe violations. |
| `"C:/Program Files/Git/bin/bash.exe" .agents/ralph/verify.sh full` | Run after the repair of the overdue target and the new pinning test: `VERIFY PASSED (full)`, all 17 steps; frontend 17 test files, 132 tests passed (131 before the new test); "Localization parity: 433 reviewed messages". The image build and browser audit above ran before that repair and were not repeated. |
| `"C:/Program Files/Git/bin/bash.exe" ./start.sh --stop` | Installation stopped; volumes kept. |

## 7. Returning the results

The reviewer sends back sections 2 (verdict column), 4.1 and 4.3 and the two result columns of 4.2. The owner then edits
the catalog and English sources together, reruns `npm run check:i18n`, and records the independent review and the
screen-reader pass in the roadmap (P05.6-O). Until then P05.6 stays open.
