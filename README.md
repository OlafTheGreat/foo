# foo — Multi-threaded directory copier

Kurze Anleitung, Usage und Hinweise zur Implementierung und zum Logging.

## Überblick
`foo` enthält eine kleine Java-Utility-Anwendung (`de.ben.App`) und eine robuste Multi-Threaded-Implementierung zum rekursiven Kopieren von Verzeichnissen (`de.ben.FileCopy`). Das Projekt verwendet Java 17, JUnit 5 für Tests und SLF4J + Logback für Logging.

## Build
Voraussetzung: Java 17 und Gradle (oder das mitgelieferte `gradlew`).

Im Projekt-Root ausführen:

```bash
./gradlew clean build
```

Die Tests werden während des Builds ausgeführt.

## Run / Usage
Beispielaufruf:

```bash
# jar bauen
./gradlew clean build

# danach (jar befindet sich typischerweise unter build/libs):
java -jar build/libs/foo-1.0-SNAPSHOT.jar /pfad/zur/quelldir /pfad/zum/zieldir [threads]
```

Parameter:
- `sourceDir`: Quellverzeichnis, das rekursiv kopiert werden soll (muss existieren)
- `targetDir`: Zielverzeichnis (wird erstellt, falls es nicht existiert)
- `threads` (optional): Anzahl Worker-Threads (Default = Runtime.getRuntime().availableProcessors())

Exit-Codes (konventionell im Projekt):
- `0` — Erfolg
- `1` — Laufzeit-Fehler (z. B. I/O-Fehler, Unterbrechung)
- `2` — Usage-Fehler (zu wenige Argumente)
- `3` — Ungültiger Parameter (z. B. Threads nicht parsebar)
- `4` — Quelle existiert nicht

## Logging
- Das Projekt verwendet SLF4J + Logback. Die Standard-Logback-Konfiguration wurde in `src/main/resources/logback.xml` angelegt.
- Logs gehen auf die Konsole und in eine Rolling-File-Konfiguration unter `logs/app.log`.
- Für Produktivbetrieb kannst du `LOG_DIR` oder das Logback-Setup anpassen.

## Verhalten & Sicherheits-Designentscheidungen
- Pfad-Normalisierung (absolute + normalize) und Prüfung mittels `startsWith` verhindern, dass beim Kopieren außerhalb des Zielverzeichnisses geschrieben wird (Path-Traversal-Schutz).
- Temporäre Dateien werden im System-Temp-Verzeichnis erstellt (robuste Strategie gegenüber speziellen Dateisystemen).
- Copy-Strategie: atomarer Move → non-atomic Move → Copy mit Attribut-Preservation-Fallbacks.
- Executor wird sauber heruntergefahren; bei Fehlern werden noch laufende Tasks abgebrochen.

## Tests
Die Unit-Tests sind unter `src/test/java` abgelegt; sie verwenden JUnit 5.

```bash
./gradlew test
```

## Weiteres / Empfehlungen
- Falls du symlink-spezifisches Verhalten benötigst (z. B. Links als Links kopieren), sag Bescheid — aktuell folgt die Implementierung dem Dateiinhalt.
- Für Produktionsbetrieb empfiehlt sich eine angepasste `logback.xml` (z. B. Pfad, Rotation, Level).

---
Wenn du möchtest, füge ich noch eine GitHub Actions CI-Datei hinzu, oder erweitere Tests (z. B. symlink test, large-file stress test). Sag kurz, was du bevorzugst.
