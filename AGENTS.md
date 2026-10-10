# Repository Guidelines

## Project Structure & Module Organization

This monorepo contains independently built applications:
- `api-gateway/`, `user-service/`, `channel-service/`, `stream-service/`, `vod-service/`, `payment-service/`, and `notification-service/`: Java 21 Spring Boot services. Code lives in `src/main/java`, configuration in `src/main/resources`, and tests in `src/test/java`.
- `chat-service/`: NestJS and TypeScript; implementation and unit tests are in `src/`, with end-to-end tests in `test/`.
- `web-app/`: React, TypeScript, and Vite; code and imported assets are in `src/`, static assets in `public/`.
- `mobile-app/`: Kotlin and Jetpack Compose Android app; code and resources are under `app/src/main/`, with `test/` and `androidTest/` alongside it.
- `docs/`: product requirements, architecture, and database design.

## Design Documentation

Read the relevant design documents before implementing or changing a feature. All paths below are relative to the repository root.

- [Product overview](docs/product/Overview.md): project goals, scope, and technology overview.
- [Features](docs/product/Features.md): product features and requirements.
- [Actor roles](docs/product/Actor_Roles.md): user roles and responsibilities.
- [System architecture](<docs/architecture/System Architecture.md>): service responsibilities and technology choices.
- [Architecture diagram](<docs/architecture/Architecture Design.png>): visual overview of the system.
- [Editable architecture diagram](<docs/architecture/System Architecture Design.drawio>): source diagram for architecture changes.
- [Data model](<docs/database/Data Model.md>): service data models, tables, collections, and relationships.

## Architecture Guidelines

Organize Java services by business module, with a service-local `common` package for shared code. Follow [.agents/skills/code-architecture/SKILL.md](.agents/skills/code-architecture/SKILL.md) when placing new code, adding integrations, refactoring packages, or reviewing code architecture. Use `adapter` for infrastructure and external-provider integrations, and `client` for API calls to other application microservices.

## Build, Test, and Development Commands

Run commands inside the relevant module; there is no root build orchestrator.
- Java services: `./mvnw clean verify` builds and tests; `./mvnw spring-boot:run` starts the service.
- Node applications: `npm ci` installs dependencies from the lockfile.
- Web: `npm run dev` starts Vite; `npm run build` checks TypeScript and builds; `npm run lint` runs ESLint.
- Chat: `npm run start:dev` starts watch mode; `npm run build` compiles; `npm run lint` runs Oxlint; `npm run format` applies Prettier.
- Android: `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` builds, tests, and lints. Run `:app:connectedDebugAndroidTest` with a device or emulator.

## Coding Style & Naming Conventions

Match existing files: four-space indentation for Java/Kotlin and two spaces for TypeScript. Use PascalCase for classes and React components, camelCase for methods and variables, and NestJS filenames such as `app.service.ts`. Chat uses single quotes and trailing commas through Prettier. Preserve the web app's existing semicolon style. Follow `.agents/skills/code-style/SKILL.md` for Java organization, imports, and comments.

Every method declared in a Java service interface must have English Javadoc. Follow `.agents/skills/service-javadoc/SKILL.md` when creating, editing, or reviewing service interfaces.

Keep Java application error handling in each service's `common.exception` package. Use `AppException(ErrorCode)` and declare new errors in the `ErrorCode` enum instead of creating separate exception classes. Follow `.agents/skills/exception-handling/SKILL.md` when adding, refactoring, or reviewing exception handling.

## Testing Guidelines

Java uses JUnit Jupiter/Spring Boot tests named `*Test.java` or `*Tests.java`. Chat uses Jest: `npm test`, `npm run test:e2e`, and `npm run test:cov`; name tests `*.spec.ts` and `*.e2e-spec.ts`. Android uses JUnit 4 and AndroidX instrumentation. No coverage threshold or web test runner is currently configured. Add relevant regression tests for behavior changes.

## Commit & Pull Request Guidelines

Use Conventional Commits, matching history: `feat: ...`, `fix: ...`, or `docs(database): ...`. Keep commits focused and subjects concise; follow `.agents/skills/commits/SKILL.md`. Stage specific files. PRs should describe affected modules, behavior changes, validation, and related issues; include screenshots for UI changes.

## Security & Configuration

Keep credentials in environment variables and commit only sanitized configuration examples. Exclude `.env`, `local.properties`, dependencies, and build outputs. Keep applications as ordinary directories in this repository; do not initialize nested Git repositories.
