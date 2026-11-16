# Lombok

Apply when creating, editing, or reviewing Java Spring beans, entities, and DTO classes covered by these Lombok conventions.

## Spring Beans

- Prefer `@RequiredArgsConstructor` with `@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)` for controllers, service implementations, and similar Spring beans.
- Declare injected dependency fields using only their type and name. Let Lombok generate `private final` fields and the constructor; do not add `@Autowired` when it is the only constructor.
- Use an explicit constructor when initialization or injection annotations cannot be expressed correctly with this setup.
- For Spring `@Value` field injection under `makeFinal = true`, add `lombok.experimental.NonFinal` so the field stays out of the required-arguments constructor. Import Spring's `org.springframework.beans.factory.annotation.Value`.
- `@NotNull` is a validation constraint, not a replacement for `@NonFinal`; Bean Validation must actually be invoked. Do not add Lombok `@NonNull` to an uninitialized `@Value` field because it makes the field a required constructor argument.

```java
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class StreamServiceImpl implements StreamService {

    StreamRepository streamRepository;

    @NonFinal
    @Value("${stream.playback-base-url}")
    String playbackBaseUrl;
}
```

## Entities and DTO Classes

- For entity and DTO classes, prefer `@Data`, `@Builder`, `@AllArgsConstructor`, `@NoArgsConstructor`, and `@FieldDefaults(level = AccessLevel.PRIVATE)`.
- Declare fields using only their type and name plus persistence/serialization/validation annotations. Keep them mutable by default; do not add `makeFinal = true` to this pattern.
- For entities with relationships or special identity rules, customize `toString`, `equals`, and `hashCode` to preserve correct behavior.
- Preserve the existing class-versus-record choice during unrelated changes; the [record formatting rule](../records/RULE.md) does not require converting DTO classes to records.
