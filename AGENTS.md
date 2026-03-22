# Agents Guide

## Purpose

Use this file as the default operating guide for contributors and coding agents.

## Stack and Baseline
- Kotlin JVM project (Gradle Kotlin DSL)
- JDK toolchain: 21
- Test stack: JUnit 5 + Strikt
- Build command of record: `./gradlew clean build`

## Day-to-Day Workflow
- Run `./pre-commit` before committing.
- `pre-commit` intentionally runs `./gradlew clean build` to reduce local/CI drift.
- For quick feedback during development, `./gradlew test` is fine, but pre-commit remains the stricter gate.

## Code Style (Project Standard)

### Macro Style (Architecture and Coupling)
- Use hexagonal architecture by default.
- The `domain` module owns domain types, use cases, and ports.
- Adapter modules depend inward on `domain`; `domain` must not depend on adapter modules.
- The `json` and `http` modules are adapters/support modules; the `service` module is the composition root and runtime entrypoint.
- The `json` module contains only `@Serializable` DTOs.
- JSON DTO property names must match the upstream JSON field names exactly.
- Translate raw transport JSON into sensible domain naming in adapter mapping code, not in the DTO definitions.
- Model upstream-required JSON fields as non-null and upstream-optional or missing fields as nullable.
- Wiring belongs at the boundary, not inside the domain core.
- Every behavioral concrete class must implement an interface.
- Pure data classes and value objects do not need interfaces.
- Design for testability by default:
  - Production code depends on interfaces, not implementations.
  - Add stubs/fakes in `src/testFixtures` only when a real test seam needs them.
  - Shared stubs/fakes must live in `src/testFixtures` in the matching component package.
  - Avoid catch-all test-double files.
  - Do not define private or local stubs in `src/test` unless the seam is truly one-off and there is a clear reason not to reuse it.
- Prefer wiring components via interfaces at boundaries. Wiring one concrete implementation directly to another creates tighter coupling.
- Good interface design keeps components loosely coupled and easier to evolve or replace over time.
- Concrete-to-concrete wiring can be acceptable when it is intentionally local, does not leak across boundaries, and avoids unnecessary abstraction.

### Micro Style (Kotlin Code)
- Do not use default parameter values in constructors or functions.
- Prefer explicit constructor parameters over hidden or default configuration.
- Never use hard casts with `as`.
- Do not use `var` unless mutation is strictly required and cannot be expressed immutably.
- Never use `lateinit var`.
- Do not use trailing commas.
- Avoid named arguments by default; use them only when required for disambiguation or readability in ambiguous call sites.
- Use explicit constructors for fixed initialization requirements.
- Prefer immutable models.
- Minimise side effects; prefer pure functions where possible.

### Types
- Use focused domain types:
  - `@JvmInline value class` wrappers for primitive or data-unit semantics.
  - Prefer domain value types over primitives at boundaries.
  - Prefer direct construction of value classes.
  - Do not add companion or static factory validation for value classes unless the validation genuinely belongs at a system boundary.

### API Design
- Keep APIs explicit and small:
  - Use clear method names.
  - All failure cases must be modeled with `Result<T>`.
  - Do not use exceptions or nullable return values to represent failure.
  - Do not throw exceptions for control flow or expected outcomes.
  - Exceptions may only be used for truly unrecoverable situations, such as programmer errors or invariant violations.
  - Prefer returning `Result<T>` over throwing exceptions.
  - Prefer `map` and `flatMap` over manual branching on success and failure in production code.
  - Avoid unwrapping results prematurely.
  - Do not add explicit return types on override methods when the interface already defines the return type.

### Naming
- When no stronger domain name is available, use `Real` as the prefix for production implementations of interfaces.
- Avoid acronyms in Kotlin names where practical.

### Readability
- Prefer expression-bodied functions (`fun foo() = ...`) instead of block bodies when possible.
- In expression-bodied functions, always place the expression on the next line after `=`.
- Avoid unnecessary explicit return types when type inference is clear and signatures already communicate intent.
- Avoid temporary locals that only feed a `when`; prefer `when (val value = ...)` or direct expression forms.
- Prefer consistent alignment in multi-line expressions so related parts are easy to scan.
- Keep helper extensions and private helpers near their call sites.
- Never use implicit lambda parameters (`it`); always name lambda variables explicitly.

### Rule Exceptions
- Rules in this guide are strong defaults, not absolute laws.
- It is acceptable to break any rule when there is a clear, explicit reason and the tradeoff is understood.
- When breaking a rule, keep the exception narrow and preserve correctness, determinism, and testability.
- A narrow exception is acceptable for `@Serializable` boundary DTOs when `kotlinx.serialization` requires a nullable field to have a `= null` default in order to represent an upstream field that is genuinely omitted.

## Testing Standard
- Use JUnit 5 tests with descriptive backtick method names.
- Use Strikt assertions (`expectThat`, `expectCatching`).

### Test Structure
- Prefer simple expressions inside `expectThat(...)`.
- Prefer chained assertions on separate lines for readability.
- Keep assertions top-level in test bodies; do not place assertions inside `let` blocks.
- Use explicit intermediate variables in tests when they improve readability.
- Separate consecutive assertion blocks with a blank line.
- Keep logic out of tests; prefer explicit setup over clever abstractions.
- Tests should prioritise readability over reuse.

### Test Behavior
- For `Result`, use Strikt assertion-builder extensions (`expectThat(result).isSuccess()` / `isFailure()`) from `src/testFixtures`.
- Prefer behavior-focused test names and edge-case coverage.
- Tests must cover all code paths, including boundary and failure cases, for each component.
- Boundary and failure-case testing is mandatory for all components and is not optional.
- HTTP adapters that call real external services must also have live tests under `src/testExternal`.
- Live external tests must run separately from the default `test` and `build` tasks.

### Test Fixtures
- Use `src/testFixtures` for reusable test doubles.
- Name reusable doubles clearly in the relevant component package, for example `StubX`, `FakeX`, or `CallbackXStub`.
- Avoid vague names such as `...TestDoubles`.
