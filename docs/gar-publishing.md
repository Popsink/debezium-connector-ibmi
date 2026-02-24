# Publishing to GCP Artifact Registry

## TL;DR

To publish the connector to GAR, run from a devbox shell:

```sh
gcloud auth application-default login   # once per session
devbox run publish
```

Artifacts land in `maven-snapshots` (version is `3.4.2-SNAPSHOT`).

---

## Why it was hard — the central-publishing-maven-plugin trap

### Background

`debezium-build-parent` (a grandparent of this project) declares:

```xml
<plugin>
    <groupId>org.sonatype.central</groupId>
    <artifactId>central-publishing-maven-plugin</artifactId>
    <extensions>true</extensions>
    ...
</plugin>
```

The `<extensions>true</extensions>` flag is not just about running a plugin goal — it
registers the plugin as a **Maven lifecycle extension** via
`AbstractMavenLifecycleParticipant`. This participant hooks into `afterProjectsRead()`,
which runs after all POMs are read but before any goals execute.

### What the extension actually does

Inside `afterProjectsRead()`, the `central-publishing-maven-plugin` extension:

1. Scans every project in the reactor for executions of
   `maven-deploy-plugin:deploy` (by goal name).
2. **Forcibly sets the phase of every such execution to `none`**, removing them
   from the build plan entirely.
3. Substitutes its own `central-publishing:publish` goal, bound to the `deploy`
   phase, which stages and uploads to Sonatype Central.

This happens unconditionally — it is not a property that can be overridden, and it
affects **all** `maven-deploy-plugin:deploy` executions regardless of:

| What you try | Why it doesn't work |
|---|---|
| `<phase>none</phase>` + `<skip>true</skip>` on the central-publishing execution | Disables the goal execution, but the **extension mechanism** is independent — it still runs `afterProjectsRead()` |
| Adding a custom `deploy-to-gar` execution at the `deploy` phase | The extension removes it along with all other `deploy` executions |
| Binding the custom execution to `verify` instead of `deploy` | The extension scans by **goal name**, not by phase — still removed |
| `-Dmaven.deploy.skip=false` on the command line | The extension removes the execution from the plan entirely; the skip flag is irrelevant when the execution doesn't exist |
| `<maven.deploy.skip>false</maven.deploy.skip>` in POM properties | Same reason |
| `<publish.skip>true</publish.skip>` | Only skips the central-publishing goal itself; the extension mechanism still fires and still clears the deploy plan |

### The fix

Override the plugin in the **root `pom.xml`** (not in a profile) with
`<extensions>false</extensions>`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.sonatype.central</groupId>
            <artifactId>central-publishing-maven-plugin</artifactId>
            <extensions>false</extensions>
            <configuration>
                <skipPublishing>true</skipPublishing>
            </configuration>
        </plugin>
    </plugins>
</build>
```

When Maven assembles the effective POM and finds `extensions=false` on the merged
plugin declaration, it does **not** load the plugin as a lifecycle extension. The
`afterProjectsRead()` hook is never registered, and the deploy plan is left intact.

The `gcloud` profile's `deploy-to-gar` execution then runs normally:

```xml
<profile>
    <id>gcloud</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-deploy-plugin</artifactId>
                <executions>
                    <execution>
                        <id>deploy-to-gar</id>
                        <phase>deploy</phase>
                        <goals><goal>deploy</goal></goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</profile>
```

### Why this is safe

This project is a popsink fork of `debezium-connector-ibmi`. It **never** publishes to
Sonatype Central — artifacts go exclusively to the internal GCP Artifact Registry.
Disabling the central-publishing extension unconditionally is therefore correct and
intentional.

---

## Repository layout

| Repository | URL | Used for |
|---|---|---|
| `maven-snapshots` | `artifactregistry://europe-west1-maven.pkg.dev/popsink-common-438615/maven-snapshots` | `*-SNAPSHOT` builds |
| `maven-releases` | `artifactregistry://europe-west1-maven.pkg.dev/popsink-common-438615/maven-releases` | release builds (future) |

Authentication is handled automatically by the `artifactregistry-maven-wagon`
extension (declared in `.mvn/extensions.xml`) via Application Default Credentials.

---

## Prerequisites for a local publish

1. **Devbox** — provides Java 21, Maven 3.9.8, and the Google Cloud SDK without
   polluting the system.
2. **Debezium core installed locally** — the parent POM (`debezium-parent:3.4.2-SNAPSHOT`)
   must be in `~/.m2`. Build it once:
   ```sh
   git clone --depth=1 --branch 3.4 https://github.com/debezium/debezium.git /tmp/debezium-core
   devbox run "mvn clean install \
     -f /tmp/debezium-core/pom.xml \
     -pl debezium-bom,debezium-core,debezium-embedded,debezium-storage,debezium-assembly-descriptors \
     -am -DskipTests -DskipITs"
   ```
3. **GCP authentication**:
   ```sh
   gcloud auth application-default login
   ```
