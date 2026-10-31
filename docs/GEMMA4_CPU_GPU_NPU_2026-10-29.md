# Gemma 4: CPU, GPU, NPU e consumo di batteria

Ricerca del **29 ottobre 2026**, raccolta per superMens. Modello principale:
**Gemma 4 E2B instruction-tuned**. I risultati E4B sono indicati separatamente.

Questo documento conserva la ricerca web e l'ispezione del codice svolte nella
conversazione. Non sono stati eseguiti nuovi benchmark sul Pixel dell'utente.
Disponibilità di SDK, modelli compilati e runtime descritta alla data della ricerca;
i collegamenti a `main` possono cambiare successivamente.

## Risultato per il Pixel 7a

Il mancato uso della TPU ha due cause distinte:

1. **superMens seleziona soltanto GPU e CPU**, senza tentare il backend NPU.
2. **Per Gemma 4 E2B sulla TPU del Tensor G2 non è stato trovato un percorso
   pubblico supportato**, comprensivo di SDK, modello compilato e librerie adatte.

Il Pixel 7a usa Tensor G2. Il Google Tensor SDK elenca invece G5 e G6 tra i SoC
supportati. Il repository dei modelli contiene varianti E2B per G5 e G6, senza una
variante G2 individuata durante la ricerca.

Fonti: [specifiche Pixel 7a](https://support.google.com/pixelphone/answer/7158570/pixel-phone-hardware-tech-specs?hl=en-GB),
[Tensor SDK: SoC supportati](https://developers.google.com/edge/litert/next/tensor-sdk),
[file Gemma 4 E2B disponibili](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/main).

La conclusione riguarda il **supporto pubblico disponibile per questa combinazione**.
Non dimostra che il G2 sia fisicamente incapace di eseguire qualsiasi LLM.
Cambiare una chiamata da `Backend.GPU()` a `Backend.NPU()` non sbloccherebbe da solo
la TPU del 7a. TPU è il nome dell'acceleratore neurale di Google, una particolare
implementazione di NPU in questo contesto.

## Cosa esegue superMens

Progetto analizzato: `superMens_locale`, package `it.supermens.offline`.

In [LocalAi.kt](../app/src/main/java/it/supermens/local/LocalAi.kt) la lista di
configurazioni tenta, nell'ordine:

| Tentativo | Modello linguistico | Immagini | Audio |
| --- | --- | --- | --- |
| 1 | GPU | GPU | CPU |
| 2 | GPU | Non abilitate | CPU |
| 3 | CPU | Non abilitate | CPU |

La selezione è nelle configurazioni dell'engine, intorno alle righe 70–82 alla
data dell'ispezione. Nessun tentativo NPU è presente: la limitazione dell'app vale
anche se viene installata su un telefono con NPU compatibile.

Ulteriori dettagli verificati:

- Dipendenza `com.google.ai.edge.litertlm:litertlm-android:0.11.0` in
  [app/build.gradle.kts](../app/build.gradle.kts).
- Contesto runtime: 4.096 token.
- File accettato da [LocalModel.kt](../app/src/main/java/it/supermens/local/LocalModel.kt):
  `gemma-4-E2B-it.litertlm`, 2.583.085.056 byte.
- Revisione fissata: `7fa1d78473894f7e736a21d920c3aa80f950c0db`.
- SHA-256 atteso:
  `ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`.

L'app verifica dimensione e hash: una diversa variante NPU non è oggi un modello
sostituibile liberamente tramite importazione. Servirebbero selezione del modello
per SoC, gestione delle librerie native, configurazione NPU e verifica sul device.
La [guida Android di LiteRT-LM](https://developers.google.com/edge/litert-lm/android)
documenta `Backend.NPU(nativeLibraryDir = ...)` e le librerie necessarie.

## Supporto sugli altri dispositivi

Gemma non sceglie autonomamente CPU, GPU o NPU. Il risultato dipende dalla
combinazione modello, conversione, runtime, driver e chip.

| Piattaforma | Evidenza trovata |
| --- | --- |
| Pixel 7a / Tensor G2 | Nessun percorso TPU pubblico supportato trovato per Gemma 4 E2B. |
| Tensor G5 / famiglia Pixel 10 | File E2B dedicato alla TPU presente nel repository; richiede integrazione e verifica sul telefono preciso. |
| Tensor G6 | E2B presente anche nella tabella ufficiale di esecuzione NPU. |
| Snapdragon | Esecuzione Gemma 4 su Hexagon documentata da runtime dedicati e build community; non equivale a supporto universale del file Google standard. |
| MediaTek | La guida NPU LiteRT-LM consultata elenca Gemma 3 1B su MT6989/MT6991: non basta a dimostrare supporto Gemma 4 E2B. |
| iPhone e Mac con LiteRT-LM | Benchmark ufficiali E2B su CPU e GPU; quei benchmark non documentano il Neural Engine. |
| Intel Lunar Lake / Panther Lake | Varianti E2B NPU dedicate disponibili. |

Fonti: [guida NPU LiteRT-LM](https://developers.google.com/edge/litert/next/litert_lm_npu),
[repository dei file compilati](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/main),
[ailia: Gemma su Qualcomm NPU](https://docs.ailia.ai/en/llm/qnn.html).

La tabella NPU Google e l'elenco dei file non erano perfettamente allineati: la
prima evidenziava E2B/G6, mentre i file comprendevano anche G5. L'assenza da una
singola tabella non è quindi sufficiente per concludere che una variante non esista.

Su Apple esistono anche percorsi differenti: il progetto
[CoreML-LLM](https://github.com/john-rocky/CoreML-LLM) dichiara E2B sul Neural Engine
di iPhone 17 Pro a 34,2 token/s di decode, con contesto 2.048. È una configurazione
diversa da LiteRT-LM: non va aggiunta ai benchmark Google come terza misura a
condizioni identiche.

Un'altra strada è AICore/ML Kit: Google ha documentato Gemma 4 nella Developer
Preview sugli acceleratori recenti Google, Qualcomm e MediaTek, con CPU su altri
device abilitati. È un'integrazione distinta dal caricamento diretto del file di
superMens. [Annuncio Android](https://developer.android.com/blog/posts/announcing-gemma-4-in-the-ai-core-developer-preview).

## Come leggere i benchmark

- **Prefill / lettura:** elaborazione dei token del prompt o documento.
- **Decode / generazione:** produzione dei token della risposta.
- **TTFT:** tempo prima del primo token; controllare se comprende caricamento,
  encoder e preparazione del modello.
- **Token/s:** più alto è meglio. I token non corrispondono direttamente alle parole.

Confrontare preferibilmente i backend **dentro lo stesso test sullo stesso telefono**.
Fra pubblicazioni cambiano lunghezza del prompt, contesto, quantizzazione,
runtime, temperature e ottimizzazioni. Un dato mancante non prova impossibilità
hardware. Le seguenti tabelle non sono una classifica a condizioni uniformi.

### Galaxy Z Fold6: CPU, GPU e NPU

Gemma 4 E2B su Snapdragon 8 Gen 3, misure pubblicate dall'autore della build
community per CyanBridge:

| Backend | Lettura, token/s | Generazione, token/s |
| --- | ---: | ---: |
| CPU | 110–205 | 11–19 |
| GPU | 540–1.050 | 14–16 |
| NPU Hexagon | 2.250–2.900 | 32–35 |

La versione della pagina aperta riporta prompt di 512–1.024 token e 128 token
generati, LiteRT-LM 0.18, dispatch LiteRT 2.3.0 e QAIRT 2.50. Il percorso NPU usa
un modello compilato appositamente, con attivazioni INT16 ricalibrate; CPU e GPU
usano il file Google standard. La differenza comprende quindi compilazione e
quantizzazione. Revisione descritta: solo testo, contesto 4.096. Nessuna verifica
indipendente eseguita in questa ricerca. Indicizzazione e pagina aperta mostravano
revisioni con valori NPU diversi: qui è conservata la tabella della pagina aperta,
non una combinazione dei risultati di revisioni diverse.

Fonte: [modello e benchmark Fold6](https://huggingface.co/Fertroll10/gemma-4-E2B-it-litert-lm-npu).

### Altri telefoni: CPU e GPU, Gemma 4 E2B

| Telefono | CPU lettura | GPU lettura | CPU generazione | GPU generazione |
| --- | ---: | ---: | ---: | ---: |
| Galaxy S26 Ultra | 557 | 3.808 | 46,9 | 52,1 |
| iPhone 17 Pro | 532 | 2.878 | 25,0 | 56,5 |
| POCO F8 Ultra | 266,65 | 2.216,66 | 27,53 | 42,07 |
| Pixel 8 | 53,35 | 426,64 | 10,57 | 11,94 |

Tutti i valori sono token/s. Condizioni e fonti:

- **S26 Ultra e iPhone 17 Pro:** Google LiteRT-LM, 1.024 token in ingresso,
  256 in uscita, contesto 2.048; CPU XNNPACK a quattro thread, cache già
  inizializzate. TTFT senza caricamento.
  [Scheda del modello](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm).
- **POCO F8 Ultra:** AI Edge Gallery 1.0.19, configurazione 1.536→256 token,
  media di tre esecuzioni; misura dell'11 settembre 2026, dati e protocollo
  pubblicati. Lo studio riporta anche un calo GPU del 31% dopo tre esecuzioni:
  picco e prestazioni sostenute differiscono.
  [Studio indipendente](https://github.com/blyzai/lexa-edge-benchmarks).
- **Pixel 8:** report dello sviluppatore con runtime 0.10.1 compilato da sorgente
  e corretto per un problema GPU; protocollo meno completo. Il confronto usa
  lo stesso modello sullo stesso telefono.
  [Report originale](https://github.com/google-ai-edge/LiteRT-LM/issues/1850).

Rapporti GPU/CPU calcolati dai valori sopra:

| Telefono | Lettura | Generazione |
| --- | ---: | ---: |
| S26 Ultra | 6,8× | 1,11× |
| iPhone 17 Pro | 5,4× | 2,26× |
| POCO F8 Ultra | 8,3× | 1,53× |
| Pixel 8 | 8,0× | 1,13× |

La GPU può quindi ridurre molto l'attesa per elaborare un documento senza
moltiplicare nella stessa misura la velocità della risposta.

### Gemma 4 E4B: risultati separati

| Telefono | Backend | Lettura, token/s | Generazione, token/s |
| --- | --- | ---: | ---: |
| Galaxy S26 Ultra | CPU | 195 | 18 |
| Galaxy S26 Ultra | GPU | 1.293 | 22 |
| iPhone 17 Pro | CPU | 159 | 10 |
| iPhone 17 Pro | GPU | 1.189 | 25 |

Modello più grande: non confrontare direttamente questi numeri con E2B come
misura del solo backend. [Benchmark ufficiali Google](https://developers.google.com/edge/litert-lm/models/gemma-4).

### NPU: esempi in cui il decode non vince

H2Loop ha pubblicato misure su Snapdragon 8 Elite fisico tramite Qualcomm Device
Cloud, stesso GGUF Q4_0 con llama.cpp:

| Backend | Lettura, 128 token | Generazione, 32 token |
| --- | ---: | ---: |
| CPU, sei thread | 225,17 | 42,48 |
| Hexagon NPU | 1.145,39 | 28,50 |

NPU 5,1× più veloce nel prefill; CPU 1,49× più veloce nel decode. È identificato
il chip e il device cloud, non il modello commerciale del telefono. La stessa
fonte distingue questi risultati dalle richieste via server, che misurano un altro
percorso e riportano circa 38 token/s NPU: non sono la stessa prova.
[Misure e limiti H2Loop](https://huggingface.co/h2loop-ai/gemma-4-e2b-hexagon/blob/main/README.md).

Anche ailia pubblica un confronto E2B, senza MTP, su Snapdragon 8+ Gen 1:

| Fase | CPU | NPU |
| --- | ---: | ---: |
| Lettura, prompt di 2.048 token | 71,244 token/s | 299,827 token/s |
| Generazione | 9,971 token/s | 8,062 token/s |

Su Snapdragon 7s Gen 3 riporta invece E2B a 59,192→130,252 token/s nel prefill
di 2.048 token e 5,805→6,410 token/s nel decode, passando da CPU a NPU.
I percorsi usano runtime/formati diversi; la pagina identifica i SoC, senza
specificare qui i modelli commerciali dei telefoni. Non è un confronto diretto con
LiteRT-LM o con superMens. [Benchmark del produttore ailia](https://docs.ailia.ai/en/llm/qnn-bench.html).

### Pixel 7a: evidenza specifica

Un progetto indipendente dichiara un test reale del 2 luglio 2026 con Gemma 4 E2B,
LiteRT-LM, backend GPU verificato e dodici domande: **15–18 token/s**.
Manca un confronto completo CPU/GPU e una misura di prefill utilizzabile per
il confronto. Non è una misura di superMens né una prestazione garantita.
[Test Pixel 7a](https://github.com/eclairsameal/android-on-device-llm-poc/blob/main/litert_lm_poc/README.md).

### MTP e speculative decoding

Google documenta MTP con benefici dipendenti dal compito. In una prova separata
su S26 Ultra, E2B GPU passa da una baseline aggregata di 51,5 token/s a
91,7 per riassunti, 84,4 per codice, 87,4 per riscrittura e 66,5 per testo libero
con speculative decoding. Non sono prove energetiche e la baseline aggregata
non è una misura abbinata per ciascun compito. Questi valori non vanno mescolati
con le tabelle precedenti.
[Fonte Google](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm).

## Batteria: stesso numero di token

Non è stato trovato un confronto affidabile e completo **CPU/GPU/NPU sullo stesso
telefono con Gemma 4**, con energia misurata e condizioni uniformi.
I benchmark di velocità precedenti non dimostrano un risparmio di batteria.

La grandezza utile è l'energia, non la sola potenza:

```text
Energia della generazione [J] = integrale della potenza nel tempo
Energia per token [J/token] = energia della generazione / token prodotti
Con potenza e velocità medie sulla stessa finestra:
J/token = potenza media [W] / velocità media [token/s]
Wh = J / 3600
```

Per una richiesta completa va considerato anche il caricamento e il prefill.
Occorre dichiarare se si misura l'intero telefono oppure il solo consumo aggiuntivo
rispetto all'inattività. Le due metriche rispondono a domande differenti.

Una NPU con meno watt può impiegare più tempo e consumare più joule per risposta.
Anche una CPU poco occupata non prova basso consumo totale: acceleratore, memoria,
schermo e resto del telefono continuano ad assorbire energia. Temperatura e
percentuale di utilizzo CPU non sostituiscono un contatore energetico.

### Misure dalla batteria: iPhone 17 Pro, E2B

Il benchmark indipendente Apple Silicon pubblica stime aggiornate:

| Configurazione | J per token prodotto | Equivalente per 1.000 token |
| --- | ---: | ---: |
| GPU, LiteRT-LM | 0,147 | 147 J = 0,041 Wh |
| GPU, MLX Swift PTQ 4-bit | 0,182 | 182 J = 0,051 Wh |
| CPU | Dato confrontabile non trovato | — |
| NPU / Neural Engine | Dato aggiornato confrontabile non trovato | — |

L'ultima colonna è una conversione aritmetica, non una singola prova da 1.000 token.
Sono energia della sessione rapportata ai token prodotti, non isolamento del solo
acceleratore o del solo decode. Runtime e quantizzazioni differiscono.
Il metodo misura fra transizioni dell'indicatore batteria; incertezza dichiarata
circa ±10%. Valori precedenti sono stati ritirati per instabilità dell'indicatore:
non usarli per costruire una classifica NPU/GPU.
[Dati aggiornati e metodo](https://github.com/john-rocky/apple-silicon-llm-bench).

### Riscontro CPU/GPU/NPU su PC Intel: non trasferibile ai telefoni

Un autore pubblica queste misure di E2B INT4 su Core Ultra 7 258V / Lunar Lake,
60 secondi di generazione, potenza dell'intero sistema registrata con HWiNFO:

| Backend | Potenza sistema | Velocità | J/token |
| --- | ---: | ---: | ---: |
| CPU | 34,4 W | 12,0 token/s | 2,85 |
| GPU | 37,0 W | 36,0 token/s | 1,03 |
| NPU | 29,0 W | 24,9 token/s | 1,17 |

La GPU consuma circa il 12% in meno per token della NPU, pur assorbendo più watt,
perché termina prima. Sono valori riportati dalla fonte, arrotondati; non usarli
per stimare batteria Pixel o classificare le NPU mobili.
[Misurazione originale](https://zenn.dev/jkudo/articles/ae85d7d099e672?locale=en).

## Implicazioni e prossima misura utile per superMens

Sul Pixel 7a il percorso concreto da valutare resta GPU/CPU. L'integrazione NPU
per altri dispositivi sarebbe un lavoro distinto con modelli e librerie per SoC.
Un aggiornamento del runtime o MTP può meritare un esperimento, ma questa ricerca
non ne dimostra né il beneficio sul 7a né il risparmio energetico.

Per scegliere il backend in base all'autonomia, misurare **mWh per riassunto** e
**J per token generato**, con questo protocollo proposto, non ancora eseguito:

1. Stesso Pixel, APK, modello, contesto, prompt e budget effettivo di output;
   contare anche eventuali token di ragionamento prodotti.
2. Verificare il backend effettivo, senza dedurlo dall'etichetta richiesta.
3. Telefono scollegato, schermo e impostazioni costanti, condizioni iniziali
   comparabili; evitare attività concorrenti variabili.
4. Separare caricamento, prefill e decode; distinguere prima richiesta e modello
   già caricato. Per documenti reali misurare anche il lavoro completo.
5. Ripetere in ordine alternato, con raffreddamento fra gruppi, includendo una
   prova sostenuta per verificare il throttling.
6. Usare misure energetiche/di carica disponibili e finestre sufficientemente
   lunghe; registrare tensione se si converte carica in energia. Una variazione
   di percentuale batteria su una singola risposta è troppo grossolana.
7. Conservare durata, token in/out, energia, temperatura/stato termico e
   dispersione fra esecuzioni, insieme alla qualità del risultato.

Fino a quella misura, **non attribuire a CPU, GPU o NPU una percentuale di
risparmio per superMens sul Pixel 7a**.
