# kinotic-queue

`org.kinotic:kinotic-queue` is a durable, replicated queue for Kinotic clusters, built on `org.kinotic:kinotic-core`, Vert.x,
Ignite and Chronicle Queue. The README describes what it does and how it is configured.

## Building

The project needs JDK 25. In Claude Code Cloud only JDK 21 is installed; download JDK 25 first (the Oracle CDN is in the
egress allowlist) and let Gradle run on JDK 21 while compiling with JDK 25:

```bash
curl -sL "https://download.oracle.com/java/25/latest/jdk-25_linux-x64_bin.tar.gz" -o /tmp/jdk25.tar.gz
mkdir -p /tmp/jdk25 && tar xzf /tmp/jdk25.tar.gz -C /tmp/jdk25 --strip-components=1
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew build -Porg.gradle.java.installations.paths=/tmp/jdk25
```

The build resolves its plugins from Maven Central, so it needs no access to the Gradle plugin portal. `kinotic-core` is
resolved at `kinoticCoreVersion` from Maven Central or its snapshot repository; to build against a local checkout of
kinotic instead, add `--include-build <path to kinotic>` and Gradle substitutes the checkout's `kinotic-core` for the
published one.

## Branch and pull request workflow

A merged pull request is finished — never stack new commits onto its history, and never reuse it to track follow-up
work. When your PR merges, restart your working branch from the latest `develop` (keeping the same branch name,
force-with-lease push) and open a **new** pull request for the next unit of work.

A pull request description never mentions Claude or any AI tool: no "Generated with Claude Code" line, no
`Co-Authored-By` trailer, no session link, and no other attribution to an assistant.

## Don't guess from names

Names suggest meaning but don't define it. Before using an annotation, framework hook, base class, or library helper you haven't used in this codebase before, read its source or docs and confirm what it actually does. Don't infer behaviour from a plausible-sounding name and ship it. If you can't verify the behaviour, ask — don't write a comment justifying the guess.

## Java Conventions

Always use Lombok where possible: `@Getter`, `@Setter`, `@Accessors(chain = true)`, `@NoArgsConstructor`, `@RequiredArgsConstructor`, `@Slf4j`, `@Data`, `@Builder`. Prefer `@RequiredArgsConstructor` over hand-written constructors for dependency injection. Use `@Slf4j` instead of manual `LoggerFactory.getLogger()` calls.

Prefer a single `return` per method. Guard clauses that short-circuit errors or degenerate cases at the top of a method are fine (throwing, or bailing before the real work), but branching LOGIC paths use if/else assigning a result variable that is returned once at the end (the `String ret; if (...) { ret = ...; } else { ret = ...; } return ret;` idiom used throughout the codebase) — never a `return` inside each branch.

Use `enum` for any field whose value is constrained to a known set — never `String` with magic-string constants. Spring and Vert.x both auto-coerce JSON strings to enum values when deserializing into typed POJOs (Jackson's `@JsonCreator` / case-insensitive matching is built-in), so the wire contract stays string-friendly while the in-process type catches typos at compile time. Examples: `DelegateType`, `OidcProviderType`.

An enum that says what sort of thing something is gets named `<Thing>Type` (`DelegateType`, `OidcProviderType`), never `<Thing>Kind`, and the field holding it is `type`, never `kind`.

When bridging a `CompletableFuture` into Vert.x, always pass the context: `Future.fromCompletionStage(stage, vertx.getOrCreateContext())`, never the single-argument overload. The stage may complete on a foreign thread (Azure SDK, Caffeine loader, JDK executor), and without the context argument everything composed after it leaves the request's Vert.x context — breaking anything downstream that reads a context-local, such as `SecurityContext`'s participant. For delays, use `vertx.timer(...)` instead of a JDK scheduled executor for the same reason.

## Package Structure (Crucial!!)

Both Java and TypeScript modules follow the same layout convention. The rule is: if something will be used by another module/node, it belongs in `api/`. If not, it belongs in `internal/`. The `internal/` structure mirrors `api/` for implementations.

- `api/` — Public interfaces, types, and DTOs used by other modules or nodes (shared/exported)
- `internal/` — Everything private to this module (not shared/exported)
  - `internal/api/` — Implementations of public `api/` interfaces (`@Publish`, `@Component`, etc.)
  - `internal/api/model/` — DTOs and value objects only used within this module

The `internal/api/` structure mirrors `api/` for implementations. Example: `api/services/ITodoService` -> `internal/api/services/DefaultTodoService`. 

When creating code to store data, call it a Repository, not a Store. If there is advanced logic needed outside of CRUD, create a Service that delegates to the Repository.

Configuration follows the same split: `api/config/` contains `@ConfigurationProperties` classes and settings POJOs meant to be configured by users, while `internal/config/` contains Spring `@Configuration` classes that wire beans internally. This applies to all modules.

Don't create a new package or folder to hold a single file. Single-file folders just spread related code across the tree without aiding discoverability. Place the file in the nearest existing package that fits. A new subpackage is justified once there are at least two or three related files that genuinely belong together.

***Give every top-level type its own file. Don't nest a class, enum, or record inside an interface — DTOs, result types, and enums that appear in an interface's method signatures belong in their own files in the same package, not inlined in the interface body. Nesting buries types, makes them awkward to import, and bloats the interface. The same applies to types nested inside a class purely for convenience.***

## Comments

**IMPORTANT: Write comments the way you would explain the code to another developer.**

Javadoc — block comments on classes, methods, fields, anything else — describes the contract from the caller's perspective: what something is for, what guarantees it makes, what the inputs and outputs mean. It should not document implementation details — how the class persists, which helper it delegates to, what bypass mechanism it uses internally — that's noise for someone using the API and rots when the implementation changes. Also they should not document what something does not do. Only what it does do. (Unless it is a security concern, Does not validate user) 

Inline comments inside method bodies are different: they're for implementation details that aren't obvious from reading the code, and only when they aren't. A subtle invariant, the reason for an unusual ordering, a workaround for a specific bug, a non-obvious choice between two valid approaches — those earn an inline comment. Self-evident code does not. If you find yourself writing a comment that restates what the next line does, delete it. Like Javadoc, an inline comment says what the code does, never what it does not do.

The split is about audience, not formatting. Javadoc is for **consumers** of the API; inline is for **maintainers** of the body. Before writing a comment, ask which one needs it. The rationale for a defensive check, a workaround, or a tricky ordering belongs inline next to the code that does it — never in the Javadoc, even if it explains why the method behaves the way it does. The caller doesn't care that an org-mismatch returns null because of an ES shard-hashing edge case; they care that it returns null when there's no doc for that org. The "because" stays in the body.

Keep comments concise — usually a single line. Spend more words only where the logic is genuinely not straightforward and the extra explanation earns its place: a subtle invariant, a non-obvious interaction, a reason that isn't visible in the code. A comment's length should track how hard the code is to follow, not how important the code is.

Comment the code as it is now, not its history. Don't narrate what the code used to do or the edit you're making — no "before", "previously", "used to", "changed from", or "now does X instead" phrasing. The diff and git history record what changed; the comment describes the present state.

Use standard programming vocabulary in comments — the terms from GoF, Fowler's Refactoring, and the JDK/framework docs: delegate, factory, guard clause, invariant, idempotent, race condition, callback, dispatch. Never literary metaphors or coined phrases ("prologue", "dance", "journey", "saga"). Reference the actual method and class names involved rather than describing them indirectly. State cause and effect directly.

Never remove or alter an existing authorship comment — `Created by <name> on <date>`, `@author`, or similar attribution. Preserve it verbatim (name, accents, emoji, date, punctuation) when editing or refactoring a file, including when you rewrite the surrounding Javadoc/JSDoc, and carry it with the type if you move that type to another file.

## Properties
Properties should never be created for something that will not need to be configured differently in different environments. i.e. Kinotic Cloud dev vs Kinotic Cloud prod. In the case of a route or something that will be the same for multiple environments, create a constant.

Never gate a bean on a Spring profile — `@Profile` is for test contexts only; profile-gated beans are hard to audit. Profiles are property bundles: an `application-<name>.yml` selects property values for a deployment shape. Which modules a server runs is decided by its server module: every library module auto-configures once it is on the runtime classpath, so the server's `build.gradle` dependencies select the modules, and `@SpringBootApplication(exclude = …)` excludes the auto-configuration of a module the server needs only for its types (`TestServerApplication` excludes `KinoticDomainAutoConfiguration`). Enabling or disabling a feature within a module is done with an explicit `kinotic.*` property read by `@ConditionalOnProperty` (`kinotic.managementApi.github.disableProvisioner` is the established idiom), so what a deployment runs can be read from its server module and YAML alone.

## Dependency Versions

Never hardcode a dependency version in a dependency declaration. Every version lives as a `*Version` property in `gradle.properties` (kept alphabetical) and is pinned once in the `dependencyManagement` block of `build.gradle`. The dependency is declared with no version, so the managed version applies.

One version per artifact, one place to bump it. Verify a move with `dependencyInsight` on `compileClasspath` — `selected by rule` confirms the managed version is in effect.

## Snapshot versions — nothing is in stone

While `queueVersion` in `gradle.properties` is a `-SNAPSHOT`, no released artifact depends on this code, so there is nothing to stay backwards-compatible with: rename fields, break APIs, and reshape wire contracts freely. Deprecation shims and compatibility fallbacks start when the first release exists — building them sooner is Speculative Generality.

## Keep docs in sync with code

When a change alters something the docs describe — a wire contract, public API signature, REST route, auth mechanism, configuration option, or user-facing behavior — update the affected docs in the same change. The README must always reflect the correct and current shape of the module; stale docs are a defect, not a follow-up. Before finishing, grep the README for the symbols, properties and field names you changed and reconcile every hit. If a change is genuinely too large to document in the same pass, say so explicitly rather than leaving the docs silently wrong.

## Docs follow features, never the other way round

The README describes what the code does; it never decides what the code should do. A
request from the user drives a feature, and the docs are updated afterwards to match what was
built. Never treat a sentence in the docs as the specification of a behaviour the code lacks, and
never build, keep or refuse something because the docs say so. When a request contradicts the
docs, or the docs claim a behaviour the code does not have, say so and ask which way to go; do not
pick the docs' side on your own. The only authority a doc has is over the docs.

## Avoid these code smells

These are the named smells from Martin Fowler and Kent Beck's catalog in *Refactoring: Improving the Design of Existing Code*, grouped by Mäntylä's taxonomy — decades of industry consensus on what makes code hard to change, not house style or one reviewer's taste. That is why they bind: each one is a pattern the field has repeatedly watched turn into maintenance cost. Check every diff against this list before presenting it. The bar for any new abstraction is YAGNI (Beck and Jeffries, Extreme Programming) and the Rule of Three (Don Roberts, in Refactoring): it must carry information or remove duplication **today** — not that it might someday.

**Dispensables — code that should not exist**

- **Speculative Generality.** No one-value enums, no parameters every call site passes the same constant to, no "seam for a future toggle," no interface with a single implementation created "just in case," no config nobody asked for. Build for the current requirement; introduce the discriminator or abstraction when the second concrete case exists to design against.
- **Dead Code.** No defensive branches every caller already makes impossible, no unused parameters or imports, no commented-out code, no "kept for later" methods without an owner's explicit say-so. A guard that is genuinely load-bearing against a corrupted state earns an inline comment saying so — otherwise delete it.
- **Lazy Element.** A class, method, or package that no longer pulls its weight after a refactor gets inlined or deleted, not left behind.
- **Duplicated Code.** Before writing new logic, find the existing seam and compose it (one logic path). Two near-identical blocks in sibling classes means the shared piece was never extracted — extract it to the nearest common layer, not to a new grab bag.
- **Comments as deodorant.** A comment explaining confusing code is a signal to fix the code. The Comments section above governs what comments are for.

**Bloaters — things that have grown past one responsibility**

- **Long Function.** A method that does several things at different levels of abstraction gets decomposed, each piece named for what it does.
- **Large Class / junk drawers.** The smell is a class that accumulates *unrelated* members and changes for many reasons; split it along the reasons it changes. Entities too: a class holding one kind of thing must not carry a name claiming generality it doesn't have. The name itself isn't the smell: a shared `Util`/`Constants`/`Helper` class is fine for cohesive, stateless, well-named static members — a single known home beats scattering them. Don't overcorrect either — a class or file created to hold a single static method is a Lazy Element; fold it into the nearest cohesive home instead of trading a grab bag for file sprawl.
- **Long Parameter List / flag arguments.** A boolean or mode parameter that forks a method's whole behavior is two methods. More than ~4 parameters is a sign some of them are a missing type.
- **Data Clumps.** Values that always travel together belong in one type, not loose parameter pairs repeated across signatures.
- **Primitive Obsession.** Covered by the enum rule in Java Conventions — applies equally to ids, keys, and wire codes used across boundaries.

**Couplers — classes that know too much about each other**

- **Middle Man / needless indirection.** No support class, wrapper, or dispatch layer with a single consumer — inline it until at least two real consumers exist; a test is never the second consumer. A flow's logic should be followable inside one class; if understanding it requires hopping between classes, the indirection is the smell. Same rule for constants: used in one class → declared in that class; shared catalogs only for genuine cross-class or cross-boundary contracts.
- **Feature Envy.** A method that mostly reads and combines another class's data belongs on that class. If a handler keeps reaching into an entity to make a decision the entity could make, move the decision.
- **Inappropriate Intimacy.** Don't reach through another class's internals (its repository, its private collaborators) — go through its interface. Crossing the `api`/`internal` boundary from another module is this smell by definition.
- **Message Chains.** `a.getB().getC().getD()` couples the caller to the whole path. Ask the nearest object for what you actually need.

**Change preventers — structure that makes edits expensive**

- **Shotgun Surgery.** When one logical change forces edits in many files (a wire code, a route, a field name), the knowledge is scattered — give it a single home first, then change it.
- **Divergent Change.** When one class keeps changing for unrelated reasons, it has multiple responsibilities — split along the reasons it changes.
- **Repeated Switches.** The same `switch`/`if-else` chain over the same discriminator in more than one place means polymorphism or a map is missing. One occurrence is fine; the second copy is the smell.

**Object-orientation abusers**

- **Alternative Classes with Different Interfaces.** Two classes doing the same job must share a shape: same method names, same parameter order.
- **Refused Bequest.** Don't extend a base class to use one method while ignoring or overriding-to-nothing the rest — compose instead.
- **Temporary Field.** A field only meaningful during one operation is a missing parameter or a missing small object, not state.
- **Data Class with leaked logic.** Entities/DTOs stay dumb (this codebase's convention), but the logic operating on them must then live in ONE service — not spread across every caller.

## Tests serve the code — they never shape it

The smells catalog binds with no testability exception: never extract, export, widen
visibility, or add a parameter/seam whose only consumer is a test. A test is never the
second consumer under the Rule of Three. Test through the interface a real caller uses
and assert on outputs the code already produces (the file written, the response returned,
the process killed); reach edge branches by controlling real inputs, not by opening
internals. Prefer one behavioral test with real infrastructure (processes, temp dirs,
containers) — gated to skip when the environment lacks it — over unit tests that each
cost a structural concession. Behavior unobservable through any public interface is a
production API gap: raise it, don't add a test-only door.

The same preference governs test level: an e2e/integration test against real
collaborators beats a unit test that must fake or mock significant functionality — the
faked collaborator is where the bugs live (wire dispatch, deserialization, persistence
hooks), and a slower test that exercises them is worth more than a fast one that assumes
them. The exception is logic with no external collaborators (parsing, guards, pure
transforms): there a unit test localizes failures earlier and more precisely than any
e2e test can, and needs no fakes to begin with.
