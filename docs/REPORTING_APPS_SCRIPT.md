# Segnalazioni AI tramite Google Apps Script

Il servizio inoltra le segnalazioni esclusivamente a **lumieitalia@gmail.com**, con oggetto fisso `[SuperMens] Segnalazione risposta AI`. Non richiede hosting a pagamento. La chat libera resta disponibile. Il titolare ha distribuito lo script e fornito l’URL il 29 ottobre 2026. Accesso anonimo, JSON e ricezione della prima email sintetica sono verificati. La configurazione pubblica dell’app ora include l’endpoint per la beta4 locale. Pubblicazione delle informative e distribuzione Play restano fasi successive.

## File e contratto

- `tools/apps-script/Code.gs`: ricevitore e invio email in testo semplice tramite `MailApp`.
- `tools/apps-script/appsscript.json`: runtime V8, esecuzione come titolare, utenti anonimi; unico scope `script.send_mail`. Non concede lettura della posta, Drive o accesso all’archivio.
- `reporting.properties`: configurazione pubblica dell’app; contatto ed endpoint impostati dopo verifica della prima email sintetica. Le variabili `SUPERMENS_REPORT_ENDPOINT` e `SUPERMENS_PRIVACY_EMAIL` hanno precedenza. Il contatto deve restare coerente con l’informativa.

Il POST JSON contiene `version: 1`, `reason` (`child_safety`, `harmful`, `deceptive`, `other`), `response` (testo selezionato e modificabile, massimo **16.000 byte UTF-8**, come nelle build precedenti), `app_version` (1–80 caratteri di versione) e, solo se scelto, `diagnostics`. L’intera richiesta, compreso l’escaping JSON, non può superare **32.768 byte**. Campi inattesi, prompt, archivio, URL aggiunti, destinatari e oggetti personalizzati vengono rifiutati. La risposta selezionata può naturalmente contenere dati personali: l’utente deve poterli rimuovere nell’anteprima.

I campi diagnostici ammessi sono gli stessi di `ModelDiagnostics.snapshot()`: versione app/codice, produttore/modello/SoC, SDK Android, RAM totale/disponibile, nome/stato del modello, backend, visione, tempi di caricamento/generazione/primo token, caratteri in uscita, stato termico, errore foreground e flag di certificazione. Si validano anche i tipi; stringhe fino a 200 caratteri e numeri interi sicuri. Il testo diagnostico visualizzato è acquisito solo all’attivazione della casella e quello stesso contenuto viene inviato. Consenso e diagnostica iniziano deselezionati a ogni apertura. Nessun nuovo sistema di raccolta delle prestazioni, invio automatico o tentativo automatico è introdotto.

Il limite iniziale è **80 prenotazioni di invio ogni 24 ore**, a partire dalla prima prenotazione della finestra. È globale per il progetto, persistente nelle Script Properties e protetto da Script Lock, mantenuto durante l’invio. Ogni tentativo email prenota un posto prima di chiamare MailApp; anche un errore email lo consuma, per evitare di superare il tetto in caso di esito incerto. Prima di ogni prenotazione si verifica `MailApp.getRemainingDailyQuota()`. Un contatore corrotto o un errore dei servizi Google blocca l’invio. Non azzerare il contatore per aggirare la quota e non creare più progetti paralleli per il medesimo servizio.

Il contratto usa JSON, anche per gli errori: successo `{"version":1,"ok":true}` solo dopo che MailApp ha accettato l’invio; rifiuto `{"version":1,"ok":false,"error":"…"}` con `invalid_report`, `quota_exceeded`, `busy`, `email_failed`, `unavailable`, oppure `method_not_allowed` per GET. ContentService restituisce normalmente HTTP 200; quel codice da solo non prova il successo. L’accettazione di MailApp non prova l’arrivo nella casella.

Il client invia il POST una sola volta a `https://script.google.com/macros/s/ID/exec`. Segue i 302/303 usando GET senza corpo verso `https://script.googleusercontent.com/macros/echo?...`; ammette al massimo tre reindirizzamenti HTTPS su quell’host/percorso. Rifiuta host di login, porte esplicite, userinfo, frammenti, altri host/percorsi e 301/307/308 sul POST. Non inoltra il contenuto attraverso un redirect. Controlla MIME JSON e dimensione della risposta (4.096 byte). I timeout mostrano **invio non confermato**, con avviso del possibile duplicato se si riprova; nessun errore genera tentativi automatici.


## Verifica del deployment del 29 ottobre 2026

Il deployment fornito dal titolare è registrato in `reporting.properties`. Senza cookie, credenziali o login, il GET ha seguito un 302 da `script.google.com` al solo `script.googleusercontent.com/macros/echo` e restituito HTTP 200 JSON `{"version":1,"ok":false,"error":"method_not_allowed"}`. Una sola richiesta POST sintetica, senza diagnostica, ha seguito lo stesso percorso e restituito `{"version":1,"ok":true}`. Codice di riscontro: `SM-REPORT-20261029-163040Z`; il titolare ha confermato in chat l’arrivo a **lumieitalia@gmail.com**. Il contenuto della segnalazione non viene conservato in questi documenti.

L’URL è quindi inserito nella configurazione della beta4 (version code 14). La prova iniziale è stata inviata da uno strumento HTTP di verifica. Anche il secondo controllo è riuscito: il dialogo Android reale, con testo sintetico modificato, consenso selezionato e diagnostica esclusa, ha ricevuto il JSON positivo per il codice `SM-APP-20261029-164722Z`. Il titolare ha confermato anche l’arrivo di questa seconda email a lumieitalia@gmail.com: il percorso dal dialogo Android alla casella è verificato per questo candidato. `LiveAiReportTest` offre una prova esplicitamente abilitata con `-e liveReport true`; per impostazione predefinita viene saltata e non spedisce email nei normali test. Le prove restano sintetiche, senza archivio o diagnostica.

## Autorizzazione e distribuzione nell’account del titolare

1. Accedere al proprio account su [Google Apps Script](https://script.google.com/) e creare un progetto autonomo dedicato, per esempio “SuperMens segnalazioni”. Controllare che l’account sia quello scelto dal titolare; la casella destinataria resta sempre `lumieitalia@gmail.com`.
2. Copiare integralmente `tools/apps-script/Code.gs` in `Code.gs`. Nelle impostazioni del progetto abilitare la visualizzazione di `appsscript.json` e sostituirlo con il manifest incluso. Non aggiungere librerie, log dei payload, servizi Gmail o altri scope. `exceptionLogging: NONE` evita la registrazione automatica delle eccezioni; il codice intercetta gli errori senza registrarli.
3. Selezionare `authorizeReportService` nell’editor e premere **Esegui**. Autorizzare l’invio della posta come titolare. La funzione controlla la quota senza inviare email. Verificare che non venga richiesto accesso alla posta in arrivo; in caso contrario confrontare codice e manifest prima di procedere. Non condividere token o credenziali con l’app.
4. **Distribuisci → Nuova distribuzione → App web**. Esegui come **me / titolare della distribuzione**; accesso **Chiunque**, compresi utenti senza account/login (`ANYONE_ANONYMOUS`, non l’opzione che richiede un account Google). Se l’organizzazione impedisce accesso anonimo, questo deployment non soddisfa il requisito.
5. Copiare l’URL di produzione che termina in `/exec`, non `/dev`, un URL di login o il link temporaneo `googleusercontent`. Dopo ogni modifica al codice usare **Gestisci distribuzioni → Modifica → Nuova versione** per aggiornare la stessa distribuzione, mantenendo il contatore nel medesimo progetto.
6. Verificare l’accesso senza login e il percorso di redirect con un invio sintetico. Un GET di controllo deve restituire l’errore JSON `method_not_allowed`, senza inviare email. Il POST di prova deve restituire `ok:true`; aprire la casella destinataria e controllare arrivo, oggetto, testo e assenza della diagnostica quando non scelta. Non usare documenti personali per la prova.
7. Solo dopo questa verifica compilare `reportEndpoint=` in `reporting.properties` con l’URL `/exec` effettivo. Non inserire segreti: un endpoint in un APK è pubblico. Pubblicare le informative EN/IT aggiornate prima di attivare invii per gli utenti. Un endpoint vuoto mantiene il pulsante di invio disabilitato. `SUPERMENS_PUBLIC_RELEASE=true` fa fallire Gradle senza endpoint, ma non certifica che il servizio funzioni.
8. Preparare una build di prova con l’endpoint verificato e inviare **dall’app** un’altra segnalazione sintetica. Verificarne l’arrivo a **lumieitalia@gmail.com** prima della build Play. Registrare data, versione app/script, esito JSON e conferma di arrivo senza copiare il contenuto del report nei documenti pubblici.

L’endpoint anonimo può essere utilizzato anche fuori dall’app e abusato fino all’esaurimento del limite globale. Non ci sono segreti client o identificatori persistenti con cui autenticare un’installazione. Se necessario sospendere/archiviare il deployment da Gestisci distribuzioni e riesaminare il servizio; non descrivere il tetto come protezione individuale antiabuso o garanzia di disponibilità.

## Gestione e cancellazione entro 30 giorni

Responsabile operativo: titolare BBNSS, contatto **lumieitalia@gmail.com**. Esaminare tempestivamente le segnalazioni sulla tutela dei minori e regolarmente le altre; usare gli esiti per migliorare filtri e test senza pubblicare il testo ricevuto nelle issue. Limitare l’accesso alle caselle alle persone autorizzate.

La cancellazione è **manuale**, perché MailApp non legge né cancella la posta. Impostare un promemoria giornaliero e cancellare le segnalazioni prima che raggiungano 30 giorni di età. Controllare la casella destinataria e quella dell’account che esegue lo script, se diversa: Posta in arrivo, Tutti i messaggi/Archivio, Inviati, Spam e Cestino. Cercare l’oggetto esatto `[SuperMens] Segnalazione risposta AI`; controllare anche eventuali copie inoltrate, esportate o sincronizzate sotto il controllo del titolare. Rimuovere le copie e **eliminarle definitivamente dal cestino entro lo stesso termine**: il normale svuotamento Gmail dopo altri 30 giorni non soddisfa questa politica. Evitare inoltri e backup delle segnalazioni; se esistono, applicare la medesima scadenza alle copie controllabili. Registrare solo data/avvenuta pulizia, senza contenuti o identificativi dell’utente.

Lo script conserva nelle Properties soltanto inizio finestra e numero delle prenotazioni; non conserva payload, IP, identificatori o indirizzi dei segnalanti. Google tratta il contenuto per Apps Script e per la consegna/conservazione email e può trattare metadati di connessione e servizio secondo le proprie politiche. Il codice non controlla la conservazione dei registri e delle copie infrastrutturali di Google: la promessa dei 30 giorni riguarda le copie sotto il controllo del titolare. Per richieste di cancellazione scrivere al contatto, indicando solo i dettagli minimi per trovare la segnalazione, senza reinviare dati sensibili.

## Verifiche ripetibili

```sh
node --test --test-isolation=none tools/apps-script/report_service.test.cjs
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=it.supermens.local.AiReportUiTest
```

La prova reale di `LiveAiReportTest` è separata: eseguirla solo dopo aver installato APK e APK dei test compilati con l’endpoint verificato, scegliendo esplicitamente l’emulatore/dispositivo. Spedisce una sola email sintetica dal dialogo reale, senza diagnostica e senza retry:

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e class it.supermens.local.LiveAiReportTest -e liveReport true \
  it.supermens.offline.test/androidx.test.runner.AndroidJUnitRunner
```

Il test attraversa anteprima modificabile e consenso, usa `AiReport.send()` senza sostituzioni e richiede il messaggio di successo dopo il JSON positivo. La conferma della casella destinataria resta separata: il test non legge la posta. Senza `liveReport=true` viene saltato anche quando si esegue l’intera suite Android.

I test Apps Script eseguono il codice reale in un contesto V8 locale con servizi Google simulati: schema, UTF-8, corpo/escaping, destinatario e oggetto fissi, diagnostica, tetto 80, scadenza finestra, quota Google esaurita, lock occupato, errori email e storage, GET e scope. Non sono un test di disponibilità o consegna Google. I test JVM coprono il POST singolo, GET dopo redirect, host vietati, loop, errori HTTP/MIME, risposta eccessiva, rete assente e timeout. I test Android coprono consenso iniziale, modifica del testo, diagnostica inclusa/esclusa e corrispondenza all’anteprima, errori senza retry, endpoint mancante e conferma JSON.

Restano obbligatorie le prove sul dispositivo con il deployment reale: consenso/diagnostica inizialmente disattivati; testo redatto nell’email; diagnostica corrispondente all’anteprima; nessuna rete; timeout senza conferma di successo; effettivo arrivo nella casella. Le prove simulate di quota/email evitano di esaurire realmente l’account.

## Fonti Google verificate il 29 ottobre 2026

- [MailApp](https://developers.google.com/apps-script/reference/mail/mail-app): invio senza accesso alla posta in arrivo e quota residua per destinatari.
- [Web app](https://developers.google.com/apps-script/guides/web) e [manifest](https://developers.google.com/apps-script/manifest/web-app-api-executable): esecuzione come titolare e accesso anonimo.
- [ContentService e redirect](https://developers.google.com/apps-script/guides/content#redirects): risposta su URL temporaneo `script.googleusercontent.com`.
- [Quote](https://developers.google.com/apps-script/guides/services/quotas): attualmente 100 destinatari/giorno per account consumer; soglie e disponibilità possono cambiare. Il tetto applicativo di 80 non sostituisce il controllo della quota effettiva.
- [Manifest](https://developers.google.com/apps-script/manifest): scope espliciti e `exceptionLogging: NONE`.
