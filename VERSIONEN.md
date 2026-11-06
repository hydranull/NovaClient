# Nova Client für mehrere Minecraft-Versionen (26.3 + 1.21.11)

Ein Quellcode in `src/`, daraus baut [Stonecutter](https://stonecutter.kikugie.dev) je Minecraft-Version eine eigene Mod.

| Datei | Inhalt |
|---|---|
| `gradle.properties` | `mod_version` und `release_stage` (Anzeige „Beta 0.100“) – gelten für alle Versionen |
| `versions/<mc>/gradle.properties` | Minecraft-, Loader-, Fabric-API- und Java-Version |
| `settings.gradle.kts` | welche Minecraft-Versionen es gibt |
| `stonecutter.gradle.kts` | aktive Version + automatische Umbenennungen zwischen den Versionen |
| `Compat.java`, `RenderCompat.java` | Helfer für Dinge, die in den Versionen anders gehen |

In `src/` steht immer der Code der **aktiven** Version (normal: 26.3).

## Alltag: Feature oder Fix einbauen

1. Ganz normal in `src/` programmieren und mit **Minecraft Client (:26.3)** testen.
2. `gradlew build` baut beide Versionen. Meldet 1.21.11 einen Fehler, den Unterschied einbauen (siehe unten).
3. In 1.21.11 testen: Gradle-Fenster → `NovaClient` → Tasks → `stonecutter` → **Set active project to 1.21.11**,
   Gradle neu laden, **Minecraft Client (:1.21.11)** starten.
4. Danach zurück: **Set active project to 26.3**.

Vor dem Umschalten alles speichern – das Umschalten schreibt `src/` um.

## Wenn sich Minecraft unterscheidet

**Nur ein Name ist anders** → eine Zeile in `stonecutter.gradle.kts`, z.B. `rename("alterName", "neuerName")`.
Dann tauscht Stonecutter den Namen überall automatisch.

**Ein paar Zeilen sind anders** → Bedingung im Code (Stonecutter kommentiert beim Umschalten selbst ein/aus):

```java
//? if >=26.3 {
entity.swing(hand, entity.getItemInHand(hand).getAttackAnimation(), false); // 26.3
//?} else {
/*entity.swing(hand);                                                        // 1.21.11
*///?}
```

Im auskommentierten Teil keine `/** ... */`-Kommentare benutzen, nur `//`.

**Häufige Sachen** → die fertigen Helfer nehmen, statt Minecraft direkt aufzurufen:
`Compat.chat(player, text)`, `Compat.swing(entity, hand)`, `Compat.rotate(pose, quaternion)`, `Compat.openUri(url)`,
`RenderCompat.pipeline(...)`, `RenderCompat.setPipeline(pass, pipeline)`, `RenderCompat.target(...)` usw.

**Shader** stehen im Format von 26.3 (`#include`, `layout(location = N)`, `gl_VertexIndex`). Für 1.21.11 wandelt
`build.gradle` sie beim Bauen automatisch zurück.

## Bauen

`gradlew build` (IntelliJ: Gradle → Tasks → build → build). Gebraucht wird JDK 25 oder neuer. Danach liegen in
`build/libs`:

- `NovaClient-26.3.jar`
- `NovaClient-1.21.11.jar`

Selbst gebaute Jars sind keine offiziellen Builds und aktualisieren sich nie von selbst. Offizielle Releases werden mit
`-Pnova_official=true` gebaut und vom Nova-Team veröffentlicht.

## Werkzeuge

- `tools/toolskins` – baut die Griffe der Laserschwerter (Hyper Tools): `python build.py` (braucht numpy und Pillow)
- `tools/nova-ai` – trainiert den Offline-Assistenten aus `knowledge.py`: `python train.py`

## Neue Minecraft-Version dazunehmen

1. `settings.gradle.kts`: Version eintragen, z.B. `versions("1.21.11", "26.3", "26.4")`.
2. Ordner `versions/26.4/` mit `gradle.properties` anlegen (Werte von https://fabricmc.net/develop).
3. Gradle neu laden, `gradlew build`, Fehler wie oben beheben (Bedingungen dann z.B. `//? if >=26.4 {`).
