# RealLive

## Continuous integration

Separate workflows build and run all tests on every push, pull request and manual Actions run:

- [API Gateway tests](.github/workflows/api-gateway-tests.yml)
- [User Service tests](.github/workflows/user-service-tests.yml)
- [Notification Service tests](.github/workflows/notification-service-tests.yml)

These are the modules with implemented business functionality; the other services currently contain application scaffolds. Add a workflow when another service is implemented.

Each module runs independently on Java 21 using `./mvnw clean verify`. Each workflow creates only the isolated Redis/PostgreSQL service containers its tests need, with temporary test credentials; it does not need application `.env` files or SendGrid/R2 credentials. Redis integration tests are enabled instead of skipped, and database tests that support PostgreSQL use the test container. Kafka integration tests start their embedded brokers and SendGrid HTTP calls use local test stubs. User Service additionally enforces 100% line and branch coverage for the Become Streamer controller and service. Test/coverage reports are uploaded even when a build fails and retained for seven days.

This is CI (automated integration checks). CD (automatic deployment) can be added when deployment is configured.

A failed workflow marks the pushed commit with a failing check; the push has already happened and is not rolled back. To protect `main`, configure a GitHub branch ruleset or branch protection rule requiring pull requests and these status checks before merging:

- `Test api-gateway`
- `Test user-service`
- `Test notification-service`

Configure the rule after the first workflow run so the checks are available for selection. Restrict bypass permissions if the rule must also apply to administrators. Repository protection is a GitHub setting; the workflow file alone does not configure it. See [GitHub protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches).
