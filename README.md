# Tastierina

Una mini groovebox per imparare la musica dance elettronica, un livello alla volta.

| Livello | Cosa aggiunge |
|---|---|
| 1. Tastierina | Tastiera con canzoni guidate a pallini |
| 2. Ritmo | Batteria a 16 passi e lezioni di ritmo (house, techno, drum and bass) |
| 3. Groove | Righe di basso e melodia, registrazione dalla tastiera, filtro del basso |
| 4. Groovebox | Giri A/B/C, giri da 1, 2 o 4 battute, scaletta, tutti i suoni di batteria |

I suoni sono generati dal telefono, quindi l'app funziona anche senza internet.

## Installare l'app su Android

1. Apri la sezione **Releases** di questo repository dal telefono.
2. Scarica il file `Tastierina-….apk` dell'ultima versione.
3. Aprilo e consenti l'installazione da quella fonte quando Android lo chiede.

Le versioni successive si installano sopra la precedente, senza perdere livello e giri salvati.

## Com'è fatto

- `web/index.html`: tutta l'app, in una sola pagina (HTML, CSS e JavaScript, con Web Audio). Si apre anche in un browser.
- `app/`: una piccola app Android che mostra la pagina a schermo intero.
- `.github/workflows/apk.yml`: a ogni modifica su `main` GitHub compila l'APK e lo pubblica in Releases.

Per compilarla sul computer: `./gradlew assembleRelease` (serve JDK 17 e l'Android SDK).

La chiave di firma `app/tastierina.keystore` è nel repository di proposito, così ogni build può aggiornare la precedente. Va bene per uso personale; per pubblicare sul Play Store serve una chiave privata, da tenere fuori dal repository.
