# Java SrcTracer

A Java implementation of [SrcTracer](https://github.com/lks9/src-tracer), originally developed for C/C++. This version reimplements the instrumenter using JavaParser (instead of Clang), provides two trace runtimes (text and binary), and includes an AbstractRetracer that converts SrcTracer traces into ProRunVis block IDs.

SrcTracer records a compact execution trace of a Java program at the source level. The trace can be used standalone or uploaded to ProRunVis for visualization.

## Prerequisites

- Java 17+

## Build

```bash
./gradlew build
```

This produces three jars:

| Jar | Description |
|-----|-------------|
| `instrumenter/build/libs/instrumenter-0.1.0-SNAPSHOT-all.jar` | Instrumenter CLI (fat jar) |
| `runtime/build/libs/runtime-0.1.0-SNAPSHOT.jar` | Text runtime (produces `.trace.txt`) |
| `runtime-binary/build/libs/runtime-binary-0.1.0-SNAPSHOT.jar` | Binary runtime (produces `.trace`) |

## Usage

All commands below assume you are in the project root directory.

### Step 1: Instrument

Insert tracing calls into the source code:

```bash
java -jar instrumenter/build/libs/instrumenter-0.1.0-SNAPSHOT-all.jar \
  <input.java> \
  <output.java>
```

Example:

```bash
java -jar instrumenter/build/libs/instrumenter-0.1.0-SNAPSHOT-all.jar \
  examples/Demo.java \
  examples/instrumented/Demo.java
```

This inserts `srctracer.Trace.*` calls at every control-flow point (if/else, loops, switch, return, try/catch, method entry).

### Step 2: Compile

Compile the instrumented file with the SrcTracer runtime on the classpath.

**Text mode:**

```bash
javac -cp runtime/build/libs/runtime-0.1.0-SNAPSHOT.jar \
  -d examples/instrumented \
  examples/instrumented/Demo.java
```

**Binary mode:**

```bash
javac -cp runtime-binary/build/libs/runtime-binary-0.1.0-SNAPSHOT.jar \
  -d examples/instrumented \
  examples/instrumented/Demo.java
```

The instrumented source code is the same for both modes. The only difference is which runtime jar is on the classpath.

### Step 3: Run

Run the compiled program. A trace file is created in the `trace-out/` directory.

**Text mode:**

```bash
java -cp runtime/build/libs/runtime-0.1.0-SNAPSHOT.jar:examples/instrumented \
  Demo
```

This creates a `.trace.txt` file in `trace-out/`.

**Binary mode:**

```bash
java -cp runtime-binary/build/libs/runtime-binary-0.1.0-SNAPSHOT.jar:examples/instrumented \
  Demo
```

This creates a `.trace` file (binary, not human-readable) in `trace-out/`.

### View the Trace

Text trace:

```bash
cat trace-out/Demo_*.trace.txt
```

Example output: `C2C1OOOOOOOORE`

Binary trace (hex dump):

```bash
xxd trace-out/Demo_*.trace
```

### Passing Arguments

If the program accepts command-line arguments, add them after the class name:

```bash
java -cp runtime/build/libs/runtime-0.1.0-SNAPSHOT.jar:examples/instrumented \
  BubbleSort 100
```

### Clearing Old Traces

Before running a new test, clear old trace files to avoid confusion:

```bash
rm trace-out/Demo_*
```

## Trace Token Reference

| Token | Meaning |
|-------|---------|
| `C<hex>` | Method call (with method ID in hexadecimal) |
| `I` | If-branch taken / loop body entered |
| `O` | Else-branch taken / loop ended |
| `R` | Return |
| `E` | Program end |
| `T` | Try block entered |
| `U` | Try block ended (no exception) |
| `J<hex>` | Catch clause entered (with catch index in hexadecimal) |
| `_CASE` | Switch case encoded as 6 bits of I/O |

The binary runtime produces the same tokens in a compact binary encoding. Both formats contain the same information.

## Using with ProRunVis

SrcTracer traces can be uploaded to ProRunVis for visualization. ProRunVis accepts both text (`.trace.txt`) and binary (`.trace`) traces.

### 1. Instrument, compile, and run

Follow Steps 1-3 above to generate a trace file.

### 2. Prepare the upload folder

Create a folder containing the **original** (not instrumented) source file and the trace file:

```bash
mkdir -p ~/Desktop/DemoUpload
cp examples/Demo.java ~/Desktop/DemoUpload/
cp trace-out/Demo_*.trace.txt ~/Desktop/DemoUpload/
```

The folder should contain:

```
DemoUpload/
├── Demo.java
└── Demo_<timestamp>.trace.txt
```

### 3. Start ProRunVis

```bash
cd /Users/salma/ProRunVis
./gradlew clean prorunvis-api:bootJar
java -jar prorunvis-api/build/libs/prorunvis-api.jar
```

### 4. Upload

1. Open `http://localhost:8080` in a browser
2. Click the upload button and select the folder
3. ProRunVis detects the trace file and uses the SrcTracer path (AbstractRetracer) instead of compiling and running the code
4. The visualization appears with colored code highlighting

The server logs show which path was used:
- `[ProRunVis] Using SrcTracer path: ...` means the SrcTracer trace was found and used
- `[ProRunVis] Using original compile-and-run path` means no trace file was found, so ProRunVis used its original path

## How It Works

```
SrcTracer side:                        ProRunVis side:

original.java                          Upload folder:
    │                                   original.java + trace file
    ▼                                       │
Instrumenter                                ▼
    │                                   Instrument (builds block-ID map)
    ▼                                       │
instrumented.java                           ▼
    │                                   AbstractRetracer
    ▼                                   (reads trace + walks AST)
Compile + Run                               │
    │                                       ▼
    ▼                                   Block-ID stack
trace file                                  │
(.trace.txt or .trace)                      ▼
                                        TraceProcessor → JSON
                                            │
                                            ▼
                                        Colored visualization
```

When a SrcTracer trace file is present, ProRunVis still runs its own Instrumenter to build the block-ID map, but it does not compile or execute the uploaded code. Instead, the AbstractRetracer reads the SrcTracer trace and walks the AST to reconstruct the block-ID stack that ProRunVis needs.
