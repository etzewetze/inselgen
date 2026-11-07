# InselGen

Ein Plugin für **Paper 26.3**, das mit einem Befehl eine neue, natürlich geformte Himmelsinsel erzeugt. Jede Insel bekommt einen Zufallsseed: ovale, gedrehte Umrisse, Einbuchtungen, Hügel oder Berge und eine unregelmäßige, nach unten zulaufende Felsunterseite. Auf geeigneten Flächen wachsen kleine Eichen, Gras und Blumen.

```text
/skyinsel 150
```

Die Insel entsteht **um deine aktuelle X-/Z-Position**. Deine Fußhöhe minus einen Block ist die ungefähre Basishöhe der Oberfläche; Berge können darüber hinausragen und die Unterseite reicht nach unten. Fliege zuerst in freien Luftraum, beispielsweise ungefähr auf Y=180 bis Y=210. Der tatsächlich benötigte Höhenbereich wird vor dem Bauen berechnet und geprüft.

## Installation

Voraussetzungen:

- Paper **26.3** und **Java 25**.
- FastAsyncWorldEdit **2.16.2**. Als Abhängigkeit installieren; InselGen enthält keine eigene FAWE-Kopie.
- VoxelSniper ist optional und kann danach zum Nachbearbeiten benutzt werden.

1. Die Datei `inselgen-0.1.0.jar` in den Ordner `plugins/` legen.
2. Den Server vollständig neu starten.
3. Als OP im Spiel `/skyinsel 100` ausprobieren.

Die JAR liegt nach einem erfolgreichen Build unter `target/inselgen-0.1.0.jar`. GitHub Actions baut und testet Pull Requests sowie Änderungen an `main`; die JAR wird beim erfolgreichen Lauf als Artefakt **InselGen** angeboten. Im jeweiligen Actions-Lauf das Artefakt herunterladen und die JAR aus der ZIP entnehmen.

## Befehle

| Befehl | Ergebnis |
| --- | --- |
| `/skyinsel 150` | Neue natürliche Insel, zufälliger Seed |
| `/skyinsel 200 bergig` | Insel mit stärkerem Gebirge und teils verschneiten Gipfeln |
| `/skyinsel 100 flach` | Weitgehend ebene, leicht unregelmäßige Oberfläche |
| `/skyinsel 150 731` | Natürliche Insel mit dem Seed `731` |
| `/skyinsel 200 731 bergig` | Bestimmter Seed und bergiger Stil |
| `/skyinsel status` | Fortschritt, Größe und Seed des aktuellen Auftrags |
| `/skyinsel cancel` | Eigenen Auftrag abbrechen; bereits gesetzte Blöcke bleiben bis zum Undo bestehen |
| `/skyinsel reload` | Konfiguration neu laden; erfordert `inselgen.admin` |
| `/skyinsel help` | Kurzhilfe |
| `//undo` | Die letzte FAWE-Änderung rückgängig machen |

`/inselgen` ist ein Alias für `/skyinsel`. Seed und Stil dürfen auch in umgekehrter Reihenfolge stehen. Ein Seed ist eine vorzeichenbehaftete 64-Bit-Ganzzahl. Ohne Angabe wird bei jedem Aufruf ein neuer gewählt und im Chat sowie im Serverlog angezeigt.

Die Größe **50–500** ist die maximale horizontale Ausdehnung des Inselkörpers in Blöcken, **kein Radius**. Durch die natürliche Form fällt die tatsächliche Breite etwas kleiner aus; Länge und Breite können unterschiedlich sein. Bäume werden innerhalb der Inselränder platziert. Die genaue Form ist bei identischem Seed, gleicher Größe, gleichem Stil und gleicher Pluginversion reproduzierbar. Vegetation kann separat abgeschaltet werden, ohne die Landschaft zu ändern.

## Bestehende Bauten und Rückgängig

Standardmäßig prüft InselGen **alle geplanten Blockpositionen einschließlich Vegetation**, bevor es den ersten Block setzt. Ist eine Position bereits belegt, wird der Auftrag ohne Bau abgebrochen und die Koordinate angezeigt. Auch Wasser und Pflanzen gelten als belegt. Weltgrenze, Bauhöhe und Blocklimit werden ebenfalls vor dem Bauen geprüft.

Die Prüfung reserviert den Bereich nicht gegen andere Spieler oder Plugins. Während eines Auftrags dort keine parallelen Änderungen durchführen. FAWE-Gebietsrechte und Limits gelten weiterhin; eine Ablehnung während des Setzens kann eine teilweise gebaute Insel hinterlassen.

Änderungen werden in der **FAWE-Historie des ausführenden Spielers** gespeichert, auch nach einem Abbruch während des Setzens. Erst nach Abschluss des Auftrags `//undo` benutzen. Dieser Befehl betrifft immer die letzte FAWE-Änderung des Spielers, deshalb zwischen Inselbau und Undo keine andere WorldEdit-Operation starten. FAWE muss Historie zulassen; insbesondere darf das History-Limit des Spielers nicht `0` sein. Wie lange Historie erhalten bleibt und ob sie einen Neustart überlebt, hängt von der FAWE-Konfiguration ab.

Es läuft höchstens ein Inselauftrag gleichzeitig. Berechnung, Prüfung und FAWE-Blockänderungen laufen auf einem eigenen Worker. FAWE kann für große Inseln trotzdem deutlich RAM, CPU und Datenträger beanspruchen; die Laufzeit hängt auch von Chunk-Laden, Beleuchtung und History-Einstellungen ab. Es gibt keine feste Laufzeit- oder Lag-Garantie.

Nach erfolgreichem Bau wirst du standardmäßig auf die baumfreie Inselmitte gesetzt, sofern du noch in derselben Welt bist. Das lässt sich abschalten.

## Einstellungen

Beim ersten Start entsteht `plugins/InselGen/config.yml`:

```yaml
minimum-size: 50
maximum-size: 500
require-empty-space: true
maximum-blocks: 12000000
vegetation:
  trees: true
  plants: true
teleport-after-generation: true
```

Der Größenbereich kann innerhalb von 50–500 eingeschränkt werden. `require-empty-space: false` erlaubt ausdrücklich das Ersetzen von Blöcken im Inselvolumen. InselGen entfernt dabei keine Blöcke außerhalb seiner geplanten Form. Änderungen mit `/skyinsel reload` laden; laufende Aufträge behalten ihre bisherigen Einstellungen.

| Berechtigung | Standard | Funktion |
| --- | --- | --- |
| `inselgen.generate` | OP | Inseln erzeugen, Status und Hilfe, eigenen Auftrag abbrechen |
| `inselgen.admin` | OP | Einstellungen neu laden, fremden Auftrag abbrechen; zusätzlich `inselgen.generate` nötig |
| `worldedit.history.undo` | FAWE-Konfiguration | `//undo` benutzen |

## Selbst bauen und testen

JDK 25 und Maven 3.9 oder neuer installieren:

```sh
mvn -B -ntp verify
```

Der Build verwendet feste Versionen von Paper-API (`26.3.build.244-stable`) und FAWE (`2.16.2`). Die Abhängigkeiten sind `provided` und werden nicht in die Plugin-JAR gepackt.

Die automatisierten Tests prüfen unter anderem deterministische Seeds, unterschiedliche Umrisse, verbundene Inselkörper, zulaufende Unterseiten, Geländeunterschiede zwischen den Stilen, Größen bis 500, exakte Blockzählung, Vegetationsgrenzen, freie Spawnposition, Abbruch und ungültige Befehle. Sie ersetzen keine Prüfung mit dem eigenen Pluginbestand und den eigenen FAWE-Einstellungen.
