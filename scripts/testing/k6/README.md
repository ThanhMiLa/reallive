# Register users and follow a channel

Run from the repository root with k6 installed:

```bash
k6 run scripts/testing/k6/register-and-follow.js
```

The script first registers 100 new users sequentially in `setup()`. Only after
all registrations succeed does it send all 100 follow requests in one parallel
`http.batch()` call. Each registered user follows the target channel exactly once.
Registration returns the access token through `Set-Cookie`, not the JSON body.
Each follow request uses its user's token in an isolated cookie jar.

Defaults:

| Environment variable | Default |
| --- | --- |
| `BASE_URL` | `http://localhost:8888` |
| `CHANNEL_ID` | `239c8ef3-47e4-457b-a7b0-797fa6ae8f6d` |
| `USER_COUNT` | `100` |
| `ACCESS_TOKEN_COOKIE_NAME` | `access_token` |
| `TEST_PASSWORD` | Generated username for each user |
| `EMAIL_DOMAIN` | `example.com` |

One VU executes one iteration containing the whole batch. `batch` and
`batchPerHost` both equal `USER_COUNT`, allowing all 100 follow requests to be
in flight together. The summary therefore shows **1 VU and 1 iteration**, while
HTTP totals remain **200 requests and 400 checks**. The former `VUS` environment
variable is no longer used. Client scheduling, TCP connections and the network
can still cause small differences in request start and arrival times; this does
not guarantee all requests reach the server at the exact same instant.

See the [k6 batch documentation](https://grafana.com/docs/k6/latest/javascript-api/k6-http/batch/)
and [batch limits](https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/#batch-per-host).

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
