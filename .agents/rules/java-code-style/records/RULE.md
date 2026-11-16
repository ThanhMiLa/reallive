# Java Records

Apply when creating, editing, or reviewing Java record declarations.

- Put each component on its own line, indented eight spaces relative to the record declaration. Do not combine components on one line.
- Put the closing `)` on its own line, followed by ` {}` for an empty body or ` {` for a non-empty body.
- Keep validation/serialization annotations attached to their component, on preceding lines when useful. Do not combine multiple annotated components on one line.

```java
public record CreateSessionCommand(
        UUID channelId,
        UUID streamerId,
        String title
) {}
```
