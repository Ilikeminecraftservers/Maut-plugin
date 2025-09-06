
# MautPlugin mit WorldGuard (Mehrere Regionen)

## Voraussetzungen
- Paper 1.20+
- Vault
- Economy-Plugin (z. B. EssentialsX Economy)
- WorldGuard + WorldEdit

## Installation
1. Mit Maven bauen:
   ```bash
   mvn package

2. Datei target/maut-1.0-SNAPSHOT.jar in den plugins/-Ordner legen.


3. Server starten.



Verwendung

Setze ein Schild mit folgendem Aufbau:

[MAUT]
regionName
preis
dauer

Zeile 1: exakt [MAUT]

Zeile 2: Name der WorldGuard-Region

Zeile 3: Preis (z. B. 10.0)

Zeile 4: Dauer in Sekunden (z. B. 30)


Beispiel

[MAUT]
bruecke
15.0
20

Spieler klickt → 15.0 Geld wird abgezogen → 20 Sekunden lang darf er in Region bruecke.

Hinweise

Mehrere Schilder für dieselbe Region sind möglich.

Mehrere Regionen mit eigenen Schildern sind möglich.

Fällt Preis oder Dauer weg, greifen Standardwerte aus config.yml.
