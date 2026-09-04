### Deploy

La cartella `site/` è il sito: HTML, JSON e due file di servizio.

- **`_headers`** — cache per Cloudflare Pages e Netlify: 5 minuti sui JSON con
  `stale-while-revalidate` di un giorno (i dati cambiano una volta al giorno), nessuna
  cache sull'HTML così un deploy si vede subito. Più `nosniff` e `Referrer-Policy`.
- **`.nojekyll`** — serve solo se pubblichi su GitHub Pages, che altrimenti passa tutto
  per Jekyll e ignora i file che iniziano con underscore.

Punta Cloudflare Pages (o GitHub Pages) alla cartella `site/`. Niente build step, niente
server.

`.github/workflows/update.yml` gira ogni giorno alle 05:17 UTC:

1. ripristina l'archivio SQLite dalla cache di Actions (se manca, rifà il backfill completo);
2. aggiorna **solo la season in corso** (`--refresh-current`, ~640 richieste);
3. riscrive `site/data` e committa se è cambiato qualcosa.

Serve un secret di repo **`POE2SCOUT_CONTACT`** con un indirizzo email: poe2scout chiede
uno User-Agent con un contatto per l'uso continuativo.

L'export dura **circa un secondo**: senza `--windows` non costruisce il backtest, quindi
non carica l'archivio in memoria e non gira 42 combinazioni.

# PoE2 Economics

Backfill e backtest dell'economia di **Path of Exile 2** sui dati pubblici di
[poe2scout](https://poe2scout.com).

Risponde a una domanda sola: **all'inizio di una lega nuova, cosa conviene tenere e cosa
conviene liquidare, sulla base di quello che è successo nelle leghe precedenti?**

Non è un price checker. Di quelli ce ne sono sei (Exiled Exchange 2, PoE Overlay II,
poe2scout, poe2pricecheck, exiledtools, e dalla 0.5.0 anche il gioco stesso).
Questo guarda il **ciclo di vita della lega**, che nessuno guarda.

---

## Come funziona

1. **backfill** — scarica da poe2scout lo storico giornaliero (OHLC + volume) di ogni
   valuta, per ogni lega, e lo mette in SQLite. Le leghe chiuse non cambiano più: si
   scarica una volta e resta.
2. **backtest** — per ogni item calcola il rendimento di *tenere quell'item invece di un
   altro* fra il giorno D e il giorno D+K della lega, e lo confronta su tutte le leghe
   passate.
3. **scan** — griglia di giorni di entrata × orizzonti, per trovare dove sta l'edge.

### Perché "contro un quote" e non "il prezzo è salito"

In PoE non possiedi valore, possiedi *una cosa invece di un'altra*. E siccome tutti i
prezzi dell'API sono denominati in **Exalted Orb** — che si svaluta massicciamente durante
ogni lega — se guardi il prezzo assoluto vedi solo inflazione e ti sembra che tutto salga.

Verificato sui dati: Divine Orb in Exalted, dal giorno 1 alla fine lega, fa ×69,6 (Dawn of
the Hunt), ×22,4 (Rise of the Abyssal), ×4,3 (Fate of the Vaal). Non è il Divine che sale,
è l'Exalted che crolla.

Quindi il quote di default è `exalted` per misurare l'inflazione, ma **l'analisi seria si
fa con `--quote divine`**, che toglie di mezzo gran parte del rumore.

---

## Uso

```bash
export POE2SCOUT_CONTACT='tua@email'      # richiesto da poe2scout per l'uso continuativo
mvn -q package

# scarica tutto (una volta sola, ~1700 richieste, ~20 minuti)
java -jar target/poe2economics.jar backfill

# solo le valute principali, per provare (~190 richieste, ~2 minuti)
java -jar target/poe2economics.jar backfill --categories currency

# cosa c'è in archivio
java -jar target/poe2economics.jar leagues

# dove sta l'edge
java -jar target/poe2economics.jar scan --quote divine --min-volume 200 --min-leagues 3

# dettaglio di una finestra, con esporto CSV
java -jar target/poe2economics.jar backtest --day 7 --horizon 14 --quote divine \
     --min-volume 200 --min-leagues 3 --top 20 --csv

# lo stesso, ma assumendo il riempimento peggiore possibile
java -jar target/poe2economics.jar backtest --day 7 --horizon 14 --quote divine \
     --min-volume 200 --min-leagues 3 --fill pessimistic
```

### `--fill avg` contro `--fill pessimistic`

`avg` usa il prezzo medio giornaliero: è comodo ed è ottimista, perché nella realtà non
riempi mai a metà del book. `pessimistic` compra l'item al **massimo** di giornata e lo
rivende al **minimo**, facendo l'opposto sul quote. È il caso peggiore, ed è l'unico
numero che puoi mostrare a qualcuno senza sentirti in colpa: se l'edge sopravvive lì,
esiste davvero.

Esporto per il sito, e la versione con anche il backtest per finestre:

```bash
java -jar target/poe2economics.jar export
java -jar target/poe2economics.jar export --windows
```

Aggiornamento quotidiano della sola lega in corso:

```bash
java -jar target/poe2economics.jar backfill --refresh-current
```

---

## L'API di poe2scout: cose imparate sul campo

Base: `https://api.poe2scout.com`, realm `poe2`, spec su `/openapi/v1.json`.
Nessuna autenticazione.

| Endpoint | Nota |
|---|---|
| `GET /poe2/Leagues` | nome, `ShortName`, **`IsCurrent`**, **`DivinePrice`** (Exalted per Divine) |
| `GET /poe2/Leagues/{lega}/Items/Categories` | `CurrencyCategories` + `UniqueCategories` |
| `GET /poe2/Leagues/{lega}/Currencies/ByCategory?category=&page=&perPage=` | `{CurrentPage, Pages, Total, Items[]}` |
| `GET /poe2/Leagues/{lega}/Items/{itemId}/DailyStatsHistory?dayCount=300` | **OHLC + Volume giornaliero. È il cuore di tutto.** |
| `GET /poe2/Leagues/{lega}/Items/{itemId}/History?logCount=` | granularità oraria |
| `GET /poe2/Leagues/{lega}/Uniques/ByCategory?category=` | unique (non ancora usato qui) |

**Trappole, tutte verificate:**

- Il nome lega va passato **per esteso e url-encoded** (`Dawn%20of%20the%20Hunt`).
  Lo `ShortName` (`hunt`) restituisce **400**.
- `dataPoints` alto su `ByCategory` restituisce **400**. Lo storico si prende da
  `DailyStatsHistory`, che non ha quel limite.
- **`referenceCurrency` non converte `CurrentPrice`**: passando `divine` torna comunque il
  valore in Exalted. Se ci si fida, si pubblicano numeri sbagliati senza accorgersene.
  La normalizzazione va fatta a mano, dividendo per il `DivinePrice` della *sua* lega.
- Gli `ItemId` sono **stabili fra leghe** (291 = Divine, 287 = Chaos, 295 = Mirror): è la
  chiave di join.
- **Hardcore permanente** non viene scaricato. **Standard** viene scaricato ma non esportato:
  serve solo come sorgente della season 0.1 (vedi sotto). La lega permanente in sé non ha un
  "giorno di lega" e non entra né nel backtest né nel sito.

**Educazione verso la fonte:** `User-Agent` con contatto obbligatorio, 300 ms minimi fra
le richieste, `Retry-After` rispettato, gzip attivo, e il backfill è idempotente
(`fetch_log`) così non si ripete mai lo stesso scaricamento.

---

## Cosa dicono i dati

Backfill completo delle sole leghe a tempo: **203.679 righe giornaliere**, tutte e 17 le
categorie di valuta.

| Lega | Giorni | Item | Da | A |
|---|---|---|---|---|
| Early Access *(0.1, derivata)* | 117 | 22 | 2024-12-08 | 2025-04-04 |
| Dawn of the Hunt (0.2) | 144 | 228 | 2025-04-05 | 2025-08-27 |
| Rise of the Abyssal (0.3) | 100 | 412 | 2025-08-30 | 2025-12-08 |
| Fate of the Vaal (0.4) | 164 | 437 | 2025-12-12 | 2026-05-25 |
| Runes of Aldur (0.5, in corso) | 98 | 643 | 2026-05-29 | oggi |

**Contro Exalted** i rendimenti sono enormi ovunque (+800%, +2000%): è quasi tutta
inflazione, non è un edge. Sono i numeri che pubblicheresti se non ci pensassi su.

**Contro Divine, con `--fill pessimistic`** (entrata giorno 7, uscita giorno 21,
volume ≥ 200, presente in almeno 3 leghe) — 335 item passano i filtri, e di quelli
solo **due** hanno il peggior caso positivo:

| Item | n | Mediana | Peggiore | Per lega |
|---|---|---|---|---|
| Hinekora's Lock | 3 | +203,8% | **+136,4%** | +136 / +204 / +348 |
| Uhtred's Augury | 3 | +194,7% | **+22,7%** | +23 / +653 / +195 |
| Atalui's Bloodletting | 3 | +149,3% | -84,9% | -85 / +275 / +149 |
| Rabbit Idol | 3 | +136,7% | -83,3% | +616 / +137 / -83 |

Tutto il resto ha mediane alte e casi peggiori disastrosi: è dispersione, non segnale.

**Il lato affidabile è quello negativo, e non è vicino.** Le rune perdono valore in
*ogni singola lega*, senza una sola eccezione:

| Item | n | Mediana | Per lega |
|---|---|---|---|
| Lesser Adept Rune | 3 | **-98,8%** | -99 / -79 / -99 |
| Lesser Body Rune | 4 | **-98,5%** | -78 / -99 / -98 / -100 |
| Greater Stone Rune | 4 | **-98,0%** | -97 / -100 / -96 / -99 |
| Body Rune | 3 | -97,7% | -98 / -92 / -98 |
| Greater Adept Rune | 4 | -97,5% | -98 / -98 / -95 / -98 |

Stessa storia per Preserved Rib (-98%), Stag Idol (-97%), le essenze perfette (-97%).

### Il conto che chiude la questione

Chiamiamo **concorde** un item su cui *tutte* le leghe osservate vanno nella stessa
direzione (nessuna eccezione). Con n ≥ 3, volume ≥ 200, quote divine, riempimento
pessimista:

| Finestra | Item analizzati | Concordi al ribasso | Concordi al rialzo |
|---|---|---|---|
| giorno 7 → 21 | 335 | **244** | **2** |
| giorno 60 → 74 | 236 | **192** | **0** |
| giorno 75 → 89 | 181 | **164** | **0** |

**Traduzione: in PoE 2 il Divine Orb batte quasi tutto, quasi sempre.** Il prodotto
robusto non è "cosa comprare per arricchirti" — è **"cosa smettere di accumulare"**.
Sul lato lungo c'è a malapena un segnale che regge il riempimento pessimista, e solo
nella prima settimana; sul lato corto ce ne sono centinaia, concordi su tutte le leghe.

È anche il consiglio più utile per il giocatore medio, ed è l'unica affermazione che
regge se qualcuno chiede i dati.

### Un limite strutturale del campione

Due leghe su quattro durano ~100 giorni (Rise of the Abyssal 100, Runes of Aldur 98
finora). Quindi **oltre il giorno ~85 il campione scende a n = 2**, e oltre il 105 sparisce.
Per questo l'esporto usa `--min-leagues 2` e la soglia vera si sceglie nella pagina:
è una decisione dell'utente, non un valore nascosto nel build.

---

## Le season, e la 0.1 che non ebbe una lega

PoE 2 è uscito in Early Access l'8 dicembre 2024, ma la prima lega a tempo
(Dawn of the Hunt) è partita il 5 aprile 2025. I quattro mesi in mezzo **sono** una season
a tutti gli effetti, ma il loro storico vive dentro Standard perché non esisteva una lega
separata dove metterlo.

Il backfill la ricostruisce: `Backfill.DERIVED` definisce `Early Access` come la
finestra `2024-12-08 → 2025-04-04` di Standard, e `Db.deriveSeason` la materializza come
una lega normale con un `INSERT ... SELECT`. Da lì in poi backtest, export e sito la
trattano come tutte le altre, e Standard resta fuori.

**Copertura onesta:** poe2scout aveva pochi dati allora. La 0.1 porta 22 item — essenze,
frammenti, rune e tre shard, con volumi reali e diversi con tutti i 118 giorni — ma
**non ha il Divine Orb**. Quindi in modalità Divine quella season sparisce, e la legenda
lo scrive: *no Divine Orb data in this window: Early Access (0.1)*. In Exalted c'è tutta.

La versione della patch di ogni season sta in `Exporter.patchOf` e viaggia dentro
`index.json`: la pagina la mostra fra parentesi ovunque compaia il nome, senza tenere una
seconda lista da riallineare a ogni lega nuova.

## Solo gli item della patch corrente

L'export tiene solo gli item che esistono **ancora** nella lega in corso. Uno che GGG ha
rimosso o rifatto non è azionabile per nessuno: lasciarlo nelle tabelle è rumore che sembra
un segnale.

Il filtro è l'insieme degli item con almeno una giornata di dati nella lega corrente.
Sull'archivio attuale toglie **17 item su 660**, e sono esattamente quello che ti aspetti:

| Categoria | Item rimossi |
|---|---|
| expedition | Black Scythe / Broken Circle / Order / Sun Artifact, Exotic Coinage |
| fragments | Primary/Secondary/Tertiary Calamity Fragment, Runic Splinter |
| ritual | Omen of Corruption, of Recombination, di Homogenising ×2, Petition Splinter |
| abyss / incursion / vaultkeys | Preserved Vertebrae, Vaal Infuser, Zarokh's Reliquary Key |

Tutta l'Expedition è sparita da Runes of Aldur, e con lei i suoi artifact. Gli altri sono
meccaniche di lega chiuse.

Per vedere anche quelli: `export --all-items`. Il filtro non tocca il database, solo
l'export: lo storico resta completo e basta riesportare per riaverlo.

## Il sito

`site/` è un sito **completamente statico**: una pagina HTML senza dipendenze e una
cartella di JSON. Nessun backend, nessun database in produzione, nessun cold start —
e regge un post su Reddit senza fare una piega.

```bash
java -jar target/poe2economics.jar export --fill pessimistic --min-leagues 2 \
     --days 1,3,5,7,10,14,21,30,45,60,75,90,105,120

# anteprima locale
jwebserver -p 8123 -d "$(pwd)/site"   # poi http://localhost:8123
```

Il sito è **una pagina sola**: una tabella di item con i filtri in alto, e il grafico che si
apre **solo cliccando la riga**.

### La tabella

Sopra i filtri c'è il selettore **League**: sceglie la season su cui sono calcolate le
colonne, e parte su quella in corso. Cambiandola, la tabella tiene solo gli item che in
quella season hanno dati — selezionando *Early Access (0.1)* restano i 22 item che
poe2scout copriva allora, non 643 righe di trattini. Il grafico invece mostra sempre tutte
le season: il selettore riguarda la tabella.

Il selettore di categoria si apre su **all categories**, che unisce tutti i file delle serie
in una tabella sola. È il default perché è la vista che uno si aspetta di trovare aperta, al
prezzo di qualche MB al primo caricamento — poi resta in cache, e scegliendo una singola
categoria si scarica solo quel file.

Colonne, calcolate sulla season selezionata:

| Colonna | Cosa contiene |
|---|---|
| Latest price *(solo Full history)* | ultimo prezzo di quella season, nell'unità scelta |
| Entry day price / Exit day price *(solo Time window)* | prezzo ai due estremi della finestra, col giorno fra parentesi. Sostituiscono *Latest price*, così la riga non mescola il prezzo di oggi con un margine che riguarda giorni passati — e il margine diventa verificabile: exit / entry − 1 |
| Divine margin *(0.5)* | variazione di prezzo **nella season selezionata** — il numero fra parentesi nell'intestazione è la sua patch — sull'intervallo scelto: tutta la season in Full history, i giorni scelti in Time window. Accanto alla percentuale una **sparkline** della stessa curva, verde o rossa secondo il verso. L'intestazione segue l'unità e diventa *Exalted margin* |
| Volume | volume mediano su tutta la season selezionata |

Il margine è nullo (`—`) quando la season in corso non ha ancora raggiunto la fine della
finestra: meglio un trattino che una percentuale calcolata su mezza finestra mentre
l'intestazione ne annuncia una intera.

Con **Exalted** selezionato compare in corsivo un avviso accanto al selettore: l'Exalted si
svaluta durante la lega, quindi un margine quotato in Exalted contiene quell'inflazione e
sovrastima il guadagno reale.

I valori sono centrati in colonna. Ogni intestazione ordina, il default è Latest price decrescente, e una sola riga resta aperta per
volta. Il selettore **Price in: Divine / Exalted** ricalcola la tabella: la conversione in
Divine usa la serie del Divine di *quella* season in *quel* giorno, non il tasso di oggi —
è la parte che quasi tutti sbagliano.

### Il grafico, e la finestra temporale

Aperta una riga, sotto compare il prezzo giorno per giorno con **una linea per season**,
sovrapposte e allineate al giorno 1 di ciascuna. Scala logaritmica, perché i prezzi coprono
ordini di grandezza. La legenda è cliccabile (ogni season si nasconde e si rimette) e
passando il mouse compare un crosshair con prezzo e volume di ogni season in quel giorno.

**Range** ha due modalità, e agisce sia sulla colonna del margine sia sul grafico:

- **Full history** (default) — tutto lo storico della season, a prezzo assoluto.
- **Time window** — mostra i controlli *Entry day* e *Held for*, e limita il grafico ai
  giorni fra i due, con **ogni season indicizzata a 100 al giorno di entrata**. Serve a
  confrontare la stessa finestra fra season con livelli di prezzo diversissimi: la fine di
  ogni linea è il rendimento di quella season in quella finestra, e il tooltip mostra sia la
  variazione dall'entrata sia il prezzo assoluto.

I nomi delle season portano la patch fra parentesi — *Runes of Aldur (0.5)* — in legenda,
nel tooltip e nel banner. La mappatura sta in `Exporter.patchOf` e finisce in
`index.json`, così la pagina non ha una seconda lista da tenere allineata.

Attenzione a una conseguenza: **Early Access (0.1) non ha il Divine**, quindi compare solo
in Exalted. In Divine la legenda lo dichiara invece di far sparire la linea in silenzio.

### I dati del sito

| Cartella | Contenuto | Peso |
|---|---|---|
| `data/index.json` | season con la loro patch, griglia dei giorni, metadati | 2 KB |
| `data/series/*.json` | 17 categorie + `_quotes` + `_index`, serie giornaliere complete | 2,2 MB |

**Totale 2,2 MB, che in brotli diventano 829 KB** — è quello che il browser scarica al primo
avvio, poi resta in cache. Il file più grande è `runes.json` con 628 KB grezzi.

Il backtest per finestre (`export --windows`) scrive altri 30-42 file per 2,3 MB, ma **il
sito non li legge**: le statistiche della tabella si calcolano dalle serie. Sono un'uscita
per la riga di comando, non parte del deploy.

## Quando esce una lega nuova

I **dati si aggiornano da soli**. Il backfill legge la lista da `GET /poe2/Leagues`, quindi
la season nuova compare da sé; per ogni item controlla `fetch_log`, che su una lega mai
vista è vuoto, e la scarica tutta. A cascata: la lega precedente si congela e resta
consultabile, il selettore si apre sulla nuova, il filtro "solo item della patch corrente"
si riaggancia, e gli item rimossi da GGG spariscono dal sito.

Anche **nome corto e colore si assegnano da soli**:

- `Exporter.shorts()` tiene una mappa esplicita per le season note — il loro storico è già
  esportato con quelle chiavi e non si tocca — e per le nuove genera uno slug dalla prima
  parola significativa del nome, con suffisso numerico in caso di collisione. Senza
  quest'ultimo, `Return of the Ancients` e `Return to Wraeclast` finirebbero sotto la
  stessa chiave e i loro dati si fonderebbero in silenzio.
- Il colore viene dalla posizione nella lista delle season, ordinata per data di inizio:
  le esistenti tengono il loro, la nuova prende il successivo della palette.

Anche **il numero di patch si deduce**. Nessuna API lo espone — verificati `/Leagues`,
`/Realms` e `LandingSplashInfo` di poe2scout, e la League ufficiale di GGG: nessuno porta
un campo di versione. Ma finora PoE 2 ha spedito una minor per season nell'ordine di
uscita, e la corrispondenza regge su tutte e cinque: prima → 0.1, quinta → 0.5. Quindi
`Exporter.patches()` la calcola dalla posizione, e una lega nuova prende 0.6 da sola.

La sequenza conosce anche il salto: le prime cinque sono le 0.1-0.5, **la sesta è la 1.0**
— l'uscita dall'Early Access, che rompe la numerazione 0.x — e da lì in poi 1.1, 1.2, come
fa PoE 1. È codificato adesso, prima che serva, così al lancio non degrada niente.

`KNOWN_PATCH` resta come override e vince sulla deduzione: serve solo se una season non
seguisse la sequenza. Una riga, e torna tutto giusto.

**Quindi, al prossimo lancio, non devi fare niente.**

## Verifica prima di pubblicare

```bash
mvn -q clean package && java -jar target/poe2economics.jar export && node tools/check.js
```

`tools/check.js` fa 29 controlli sul risultato: coerenza fra `index.json` e le serie,
patch in ordine, item allineati al filtro della patch corrente, nessun file di finestra nel
deploy, sintassi e assenza di codice morto nella pagina, id referenziati esistenti, meta
Open Graph, permessi e comandi del workflow, `.gitignore`. Esce con codice 1 se qualcosa
non torna, quindi si può mettere in CI.

## Sicurezza e stabilità

Il sito è statico e senza backend: niente login, niente dati di chi lo visita, niente
scrittura. La superficie è quindi solo quella dei dati che entrano nel DOM.

- **Nomi degli item**: arrivano da poe2scout e finiscono in `innerHTML`. Passano tutti da
  `esc()` (nome, categoria, nome season, patch, file di categoria). Titolo e messaggi usano
  `textContent`. La sparkline è generata da soli numeri.
- **SQL**: tutte le query usano `PreparedStatement`. L'unica concatenazione è la lista di
  segnaposto `IN (?,?,?)`, costruita dalla *dimensione* della collezione, non dal contenuto.
- **Nomi dei file esportati**: `Exporter.slug()` riduce il nome categoria a `[a-z0-9-]`,
  quindi non si può risalire fuori dalla cartella di output.
- **URL verso l'API**: costruiti con `URLEncoder`.
- **Il contatto non finisce nei log**: `backfill` stampa `u***@gmail.com`. GitHub maschera
  già i secret, ma non stamparlo affatto costa meno che fidarsi del mascheramento.
- **Workflow**: `permissions: contents: write` e basta; il secret passa da `env:`, mai
  interpolato dentro uno script shell.

Sul fronte stabilità, quattro cose corrette dopo la revisione:

| Problema | Perché contava | Fix |
|---|---|---|
| `fetch` non rigetta sui 404 | un file mancante dava un errore di parsing incomprensibile | `getJson()` controlla `res.ok` |
| `loadCategory` senza `catch` | un fetch fallito lasciava la tabella vecchia e nessun messaggio | errore mostrato, con il file e lo status |
| `renderTable` durante il caricamento | i controlli sono cliccabili mentre arrivano i 2,2 MB: un click buttava giù la pagina | guardia su `S.catData` |
| `DAY_COUNT = 300` | l'API restituisce gli **ultimi** N giorni: una season più lunga avrebbe perso l'inizio, in silenzio | alzato a 500 e avviso se una serie ci arriva vicino |

## Limiti da tenere in vista

- **n = 3 o 4 leghe.** Non è un campione. Fra una lega e l'altra GGG ha cambiato patch
  (0.2 → 0.3 → 0.5), drop rate e meccaniche. Mostrare sempre le osservazioni per lega
  separate, mai una media sola.
- I volumi delle prime giornate di lega sono minuscoli (quantità a una cifra): il filtro
  `--min-volume` non è un dettaglio, è quello che separa un segnale dal rumore.
  `--fill pessimistic`: quello che sopravvive è il segnale, il resto è spread.
- Alcune valute non esistono in tutte le leghe (`--min-leagues` serve a questo).
- Gli unique (`--uniques`) hanno volumi molto più bassi delle valute: il filtro
  `--min-volume` va abbassato di conseguenza, con tutti i rischi che comporta.
- poe2scout è il progetto di una persona sola. Lo storico scaricato va conservato.

---

## Prossimi passi

1. ~~`backfill` completo su tutte e 17 le categorie di valuta.~~ fatto
2. ~~Riempimento realistico con `Low`/`High`.~~ fatto (`--fill pessimistic`)
3. ~~Esporto in JSON statico + sito + workflow giornaliero.~~ fatto
4. `backfill --uniques`: il codice c'è, manca la corsa (è lunga, ~4.000 richieste).
5. Allineare anche per **percentuale di lega trascorsa**, non solo per giorno assoluto:
   le leghe vanno da 100 a 165 giorni, e il giorno 90 di una lega da 100 non è il giorno
   90 di una da 165. Risolverebbe anche il buco di copertura dopo il giorno 85.
6. Grafico delle curve sovrapposte per singolo item (una linea per lega, allineate al
   giorno di inizio).

---

## Licenza e attribuzione

**Tutti i diritti riservati.** Questo repository è pubblico per trasparenza, non è
open source: non è concessa licenza per usare, modificare, ridistribuire o rimettere
online il codice o i dati esportati. Il fork su GitHub resta possibile perché lo
prevedono i termini della piattaforma, ma non concede nessuno di quei diritti.

Progetto non ufficiale, non affiliato né approvato da Grinding Gear Games.
Dati economici forniti da [poe2scout](https://poe2scout.com), rilasciato sotto licenza
MIT: quella licenza copre il loro codice, non i dati serviti dalla loro API.
