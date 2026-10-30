# Free My Fire TV

Una Home **senza pubblicità** per le **Amazon Fire TV Stick** (Fire OS 7 = Android 9, Fire OS 8 = Android 11): app
preferite, film e serie consigliati, meteo, orologio e sfondi, senza righe sponsorizzate. È un fork di
[Free My TV](https://github.com/simbasjungles/free-my-tv) (MIT) di simbasjungles e ne mantiene grafica, animazioni e
uso con il telecomando.

> **Stato, da leggere subito.** L'app si compila da sorgente con GitHub Actions e la logica di decisione è coperta da
> test automatici. **Provata finora su un solo dispositivo:** una Fire TV Stick con Fire OS 7.7.1.7 (segnalato dal
> proprietario): la Home gira fluida e il reindirizzamento del tasto Home funziona, con la Home di Amazon visibile per
> circa un secondo prima. Altri modelli e Fire OS 8 non sono provati, e l'apertura all'accensione è nuova nella 0.1.1
> e ancora da confermare. Se il reindirizzamento non funziona sulla tua chiavetta, l'app resta utilizzabile come app che
> avvii tu. Cosa serve ancora provare è in [docs/DEVICE-TESTS.md](docs/DEVICE-TESTS.md).

English: [README.md](README.md)

## Come funziona su Fire TV

Amazon mantiene il proprio launcher come Home di sistema. Questa app **non** lo sostituisce, non usa root e non
disattiva nulla (nessun pacchetto `com.amazon.*` viene mai disattivato). Invece:

1. **Avvio manuale:** l'icona *Free My Fire TV* tra le tue app apre la Home. Funziona sempre.
2. **Reindirizzamento del tasto Home (facoltativo):** un piccolo servizio di accessibilità rileva quando la Home di
   Amazon passa in primo piano (solo cambi di finestra: non legge il contenuto dello schermo né i tasti premuti) e
   apre Free My Fire TV sopra. Dove Fire OS lo consente, premere Home apre Free My Fire TV.

L'idea del reindirizzamento è ispirata a [Home on Fire](https://github.com/toolicious/home-on-fire) (GPLv3). **Nessun
codice di quel progetto è usato qui**: il servizio è stato scritto in modo indipendente e il progetto resta MIT.

## Installazione

Servono l'indirizzo IP della chiavetta (Impostazioni > My Fire TV > Informazioni > Rete) e il *Debug ADB* attivo
(Impostazioni > My Fire TV > Opzioni sviluppatore; se non compaiono, premi OK 7 volte su *Informazioni > Fire TV Stick*).

1. Scarica `FreeMyFireTV.apk` dalla pagina [Releases](../../releases), oppure dall'ultima esecuzione riuscita nella
   scheda **Actions** (artifact `FreeMyFireTV-apk`). Non servono strumenti di compilazione sul tuo computer.
   Prima compilazione? Vedi [docs/FIRST-BUILD.md](docs/FIRST-BUILD.md) (in inglese).
2. Da un computer sulla stessa rete Wi-Fi, con [adb](https://developer.android.com/tools/releases/platform-tools):
   ```
   adb connect <IP>:5555        # accetta "Consentire il debug USB?" sulla TV e spunta "Consenti sempre"
   adb install -r FreeMyFireTV.apk
   ```
3. Apri *Free My Fire TV* dalle tue app. Dalla sua Home, l'ingranaggio apre le impostazioni, come il tasto Menu (≡)
   del telecomando.

Un APK generato **senza** la chiave di firma del progetto usa una chiave temporanea: si installa, ma una build
successiva non può aggiornarlo (prima va disinstallato). Le release sono firmate con la chiave del progetto.

## Configurazione del tasto Home (facoltativa)

Sulla TV: **Impostazioni > Tasto Home > Configura il tasto Home.** L'app prova, in ordine, e ti dice quale metodo ha
funzionato e cosa ha potuto verificare:

1. il servizio è già attivo;
2. l'app ha già il permesso `WRITE_SECURE_SETTINGS`;
3. l'ADB della TV stessa (`127.0.0.1:5555`): la TV chiede una volta *Consentire il debug USB?*. Il risultato viene
   controllato dopo, non dato per scontato;
4. altrimenti apre le impostazioni di Accessibilità e mostra il comando esatto da eseguire da un computer:
   ```
   adb shell pm grant io.freemyfiretv android.permission.WRITE_SECURE_SETTINGS
   ```

Lo stato diventa **Funzionante** solo dopo un reindirizzamento reale. *Pronto: premi Home per provare* significa che il
servizio è attivo ma non è ancora stato visto alcun reindirizzamento. **Non è garantito che un permesso o un passaggio
ADB bastino sulla tua versione di Fire OS**: lo dice solo il risultato mostrato sulla TV.

## Limiti

- **Poche prove su hardware reale** (vedi sopra): finora una sola chiavetta con Fire OS 7.
- **Fire OS 7** ritarda le app avviate in background. Su Fire OS 7.7.1.7 il reindirizzamento ha richiesto circa 1
  secondo; altre versioni possono essere più lente (fino a circa 5 secondi).
- **All'accensione** parte sempre prima il launcher di Amazon; dalla 0.1.1 il servizio apre anche Free My Fire TV
  quando si collega entro 3 minuti dall'avvio della TV, quindi la Home di Amazon può comparire ancora per un attimo.
  Non ancora confermato su una chiavetta.
- **Fire OS 8** può mostrare per un attimo la Home di Amazon prima di Free My Fire TV.
- Se la Home di Amazon è già in primo piano quando avvii il servizio, non succede nulla fino al prossimo arrivo.
- **Premi Home due volte velocemente** per andare alla Home di Amazon (il reindirizzamento si ferma 20 s). Se Free My
  Fire TV non riesce a restare aperta, il reindirizzamento si ferma da solo per un minuto; dopo 3 arresti in 5 minuti si
  disattiva.
- Le chiavette **Vega OS** (più recenti, non Android) non sono supportate.
- Mostrati come *Non disponibile* su Fire TV in questa versione: Continua a guardare, In riproduzione, servizio dei
  tasti del telecomando e aggiornamenti automatici. Il timer di spegnimento c'è ma non è stato provato su una chiavetta.
- Niente aggiornamenti automatici: scarica il nuovo APK e installalo con `adb install -r`.
- Android disattiva i servizi di accessibilità quando un'app viene aggiornata; l'app li riattiva se ha il permesso e
  l'avevi configurata (controlla dopo l'aggiornamento).
- Profili bambini e app a schermo intero possono comportarsi in modo diverso. Segnala cosa osservi.

## Coprire la Home di Amazon (0.3.0, non provato)

Il launcher di Amazon non si può spegnere né sostituire su Fire OS 7.7.1.7 (vedi sotto; impostare quest'app come Home
predefinita viene accettato ma Amazon si vede comunque per primo). Allora **Impostazioni > Tasto Home > Copri la Home di
Amazon durante il passaggio** (attivo di default) mette una schermata nera sopra lo schermo appena arriva la Home di
Amazon e la toglie quando Free My Fire TV è pronta (al massimo 4 secondi, non prende i tasti). Nasconde il lampo, non lo
elimina: il reindirizzamento parte comunque dopo che la Home di Amazon è comparsa. Premere Home due volte porta ancora
alla Home di Amazon.

## Nascondere la Home di Amazon (sperimentale, facoltativo)

> **Risultato su una chiavetta vera:** su Fire OS 7.7.1.7 Android rifiuta qualsiasi modifica al launcher di Amazon
> (`Cannot disable a protected package: com.amazon.tv.launcher`, visto con `pm enable`), quindi questa opzione dovrebbe
> fallire lì con un messaggio chiaro, senza cambiare nulla. Resta per altre versioni di Fire OS, non provate.

Impostazioni > Tasto Home > **Nascondi la Home di Amazon (sperimentale)** (premi OK due volte) **spegne, senza
rimuoverla,** la Home di Amazon (`pm disable-user com.amazon.tv.launcher` con l'ADB della TV stessa) e imposta Free My
Fire TV come Home. Il tasto Home e l'accensione aprono solo Free My Fire TV. L'app controlla dopo; se i controlli
falliscono rimette tutto a posto.

Cosa comporta:
- **Reversibile:** Impostazioni > Tasto Home > *Riaccendi la Home di Amazon* (o *Ripristina la Home di Amazon*), oppure da
  un computer `adb shell pm enable com.amazon.tv.launcher`; anche disinstallare l'app restituisce la Home, se il launcher
  è riacceso. Dopo 3 arresti in 5 minuti l'app riaccende da sola il launcher di Amazon.
- **Le impostazioni di Amazon vivono nel suo launcher** e quindi spariscono: *Impostazioni TV* apre direttamente le pagine
  dell'app di impostazioni di Amazon, e il cassetto ha scorciatoie per Rete, Suoni e schermo, App, Controller e Bluetooth,
  Preferenze e Accessibilità. Impostazioni rapide, Alexa dalla Home e altro legato al launcher possono smettere di
  funzionare. **Non verificato oltre quanto scritto in docs/DEVICE-TESTS.md.**
- Un aggiornamento di Fire OS o un riavvio può riaccendere il launcher: allora la Home di Amazon può ricomparire.
- Se la Home dell'app non parte e il launcher di Amazon è spento, la TV potrebbe non mostrare nulla finché non esegui il
  comando sopra da un computer (serve la chiavetta in rete). Tieni il comando a portata di mano prima di provare.
- Va contro la regola abituale del progetto (niente di Amazon viene spento): esiste solo perché il proprietario l'ha
  chiesta, ed è spenta finché non la attivi.

## Ripristinare la Home di Amazon (sempre possibile)

- **Dalla TV:** Impostazioni > Tasto Home > **Ripristina la Home di Amazon**. Spegne il servizio e lo toglie dai
  servizi di accessibilità attivi.
- **Premi Home due volte velocemente** per andare subito alla Home di Amazon.
- **Riquadro nella Home:** *Home di Amazon* nel cassetto delle app.
- **Da un computer**, se l'app è bloccata:
  ```
  adb shell settings put secure enabled_accessibility_services ""
  adb shell pm revoke io.freemyfiretv android.permission.WRITE_SECURE_SETTINGS
  ```
  (la prima riga cancella tutti i servizi di accessibilità attivi, anche di altre app; per tenere gli altri leggi
  l'elenco con `adb shell settings get secure enabled_accessibility_services` e togli solo la voce
  `io.freemyfiretv/...`)
- **Disinstallazione:** `adb uninstall io.freemyfiretv` o da Impostazioni > Applicazioni. Non resta nessun'altra
  modifica.

## Privacy

Contatta solo TMDB (consigli, con la tua chiave, facoltativa), Open-Meteo (meteo) e, se attivo, Binance. Nessuna
pubblicità, nessuna statistica. Il servizio di accessibilità legge soltanto il nome della finestra che è passata in
primo piano. L'aggiornamento automatico è disattivato.

## Compilazione

GitHub Actions compila con gli strumenti ufficiali Google (`.github/workflows/build.yml`). In locale: JDK 17, Android
SDK `build-tools;34.0.0` + `platforms;android-34`, poi `./build.sh --generic`. Controlli: `tools/check.sh`.

## Crediti e licenza

MIT, vedi [LICENSE](LICENSE). Basato su **Free My TV**, copyright (c) simbasjungles e collaboratori; questo fork ne
conserva l'avviso. Crediti come nell'originale: TMDB (questo prodotto usa le API di TMDB ma non è approvato né
certificato da TMDB), Open-Meteo, Material Symbols, Inter e il generatore di QR di Project Nayuki (MIT). *Home on
Fire* di toolicious è solo un'ispirazione; nessun suo codice è incluso. Fire TV e Amazon sono marchi di Amazon.com,
Inc.; il progetto non è affiliato ad Amazon.
