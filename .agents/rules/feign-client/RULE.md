# Feign Client Naming

Apply this rule when creating or renaming a Java Feign client.

- Name the interface annotated with `@FeignClient` `<Target>Client`.
- Create a corresponding concrete wrapper class named `<Target>ApiClient` that receives and calls the Feign interface.

Example: `UserClient.java` is the `@FeignClient` interface; `UserApiClient.java` is its wrapper class.
