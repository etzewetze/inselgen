# InselGen

Natürliche, zufällige Himmelsinseln für **Spigot, Paper und Folia ab Minecraft 1.21**. Ein Befehl erzeugt ovale, gedrehte Umrisse mit Einbuchtungen, Hügeln oder Bergen und einer nach unten zulaufenden Felsunterseite. Dazu kommen wählbare Baumarten, einstellbare Vegetationsdichten, Seen und Erzadern. Eine Bossbar zeigt den Fortschritt:

```text
/skyinsel 300 biome:beach
/skyinsel 300 32 bergig biome:beach
/skyinsel 300 biome:beach tree:palm trees:50 plants:10 lakes:2 ores:true
```

## Installation und Kompatibilität

1. [inselgen-0.3.0.jar](https://github.com/etzewetze/inselgen/releases/download/v0.3.0/inselgen-0.3.0.jar) herunterladen und in `plugins/` legen. Beim Aktualisieren die alte InselGen-JAR ersetzen.
2. Den Server vollständig neu starten.
3. Als OP im Spiel in freien Luftraum fliegen und `/skyinsel 100` ausprobieren.

Die JAR wird gegen die **Spigot-API 1.21** für **Java 21** gebaut. Neuere Server behalten ihre eigene Java-Anforderung, beispielsweise Java 25 für Minecraft 26.3. FAWE ist optional und wird nicht mitgeliefert.

| Plattform | Platzierung im Modus `auto` | Rückgängig |
| --- | --- | --- |
| Spigot / Paper mit aktiviertem, passendem FAWE | FAWE | `//undo` oder `/skyinsel undo` |
| Spigot / Paper ohne FAWE | Native Bukkit-Platzierung | `/skyinsel undo` |
| Folia | Native Platzierung über den jeweiligen Regionscheduler | `/skyinsel undo` |

Auf Folia wird normales FAWE nicht benötigt oder verwendet. Auf anderen Servern muss eine installierte FAWE-Version zur jeweiligen Minecraft-Version passen. Die FAWE-Anbindung kompiliert gegen API 2.11.2; der aktuelle Paper-26.3-Test verwendet FAWE 2.16.2.

Die automatisierten Builds laufen mit Java 21. Nach `mvn verify` liegt die JAR unter `target/inselgen-0.3.0.jar`. GitHub Actions bietet sie im erfolgreichen Lauf als Artefakt **InselGen** an.

## Befehle

| Befehl | Ergebnis |
| --- | --- |
| `/skyinsel 150` | Natürliche Insel mit neuem Zufallsseed |
| `/skyinsel 200 bergig` | Stärkeres Gebirge, normalerweise mit teils verschneiten Gipfeln |
| `/skyinsel 100 flach` | Weitgehend ebene Oberfläche |
| `/skyinsel 150 731` | Insel mit festem Seed `731` |
| `/skyinsel 300 biome:beach` | Neue zufällige Insel mit Beach-Biom und sandigem Boden |
| `/skyinsel 300 32 bergig biome:beach` | Größe, Seed, Stil und Biom kombinieren |
| `/skyinsel 150 biome:minecraft:birch_forest` | Vollständigen Biomschlüssel verwenden |
| `/skyinsel 200 trees:80 plants:40` | Dichtere Bäume und Bodenpflanzen |
| `/skyinsel 200 biome:beach tree:palm lakes:2` | Strandinsel mit Palmen und bis zu zwei Seen |
| `/skyinsel 150 tree:cherry trees:50` | Kirschbäume ausdrücklich wählen |
| `/skyinsel 150 trees:0 plants:0 lakes:0 ores:false` | Vegetation, Seen und Erze abschalten |
| `/skyinsel trees` | Eingebaute und eigene Baumarten anzeigen |
| `/skyinsel biomes [Suche]` | Verfügbare Biome des laufenden Servers anzeigen |
| `/skyinsel status` | Aktuellen Fortschritt anzeigen |
| `/skyinsel cancel` | Eigenen Auftrag abbrechen |
| `/skyinsel undo` | Letzten Auftrag rückgängig machen; bei FAWE wird dessen Undo aufgerufen |
| `/skyinsel reload` | Konfiguration neu laden |
| `/skyinsel help` | Kurzhilfe |

`/inselgen` ist ein Alias. Alle Optionen können in beliebiger Reihenfolge nach der Größe stehen und werden per Tab vorgeschlagen. Optionen im Befehl überschreiben die Konfiguration für diesen Auftrag. Ohne Biomoption bleiben die vorhandenen Biomdaten unverändert; `tree:auto` wählt dann Eichen. Nicht vorhandene Biome oder Baumarten werden vor dem Bauen abgewiesen; neue Biome stehen erst zur Verfügung, wenn die Serverversion bzw. ihr Datapack sie registriert.

**Ein fester Seed wiederholt die Form.** Ohne Zahl entsteht bei jedem Aufruf ein neuer Seed, der im Chat und Serverlog angezeigt wird. Die Form ist bei gleicher Pluginversion, Größe, gleichem Seed und Stil reproduzierbar. Die Größe 50–500 ist die maximale horizontale Ausdehnung, kein Radius; natürliche Inseln sind etwas kleiner und unterschiedlich lang und breit.

Die Insel entsteht um deine aktuelle X-/Z-Position. Deine Fußhöhe minus einen Block ist die ungefähre Basishöhe; Berge reichen darüber und die Unterseite darunter. Beispielsweise auf Y=200 ausprobieren. Der notwendige Höhenbereich wird vor dem Bauen geprüft. Nach erfolgreichem Bau wirst du standardmäßig auf die baumfreie Mitte gesetzt, sofern du noch in derselben Welt bist; Folia verwendet dafür asynchrones Teleportieren.

## Biome und Oberflächen

Die Option setzt die **echten dreidimensionalen Biomdaten**, nicht nur die Blockfarben. Zusätzlich passen ausgewählte Biomfamilien den Boden an:

| Biomfamilie | Boden |
| --- | --- |
| `beach`, `desert` | Sand mit Sandstein darunter |
| `*badlands*` | Roter Sand mit Terrakotta |
| `snowy_*`, `frozen_*`, `ice_spikes` | Schneeblöcke |
| `mushroom_fields` | Myzel |
| Andere Biome | Normales Inselgelände mit dem gewählten Biom |

Es handelt sich um InselGen-Gelände, keine vollständige Kopie der Vanilla-Weltgenerierung eines Bioms. Grasbüschel und Blumen entstehen auf Gras; Hügel und Inselumriss bleiben durch Seed und Stil bestimmt.

Biomdaten werden in 4×4×4-Zellen gespeichert. Verändert werden Zellen der Inselspalten von der Unterseite bis acht Blöcke über der Oberfläche und gegebenenfalls bis zu höheren Baumkronen, begrenzt durch die Bauhöhe. An Rändern reicht eine Änderung deshalb bis zur jeweiligen Zellgrenze. Undo schließt diese Biomänderungen ein.

## Bäume, Dichten, Seen und Erze

| Option | Bedeutung |
| --- | --- |
| `trees:0` bis `trees:100` | Anteil der geeigneten Baumstandorte in Prozent; Standard 30 |
| `plants:0` bis `plants:100` | Anteil geeigneter Grasblöcke für Gras, Farne und Blumen; Standard 8 |
| `tree:auto` | Baumart passend zum angegebenen Biom wählen |
| `tree:oak`, `birch`, `spruce`, `jungle`, `acacia`, `dark_oak`, `cherry`, `mangrove`, `palm` | Prozedural erzeugte Baumart ausdrücklich wählen |
| `tree:none` | Keine Bäume |
| `lakes:0` bis `lakes:8` | Gewünschte Anzahl kleiner Süßwasserseen; Standard 1 |
| `ores:true` / `ores:false` | Erzadern im Steinkern an-/abschalten; Standard an |

Auch Dezimalwerte wie `trees:12.5` und Prozentangaben wie `plants:20%` sind möglich. Baumdichte bezieht sich auf Kandidaten mit artabhängigem Abstand, nicht auf den Anteil aller Bodenblöcke. Steiles Gelände, Ufer, ungeeigneter Boden und der freie Bereich in der Mitte begrenzen die Anzahl. Bei einer positiven Baumdichte versucht der Generator auf kleinen Inseln mindestens einen passenden Standort zu finden. `trees:0` schaltet Bäume vollständig ab. Baumblätter sind dauerhaft und verschwinden nicht durch natürlichen Laubverfall.

`auto` wählt Palmen für `beach`, Birken für Birkenwälder, Fichten für Taiga/Schnee, Tropenbäume für Dschungel, Akazien für Savannen, dunkle Eichen für `dark_forest`, Kirschbäume für `cherry_grove` und Mangroven für `mangrove_swamp`. Wüsten, Badlands, Pilzinseln, Ozeane sowie Nether-/Endbiome erhalten automatisch keine Bäume. Andere Biome verwenden Eichen auf Grasflächen. Palmen bestehen aus gebogenen Tropenholzstämmen und Blattwedeln; ein Ressourcenpaket ist nicht nötig.

Seen werden in die Oberfläche geschnitten und nur mit geschlossenem Ufer und mindestens drei festen Bodenlagen erzeugt. Bei ungeeignetem Gelände können weniger als angefordert entstehen; der Chat nennt die tatsächliche Anzahl. Die Inselmitte bleibt trocken. Der Seeboden besteht aus Ton/Erde, auf Strandinseln aus Sand. Erzadern ersetzen ausschließlich tieferen Stein: Kohle, Eisen, Kupfer, Redstone, Lapislazuli, Gold, Diamant und selten Smaragd. Die relative Tiefe in der Insel zählt, nicht die absolute Welthöhe.

Die Bossbar zeigt Planung, Luftraumprüfung, Platzierung und Abschluss mit einem gewichteten Gesamtfortschritt. Sie ist keine Zeitschätzung und erscheint auch beim nativen Undo. Nach Fertigstellung zeigt sie kurz 100 %, bei Abbruch oder Fehler verschwindet sie. Ein von FAWE ausgeführtes Undo verwendet dessen eigene Anzeige.

## Eigene Baumvorlagen

Beim ersten Start wird `plugins/InselGen/trees/example_palm.yml` als bearbeitbares Beispiel angelegt. Kopiere die Datei beispielsweise nach `my_palm.yml`, passe die Blockliste an und lade sie mit `/skyinsel reload`. Danach verwenden:

```text
/skyinsel 200 biome:beach tree:my_palm trees:50
```

```yaml
soil: [grass_block, sand]
blocks:
  - {x: 0, y: 0, z: 0, block: 'minecraft:jungle_log[axis=y]'}
  - {x: 0, y: 1, z: 0, block: 'minecraft:jungle_log[axis=y]'}
  - {x: 0, y: 2, z: 0, block: 'minecraft:jungle_leaves'}
```

Koordinaten sind relativ zum Stammfuß direkt über dem Boden. Pro Baum sind bis zu 4096 eindeutige Blöcke innerhalb X/Z −15…15 und Y 0…63 möglich; `(0,0,0)` ist erforderlich. Vanilla-Blockzustände wie `axis=x` werden übernommen, Blätter automatisch dauerhaft gesetzt. Luft und Flüssigkeiten sind als Baumblöcke ausgeschlossen. `soil` ist eine Liste erlaubter Bodenmaterialien aus der Inselpalette; ohne Angabe gelten Gras, Erde, Sand und Schneeblöcke. Namen müssen aus kleinen Buchstaben, Ziffern, `_` oder `-` bestehen, mit einem Buchstaben beginnen und maximal 32 Zeichen lang sein. Eingebaute Namen sind reserviert. Es sind bis zu 32 Dateien mit je höchstens 256 KiB möglich.

Die Vorlagen enthalten Blockzustände, keine Inventare, Blockentitätsdaten oder `.schem`-Dateien. Sie funktionieren auch ohne FAWE auf Spigot, Paper und Folia. Ungültige Vorlagen werden beim Laden abgewiesen; ein fehlgeschlagenes Reload behält die bisher geladenen Einstellungen und Vorlagen.

## Schutz, Abbruch und Undo

Vor dem ersten Block werden Weltgrenze, Bauhöhe und Blocklimit geprüft. Standardmäßig müssen **alle geplanten Positionen einschließlich Vegetation frei** sein. Auch Wasser, Pflanzen und Gebäude zählen als belegt. Die Prüfung reserviert den Bereich nicht gegen parallele Änderungen durch Spieler oder andere Plugins.

Die native Platzierung erfordert immer `require-empty-space: true` und zusätzlich `inselgen.native` (standardmäßig OP). Sie besitzt keine WorldGuard-/PlotSquared-Anbindung und ist für vertrauenswürdige Builder gedacht. Die FAWE-Platzierung verwendet den ausführenden Spieler und die von FAWE vorgesehenen Limits und Gebietsrechte; nur dort ist Überschreiben mit `require-empty-space: false` möglich.

Es läuft ein Insel- oder nativer Undo-Auftrag gleichzeitig. `/skyinsel cancel` stoppt einen Auftrag; bereits gesetzte Teile bleiben zunächst bestehen und lassen sich nach Abschluss rückgängig machen.

- **FAWE:** Änderungen einschließlich Biomdaten landen in der FAWE-Historie des Spielers. `//undo` betrifft dessen letzte FAWE-Änderung, deshalb dazwischen keine andere WorldEdit-Operation starten. FAWE muss Historie zulassen; ein History-Limit von `0` verhindert Undo. Aufbewahrung und Neustartverhalten bestimmt FAWE.
- **Native:** Die letzte gebaute Insel pro Spieler wird komprimiert unter `plugins/InselGen/native-history/` protokolliert. `/skyinsel undo` stellt die vorherigen Blöcke und Biomdaten wieder her. Positionen, die inzwischen anders verändert wurden, werden übersprungen. Das Protokoll ist auch nach einem regulären Neustart lesbar. Bei Abbruch eines Undo kann derselbe Befehl fortgesetzt werden. Nach erfolgreichem Undo wird das Protokoll entfernt; beim nächsten Bau wird das vorherige ersetzt. Es gibt kein natives Redo.

Berechnung und native Dateizugriffe laufen auf einem Worker. Native Weltänderungen werden in begrenzten Portionen auf dem Serverthread bzw. auf Folias zuständigem Regionsthread ausgeführt; Spieleraktionen laufen auf ihrem Entity-Scheduler. FAWE arbeitet über seine eigene asynchrone API. Große Inseln benötigen je nach Backend, Chunk-Laden, Beleuchtung und Hardware deutlich Zeit und Arbeitsspeicher. Die native Platzierung kann bei Größe 500 mehrere Minuten brauchen; es gibt keine feste Laufzeit- oder Lag-Garantie.

## Konfiguration

`plugins/InselGen/config.yml`:

```yaml
minimum-size: 50
maximum-size: 500
placement-backend: auto
require-empty-space: true
maximum-blocks: 12000000
vegetation:
  trees: true
  plants: true
  tree-density: 30
  plant-density: 8
  tree-type: auto
lakes:
  count: 1
ores:
  enabled: true
  density: 35
teleport-after-generation: true
```

`placement-backend` akzeptiert `auto`, `native` und `fawe`; eine Änderung benötigt einen Neustart. `fawe` ist auf Folia nicht verfügbar. Andere Einstellungen mit `/skyinsel reload` laden. Laufende Aufträge behalten ihre bisherigen Einstellungen. Ein vorhandenes Config-File ohne `placement-backend` verwendet automatisch `auto`.

Auch vorhandene Konfigurationen ohne die neuen Feature-Schlüssel verwenden deren oben gezeigte Standardwerte. `ores.density` ist der prozentuale Anteil der Ader-Kandidaten, kein Anteil aller Steinblöcke. Die alten Schalter `vegetation.trees` und `vegetation.plants` bleiben gültig; ausdrückliche Dichten im Befehl haben Vorrang.

| Berechtigung | Standard | Funktion |
| --- | --- | --- |
| `inselgen.generate` | OP | Inseln, Hilfe, Biom-/Baumliste, Status, eigenes Cancel und Undo |
| `inselgen.native` | OP | Native Platzierung und natives Undo |
| `inselgen.admin` | OP | Reload und fremde Aufträge abbrechen; zusätzlich `inselgen.generate` erforderlich |
| `worldedit.history.undo` | FAWE-Konfiguration | FAWE-Undo benutzen |

## Bauen und prüfen

JDK 21 oder neuer und Maven 3.9 oder neuer:

```sh
mvn -B -ntp verify
```

Tests prüfen Seed-Reproduzierbarkeit, verbundene Formen, zulaufende Unterseiten, Größen bis 500, Dichten und alle Baumarten, eigene Vorlagen, geschlossene Seen, Erzadern, Feature- und Biomoptionen, Fortschrittsphasen, eindeutige Chunk-Zuordnung auch bei negativen Koordinaten, begrenzte und eindeutige Biomzellen sowie komprimierte Undo-Protokolle und nachträglich veränderte Blockzustände.

Die Kompatibilitätsprüfung verwendet echte Paper-1.21.1-/26.3- und Folia-1.21.4-/26.3-Testserver. Der Build kompiliert ausschließlich gegen die Spigot-1.21-API; Paper-spezifische Scheduler werden nur auf Folia per Reflexion angebunden. Ein direkter Spigot-Serverlauf ist gesondert zu prüfen. Weitere installierte Plugins und deren konkrete Gebietsschutz-Konfiguration sind nicht Teil dieser Tests.
