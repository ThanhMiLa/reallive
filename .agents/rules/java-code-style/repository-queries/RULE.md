# Repository Queries

Apply when creating, editing, or reviewing JPQL or native SQL queries in Java repositories.

- Write all JPQL and native SQL keywords in uppercase, including queries in `@Query`, `@NamedQuery`, `@NamedNativeQuery`, and custom repository implementations.
- Preserve entity/property/table/column names, aliases, parameters, string literals, and query behavior when formatting.
- Put each main clause and each `JOIN` on its own line. For long projections or assignments, use one item per continuation line.
- Put additional `AND`/`OR` conditions on consistently indented lines for multi-condition expressions; simple short conditions may stay together.
- Indent query text blocks consistently. Do not insert line breaks inside literals or expressions that change semantics.

```java
@Query("""
        SELECT s
        FROM StreamSessionEntity s
        WHERE s.ownerUserId = :ownerUserId
          AND s.endedAt IS NULL
        ORDER BY s.startedAt DESC
        """)
List<StreamSessionEntity> findActiveSessionsByOwnerUserId(UUID ownerUserId);
```
