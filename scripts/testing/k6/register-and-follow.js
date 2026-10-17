import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8888').replace(/\/+$/, '');
const channelId = __ENV.CHANNEL_ID || '239c8ef3-47e4-457b-a7b0-797fa6ae8f6d';
const cookieName = __ENV.ACCESS_TOKEN_COOKIE_NAME || 'access_token';
const userCount = positiveInteger('USER_COUNT', 100);
const vus = positiveInteger('VUS', Math.min(10, userCount));
const followUrl = `${baseUrl}/channel/api/channels/${channelId}/follow`;
const headers = { 'Content-Type': 'application/json', Accept: '*/*' };
const registeredUsers = new Counter('registered_users');
const followedUsers = new Counter('followed_users');

if (vus > userCount) {
  throw new Error('VUS must be less than or equal to USER_COUNT');
}

export const options = {
  setupTimeout: '10m',
  scenarios: {
    follow_channel: {
      executor: 'shared-iterations',
      vus,
      iterations: userCount,
      maxDuration: '5m',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    registered_users: [`count==${userCount}`],
    followed_users: [`count==${userCount}`],
    http_req_failed: ['rate==0'],
  },
};

export function setup() {
  const runId = `${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
  const users = [];

  for (let index = 1; index <= userCount; index += 1) {
    const username = `k6_${runId}_${index}`;
    const password = __ENV.TEST_PASSWORD || username;
    const response = http.post(
      `${baseUrl}/user/api/auth/register`,
      JSON.stringify({
        username,
        password,
        confirmPassword: password,
        email: `${username}@${__ENV.EMAIL_DOMAIN || 'example.com'}`,
      }),
      {
        headers,
        // A fresh jar prevents a previous user's cookies leaking into registration.
        jar: new http.CookieJar(),
        redirects: 0,
        timeout: '30s',
        tags: { name: 'POST /user/api/auth/register', step: 'register' },
      },
    );
    const body = jsonOrNull(response);
    const cookies = response.cookies[cookieName] || [];
    const accessToken = cookies.length === 1 ? cookies[0].value : '';
    const success = check(response, {
      'register: HTTP 200 and code 1000': (r) => r.status === 200 && body?.code === 1000,
      'register: access token cookie returned': () => Boolean(accessToken),
    });

    if (!success) {
      fail(`Registration failed for ${username}: HTTP ${response.status}, code ${body?.code ?? 'unknown'}. Follow phase cancelled.`);
    }

    registeredUsers.add(1);
    users.push({ username, accessToken });
  }

  console.log(`Registered ${users.length} users. Starting follow requests for channel ${channelId}.`);
  return users;
}

export default function (users) {
  // Global iteration IDs assign each registered user exactly one follow request.
  const user = users[exec.scenario.iterationInTest];
  if (!user) {
    fail('No registered user for this iteration');
  }

  const jar = new http.CookieJar();
  jar.set(followUrl, cookieName, user.accessToken, { path: '/' });
  const response = http.put(followUrl, '{}', {
    headers,
    jar,
    redirects: 0,
    timeout: '30s',
    tags: { name: 'PUT /channel/api/channels/:channelId/follow', step: 'follow' },
  });
  const body = jsonOrNull(response);
  const success = check(response, {
    'follow: HTTP 200 and code 1000': (r) => r.status === 200 && body?.code === 1000,
    'follow: new follower created': () => body?.result === true,
  });

  followedUsers.add(success ? 1 : 0);
  if (!success) {
    console.error(`Follow failed for ${user.username}: HTTP ${response.status}, code ${body?.code ?? 'unknown'}`);
  }
}

function positiveInteger(name, fallback) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new Error(`${name} must be a positive integer`);
  }
  return value;
}

function jsonOrNull(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}
