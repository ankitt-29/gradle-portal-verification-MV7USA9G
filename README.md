# Liferay Ordered Deploy Plugin

[![Gradle Plugin Portal](https://img.shields.io/badge/Gradle%20Plugin%20Portal-io.github.ankitt--29.deploy--ordered-blue)](https://plugins.gradle.org/plugin/io.github.ankitt-29.deploy-ordered)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A Gradle plugin that deploys Liferay OSGi modules in **correct topological dependency order**, automatically partitioning them into deployment **waves** and verifying each bundle reaches the `Active` state via the Gogo shell before proceeding to the next wave.

---

## Why This Plugin?

When a Liferay workspace has many interdependent OSGi modules (services, APIs, portlets), deploying them all at once can cause bundles to fail activation because their dependencies are not yet active. This plugin solves the problem by:

1. **Analyzing** the inter-module dependency graph of your workspace
2. **Partitioning** modules into ordered waves (dependencies first)
3. **Deploying** each wave to the Liferay deploy folder
4. **Verifying** via the Gogo shell that every bundle in a wave is `Active` (or `Resolved` for fragments) before starting the next wave

---

## Requirements

| Requirement | Version |
|---|---|
| Gradle | 4.10.3 or later |
| Liferay | 7.x / DXP (any version with Gogo shell enabled) |
| Java | 11 or later |

---

## Installation

Apply the plugin in your **root** `build.gradle`:

### Groovy DSL

```groovy
plugins {
    id 'io.github.ankitt-29.deploy-ordered' version '1.0.2'
}
```

### Kotlin DSL

```kotlin
plugins {
    id("io.github.ankitt-29.deploy-ordered") version "1.0.2"
}
```

---

## Configuration

The plugin adds an `orderedDeploy` extension block with the following optional properties:

```groovy
orderedDeploy {
    // Path to the Liferay deploy folder.
    // Auto-detected from liferay.workspace.home.dir or liferay.home in
    // gradle.properties / gradle-local.properties if not set.
    deployDir = "/opt/liferay/deploy"

    // Seconds to wait after dropping JARs into the deploy folder
    // before starting Gogo shell verification. Default: 10
    waitDelaySeconds = 10

    // Port of the Liferay Gogo shell. Default: 11311
    gogoShellPort = 11311
}
```

### Auto-detection of Deploy Directory

If `deployDir` is not set, the plugin reads the following properties (in order) from `gradle.properties` and `gradle-local.properties` in your root project:

1. `liferay.workspace.home.dir`
2. `liferay.home`

It then appends `/deploy` to the resolved path. If neither property is found, it falls back to `<rootProject>/bundles/deploy`.

---

## Usage

Run the task from your Liferay workspace root:

```bash
./gradlew deployOrdered
```

The plugin will:

1. Discover all OSGi module sub-projects (projects under a `/modules/` directory that have the `java` or `groovy` plugin applied, or contain a `bnd.bnd` file)
2. Build the inter-module dependency graph
3. Print the full deployment plan (waves with module names)
4. Deploy wave by wave, verifying bundle states after each wave

### Example Output

```
╔══════════════════════════════════════════════════════╗
║     Liferay Ordered Deploy Plugin                    ║
╚══════════════════════════════════════════════════════╝

→ Deploy folder : /opt/liferay/deploy
→ Gogo shell    : localhost:11311
→ Wave delay    : 10s

→ Found 12 OSGi module projects

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  DEPLOYMENT PLAN (3 waves, 12 modules)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  ┌─ WAVE 1 of 3 (4 module(s))
  │   ✦ :modules:my-api
  │   ✦ :modules:my-util
  └─────────────────────────────────────────

  ┌─ WAVE 2 of 3 (5 module(s))
  │   ✦ :modules:my-service
  └─────────────────────────────────────────

  ┌─ WAVE 3 of 3 (3 module(s))
  │   ✦ :modules:my-portlet
  └─────────────────────────────────────────

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  DEPLOYING
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  ┌─ WAVE 1 of 3 (4 module(s))
  │   ✅ my-api-1.0.0.jar
  │   ✅ my-util-1.0.0.jar
  │   Dropped 2 JAR(s) into deploy folder.
  │   ⏳ Waiting 10s for Liferay to pick up this wave...
  │   🔍 Verifying bundle states via Gogo shell...
  │   ✅ All 2 bundle(s) reached expected state.
  │   → Proceeding to next wave.
  └─────────────────────────────────────────
```

---

## How It Works

### 1. Module Discovery

The plugin scans all sub-projects of the root project and selects those that are:
- Located under a path containing `/modules/`
- Have the `java` or `groovy` Gradle plugin applied, **or** contain a `bnd.bnd` file

### 2. Dependency Graph

For each module, the plugin inspects the following configurations for inter-project dependencies:
`implementation`, `api`, `compileOnly`, `runtimeOnly`, `compileInclude`, `provided`

### 3. Topological Sort into Waves

The plugin performs a topological sort (Kahn's algorithm) to group modules into waves:
- **Wave 1**: modules with no dependencies on other workspace modules
- **Wave 2**: modules whose dependencies are all in Wave 1
- And so on…

If a **circular dependency** is detected, the remaining modules are placed in a final wave with a warning.

### 4. Deployment & Verification

For each wave:
1. The compiled JAR of each module is copied into the Liferay deploy folder
2. The plugin waits `waitDelaySeconds` for Liferay's hot-deploy scanner to pick up the JARs
3. It connects to the Gogo shell via Telnet and runs `lb -s <Bundle-SymbolicName>` for each bundle
4. **Regular bundles** must reach `Active` state
5. **OSGi fragment bundles** (those with a `Fragment-Host` MANIFEST header) are accepted in `Resolved` state
6. Verification retries up to 30 times (every 3 seconds = 90 seconds max per wave)

---

## OSGi Fragment Bundle Support

Fragment bundles are automatically detected by reading the `Fragment-Host` header from the JAR's `MANIFEST.MF`. They are verified as `Resolved` (not `Active`), which is the correct OSGi lifecycle state for fragments.

---

## Troubleshooting

| Problem | Solution |
|---|---|
| `Deploy folder not found` | Set `liferay.workspace.home.dir` in `gradle.properties` or configure `deployDir` in the `orderedDeploy` block |
| `Gogo shell unavailable on port 11311` | Ensure Liferay is running and the Gogo shell is enabled. Configure a different port with `gogoShellPort` |
| Bundles not reaching `Active` in time | Increase `waitDelaySeconds` or check Liferay logs for bundle errors |
| Circular dependency warning | Check your inter-module dependencies for cycles |

---

## License

This project is licensed under the [MIT License](LICENSE).
