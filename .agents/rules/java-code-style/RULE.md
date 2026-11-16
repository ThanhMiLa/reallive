# Java Code Style

Apply when writing, editing, or reviewing Java code. Follow the closest existing code in the backend module alongside these conventions.

## Organization, Naming, and Imports

- Use four-space indentation, lower camel case for variables/methods/fields, and PascalCase for classes/interfaces/enums/records/annotations.
- Keep each class focused on related responsibilities. Extract meaningful validation, transformation, or business steps when that clarifies a method; avoid trivial one-line wrappers created only to add methods.
- Use names that convey domain intent. Avoid vague names such as `data`, `temp`, or single-letter names except for conventional short-lived loop indexes.
- Use normal imports at the top of the file and simple type names in the body; use fully qualified names only when needed to resolve ambiguity. Remove unused imports and match the module's import ordering.

## Topic Rules

Read and follow each child rule matching the code being created, edited, or reviewed.

| Code affected | Required child rule |
| --- | --- |
| Spring beans, entities, or DTO classes | [Lombok](lombok/RULE.md) |
| Java record declarations | [Records](records/RULE.md) |
| JPQL or native SQL repository queries | [Repository queries](repository-queries/RULE.md) |
| Interfaces in `service` packages or their subpackages | [Service interface Javadoc](service-javadoc/RULE.md) |

## Comments and Service Documentation

- Every method declared in a Java interface in a `service` package or its subpackages must have English Javadoc, including default and static methods.
- Avoid comments that restate obvious code. For public APIs, complex methods, or non-obvious rules, document intent, constraints, and meaningful side effects directly above the declaration.
