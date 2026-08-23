# How to Use Java SrcTracer

## Prerequisites

- Java 17+
- The SrcTracer project built (`./gradlew build` from the project root)

## Overview

SrcTracer records a compact execution trace of a Java program. The workflow has 3 steps:

1. **Instrument** — insert tracing calls into the source code
2. **Compile** — compile the instrumented code with the SrcTracer runtime
3. **Run** — execute the program to generate a `.trace.txt` file

## Step 0: Build SrcTracer

```bash
cd /Users/salma/Java_Src_tracer/java-src-tracer
./gradlew build
```

This produces three jars:
- `instrumenter/build/libs/instrumenter-0.1.0-SNAPSHOT-all.jar` — the instrumenter CLI (fat jar)
- `runtime/build/libs/runtime-0.1.0-SNAPSHOT.jar` — text-mode runtime (produces `.trace.txt`)
- `runtime-binary/build/libs/runtime-binary-0.1.0-SNAPSHOT.jar` — binary-mode runtime (produces `.trace`)

## Step 1: Instrument

Take your original Java file and produce an instrumented version:

```bash
java -jar instrumenter/build/libs/instrumenter-0.1.0-SNAPSHOT-all.jar \
  examples/Demo.java \
  examples/instrumented/Demo.java
```

This inserts `srctracer.Trace.*` calls at every control-flow point (if/else, loops, switch, return, try/catch, method entry).

## Step 2: Compile

Compile the instrumented file with the SrcTracer runtime on the classpath:

```bash
javac -cp runtime/build/libs/runtime-0.1.0-SNAPSHOT.jar \
  examples/instrumented/Demo.java
```

## Step 3: Run

Execute the compiled program:

```bash
java -cp examples/instrumented:runtime/build/libs/runtime-0.1.0-SNAPSHOT.jar \
  Demo
```

This creates a `trace-out/` directory containing a `.trace.txt` file.

## View the Trace

To see the trace content in the terminal after running:

```bash
cat trace-out/*.trace.txt
```

Or combine run + view in one command:

```bash
java -cp examples/instrumented:runtime/build/libs/runtime-0.1.0-SNAPSHOT.jar Demo \
  && cat trace-out/*.trace.txt
```

Example output: `C2C1OOOOOOOORE`

## Trace Token Reference

| Token | Meaning |
|-------|---------|
| `C<hex>` | Function/method call (with ID) |
| `I` | If-branch taken / loop iteration |
| `O` | Else-branch taken / loop ended |
| `R` | Return |
| `T` | Try block entered |
| `U` | Try block ended (no exception) |
| `J<hex>` | Catch clause entered |
| `E` | Program end |
| `_CASE` | Switch case encoded as 6 bits of I/O |

## Binary Trace Mode

SrcTracer also supports a binary trace format that is more compact. To use it, swap the runtime jar at compile and run time:

### Compile with binary runtime

```bash
javac -cp runtime-binary/build/libs/runtime-binary-0.1.0-SNAPSHOT.jar \
  examples/instrumented/Demo.java
```

### Run with binary runtime

```bash
java -cp examples/instrumented:runtime-binary/build/libs/runtime-binary-0.1.0-SNAPSHOT.jar \
  Demo
```

This produces a `.trace` file (binary, not human-readable) in `trace-out/`. To inspect it, use a hex dump:

```bash
xxd trace-out/*.trace
```

The instrumented code is the same for both modes — the only difference is which runtime jar is on the classpath.

## End-to-End: SrcTracer + ProRunVis

This walkthrough uses `Demo.java` as an example. Replace it with any Java program.

### 1. Instrument with SrcTracer

```bash
cd /Users/salma/Java_Src_tracer/java-src-tracer
java -jar instrumenter/build/libs/instrumenter-0.1.0-SNAPSHOT-all.jar \
  examples/Demo.java \
  examples/instrumented/Demo.java
```

### 2. Compile and run to generate the trace

```bash
javac -cp runtime/build/libs/runtime-0.1.0-SNAPSHOT.jar \
  examples/instrumented/Demo.java

java -cp examples/instrumented:runtime/build/libs/runtime-0.1.0-SNAPSHOT.jar Demo
```

This creates a `.trace.txt` file in `trace-out/`. Verify it:

```bash
cat trace-out/*.trace.txt
```

Example output: `C2C1OOOOOOOORE`

### 3. Prepare a folder for ProRunVis upload

Create a folder containing the **original** (uninstrumented) source + the generated trace file:

```bash
mkdir -p ~/Desktop/DemoForProRunVis
cp examples/Demo.java ~/Desktop/DemoForProRunVis/
cp trace-out/*.trace.txt ~/Desktop/DemoForProRunVis/
```

The folder should look like:

```
DemoForProRunVis/
├── Demo.java
└── Demo_2026-08-08_19.38.56.618805000.trace.txt
```

### 4. Start ProRunVis

```bash
cd /Users/salma/ProRunVis
./gradlew clean prorunvis-api:bootJar
java -jar prorunvis-api/build/libs/prorunvis-api.jar
```

### 5. Upload to ProRunVis

1. Open `http://localhost:8080` in a browser
2. Click the upload button and select the `DemoForProRunVis` folder
3. ProRunVis auto-detects the `.trace.txt` file and uses the SrcTracer path (AbstractRetracer) instead of compiling and running the code
4. The visualization appears with colored code highlighting

You can verify which path was used by checking the server terminal output:
- `[ProRunVis] Using SrcTracer path: ...` — AbstractRetracer was used
- `[ProRunVis] Using original compile-and-run path` — no `.trace.txt` found, original path was used

### How it works

```
SrcTracer side:                          ProRunVis side:
                                        
Demo.java                               Upload folder:
    │                                    Demo.java + .trace.txt
    ▼                                        │
Instrumenter                                 ▼
    │                                    Instrument (block ID map only)
    ▼                                        │
Demo_instrumented.java                       ▼
    │                                    AbstractRetracer
    ▼                                    reads .trace.txt + walks AST
Compile + Run                                │
    │                                        ▼
    ▼                                    Block IDs: [3, 0, 2, 2, 2]
trace-out/*.trace.txt                        │
(e.g. C2C1OIIIORE)                          ▼
                                         TraceProcessor → JSON
                                             │
                                             ▼
                                         Colored visualization
```
