# Release Versioning

## TL;DR

To cut a release (e.g. `3.4.2-popsink-2`):

1. Update the version in all four POMs:
   - `pom.xml` — root `<version>`
   - `debezium-connector-ibmi/pom.xml` — parent `<version>`
   - `journal-parsing/pom.xml` — parent `<version>`
   - `jt400-override-ccsid/pom.xml` — parent `<version>`
2. Publish from a devbox shell:
   ```sh
   gcloud auth application-default login   # once per session
   devbox run publish
   ```
3. Artifacts land in `maven-releases`.

---

## Versioning strategy

This project uses a popsink-specific version that is **decoupled** from the upstream
debezium version:

| Property | Example value | What it is |
|---|---|---|
| `project.version` | `3.4.2-popsink-1` | This connector's own version |
| `version.debezium` | `3.4.2-SNAPSHOT` | The upstream debezium core version |

`version.debezium` is hardcoded in the root `pom.xml`. It always points to the debezium
core branch this connector is based on. It must **never** be set to `${project.version}`.

---

## Why releasing is hard — the `${project.version}` trap in debezium-parent

`debezium-parent` (the grandparent of this project) uses `${project.version}` in several
places to reference its own sibling artifacts:

```xml
<!-- debezium-parent.pom — dependencyManagement -->
<dependency>
    <groupId>io.debezium</groupId>
    <artifactId>debezium-bom</artifactId>
    <version>${project.version}</version>   <!-- resolves to OUR project version! -->
    ...
</dependency>

<!-- debezium-parent.pom — pluginManagement -->
<plugin>
    <groupId>net.revelc.code.formatter</groupId>
    <artifactId>formatter-maven-plugin</artifactId>
    <dependencies>
        <dependency>
            <groupId>io.debezium</groupId>
            <artifactId>debezium-ide-configs</artifactId>
            <version>${project.version}</version>   <!-- same trap -->
        </dependency>
    </dependencies>
</plugin>
```

In Maven, `${project.version}` always reflects the **currently built project's version**.
When the parent POM is read in the context of our project (version `3.4.2-popsink-1`),
those references expand to `3.4.2-popsink-1` — an artifact that does not exist in any
repository.

This affects four things:

| Artifact | Where declared | Symptom |
|---|---|---|
| `debezium-bom` | `debezium-parent` `dependencyManagement` | Build fails immediately at project scan |
| `debezium-ide-configs` | `debezium-parent` `pluginManagement` | `formatter-maven-plugin` fails to load |
| `debezium-revapi` | `debezium-parent` `pluginManagement` | `revapi-maven-plugin` fails to load |
| `debezium-checkstyle` | `debezium-parent` `qa` profile `<plugins>` | `maven-checkstyle-plugin` fails to load |

---

## The fixes (all in root `pom.xml`)

### 1. `debezium-bom`

Override the import in `<dependencyManagement>`. Because the child's `dependencyManagement`
is dominant over the parent's, this replaces the bad resolution:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.debezium</groupId>
            <artifactId>debezium-bom</artifactId>
            <version>${version.debezium}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        ...
    </dependencies>
</dependencyManagement>
```

### 2. `debezium-ide-configs` and `debezium-revapi`

Both are in `debezium-parent`'s `<pluginManagement>`. The child's `<pluginManagement>` is
dominant over the parent's, so overriding there works:

```xml
<build>
    <pluginManagement>
        <plugins>
            <plugin>
                <groupId>net.revelc.code.formatter</groupId>
                <artifactId>formatter-maven-plugin</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>io.debezium</groupId>
                        <artifactId>debezium-ide-configs</artifactId>
                        <version>${version.debezium}</version>
                    </dependency>
                </dependencies>
            </plugin>
            <plugin>
                <groupId>org.revapi</groupId>
                <artifactId>revapi-maven-plugin</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>io.debezium</groupId>
                        <artifactId>debezium-revapi</artifactId>
                        <version>${version.debezium}</version>
                    </dependency>
                </dependencies>
            </plugin>
        </plugins>
    </pluginManagement>
    ...
</build>
```

### 3. `debezium-checkstyle`

This one is harder. `debezium-checkstyle` is a dependency of `maven-checkstyle-plugin`,
but it is declared inside the **`qa` profile's `<plugins>` section** of `debezium-parent`
(not in `pluginManagement`). Profile `<plugins>` sections are not overridable by
`pluginManagement`, and same-ID profile merging between parent and child does not work
reliably — both profiles remain active and Maven appends the dependency lists, keeping
the bad `3.4.2-popsink-1` version alongside the corrected one. Any version in the list
that cannot be resolved will cause a build failure.

The only mechanism that reliably wins over an inherited profile's plugin configuration is
the child's **main build `<plugins>` section**. Binding the `check-style` execution to
`<phase>none</phase>` there prevents the execution from running — and since no execution
is active, Maven never loads the plugin or attempts to resolve `debezium-checkstyle`:

```xml
<build>
    <plugins>
        <!-- Disable check-style: maven-checkstyle-plugin in the inherited qa profile
             has debezium-checkstyle:${project.version} as a dependency. When our version
             diverges from the upstream debezium version, ${project.version} resolves to
             a non-existent artifact. -->
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-checkstyle-plugin</artifactId>
            <executions>
                <execution>
                    <id>check-style</id>
                    <phase>none</phase>
                </execution>
            </executions>
        </plugin>
        ...
    </plugins>
</build>
```

---

## The double-deploy problem

Disabling `central-publishing-maven-plugin` (see `docs/gar-publishing.md`) re-activates
the standard `default-deploy` lifecycle execution. With the `gcloud` profile active, two
deploy executions are now in scope for the `deploy` phase:

1. `default-deploy` — the standard Maven lifecycle binding
2. `deploy-to-gar` — the explicit execution added in the `gcloud` profile

Both upload to the same `maven-releases` repository. The first succeeds; the second gets
a `400 already_exists` error.

The fix is to disable `default-deploy` within the `gcloud` profile, so only `deploy-to-gar`
runs when the profile is active. Both executions must be declared in a single
`<plugin>` block (Maven rejects duplicate plugin declarations within the same profile):

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
                        <id>default-deploy</id>
                        <phase>none</phase>
                    </execution>
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

---

## Maven profile override reference

A summary of what works and what doesn't when trying to override an **inherited** profile's
plugin configuration:

| Approach | Works? | Why |
|---|---|---|
| Child `<pluginManagement>` overrides parent `<pluginManagement>` | ✓ | Standard child-dominates-parent inheritance |
| Child `<dependencyManagement>` overrides parent `<dependencyManagement>` | ✓ | Same |
| Child defines same-ID profile, overrides `<dependencies>` in plugin | ✗ | Maven appends both dependency lists; old version survives |
| `combine.self="override"` on `<dependencies>` in profile plugin | ✗ | Attribute only honoured on `<configuration>` elements, not `<dependencies>` |
| Child defines same-ID profile, overrides execution `<phase>` | ✗ | Parent profile's execution phase is not reliably overridden |
| Child main `<build><plugins>` binds execution to `<phase>none</phase>` | ✓ | Main build is more dominant than inherited profiles |
