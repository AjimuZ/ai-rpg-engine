# Ricerca competitiva e revisione tecnica: riepilogo per il proprietario

Data: 23 ottobre 2026. Questo aggiornamento riguarda ricerca, contratti tecnici,
priorità e documentazione. Non implementa nuove funzionalità, non installa
modelli e non misura prestazioni o qualità narrativa. I risultati già verificati
dello sviluppo mantengono il loro ambito e le loro evidenze.

## 1. Cosa abbiamo trovato

Sono stati esaminati cinque repository alle revisioni riportate nella
[matrice competitiva](27-competitive-analysis-and-gaps.md), leggendo codice,
contratti, persistenza, dipendenze, definizioni dei test, issue e licenze. Nessuna
applicazione concorrente è stata eseguita. Una funzione presente nel sorgente
non dimostra che sia affidabile o migliore in una partita reale.

La base del progetto è coerente: backend autorevole, stato persistente distinto
dalla conversazione, regole deterministiche, isolamento delle conoscenze e
identità stabili. Il lavoro utile consiste nel precisare ciò che manca, senza
sostituire Angular, Spring Boot, Java, PostgreSQL o il runtime Codex.

## 2. Pattern utili dei concorrenti

- **Fugue:** composizione visiva a livelli, pose/espressioni, voci associate ai
  personaggi e simulazione sociale. Sono spunti per grafica e continuità; la sua
  licenza personalizzata limita la redistribuzione commerciale del frontend.
  Non sono stati copiati codice o asset.
- **SakalioLabs Monogatari:** separazione fra generazione, valutazione e
  transizioni deterministiche; prove collegate a citazioni, report riproducibili
  e pacchetti con inventario/hash. Il valutatore osservato viene atteso: non è
  una dimostrazione di pianificazione asincrona.
- **NarrativeEngine-P:** memoria delle cause e degli esiti delle relazioni,
  recupero contestuale e gestione di archivi. Embedding e vector store esistono
  nel codice, ma non provano che qui siano necessari o migliori del recupero
  per identità, fonti e ricerca testuale già previsto.
- **kiselyovd/dungeon-master-ai:** operazioni durevoli, ricevute, recupero e
  test di errore sulle transazioni. Conferma il valore della nostra separazione
  fra proposte e stato, senza giustificare l'importazione del suo mondo o stack.
- **Mortyl/ai-dungeon-master:** regole tipizzate e valutazioni oggettive separate
  dal giudizio sulla prosa. La persistenza osservata resta in memoria; non è
  una prova di continuità dopo il riavvio. Non è stata trovata una licenza
  esplicita per riusarne il software.

## 3. Vantaggi da dimostrare

I possibili elementi distintivi sono conseguenze verificabili e recuperabili,
missioni preparate senza imporre soluzioni, personaggi che ricordano perché una
relazione è cambiata, identità audiovisive persistenti e un'esperienza curata
anche in italiano. Sono obiettivi di prodotto, non una superiorità già misurata.
La matrice registra gap, costi, dipendenze, manutenzione e scelte di adozione,
adattamento, rinvio o rifiuto.

## 4. Pianificazione narrativa senza fermare la partita

La [specifica asincrona](28-asynchronous-narrative-planning.md) distingue narratore
interattivo, pianificatore delle missioni, direzione occasionale del mondo e
revisore opzionale. Sono ruoli logici con contesti separati: non agenti con
accesso autonomo a file, shell o database.

Una missione accettata può partire da un obiettivo e un primo indizio già validi;
il giocatore continua mentre un lavoro in background prepara motivazioni,
possibili sviluppi e alternative. Il risultato rimane una proposta. Se nel
frattempo muore un testimone, cambia una fazione o viene distrutto un oggetto,
si invalidano le parti dipendenti, senza riscrivere ciò che è successo. Cambiamenti
estranei non devono far rigenerare tutto. Anche assenze e risultati di ricerche
vuote hanno dipendenze, per evitare errori quando compare una nuova entità.

La conclusione di un lavoro non avanza tempo, non assegna premi e non crea fatti
di gioco. Ammissione e successiva attivazione passano dal backend con controlli
di versione, transazione e idempotenza. La partita resta giocabile quando il
piano non arriva, costa troppo o viene scartato.

## 5. Orchestrazione consigliata

Una coda durevole in PostgreSQL, un solo runtime isolato e thread separati per
ruolo, avventura e pubblico. Impostazione iniziale: una generazione attiva.
La concorrenza reale si abilita solo dopo verifiche di correlazione, cancellazione,
quote e tempi. Il test con turni sovrapposti su un upstream simulato non dimostra
maggiore velocità del servizio reale.

Le richieste del giocatore hanno precedenza; lavori speculativi, tentativi e
budget sono limitati. Nessuna spesa aggiuntiva o API alternativa viene attivata
implicitamente. Il revisore e una direzione più costosa restano disabilitabili
finché non mostrano un vantaggio. Questa architettura di gioco è distinta
dall'orchestratore di sviluppo che alterna Claude e Codex.

## 6. Inglese e italiano dal primo rilascio

[Localizzazione](29-localization.md) e ADR 0015 richiedono inglese predefinito e
italiano pienamente supportato già nell'MVP: interfaccia, errori, combattimento,
inventario, missioni, accessibilità e prosa. La narrazione viene generata
direttamente nella lingua selezionata, con glossario e identità stabili.

Il cambio lingua conserva bozze e posizione di lettura e non ripete azioni.
La cronologia mantiene il testo originale. Un pacchetto non tradotto segnala
la lingua di origine; non viene presentato come una traduzione completa già
verificata. Le due avventure dimostrative dovranno avere contenuti EN/IT curati.

## 7. Voci locali opzionali

La [ricerca TTS](30-local-speech-synthesis.md) propone **Qwen3-TTS 0.6B Base**
come candidato principale e **Kokoro CPU/ONNX** come fallback leggero. La scelta
è condizionata: codice, pesi, tokenizer, singole voci, registrazioni e dipendenze
hanno diritti distinti; i campioni vocali devono essere autorizzati.

Chatterbox Multilingual rimane un'alternativa da confrontare; Piper richiede
attenzione alla licenza GPL e ai singoli modelli vocali; XTTS-v2 non è selezionato
per il percorso commerciale predefinito a causa delle restrizioni dei pesi e
degli output. Non sono stati scaricati pesi o registrazioni.

La RTX 4070 12 GB, i 32 GB RAM e Windows/WSL2 sono il banco di prova previsto,
non una configurazione già misurata. Italiano, stabilità delle voci, latenza e
VRAM devono essere provati. L'audio usa le battute già accettate, si può saltare
e non modifica la partita. Un limite GPU o un modello assente lascia testo e
sottotitoli disponibili. Il TTS non blocca MVP o core v1.

## 8. Editor e storie condivisibili

Le storie potranno passare da una persona all'altra come directory o ZIP nel
[formato avventura](09-adventure-packs.md#14-portable-stories-and-person-to-person-sharing):
mondo, personaggi, regole supportate, vincoli dell'autore, traduzioni e risorse
redistribuibili, con versioni, dipendenze, hash e crediti. Il destinatario vede
un'anteprima e importa una nuova avventura locale.

L'esportazione ordinaria non trasferisce credenziali, cronologie private,
salvataggi attivi o lavori in esecuzione. Un pacchetto completo per autori può
contenere segreti della trama e lo deve indicare. Conflitti di versione e risorse
non condivisibili producono avvisi, senza sovrascrivere mondi esistenti.

Il [percorso editor E01–E06](26-adventure-editor-roadmap.md) resta successivo al
core. Import/export dei file e le operazioni P16 preparano prima la portabilità;
l'editor userà lo stesso contratto, senza richiedere un marketplace online.

## 9. Documenti e roadmap aggiornati

Nuovi capitoli 27–32 e ADR 0014–0016, integrati nelle specifiche esistenti.
Nuovi requisiti FR-033–037 e NFR-013–015 e scenari QA-35–41. Le fasi P00–P19
e le caselle completate restano preservate; undici task aggiunti rendono espliciti
concorrenza, persistenza dei lavori, localizzazione, presentazione, ammissione,
coda, pianificazione, direzione opzionale, audio opzionale e valutazioni.

Priorità: bilingue e affidabilità nel percorso del primo prodotto giocabile;
fondazioni durevoli per v1; pianificazione profonda/revisore/voci abilitate solo
con evidenze e fallback. Un modello vocale o un'architettura più complessa non
diventa un prerequisito nascosto per giocare. Nessuna casella è completata da
questa revisione documentale.

## 10. Verifiche e incognite aperte

Il [protocollo di valutazione](31-research-validation-and-evaluations.md) confronta
narratore semplice, narratore con pianificazione e revisione opzionale: coerenza,
motivazioni, libertà del giocatore, segreti, tempi, quote e lavoro sprecato, in
entrambe le lingue. Prevede continuità a 100/500 turni, errori e ripristino,
rubriche umane e misure vocali/GPU. Non riporta benchmark mai eseguiti.

Restano da dimostrare benefici narrativi, concorrenza del servizio reale,
qualità italiana delle voci, prestazioni sulla macchina target e diritti degli
artefatti effettivamente distribuiti. I controlli finali di questo aggiornamento
riguardano fonti, collegamenti, tracciabilità, priorità, dipendenze e assenza di
modifiche al codice; non sostituiscono i test di implementazione pianificati.

Controllo documentale eseguito: 55 documenti e 483 collegamenti locali senza
errori, 20 pacchetti P00–P19 senza dipendenze cicliche, 117 task con le 12
caselle completate invariate, 37 requisiti funzionali, 15 non funzionali e 41
scenari QA referenziati. Due revisioni indipendenti hanno individuato e poi
ricontrollato le correzioni sulle priorità del planner e sulla tracciabilità
dei test vocali; nessun rilievo residuo nei rispettivi ambiti. Nessun sorgente
applicativo, probe, configurazione runtime o credenziale è stato modificato.
