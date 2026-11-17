// k6 load test: realistic mix of wallet reads, P2P transfers and merchant payments.
// Run: see README "Load test". BASE_URL defaults to the API on the Docker host.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const USERS = Number(__ENV.USERS || 50);
const RATE = Number(__ENV.RATE || 300); // requests per second
const DURATION = __ENV.DURATION || '60s';

export const options = {
  setupTimeout: '120s',
  scenarios: {
    mixed: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 100,
      maxVUs: 400,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{op:transfer}': ['p(95)<250'],
    'http_req_duration{op:payment}': ['p(95)<250'],
    'http_req_duration{op:balance}': ['p(95)<250'],
  },
};

const json = { 'Content-Type': 'application/json' };

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export function setup() {
  const run = Date.now();
  const users = [];
  for (let i = 0; i < USERS; i++) {
    const reg = http.post(`${BASE}/auth/register`,
      JSON.stringify({ email: `load${run}-${i}@k6.test`, password: 'load-test-password' }), { headers: json });
    const token = reg.json('accessToken');
    const auth = { ...json, Authorization: `Bearer ${token}` };
    const wallet = http.post(`${BASE}/wallets`, null, { headers: auth }).json('walletId');
    http.post(`${BASE}/wallets/me/topups`, JSON.stringify({ amountSen: 500000 }),
      { headers: { ...auth, 'Idempotency-Key': uuid() } });
    users.push({ token, wallet });
  }
  const m = http.post(`${BASE}/auth/register/merchant`, JSON.stringify({
    email: `shop${run}@k6.test`, password: 'load-test-password', name: 'Kedai k6',
  }), { headers: json });
  return { users, merchantId: m.json('merchant.merchantId') };
}

export default function (data) {
  const n = data.users.length;
  const i = exec.scenario.iterationInTest % n;
  const me = data.users[i];
  const auth = { ...json, Authorization: `Bearer ${me.token}` };
  const roll = Math.random();

  if (roll < 0.3) {
    const r = http.get(`${BASE}/wallets/me`, { headers: auth, tags: { op: 'balance' } });
    check(r, { 'balance 200': (x) => x.status === 200 });
  } else if (roll < 0.9) {
    const to = data.users[(i + 1 + Math.floor(Math.random() * (n - 1))) % n].wallet;
    const r = http.post(`${BASE}/transfers`, JSON.stringify({ toWalletId: to, amountSen: 100 }),
      { headers: { ...auth, 'Idempotency-Key': uuid() }, tags: { op: 'transfer' } });
    check(r, { 'transfer 201': (x) => x.status === 201 });
  } else {
    const r = http.post(`${BASE}/payments`, JSON.stringify({ merchantId: data.merchantId, amountSen: 250 }),
      { headers: { ...auth, 'Idempotency-Key': uuid() }, tags: { op: 'payment' } });
    check(r, { 'payment 201': (x) => x.status === 201 });
  }
}
