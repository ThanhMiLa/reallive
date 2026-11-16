# Security Endpoint Constants

Apply this rule when adding or changing endpoint access configuration in Java services and the API gateway.

- Declare every endpoint allowed without user authentication in a named `private static final` constant. Do not write endpoint path literals directly inside security matchers or authentication bypass conditions.
- Group endpoints by audience and HTTP method. Use `PUBLIC_ENDPOINTS_<METHOD>` for client-facing endpoints that do not require user authentication and `INTERNAL_ENDPOINTS_<METHOD>` for service-to-service endpoints. Examples: `PUBLIC_ENDPOINTS_GET`, `PUBLIC_ENDPOINTS_POST`, `INTERNAL_ENDPOINTS_GET`, and `INTERNAL_ENDPOINTS_PATCH`.
- Keep public and internal endpoints in separate constants, even when both use `permitAll()`. Keep GET, POST, PUT, PATCH, DELETE, HEAD, and OPTIONS separate as needed; create only the groups used by the configuration.
- Use `String[]` for Spring MVC matcher groups and the existing collection type where another matcher requires it. Keep constants together near the top of the owning security configuration or authentication filter.
- Match each group with its explicit `HttpMethod`. Do not broaden access to every method when permitting one method.
- Reuse the same internal constants when selecting an internal filter chain and declaring its permitted endpoints. A narrowly scoped internal chain may use `anyRequest().permitAll()` after its method-and-path matchers select the chain.
- Keep the service's default authentication requirement for all other requests. At the gateway, preserve authentication in its existing authentication filter; the outer filter chain's `permitAll()` does not make every routed endpoint public.
- Framework rules such as error dispatch handling and CORS preflight handling are not endpoint path lists. Keep those rules separate from endpoint constants. A CSRF exemption also does not make an endpoint public.
- Use paths relative to the service's servlet context in service security configuration and routed paths in the gateway. Do not copy gateway prefixes into service matchers.
- Follow [Internal Service Access](../internal-service-access/RULE.md) for internal authentication, gateway routing, and network boundaries. Refactoring constants must preserve existing authorization and provider webhook verification.
- Run the affected security and endpoint regression tests after changing the configuration. Add behavior tests when access rules change; extracting unchanged paths into constants does not require tests that merely assert constant contents.

Example:

```java
private static final String[] PUBLIC_ENDPOINTS_POST = {
        "/api/auth/login",
        "/api/auth/register"
};

private static final String[] INTERNAL_ENDPOINTS_PATCH = {
        "/internal/api/users/*/channel-profile"
};
```

Reference internal constants with their HTTP method when selecting the internal filter chain:

```java
http.securityMatchers(matchers -> matchers
        .requestMatchers(HttpMethod.PATCH, INTERNAL_ENDPOINTS_PATCH));
```

Reference public constants in the separate authenticated filter chain:

```java
http.authorizeHttpRequests(auth -> auth
        .requestMatchers(HttpMethod.POST, PUBLIC_ENDPOINTS_POST).permitAll()
        .anyRequest().authenticated());
```
