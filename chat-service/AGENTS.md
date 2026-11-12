# Chat Service Conventions

Follow the repository root guidelines and these TypeScript spacing conventions:

- Separate adjacent class members with exactly one blank line, including fields, constructors, methods and accessors.
- Separate adjacent interface properties or method signatures with exactly one blank line.
- Separate top-level functions and other non-import declarations with one blank line. Keep related imports together.
- Put each class-member decorator on its own line directly above the member it decorates. Keep documentation and decorators attached to their declaration.
- Do not insert blank lines between constructor parameters, function parameters, import specifiers or ordinary object properties merely to satisfy member spacing.
- Use two-space indentation and the existing Prettier settings. Preserve member spacing when running Prettier.

## Layer responsibilities

- Keep MongoDB queries, writes and transaction execution in repositories. Repositories return persistence data and must not depend on response DTOs, mappers or application error codes.
- Keep authorization, request validation, pagination policy and idempotency decisions in services. Perform idempotency lookup, counter increment and insert in one transaction; broadcast only after commit.
- Keep document/input/response transformations in mappers. Mappers do not query databases or decide access permissions.
- Gateways and controllers call services rather than repositories. Database readiness validation belongs to its dedicated service.
- The private Stream `chat-context`, Channel `chat-access` and User `chat-profile` endpoints use `permitAll()`. Call them without an internal token or user credentials; access relies on the private service network. Keep user JWT/Redis validation for external chat operations.
