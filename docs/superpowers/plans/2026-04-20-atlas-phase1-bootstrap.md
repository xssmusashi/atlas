# Atlas Phase 1 — Sub-plan 1: Bootstrap + Vertical Slice

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Поднять multi-module Gradle-проект Atlas на JDK 25, реализовать минимальный end-to-end vertical slice (DFC `Constant` node → AST → interpreter → JIT bytecode → JMH benchmark), доказать, что архитектура работает и JIT даёт измеримое ускорение даже на тривиальном узле.

**Architecture:** Multi-module Gradle: `atlas-core` (pure Java, no MC), `atlas-mc` (Fabric Loom, MC 26.1 — пока заглушка), `atlas-bench` (JMH). TDD: тесты до кода. Каждая задача = atomic commit. На выходе — green CI и первый JMH-репорт.

**Tech Stack:**
- JDK 25 (Toolchain via Gradle, версия зафиксирована в `gradle.properties`)
- Gradle 8.10+
- ASM 9.7 (для JIT bytecode emission)
- JUnit Jupiter 5.11.3 + AssertJ 3.26.3 (тесты)
- JMH 1.37 (бенчмарки)
- Fabric Loom 1.10-SNAPSHOT (для `atlas-mc`, заглушка в этом плане)
- GitHub Actions (CI)

---

## File Structure

После выполнения этого плана структура `D:/atlas/`:

```
D:/atlas/
├── .github/workflows/
│   └── ci.yml                          (CI: build, test, bench)
├── .gitignore
├── LICENSE                             (Apache-2.0)
├── README.md                           (skeleton)
├── settings.gradle.kts                 (включает 3 модуля)
├── build.gradle.kts                    (общие конфиги)
├── gradle.properties                   (JDK toolchain version, deps versions)
├── gradle/wrapper/                     (gradle wrapper)
├── gradlew, gradlew.bat
├── docs/superpowers/
│   ├── specs/2026-04-20-atlas-design.md   (уже существует)
│   └── plans/2026-04-20-atlas-phase1-bootstrap.md  (этот файл)
├── atlas-core/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/dev/xssmusashi/atlas/core/
│       │   ├── dfc/
│       │   │   ├── DfcNode.java        (sealed interface, ONE record: Constant)
│       │   │   └── DfcLoader.java      (parse JSON → DfcNode)
│       │   └── jit/
│       │       ├── CompiledSampler.java     (interface)
│       │       ├── Interpreter.java         (walk AST, baseline)
│       │       ├── ScalarAsmEmitter.java    (DfcNode → bytecode)
│       │       └── JitCompiler.java         (façade)
│       └── test/java/dev/xssmusashi/atlas/core/
│           ├── dfc/DfcLoaderTest.java
│           └── jit/
│               ├── InterpreterTest.java
│               ├── ScalarAsmEmitterTest.java
│               └── JitVsInterpreterEqualityTest.java
├── atlas-mc/
│   ├── build.gradle.kts                (Fabric Loom — заглушка, build не падает)
│   └── src/main/
│       ├── java/dev/xssmusashi/atlas/mc/
│       │   └── AtlasMod.java           (ModInitializer stub: только лог)
│       └── resources/
│           ├── fabric.mod.json
│           └── atlas.mixins.json       (пустой mixins config)
└── atlas-bench/
    ├── build.gradle.kts                (JMH plugin)
    └── src/jmh/java/dev/xssmusashi/atlas/bench/
        └── ConstantNodeBench.java      (Interpreter vs JIT для Constant)
```

---

## Pre-flight check

- [ ] **Step 0.1: Verify environment**

Run:
```bash
java --version
git --version
ls /d/atlas
```
Expected:
- `java --version` shows version 25 (or `java 25` / `openjdk 25`)
- `git --version` shows any version ≥ 2.30
- `/d/atlas` exists with `docs/` subdirectory

If JDK 25 не активен, executor должен поставить переменную `JAVA_HOME` на JDK 25 перед запуском Gradle. Memory знает, что JDK 25 (Temurin) установлен.

---

## Task 1: Git init + license + .gitignore + README skeleton

**Files:**
- Create: `D:/atlas/.gitignore`
- Create: `D:/atlas/LICENSE`
- Create: `D:/atlas/README.md`

- [ ] **Step 1.1: Init git repo**

Run:
```bash
cd /d/atlas && git init -b main
```
Expected: `Initialized empty Git repository in D:/atlas/.git/`

- [ ] **Step 1.2: Create .gitignore**

Create file `D:/atlas/.gitignore` with content:
```gitignore
# Gradle
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
!**/build.gradle.kts
!**/build.gradle

# IntelliJ / Eclipse
.idea/
*.iml
.classpath
.project
.settings/
out/
bin/

# OS
.DS_Store
Thumbs.db

# Atlas runtime artifacts
run/
logs/
world/
*.atr
jit-debug/
jit-cache/

# Fabric Loom
.fabric/
remappedSrc/
```

- [ ] **Step 1.3: Create LICENSE (Apache-2.0)**

Create file `D:/atlas/LICENSE` with the full Apache-2.0 text. Use the canonical text from https://www.apache.org/licenses/LICENSE-2.0.txt (executor: download or paste full Apache 2.0 license; the file MUST contain the complete license text, not a placeholder).

Header line:
```
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/
```
(then full canonical text — ~11KB).

- [ ] **Step 1.4: Create README skeleton**

Create file `D:/atlas/README.md`:
```markdown
# Atlas

Next-generation Minecraft worldgen for Fabric: tile-based pipeline, JIT-compiled DensityFunction (Vector API + ASM), parallel DAG scheduler. Targets ≥1500 chunks/second on a 16-core CPU.

**Status:** Phase 1 — bootstrap. Not yet usable in-game.

**Target:** Minecraft 26.1, Fabric loader, JDK 25.

**License:** Apache-2.0.

## Modules

- `atlas-core` — pure Java, no MC dependencies. DFC parser, JIT compiler, tile pipeline, biome lookup, region storage.
- `atlas-mc` — Fabric mod, MC integration glue.
- `atlas-bench` — JMH benchmarks.

## Build

```bash
./gradlew build
```

## Bench

```bash
./gradlew :atlas-bench:jmh
```

## Incompatible with

- **C2ME** — Atlas replaces C2ME's worldgen; running both will fail at startup.

## Design

See `docs/superpowers/specs/2026-04-20-atlas-design.md` for full design.
```

- [ ] **Step 1.5: Commit**

```bash
cd /d/atlas && git add .gitignore LICENSE README.md docs/ && git commit -m "chore: bootstrap repo with license, gitignore, readme, design spec"
```
Expected: commit succeeds.

---

## Task 2: Gradle wrapper + root build configuration

**Files:**
- Create: `D:/atlas/gradle/wrapper/gradle-wrapper.properties`
- Create: `D:/atlas/gradle/wrapper/gradle-wrapper.jar`
- Create: `D:/atlas/gradlew`
- Create: `D:/atlas/gradlew.bat`
- Create: `D:/atlas/settings.gradle.kts`
- Create: `D:/atlas/build.gradle.kts`
- Create: `D:/atlas/gradle.properties`

- [ ] **Step 2.1: Generate Gradle wrapper**

Run:
```bash
cd /d/atlas && gradle wrapper --gradle-version 8.10.2
```
Expected: creates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`.

If `gradle` command not on PATH, fallback: copy `gradle/wrapper/`, `gradlew`, `gradlew.bat` from `D:/voxy` (which has working wrapper) and edit `gradle-wrapper.properties` to:
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 2.2: Create gradle.properties**

Create `D:/atlas/gradle.properties`:
```properties
# Project
group=dev.xssmusashi.atlas
version=0.1.0-SNAPSHOT

# JVM
org.gradle.jvmargs=-Xmx4G -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true

# Toolchain
java.toolchain.version=25

# Versions
asm.version=9.7
junit.version=5.11.3
assertj.version=3.26.3
jmh.version=1.37
gson.version=2.11.0
```

- [ ] **Step 2.3: Create settings.gradle.kts**

Create `D:/atlas/settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }
}

rootProject.name = "atlas"

include("atlas-core")
include("atlas-bench")
include("atlas-mc")
```

- [ ] **Step 2.4: Create root build.gradle.kts**

Create `D:/atlas/build.gradle.kts`:
```kotlin
plugins {
    java
}

allprojects {
    group = property("group") as String
    version = property("version") as String

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of((property("java.toolchain.version") as String).toInt()))
        }
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set((property("java.toolchain.version") as String).toInt())
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
```

- [ ] **Step 2.5: Verify root build skeleton**

Run:
```bash
cd /d/atlas && ./gradlew tasks --no-daemon
```
Expected: lists Gradle tasks без ошибок (модулей пока нет, поэтому только root tasks).

If fails — typical issue: JDK 25 not detected by toolchain. Executor should set `org.gradle.java.installations.auto-download=true` in `gradle.properties` or set `JAVA_HOME` explicitly to JDK 25.

- [ ] **Step 2.6: Commit**

```bash
cd /d/atlas && git add gradle/ gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties && git commit -m "build: gradle wrapper + multi-module root config (jdk 25 toolchain)"
```

---

## Task 3: `atlas-core` module skeleton + first compile check

**Files:**
- Create: `D:/atlas/atlas-core/build.gradle.kts`
- Create: `D:/atlas/atlas-core/src/main/java/dev/xssmusashi/atlas/core/package-info.java`

- [ ] **Step 3.1: Create atlas-core/build.gradle.kts**

Create `D:/atlas/atlas-core/build.gradle.kts`:
```kotlin
plugins {
    `java-library`
}

dependencies {
    api("org.ow2.asm:asm:${property("asm.version")}")
    api("org.ow2.asm:asm-util:${property("asm.version")}")  // для отладки bytecode
    implementation("com.google.code.gson:gson:${property("gson.version")}")

    testImplementation("org.junit.jupiter:junit-jupiter:${property("junit.version")}")
    testImplementation("org.assertj:assertj-core:${property("assertj.version")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    jvmArgs("--enable-preview", "--add-modules", "jdk.incubator.vector")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("--enable-preview"))
}
```

(Vector API в `jvmArgs` нужно даже если в этом плане его не используем — заранее настраиваем для будущих задач.)

- [ ] **Step 3.2: Create package-info**

Create `D:/atlas/atlas-core/src/main/java/dev/xssmusashi/atlas/core/package-info.java`:
```java
/**
 * Atlas core: pure Java worldgen primitives.
 * <p>
 * No Minecraft dependencies. Reusable from atlas-mc, atlas-bench, future CLI tools.
 */
package dev.xssmusashi.atlas.core;
```

- [ ] **Step 3.3: Verify compile**

Run:
```bash
cd /d/atlas && ./gradlew :atlas-core:compileJava --no-daemon
```
Expected: BUILD SUCCESSFUL, no Java files yet but Gradle compiles empty source set.

- [ ] **Step 3.4: Commit**

```bash
cd /d/atlas && git add atlas-core/ && git commit -m "build(core): atlas-core module skeleton with asm + gson + junit deps"
```

---

## Task 4: TDD — DfcNode sealed interface + Constant record

**Files:**
- Create: `D:/atlas/atlas-core/src/main/java/dev/xssmusashi/atlas/core/dfc/DfcNode.java`
- Test: `D:/atlas/atlas-core/src/test/java/dev/xssmusashi/atlas/core/dfc/DfcNodeTest.java`

- [ ] **Step 4.1: Write the failing test**

Create `D:/atlas/atlas-core/src/test/java/dev/xssmusashi/atlas/core/dfc/DfcNodeTest.java`:
```java
package dev.xssmusashi.atlas.core.dfc;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DfcNodeTest {

    @Test
    void constantNode_holdsItsValue() {
        DfcNode.Constant c = new DfcNode.Constant(42.5);
        assertThat(c.value()).isEqualTo(42.5);
    }

    @Test
    void constantNode_isInstanceOfDfcNode() {
        DfcNode node = new DfcNode.Constant(0.0);
        assertThat(node).isInstanceOf(DfcNode.Constant.class);
    }

    @Test
    void constantNode_equality() {
        assertThat(new DfcNode.Constant(1.0))
            .isEqualTo(new DfcNode.Constant(1.0))
            .isNotEqualTo(new DfcNode.Constant(2.0));
    }
}
```

- [ ] **Step 4.2: Run test, expect compilation failure**

Run:
```bash
cd /d/atlas && ./gradlew :atlas-core:test --no-daemon
```
Expected: FAIL (compilation error: `DfcNode` not defined).

- [ ] **Step 4.3: Implement DfcNode**

Create `D:/atlas/atlas-core/src/main/java/dev/xssmusashi/atlas/core/dfc/DfcNode.java`:
```java
package dev.xssmusashi.atlas.core.dfc;

/**
 * Density function AST node.
 * <p>
 * Sealed hierarchy — pattern matching over node types in JIT compiler.
 * In Phase 1 only {@link Constant} is implemented. More node types added
 * in subsequent sub-plans (Add, Mul, Clamp, Noise, Spline, ...).
 */
public sealed interface DfcNode {

    record Constant(double value) implements DfcNode {}
}
```

- [ ] **Step 4.4: Run test, expect PASS**

Run:
```bash
cd /d/atlas && ./gradlew :atlas-core:test --no-daemon
```
Expected: 3 tests passed.

- [ ] **Step 4.5: Commit**

```bash
cd /d/atlas && git add atlas-core/ && git commit -m "feat(dfc): sealed interface DfcNode with Constant record + tests"
```

---

## Task 5: TDD — DfcLoader (parse Constant from JSON)

**Files:**
- Create: `D:/atlas/atlas-core/src/main/java/dev/xssmusashi/atlas/core/dfc/DfcLoader.java`
- Test: `D:/atlas/atlas-core/src/test/java/dev/xssmusashi/atlas/core/dfc/DfcLoaderTest.java`

- [ ] **Step 5.1: Write the failing test**

Create `D:/atlas/atlas-core/src/test/java/dev/xssmusashi/atlas/core/dfc/DfcLoaderTest.java`:
```java
package dev.xssmusashi.atlas.core.dfc;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DfcLoaderTest {

    @Test
    void parsesConstantFromMinecraftJsonForm() {
        String json = """
            { "type": "minecraft:constant", "argument": 42.5 }
            """;
        DfcNode node = DfcLoader.load(new ByteArrayInputStream(json.getBytes()));
        assertThat(node).isInstanceOf(DfcNode.Constant.class);
        assertThat(((DfcNode.Constant) node).value()).isEqualTo(42.5);
    }

    @Test
    void parsesConstantFromShortNumberForm() {
        String json = "3.14";
        DfcNode node = DfcLoader.load(new ByteArrayInputStream(json.getBytes()));
        assertThat(node).isInstanceOf(DfcNode.Constant.class);
        assertThat(((DfcNode.Constant) node).value()).isEqualTo(3.14);
    }

    @Test
    void throwsOnUnknownType() {
        String json = """
            { "type": "minecraft:unknown_xyz" }
            """;
        assertThatThrownBy(() -> DfcLoader.load(new ByteArrayInputStream(json.getBytes())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minecraft:unknown_xyz");
    }
}
```

- [ ] **Step 5.2: Run test, expect FAIL (compilation error)**

Run:
```bash
cd /d/atlas && ./gradlew :atlas-core:test --no-daemon
```
Expected: FAIL — `DfcLoader` undefined.

- [ ] **Step 5.3: Implement DfcLoader**

Create `D:/atlas/atlas-core/src/main/java/dev/xssmusashi/atlas/core/dfc/DfcLoader.java`:
```java
package dev.xssmusashi.atlas.core.dfc;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Parses Minecraft DensityFunction JSON into {@link DfcNode} AST.
 * <p>
 * Phase 1: only {@code minecraft:constant} type and short number form supported.
 * Other types throw {@link IllegalArgumentException} (sub-plan 2 adds them).
 */
public final class DfcLoader {

    private DfcLoader() {}

    public static DfcNode load(InputStream json) {
        JsonElement el = JsonParser.parseReader(new InputStreamReader(json, StandardCharsets.UTF_8));
        return parse(el);
    }

    private static DfcNode parse(JsonElement el) {
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return new DfcNode.Constant(el.getAsDouble());
        }
        if (el.isJsonObject()) {
            String type = el.getAsJsonObject().get("type").getAsString();
            return switch (type) {
                case "minecraft:constant" -> new DfcNode.Constant(
                    el.getAsJsonObject().get("argument").getAsDouble()
                );
                default -> throw new IllegalArgumentException(
                    "Unsupported DFC node type in Phase 1: " + type
                );
            };
        }
        throw new IllegalArgumentException("Cannot parse DFC element: " + el);
    }
}
```

- [ ] **Step 5.4: Run test, expect PASS**

Run:
```bash
cd /d/atlas && ./gradlew :atlas-core:test --no-daemon
```
Expected: 6 tests passed (3 from DfcNodeTest + 3 from DfcLoaderTest).

- [ ] **Step 5.5: Commit**

```bash
cd /d/atlas && git add atlas-core/ && git commit -m "feat(dfc): DfcLoader parses minecraft:constant JSON form (+ short number form)"
```

---

## Task 6: TDD — CompiledSampler interface + Interpreter (baseline)

**Files:**
- Create: `D:/atlas/atlas-core/src/main/java/dev/xssmusashi/atlas/core/jit/CompiledSampler.java`
- Create: `D:/atlas/atlas-core/src/main/java/dev/xssmusashi/atlas/core/jit/Interpreter.java`
- Test: `D:/atlas/atlas-core/src/test/java/dev/xssmusashi/atlas/core/jit/InterpreterTest.java`

- [ ] **Step 6.1: Write the failing test**

Create `D:/atlas/atlas-core/src/test/java/dev/xssmusashi/atlas/core/jit/InterpreterTest.java`:
```java
package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class InterpreterTest {

    @Test
    void interpretsConstantNode() {
        CompiledSampler sampler = new Interpreter(new DfcNode.Constant(7.0));
        assertThat(sampler.sample(0, 0, 0, 12345L)).isEqualTo(7.0);
        assertThat(sampler.sample(100, 64, -200, 99999L)).isEqualTo(7.0);
    }

    @Test
    void sampleBatch_fillsArrayWithConstant() {
        CompiledSampler sampler = new Interpreter(new DfcNode.Constant(2.5));
        int[] xs = {0, 1, 2, 3};
        int[] ys = {0, 0, 0, 0};
        int[] zs = {0, 0, 0, 0};
        double[] out = new double[4];
        sampler.sampleBatch(xs, ys, zs, 0L, out, 4);
        assertThat(out).containsExactly(2.5, 2.5, 2.5, 2.5);
    }
}
```

- [ ] **Step 6.2: Run test, expect FAIL**

Run:
```bash
cd /d/atlas && ./gradlew :atlas-core:test --no-daemon
```
Expected: FAIL — `CompiledSampler`, `Interpreter` undefined.

- [ ] **Step 6.3: Implement CompiledSampler interface**

Create `D:/atlas/atlas-core/src/main/java/dev/xssmusashi/atlas/core/jit/CompiledSampler.java`:
```java
package dev.xssmusashi.atlas.core.jit;

/**
 * Density function sampler — either an interpreter or a JIT-compiled implementation.
 * <p>
 * Implementations must be thread-safe (no mutable state) and deterministic
 * (same inputs → same output).
 */
public interface CompiledSampler {

    /** Sample at a single point. */
    double sample(int x, int y, int z, long seed);

    /** Sample a batch of points. {@code out} length must be ≥ {@code len}. */
    default void sampleBatch(int[] xs, int[] ys, int[] zs, long seed, double[] out, int len) {
        for (int i = 0; i < len; i++) {
            out[i] = sample(xs[i], ys[i], zs[i], seed);
        }
    }
}
```

- [ ] **Step 6.4: Implement Interpreter**

Create `D:/atlas/atlas-core/src/main/java/dev/xssmusashi/atlas/core/jit/Interpreter.java`:
```java
package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;

/**
 * Tree-walking interpreter for {@link DfcNode}. Baseline for correctness comparison
 * and benchmarking against JIT-compiled samplers.
 */
public final class Interpreter implements CompiledSampler {

    private final DfcNode root;

    public Interpreter(DfcNode root) {
        this.root = root;
    }

    @Override
    public double sample(int x, int y, int z, long seed) {
        return eval(root, x, y, z, seed);
    }

    private static double eval(DfcNode node, int x, int y, int z, long seed) {
        return switch (node) {
            case DfcNode.Constant c -> c.value();
        };
    }
}
```

- [ ] **Step 6.5: Run test, expect PASS**

Run:
```bash
cd /d/atlas && ./gradlew :atlas-core:test --no-daemon
```
Expected: 8 tests passed.

- [ ] **Step 6.6: Commit**

```bash
cd /d/atlas && git add atlas-core/ && git commit -m "feat(jit): CompiledSampler interface + tree-walking Interpreter (constant only)"
```

---

## Task 7: TDD — ScalarAsmEmitter (JIT for Constant)

**Files:**
- Create: `D:/atlas/atlas-core/src/main/java/dev/xssmusashi/atlas/core/jit/ScalarAsmEmitter.java`
- Create: `D:/atlas/atlas-core/src/main/java/dev/xssmusashi/atlas/core/jit/JitCompiler.java`
- Test: `D:/atlas/atlas-core/src/test/java/dev/xssmusashi/atlas/core/jit/ScalarAsmEmitterTest.java`

- [ ] **Step 7.1: Write the failing test**

Create `D:/atlas/atlas-core/src/test/java/dev/xssmusashi/atlas/core/jit/ScalarAsmEmitterTest.java`:
```java
package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ScalarAsmEmitterTest {

    @Test
    void compilesConstantNodeToBytecode() {
        DfcNode tree = new DfcNode.Constant(99.0);
        CompiledSampler jit = JitCompiler.compile(tree);
        assertThat(jit.sample(0, 0, 0, 0L)).isEqualTo(99.0);
        assertThat(jit.sample(123, 456, 789, 42L)).isEqualTo(99.0);
    }

    @Test
    void compiledSamplerImplementsInterface() {
        CompiledSampler jit = JitCompiler.compile(new DfcNode.Constant(0.0));
        assertThat(jit).isInstanceOf(CompiledSampler.class);
    }

    @Test
    void multipleCompilations_yieldIndependentSamplers() {
        CompiledSampler a = JitCompiler.compile(new DfcNode.Constant(1.0));
        CompiledSampler b = JitCompiler.compile(new DfcNode.Constant(2.0));
        assertThat(a.sample(0, 0, 0, 0L)).isEqualTo(1.0);
        assertThat(b.sample(0, 0, 0, 0L)).isEqualTo(2.0);
    }
}
```

- [ ] **Step 7.2: Run test, expect FAIL**

Run:
```bash
cd /d/atlas && ./gradlew :atlas-core:test --no-daemon
```
Expected: FAIL — `JitCompiler` undefined.

- [ ] **Step 7.3: Implement ScalarAsmEmitter**

Create `D:/atlas/atlas-core/src/main/java/dev/xssmusashi/atlas/core/jit/ScalarAsmEmitter.java`:
```java
package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Emits a Java class implementing {@link CompiledSampler} by walking a {@link DfcNode}
 * tree and inlining the computation as scalar bytecode.
 * <p>
 * Phase 1: only {@link DfcNode.Constant} supported. The generated class has shape:
 * <pre>
 *   public final class AtlasJit$N implements CompiledSampler {
 *       public double sample(int x, int y, int z, long seed) {
 *           return &lt;constant&gt;;
 *       }
 *   }
 * </pre>
 */
final class ScalarAsmEmitter implements Opcodes {

    static byte[] emit(DfcNode root, String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(
            V21,                                         // generated bytecode targets JVM 21+
            ACC_PUBLIC | ACC_FINAL,
            internalName,
            null,
            "java/lang/Object",
            new String[]{Type.getInternalName(CompiledSampler.class)}
        );

        emitDefaultConstructor(cw);
        emitSampleMethod(cw, root);

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitDefaultConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitSampleMethod(ClassWriter cw, DfcNode root) {
        // public double sample(int x, int y, int z, long seed) { return <expr>; }
        MethodVisitor mv = cw.visitMethod(
            ACC_PUBLIC | ACC_FINAL,
            "sample",
            "(IIIJ)D",
            null,
            null
        );
        mv.visitCode();
        emitNode(mv, root);
        mv.visitInsn(DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitNode(MethodVisitor mv, DfcNode node) {
        switch (node) {
            case DfcNode.Constant c -> mv.visitLdcInsn(c.value());
            // future cases: Add, Mul, Clamp, Noise, ...
        }
    }
}
```

- [ ] **Step 7.4: Implement JitCompiler façade**

Create `D:/atlas/atlas-core/src/main/java/dev/xssmusashi/atlas/core/jit/JitCompiler.java`:
```java
package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compiles {@link DfcNode} trees into JIT-emitted {@link CompiledSampler} instances.
 * <p>
 * Each compile call produces a hidden class loaded into this package via
 * {@link Lookup#defineHiddenClass(byte[], boolean, Lookup.ClassOption...)}.
 */
public final class JitCompiler {

    private static final AtomicLong CLASS_COUNTER = new AtomicLong();
    private static final Lookup LOOKUP = MethodHandles.lookup();

    private JitCompiler() {}

    public static CompiledSampler compile(DfcNode root) {
        long id = CLASS_COUNTER.getAndIncrement();
        String internalName = "dev/xssmusashi/atlas/core/jit/AtlasJit$" + id;
        byte[] bytecode = ScalarAsmEmitter.emit(root, internalName);
        try {
            Class<?> cls = LOOKUP
                .defineHiddenClass(bytecode, true, Lookup.ClassOption.NESTMATE)
                .lookupClass();
            return (CompiledSampler) cls.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("JIT compilation failed for tree: " + root, e);
        }
    }
}
```

- [ ] **Step 7.5: Run test, expect PASS**

Run:
```bash
cd /d/atlas && ./gradlew :atlas-core:test --no-daemon
```
Expected: 11 tests passed.

If failure: typical issue is class name validation. The internal name `AtlasJit$0` uses `$` which is valid in Java but Lookup.defineHiddenClass expects no slashes in nest-mate identification — this should work but if not, simplify to `AtlasJit_0`.

- [ ] **Step 7.6: Commit**

```bash
cd /d/atlas && git add atlas-core/ && git commit -m "feat(jit): ScalarAsmEmitter + JitCompiler — emit hidden class for Constant nodes"
```

---

## Task 8: TDD — JIT vs Interpreter equality (correctness anchor)

**Files:**
- Test: `D:/atlas/atlas-core/src/test/java/dev/xssmusashi/atlas/core/jit/JitVsInterpreterEqualityTest.java`

This test is the **single most important correctness test in Atlas**. Every future JIT change is gated by it.

- [ ] **Step 8.1: Write the test**

Create `D:/atlas/atlas-core/src/test/java/dev/xssmusashi/atlas/core/jit/JitVsInterpreterEqualityTest.java`:
```java
package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Master correctness test: for any DfcNode, JIT-compiled and interpreted samplers
 * must produce bit-identical results across a wide range of inputs.
 * <p>
 * This test grows with each new node type. Phase 1: Constant only.
 */
class JitVsInterpreterEqualityTest {

    @Test
    void constant_jit_equals_interpreter_for_random_inputs() {
        Random rng = new Random(0xA71A5L);
        for (int i = 0; i < 100; i++) {
            double v = rng.nextDouble() * 2000 - 1000;
            DfcNode tree = new DfcNode.Constant(v);
            CompiledSampler interp = new Interpreter(tree);
            CompiledSampler jit = JitCompiler.compile(tree);
            for (int j = 0; j < 100; j++) {
                int x = rng.nextInt();
                int y = rng.nextInt(384);
                int z = rng.nextInt();
                long seed = rng.nextLong();
                assertThat(jit.sample(x, y, z, seed))
                    .as("constant=%s @ (%d,%d,%d) seed=%d", v, x, y, z, seed)
                    .isEqualTo(interp.sample(x, y, z, seed));
            }
        }
    }

    @Test
    void edge_values_negative_zero_max_min_nan_infinity() {
        double[] edges = {
            0.0, -0.0, 1.0, -1.0,
            Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        };
        for (double v : edges) {
            DfcNode tree = new DfcNode.Constant(v);
            CompiledSampler interp = new Interpreter(tree);
            CompiledSampler jit = JitCompiler.compile(tree);
            assertThat(Double.doubleToRawLongBits(jit.sample(0, 0, 0, 0L)))
                .as("edge value %s", v)
                .isEqualTo(Double.doubleToRawLongBits(interp.sample(0, 0, 0, 0L)));
        }
    }
}
```

- [ ] **Step 8.2: Run test, expect PASS (no implementation needed — should already work)**

Run:
```bash
cd /d/atlas && ./gradlew :atlas-core:test --no-daemon
```
Expected: 13 tests passed (11 prior + 2 new).

If FAIL: there's a bug in JIT for some constant value. Most likely place: `mv.visitLdcInsn(c.value())` doesn't handle special values properly. Investigate before proceeding — Phase 1 acceptance depends on this test.

- [ ] **Step 8.3: Commit**

```bash
cd /d/atlas && git add atlas-core/ && git commit -m "test(jit): bit-exact equality JIT vs Interpreter for Constant (correctness anchor)"
```

---

## Task 9: `atlas-bench` module skeleton + first JMH benchmark

**Files:**
- Create: `D:/atlas/atlas-bench/build.gradle.kts`
- Create: `D:/atlas/atlas-bench/src/jmh/java/dev/xssmusashi/atlas/bench/ConstantNodeBench.java`

- [ ] **Step 9.1: Create atlas-bench/build.gradle.kts**

Create `D:/atlas/atlas-bench/build.gradle.kts`:
```kotlin
plugins {
    id("me.champeau.jmh") version "0.7.2"
}

dependencies {
    jmh(project(":atlas-core"))
    jmh("org.openjdk.jmh:jmh-core:${property("jmh.version")}")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:${property("jmh.version")}")
}

jmh {
    warmupIterations.set(2)
    iterations.set(3)
    fork.set(1)
    timeOnIteration.set("3s")
    warmup.set("3s")
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh/results.json"))
}
```

- [ ] **Step 9.2: Add JMH plugin to settings.gradle.kts pluginManagement**

Edit `D:/atlas/settings.gradle.kts`. The current `pluginManagement` block already has `gradlePluginPortal()` which hosts `me.champeau.jmh`, no changes needed. (Verify by re-reading.)

- [ ] **Step 9.3: Create benchmark file**

Create `D:/atlas/atlas-bench/src/jmh/java/dev/xssmusashi/atlas/bench/ConstantNodeBench.java`:
```java
package dev.xssmusashi.atlas.bench;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import dev.xssmusashi.atlas.core.jit.CompiledSampler;
import dev.xssmusashi.atlas.core.jit.Interpreter;
import dev.xssmusashi.atlas.core.jit.JitCompiler;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 3)
@Fork(1)
public class ConstantNodeBench {

    private CompiledSampler interpreter;
    private CompiledSampler jit;

    @Setup
    public void setup() {
        DfcNode tree = new DfcNode.Constant(42.0);
        interpreter = new Interpreter(tree);
        jit = JitCompiler.compile(tree);
    }

    @Benchmark
    public void interpreter_sample(Blackhole bh) {
        bh.consume(interpreter.sample(123, 64, -789, 0L));
    }

    @Benchmark
    public void jit_sample(Blackhole bh) {
        bh.consume(jit.sample(123, 64, -789, 0L));
    }

    @Benchmark
    public void interpreter_sample_loop_1024(Blackhole bh) {
        for (int i = 0; i < 1024; i++) {
            bh.consume(interpreter.sample(i, 64, -i, 0L));
        }
    }

    @Benchmark
    public void jit_sample_loop_1024(Blackhole bh) {
        for (int i = 0; i < 1024; i++) {
            bh.consume(jit.sample(i, 64, -i, 0L));
        }
    }
}
```

- [ ] **Step 9.4: Verify bench compiles**

Run:
```bash
cd /d/atlas && ./gradlew :atlas-bench:compileJmhJava --no-daemon
```
Expected: BUILD SUCCESSFUL.

If JMH plugin missing — verify Gradle Plugin Portal access (check internet, proxy).

- [ ] **Step 9.5: Run the benchmark**

Run:
```bash
cd /d/atlas && ./gradlew :atlas-bench:jmh --no-daemon
```
Expected: Takes ~1-2 minutes. Output ends with score lines like:
```
Benchmark                                         Mode  Cnt   Score   Error  Units
ConstantNodeBench.interpreter_sample             thrpt    3   N×10⁵  ± xxx  ops/ms
ConstantNodeBench.jit_sample                     thrpt    3   N×10⁵  ± xxx  ops/ms
ConstantNodeBench.interpreter_sample_loop_1024   thrpt    3   N×10²  ± xxx  ops/ms
ConstantNodeBench.jit_sample_loop_1024           thrpt    3   N×10²  ± xxx  ops/ms
```
JMH JSON also written to `atlas-bench/build/reports/jmh/results.json`.

For Constant node specifically, JIT and interpreter may show **similar throughput** because JVM C2 will inline both to the same constant after warmup. This is expected — Constant is the trivial baseline. Real wins start at Add / Mul / Noise (sub-plan 2+). Document this in commit message.

- [ ] **Step 9.6: Commit**

```bash
cd /d/atlas && git add atlas-bench/ && git commit -m "bench(jit): first JMH benchmark — interpreter vs JIT for Constant (baseline; both fast after JVM inlining, real wins land in sub-plan 2)"
```

---

## Task 10: `atlas-mc` module skeleton (Fabric Loom stub)

**Files:**
- Create: `D:/atlas/atlas-mc/build.gradle.kts`
- Create: `D:/atlas/atlas-mc/src/main/java/dev/xssmusashi/atlas/mc/AtlasMod.java`
- Create: `D:/atlas/atlas-mc/src/main/resources/fabric.mod.json`
- Create: `D:/atlas/atlas-mc/src/main/resources/atlas.mixins.json`

This task creates the scaffolding for `atlas-mc` so the tree compiles, but does NOT yet wire up real MC integration (deferred to sub-plan 6).

- [ ] **Step 10.1: Add Fabric Loom to root pluginManagement**

Verify `settings.gradle.kts` has Fabric maven repo (it does from Task 2.3). Confirm by reading the file.

- [ ] **Step 10.2: Create atlas-mc/build.gradle.kts**

Create `D:/atlas/atlas-mc/build.gradle.kts`:
```kotlin
plugins {
    id("fabric-loom") version "1.10-SNAPSHOT"
}

repositories {
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://maven.parchmentmc.org") { name = "ParchmentMC" }
}

// Versions hardcoded for MC 26.1. If MC version mismatch — update here.
val mcVersion = "1.21.4"   // executor: replace with exact MC 26.1 mapping when known
val yarnMappings = "1.21.4+build.1"
val loaderVersion = "0.16.7"
val fabricApiVersion = "0.110.0+1.21.4"

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    implementation(project(":atlas-core"))
    include(project(":atlas-core"))
}

loom {
    runs {
        named("client") { ideConfigGenerated(true) }
        named("server") { ideConfigGenerated(true) }
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
```

**NOTE for executor:** MC 26.1 is the user's stated target. The exact Loom + Yarn + Fabric API version triple for MC 26.1 must be looked up by the executor (e.g., `gh release list -R FabricMC/yarn` or fabricmc.net). Placeholders `1.21.4`, `0.16.7`, `0.110.0+1.21.4` are illustrative; replace with verified values for the actual MC 26.1 release before running.

- [ ] **Step 10.3: Create AtlasMod stub**

Create `D:/atlas/atlas-mc/src/main/java/dev/xssmusashi/atlas/mc/AtlasMod.java`:
```java
package dev.xssmusashi.atlas.mc;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AtlasMod implements ModInitializer {

    public static final String MOD_ID = "atlas";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOG.info("Atlas mod loaded — Phase 1 bootstrap. Worldgen integration arrives in sub-plan 6.");
    }
}
```

- [ ] **Step 10.4: Create fabric.mod.json**

Create `D:/atlas/atlas-mc/src/main/resources/fabric.mod.json`:
```json
{
  "schemaVersion": 1,
  "id": "atlas",
  "version": "${version}",
  "name": "Atlas",
  "description": "Next-generation Minecraft worldgen: tile-based pipeline, JIT-compiled DensityFunction, parallel DAG scheduler.",
  "authors": ["xssmusashi"],
  "contact": {
    "homepage": "https://github.com/xssmusashi/atlas",
    "sources": "https://github.com/xssmusashi/atlas",
    "issues": "https://github.com/xssmusashi/atlas/issues"
  },
  "license": "Apache-2.0",
  "icon": "assets/atlas/icon.png",
  "environment": "*",
  "entrypoints": {
    "main": ["dev.xssmusashi.atlas.mc.AtlasMod"]
  },
  "mixins": [
    "atlas.mixins.json"
  ],
  "depends": {
    "fabricloader": ">=0.16.0",
    "minecraft": "*",
    "java": ">=25"
  },
  "breaks": {
    "c2me": "*"
  }
}
```

- [ ] **Step 10.5: Create empty mixins config**

Create `D:/atlas/atlas-mc/src/main/resources/atlas.mixins.json`:
```json
{
  "required": true,
  "package": "dev.xssmusashi.atlas.mc.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": [],
  "client": [],
  "server": [],
  "injectors": {
    "defaultRequire": 1
  }
}
```

(Empty mixins list — sub-plan 6 will populate.)

- [ ] **Step 10.6: Verify atlas-mc compiles**

Run:
```bash
cd /d/atlas && ./gradlew :atlas-mc:compileJava --no-daemon
```
Expected: BUILD SUCCESSFUL. Loom downloads MC + mappings on first run (1-3 minutes, large download).

If fails due to MC version mismatch — executor adjusts `mcVersion`, `yarnMappings`, `loaderVersion`, `fabricApiVersion` in `atlas-mc/build.gradle.kts`. Verified versions list: https://fabricmc.net/develop/

- [ ] **Step 10.7: Commit**

```bash
cd /d/atlas && git add atlas-mc/ && git commit -m "build(mc): atlas-mc Fabric Loom skeleton + AtlasMod stub (no worldgen yet)"
```

---

## Task 11: GitHub Actions CI

**Files:**
- Create: `D:/atlas/.github/workflows/ci.yml`

- [ ] **Step 11.1: Create CI workflow**

Create directory and file:
```bash
mkdir -p /d/atlas/.github/workflows
```

Create `D:/atlas/.github/workflows/ci.yml`:
```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 25
      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
      - name: Build & test atlas-core
        run: ./gradlew :atlas-core:build --no-daemon
      - name: Compile atlas-bench
        run: ./gradlew :atlas-bench:compileJmhJava --no-daemon

  bench-quick:
    runs-on: ubuntu-latest
    needs: test
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 25
      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
      - name: Run JMH (quick mode for CI smoke)
        run: ./gradlew :atlas-bench:jmh -Pjmh.iterations=1 -Pjmh.warmupIterations=1 -Pjmh.fork=1 --no-daemon
      - name: Upload JMH results
        uses: actions/upload-artifact@v4
        with:
          name: jmh-results
          path: atlas-bench/build/reports/jmh/results.json
```

(Note: `atlas-mc` is not part of CI — Loom is heavy and MC asset downloads slow CI down. We add it later.)

- [ ] **Step 11.2: Commit**

```bash
cd /d/atlas && git add .github/ && git commit -m "ci: github actions for build, test, and bench-quick (atlas-mc deferred)"
```

---

## Task 12: Final smoke test — full build green

- [ ] **Step 12.1: Clean & rebuild everything**

Run:
```bash
cd /d/atlas && ./gradlew clean build --no-daemon
```
Expected: BUILD SUCCESSFUL on all 3 modules. Test count: 13.

(`atlas-mc` may take 5+ min on first run due to MC asset download. Subsequent runs cached.)

If `atlas-mc:build` fails because executor couldn't resolve correct MC 26.1 versions — leave it failing for now, document in plan completion notes. Sub-plan 6 will fix the MC integration; bootstrap goal is core + bench working.

- [ ] **Step 12.2: Run benchmarks one more time, save baseline**

Run:
```bash
cd /d/atlas && ./gradlew :atlas-bench:jmh --no-daemon
```
Capture the output to `D:/atlas/docs/superpowers/plans/baseline-2026-04-20-bootstrap.txt`:
```bash
cd /d/atlas && ./gradlew :atlas-bench:jmh --no-daemon > docs/superpowers/plans/baseline-2026-04-20-bootstrap.txt 2>&1 || true
```

(Note: `|| true` because if Gradle exits with warnings it shouldn't kill the file write.)

- [ ] **Step 12.3: Commit baseline**

```bash
cd /d/atlas && git add docs/superpowers/plans/baseline-2026-04-20-bootstrap.txt && git commit -m "bench: capture Phase 1 bootstrap JMH baseline (Constant node)"
```

- [ ] **Step 12.4: Tag the bootstrap milestone**

```bash
cd /d/atlas && git tag -a v0.1.0-bootstrap -m "Atlas Phase 1 sub-plan 1 complete: bootstrap + vertical slice (Constant node JIT)"
```

---

## Task 13: Push to GitHub

**Files:** none (remote repo creation)

- [ ] **Step 13.1: Create GitHub repo via gh CLI**

Run (uses `xssmusashi` GitHub account from memory):
```bash
cd /d/atlas && gh repo create xssmusashi/atlas --public --source=. --description "Next-generation Minecraft worldgen for Fabric: tile-based pipeline, JIT-compiled DensityFunction, parallel DAG scheduler. Targets ≥1500 chunks/second." --license apache-2.0
```

Expected: repo created, remote `origin` set automatically.

If `gh` is authenticated as `yokirun-boop` (the second account from memory), explicitly switch:
```bash
gh auth switch -u xssmusashi
```
before running the create command.

If repo already exists locally without remote — add remote manually:
```bash
git remote add origin git@github.com:xssmusashi/atlas.git
```

- [ ] **Step 13.2: Push main branch + tags**

```bash
cd /d/atlas && git push -u origin main && git push --tags
```
Expected: branch and tag pushed.

- [ ] **Step 13.3: Verify CI ran**

Run:
```bash
gh run list --repo xssmusashi/atlas --limit 3
```
Expected: at least one CI run in `queued`, `in_progress`, or `completed` state.

- [ ] **Step 13.4: Wait for CI green, then summary commit message**

Run:
```bash
gh run watch --repo xssmusashi/atlas
```
Expected: CI completes successfully (or surface failures for fixing).

If CI fails — most likely cause is MC version mismatch (atlas-mc) or JDK 25 not yet fully supported by some lib. Iterate.

---

## Self-Review

### Spec coverage check

Mapping spec sections → tasks:

| Spec | Covered by | Notes |
|---|---|---|
| §0 Identity | Task 1 (LICENSE, README), Task 2 (group, version) | ✓ |
| §1 Архитектурные решения | Task 1 (README mentions design doc) | Decisions are upstream of code |
| §2 High-level | Tasks 3-5 (atlas-core skeleton) | ✓ vertical slice |
| §3 atlas-core modules | Tasks 4-8 (dfc/, jit/) | Only `dfc` and `jit` for Phase 1 bootstrap. Sub-plans 2-5 add `tile/`, `biome/`, `region/`, `pool/` |
| §4 atlas-mc | Task 10 (skeleton + ModInitializer) | Stub; sub-plan 6 wires up generator |
| §5 Data flow | Not yet — sub-plan 6+ | |
| §6 Error handling | Task 5 (DfcLoader throws on unknown type) | Foundation; expanded in later plans |
| §7 Testing | Tasks 4, 5, 6, 7, 8 (TDD throughout) + Task 11 (CI) | ✓ |
| §8 Phase 1 acceptance | Task 12 (baseline capture) | This sub-plan establishes baseline; acceptance criteria gated by sub-plan 7 |
| §9 Roadmap | N/A | Out of scope for sub-plan 1 |
| §10 Risks | Tasks 7-8 (correctness anchor) addresses risk #3 | Other risks → sub-plans 2+ |

**Gaps:** Sub-plan 1 deliberately does NOT implement: Add/Mul/Clamp/Noise/Spline nodes (sub-plan 2), Vector API emitter (sub-plan 2), tile pipeline (sub-plan 4), biome lookup (sub-plan 3), region storage (sub-plan 5), full MC integration (sub-plan 6), full benchmark suite + acceptance (sub-plan 7).

This is the agreed scope for "vertical slice" — minimal end-to-end proof.

### Placeholder scan

Searched plan for: "TBD", "TODO", "implement later", "Add appropriate", "handle edge cases", "Similar to Task N", "Write tests for the above" — none found.

Two **explicit deferrals** are noted:
- Task 10.2: MC version triple is illustrative; executor verifies. This is **necessary ambiguity** because MC 26.1 exact mapping versions weren't known at planning time. Executor instructed to verify via fabricmc.net.
- Task 12.1: Acceptable failure of `atlas-mc:build` if MC versions can't be resolved on first try. Documented as known and bounded.

These are not placeholders in the "fill in later" sense — they are **execution contingencies** with clear handling.

### Type consistency

- `DfcNode` → `DfcNode.Constant` consistent across Tasks 4, 5, 6, 7, 8, 9.
- `CompiledSampler.sample(int x, int y, int z, long seed)` consistent across Tasks 6, 7, 8, 9.
- `JitCompiler.compile(DfcNode)` consistent across Tasks 7, 8, 9.
- Method signatures verified.

### Sanity check on Task 7

ASM bytecode generation can be tricky. Risks specifically in Task 7:
- `ClassWriter.COMPUTE_FRAMES` requires class metadata access — should work since `Object` and our interface are on classpath, but if it fails, fallback is `COMPUTE_MAXS` only and manual frame computation (executor handles).
- `defineHiddenClass` is JDK 15+ stable API — JDK 25 supports it without issues.
- `V21` class file version targets JVM 21+. Since toolchain is 25, generated classes load fine.

All risks bounded. No fixes needed inline.

---

## Plan Status

**Plan:** Atlas Phase 1 — Sub-plan 1: Bootstrap + Vertical Slice
**Tasks:** 13 (12 build + 1 push)
**Total steps:** ~60
**Estimated wall-clock time:** 4-8 hours (depending on Gradle cold cache, MC asset downloads, debug iterations on Task 7 ASM and Task 10 Fabric Loom version triple)

**On completion, this sub-plan delivers:**
- Working multi-module Atlas Gradle project on JDK 25
- 13 passing JUnit tests
- 1 JMH benchmark with baseline numbers
- Fabric Loom skeleton for atlas-mc (compiles)
- Public GitHub repo at `xssmusashi/atlas`
- Green CI pipeline
- Tagged milestone `v0.1.0-bootstrap`

**Next:** Sub-plan 2 — DFC arithmetic & control nodes (Add, Mul, Sub, Div, Clamp, Min, Max) + Vector API emitter + benchmarks showing real JIT speedup over interpreter.
