# Tastierina

Una groovebox per imparare la musica dance elettronica, un livello alla volta.
App Android nativa: motore audio in Rust su AAudio (bassa latenza),
interfaccia in Kotlin e Jetpack Compose.

| Livello | Cosa aggiunge | Nativo |
|---|---|---|
| 0. Pad | Otto pad e il gioco "Tieni il tempo" | sì |
| 0.5 Ripeti | L'app suona una frase, conta 4-3-2-1, tu la ripeti a tempo quante volte vuoi | sì |
| 1. Tastierina | Tastiera a 18 tasti, 9 strumenti, 21 canzoni guidate, modi Impara e A tempo con stelle | sì |
| 2. Ritmo | Batteria a 16 passi con 16 suoni (la griglia scorre) e 14 lezioni di genere | sì |
| 3. Groove | Righe di basso e melodia, registrazione dai tasti, filtro del basso, lezioni con il basso | sì |
| 4. Groovebox | Giri A/B/C lunghi 1, 2 o 4 battute, cambio a fine giro, scaletta, durata del basso | sì |

Tutti i livelli sono nativi. La prima versione, una pagina web, resta in
`web/index.html` come riferimento.

## Installare l'app

Dal telefono apri **Releases**, scarica l'ultimo `Tastierina-….apk` e aprilo.
Le versioni nuove si installano sopra la precedente.

## Tieni il tempo

Il tocco viene misurato sull'orologio dell'audio, non su quello dello
schermo: il timestamp del tocco viene riportato sul fotogramma audio che in
quel momento usciva dall'altoparlante (`AAudioStream_getTimestamp`). Così il
ritardo dell'audio del telefono non conta contro chi suona.

## Sviluppo

Vedi [AGENTS.md](AGENTS.md): struttura, comandi, regole per il thread audio.

La chiave di firma `app/tastierina.keystore` è nel repository di proposito,
così ogni build aggiorna la precedente. Va bene per uso personale; per il
Play Store serve una chiave privata fuori dal repository.
