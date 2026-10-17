# Register users and follow a channel

Run from the repository root with k6 installed:

```bash
k6 run scripts/testing/k6/register-and-follow.js
```

The script first registers 100 new users sequentially in `setup()`. Only after
all registrations succeed does it send 100 follow requests using 10 concurrent
virtual users. Each registered user follows the target channel exactly once.
Registration returns the access token through `Set-Cookie`, not the JSON body.
Each follow request uses its user's token in an isolated cookie jar.

Defaults:

| Environment variable | Default |
| --- | --- |
| `BASE_URL` | `http://localhost:8888` |
| `CHANNEL_ID` | `239c8ef3-47e4-457b-a7b0-797fa6ae8f6d` |
| `USER_COUNT` | `100` |
| `VUS` | `10` (or `USER_COUNT` if smaller) |
| `ACCESS_TOKEN_COOKIE_NAME` | `access_token` |
| `TEST_PASSWORD` | Generated username for each user |
| `EMAIL_DOMAIN` | `example.com` |

For 100 virtual users, each making one follow request:

```bash
k6 run -e VUS=100 scripts/testing/k6/register-and-follow.js
```

If the application is configured to name its cookie `accessToken` instead:

```bash
k6 run -e ACCESS_TOKEN_COOKIE_NAME=accessToken scripts/testing/k6/register-and-follow.js
```

Usernames and emails contain a generated run ID to avoid duplicates across runs.
The target channel must exist and be active. The gateway, User Service,
Channel Service and their dependencies (including wallet creation during
registration) must be available.

Success requires `registered_users = 100`, `followed_users = 100`, all checks
passing and no failed HTTP requests. When overriding `USER_COUNT`, the expected
counts change accordingly. A registration error cancels the follow phase; a
failed follow makes the run fail its thresholds. Requests are not retried.

Created accounts and follow records remain in the application after execution,
including accounts created before a registration failure. Tokens are kept in
memory and are not printed or saved. The script does not wait for or trigger the
scheduled follower-count reconciliation job; `channels.follower_count` may
remain unchanged until reconciliation runs.
