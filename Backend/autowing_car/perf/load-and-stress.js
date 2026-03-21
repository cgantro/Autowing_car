import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const authFailureRate = new Rate('auth_failure_rate');
const mapLatency = new Trend('map_latency');
const missionLatency = new Trend('mission_latency');
const flightLatency = new Trend('flight_latency');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PILOT_TOKEN = __ENV.PILOT_TOKEN || '';
const MAP_ID = __ENV.MAP_ID || 'final_map';

export const options = {
  scenarios: {
    smoke_map: {
      executor: 'constant-vus',
      exec: 'smokeMapScenario',
      vus: Number(__ENV.SMOKE_VUS || 5),
      duration: __ENV.SMOKE_DURATION || '1m',
      tags: { stage: 'smoke' },
    },
    mixed_load: {
      executor: 'ramping-vus',
      exec: 'mixedLoadScenario',
      stages: [
        { duration: '2m', target: Number(__ENV.RAMP_UP_VUS || 20) },
        { duration: '3m', target: Number(__ENV.SUSTAIN_VUS || 50) },
        { duration: '2m', target: 0 },
      ],
      tags: { stage: 'load' },
      gracefulRampDown: '30s',
    },
    stress_load: {
      executor: 'ramping-arrival-rate',
      exec: 'stressScenario',
      preAllocatedVUs: Number(__ENV.PREALLOCATED_VUS || 50),
      maxVUs: Number(__ENV.MAX_VUS || 300),
      stages: [
        { duration: '1m', target: Number(__ENV.STRESS_RPS_1 || 30) },
        { duration: '2m', target: Number(__ENV.STRESS_RPS_2 || 80) },
        { duration: '2m', target: Number(__ENV.STRESS_RPS_3 || 120) },
        { duration: '1m', target: 0 },
      ],
      timeUnit: '1s',
      tags: { stage: 'stress' },
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<800', 'p(99)<1500'],
    map_latency: ['p(95)<500'],
    mission_latency: ['p(95)<700'],
    flight_latency: ['p(95)<700'],
    auth_failure_rate: ['rate<0.02'],
  },
};

function requestMap() {
  const res = http.get(`${BASE_URL}/api/map/info?mapId=${encodeURIComponent(MAP_ID)}`);
  mapLatency.add(res.timings.duration);
  check(res, {
    'map status is 200': (r) => r.status === 200,
    'map contains nodes': (r) => {
      try {
        return Array.isArray(r.json('nodes'));
      } catch (e) {
        return false;
      }
    },
  });
  return res;
}

function authHeaders() {
  return PILOT_TOKEN
    ? {
        headers: {
          Authorization: `Bearer ${PILOT_TOKEN}`,
        },
      }
    : {};
}

function requestMission() {
  const res = http.get(`${BASE_URL}/api/mission`, authHeaders());
  missionLatency.add(res.timings.duration);
  authFailureRate.add(res.status === 401 || res.status === 403);
  check(res, {
    'mission status acceptable': (r) => [200, 401, 403].includes(r.status),
  });
  return res;
}

function requestFlightInfo() {
  const res = http.get(`${BASE_URL}/api/flight/info`, authHeaders());
  flightLatency.add(res.timings.duration);
  authFailureRate.add(res.status === 401 || res.status === 403);
  check(res, {
    'flight status acceptable': (r) => [200, 401, 403].includes(r.status),
  });
  return res;
}

export function smokeMapScenario() {
  requestMap();
  sleep(1);
}

export function mixedLoadScenario() {
  requestMap();
  requestMission();
  requestFlightInfo();
  sleep(Math.random() * 2);
}

export function stressScenario() {
  const selector = __ITER % 3;
  if (selector === 0) {
    requestMap();
  } else if (selector === 1) {
    requestMission();
  } else {
    requestFlightInfo();
  }
}
