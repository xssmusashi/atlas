# Atlas Phase 1 — Sub-plan 2: Arithmetic Nodes + Vector API Emitter

> **For agentic workers:** Execute task-by-task. Tests before implementation.

**Goal:** Расширить DFC до позиционных и арифметических узлов (XPos, YPos, ZPos, Add, Sub, Mul, Negate, Abs, Min, Max, Clamp), реализовать `sampleTile` для батчевой обработки, добавить **Vector API** SIMD-эмиттер. На выходе — JMH-бенчмарк, показывающий **5-15× ускорение JIT vs interpreter** на реальном дереве, и сравнение Scalar JIT vs Vector JIT.

**Architecture:** Pattern matching на расширенном sealed `DfcNode`. Один общий `JitCompiler` принимает `JitOptions(emitter = SCALAR | VECTOR | AUTO)`. Vector эмиттер использует `jdk.incubator.vector.DoubleVector` для `sampleTile`.

**Tech Stack:** ASM 9.7, `jdk.incubator.vector` (preview в JDK 25), JMH 1.37.

## Tasks

1. Add positional nodes (XPos, YPos, ZPos) + tests
2. Add arithmetic nodes (Add, Sub, Mul, Negate, Abs) + tests
3. Add control nodes (Min, Max, Clamp) + tests
4. Extend DfcLoader to parse all new types
5. Extend Interpreter switch
6. Extend ScalarAsmEmitter switch
7. Extend JitVsInterpreterEqualityTest with random arithmetic trees
8. Add `sampleTile(int tileX, int tileZ, long seed, double[] out)` to CompiledSampler
9. Implement sampleTile in Interpreter + scalar JIT
10. Build complex-tree JMH benchmark — show real JIT speedup
11. Add VectorAsmEmitter using DoubleVector
12. JitOptions(emitter = SCALAR/VECTOR/AUTO) wiring
13. Vector vs Scalar bench, document results, commit, tag v0.2.0-arithmetic

## Acceptance

- ≥30 unit tests passing
- JitVsInterpreterEqualityTest covers random arithmetic trees of depth 5-10, all bit-exact
- Complex-tree bench: Scalar JIT ≥ 5× faster than Interpreter
- Vector JIT bench available (numbers documented; gain depends on tree shape)
- All 3 modules build clean
