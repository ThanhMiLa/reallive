# Repository Guidelines

## Instruction Routing

Paths in this file are relative to the repository root. Before changing a module, read its local `AGENTS.md` when present, including when working from the repository root. Apply module-specific instructions within that module.

Read every rule matching the requested work before implementing or reviewing it; multiple rows can apply. For Java topic rules, also read the parent Java code style rule. Follow required links inside the selected rules, but do not load unrelated rules or whole reference directories. Keep the detailed conventions in their owning files.

| When the work involves | Read |
| --- | --- |
| Writing, editing, or reviewing Java source or tests | [Java code style](.agents/rules/java-code-style/RULE.md) |
| Java Spring beans, entity classes, or DTO classes | [Lombok conventions](.agents/rules/java-code-style/lombok/RULE.md) |
| Java record declarations | [Record formatting](.agents/rules/java-code-style/records/RULE.md) |
| JPQL or native SQL repository queries | [Repository query formatting](.agents/rules/java-code-style/repository-queries/RULE.md) |
| Interfaces in Java `service` packages or their subpackages | [Service interface Javadoc](.agents/rules/java-code-style/service-javadoc/RULE.md) |
| Placing Java code, adding integrations, or refactoring/reviewing packages | [Java architecture](.agents/rules/java-architecture/RULE.md) |
| Adding, refactoring, or reviewing Java application error handling | [Java exception handling](.agents/rules/java-exception-handling/RULE.md) |
| Creating or renaming Feign interfaces and wrapper classes | [Feign client naming](.agents/rules/feign-client/RULE.md) |
| Adding or changing endpoint access configuration in Java services or the gateway | [Security endpoint constants](.agents/rules/security-endpoint-constants/RULE.md) |
| Implementing or changing gateway routes, internal endpoints, or service-to-service calls | [Internal service access](.agents/rules/internal-service-access/RULE.md) |

## Task Skills

Read [commit](.agents/skills/commit/SKILL.md) when asked to prepare, organize, write, or create Git commits. Follow its workflow alongside the rules for the affected code. A request for a commit message alone does not request creating a commit.

## Project Structure & Module Organization

This monorepo contains independently built applications:

- `api-gateway/`, `user-service/`, `channel-service/`, `stream-service/`, `vod-service/`, `payment-service/`, and `notification-service/`: Java 21 Spring Boot services. Code lives in `src/main/java`, configuration in `src/main/resources`, and tests in `src/test/java`.
- `chat-service/`: NestJS and TypeScript; implementation and unit tests are in `src/`, with end-to-end tests in `test/`. Follow [Chat service conventions](chat-service/AGENTS.md).
- `media-worker/`: NestJS and TypeScript thumbnail worker; implementation and unit tests are in `src/`, with HTTP contract tests in `test/`.
- `media-server/`: MediaMTX media server container configuration and webhook scripts.
- `web-app/`: React, TypeScript, and Vite; code and imported assets are in `src/`, static assets in `public/`. Follow [Web app conventions](web-app/AGENTS.md) for UI work.
- `mobile-app/`: Kotlin and Jetpack Compose Android app; code and resources are under `app/src/main/`, with `test/` and `androidTest/` alongside it.
- `docs/`: product requirements, architecture, and database design.

## Design Documentation

Read the documents relevant to the feature before implementing or changing it. Consult contracts when changing APIs, events, or service integrations; read diagrams and their source when the architecture change requires them.

- [Product overview](docs/product/Overview.md): project goals, scope, and technology overview.
- [Features](docs/product/Features.md): product features and requirements.
- [Actor roles](docs/product/Actor_Roles.md): user roles and responsibilities.
- [System architecture](<docs/architecture/System Architecture.md>): service responsibilities and technology choices.
- [Architecture diagram](<docs/architecture/image/Architecture Design.png>): visual overview of the system.
- [Editable architecture diagram](<docs/architecture/image/System Architecture Design.drawio>): source diagram for architecture changes.
- [Data model](<docs/data-model/Data Model.md>): service data models, tables, collections, and relationships.
- [Service contracts](docs/contracts/README.md): API and event contracts, compatibility, and shared fixtures.

## Build, Test, and Development Commands

Run commands inside the relevant module; there is no root build orchestrator.

- Java services: `./mvnw clean verify` builds and tests; `./mvnw spring-boot:run` starts the service.
- Node applications: `npm ci` installs dependencies from the lockfile.
- Web: `npm run dev` starts Vite; `npm run build` checks TypeScript and builds; `npm run lint` runs ESLint.
- Chat: `npm run start:dev` starts watch mode; `npm run build` compiles; `npm run lint` runs Oxlint; `npm run format` applies Prettier.
- Media worker: `npm run start:dev` starts watch mode; `npm run build` compiles; `npm run lint` runs Oxlint; `npm test` runs unit tests; `npm run test:e2e` runs HTTP contract tests. Build its container with `docker build -t reallive-media-worker ./media-worker`.
- Android: `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` builds, tests, and lints. Run `:app:connectedDebugAndroidTest` with a device or emulator.

## Coding Style & Naming Conventions

Match existing files: four-space indentation for Java/Kotlin and two spaces for TypeScript. Use PascalCase for classes and React components, camelCase for methods and variables, and NestJS filenames such as `app.service.ts`. Chat uses single quotes and trailing commas through Prettier. Preserve the web app's existing semicolon style. Use the rules above for detailed Java conventions and keep changes within the requested scope.

## Testing Guidelines

Java uses JUnit Jupiter/Spring Boot tests named `*Test.java` or `*Tests.java`. Chat and Media worker use Jest: `npm test`, `npm run test:e2e`, and `npm run test:cov`; name tests `*.spec.ts` and `*.e2e-spec.ts`. Android uses JUnit 4 and AndroidX instrumentation. See the Web app conventions for its Node and Vitest suites. Add relevant regression tests for behavior changes.

Run the checks relevant to the affected modules and behavior. For documentation-only changes, verify affected links and run `git diff --check`. Report the checks actually run and any failures or checks that could not be completed.

## Commit & Pull Request Guidelines

Use Conventional Commits, matching history: `feat: ...`, `fix: ...`, or `docs(database): ...`. Keep commits focused, subjects concise, and staged files specific; use the commit skill above for the workflow. PRs should describe affected modules, behavior changes, validation, and related issues; include screenshots for UI changes.

## Security & Configuration

Keep credentials in environment variables and commit only sanitized configuration examples. Exclude `.env`, `local.properties`, dependencies, and build outputs. Keep applications as ordinary directories in this repository; do not initialize nested Git repositories.
