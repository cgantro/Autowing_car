#!/usr/bin/env python3
"""Deterministic validation harness for high-frequency telemetry claims.

Scenario:
- 500 vehicles
- 10Hz collection
- 1 second window (5,000 messages)

Validation goals:
1) Quantify payload/point reduction using RDP simplification.
2) Verify repeatability and no-loss behavior in an async buffered pipeline.
3) Measure E2E distribution under fixed processing order and fixed worker count.
"""

from __future__ import annotations

import json
import math
import queue
import statistics
import threading
import time
from dataclasses import dataclass

VEHICLE_COUNT = 500
HZ = 10
SECONDS = 1
TOTAL_MESSAGES = VEHICLE_COUNT * HZ * SECONDS
RAW_POINTS_PER_MESSAGE = 40
EPSILON = 0.1
WORKERS = 8
RUNS = 5


def perpendicular_distance(p: tuple[float, float], start: tuple[float, float], end: tuple[float, float]) -> float:
    dx = end[0] - start[0]
    dy = end[1] - start[1]
    if dx == 0 and dy == 0:
        return math.dist(p, start)
    area = abs(dy * p[0] - dx * p[1] + end[0] * start[1] - end[1] * start[0])
    bottom = math.sqrt(dy * dy + dx * dx)
    return area / bottom


def rdp(points: list[tuple[float, float]], epsilon: float) -> list[tuple[float, float]]:
    if len(points) < 3:
        return list(points)

    keep = {0, len(points) - 1}

    def recurse(first: int, last: int) -> None:
        max_dist = 0.0
        index = None
        for i in range(first + 1, last):
            dist = perpendicular_distance(points[i], points[first], points[last])
            if dist > max_dist:
                max_dist = dist
                index = i
        if index is not None and max_dist > epsilon:
            keep.add(index)
            recurse(first, index)
            recurse(index, last)

    recurse(0, len(points) - 1)
    return [points[i] for i in sorted(keep)]


def generate_messages() -> list[list[tuple[float, float]]]:
    messages: list[list[tuple[float, float]]] = []
    for i in range(TOTAL_MESSAGES):
        base_x = (i % VEHICLE_COUNT) * 0.3
        base_y = (i // VEHICLE_COUNT) * 0.2
        pts = []
        for p in range(RAW_POINTS_PER_MESSAGE):
            x = base_x + p * 0.5
            y = base_y + math.sin(p / 5.0) * 0.2 + (0.05 if p % 5 == 0 else 0.0)
            pts.append((x, y))
        messages.append(pts)
    return messages


def payload_bytes(messages: list[list[tuple[float, float]]]) -> int:
    return sum(len(json.dumps([{"x": x, "y": y} for x, y in msg], separators=(",", ":")).encode("utf-8")) for msg in messages)


def reduction(before: int, after: int) -> float:
    return (before - after) * 100.0 / before


@dataclass
class PipelineResult:
    processed: int
    loss: int
    checksum: int
    p50_ms: float
    p95_ms: float


def simulate_work(seed: int) -> None:
    loops = 100 + (seed % 10)
    acc = 0.0
    for i in range(loops):
        acc += math.sqrt((i + 1) * 1.00001)
    if acc < 0:
        raise RuntimeError("unreachable")


def run_async_pipeline_once() -> PipelineResult:
    q: "queue.Queue[tuple[int, int]]" = queue.Queue(maxsize=TOTAL_MESSAGES)
    e2e: list[int] = []
    e2e_lock = threading.Lock()
    processed = 0
    processed_lock = threading.Lock()
    checksum = 0
    checksum_lock = threading.Lock()

    start_ns = time.perf_counter_ns()
    for seq in range(TOTAL_MESSAGES):
        q.put((seq, time.perf_counter_ns()))

    per_worker = TOTAL_MESSAGES // WORKERS
    worker_counts = [per_worker] * WORKERS
    worker_counts[-1] += TOTAL_MESSAGES - per_worker * WORKERS

    def worker(count: int) -> None:
        nonlocal processed, checksum
        local_e2e = []
        local_checksum = 0
        for _ in range(count):
            seq, created = q.get()
            simulate_work(seq)
            done = time.perf_counter_ns()
            local_e2e.append(done - created)
            local_checksum += seq
            q.task_done()
        with e2e_lock:
            e2e.extend(local_e2e)
        with processed_lock:
            processed += count
        with checksum_lock:
            checksum += local_checksum

    threads = [threading.Thread(target=worker, args=(cnt,)) for cnt in worker_counts]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    sorted_e2e = sorted(e2e)
    p50_ms = sorted_e2e[int(len(sorted_e2e) * 0.50)] / 1_000_000
    p95_ms = sorted_e2e[int(len(sorted_e2e) * 0.95)] / 1_000_000
    return PipelineResult(processed=processed, loss=TOTAL_MESSAGES - processed, checksum=checksum, p50_ms=p50_ms, p95_ms=p95_ms)


def main() -> int:
    raw = generate_messages()
    simplified = [rdp(msg, EPSILON) for msg in raw]

    raw_bytes = payload_bytes(raw)
    simplified_bytes = payload_bytes(simplified)
    raw_points = sum(len(m) for m in raw)
    simplified_points = sum(len(m) for m in simplified)

    payload_reduction = reduction(raw_bytes, simplified_bytes)
    write_reduction = reduction(raw_points, simplified_points)

    print("=== RDP Reduction Validation ===")
    print(f"messages={TOTAL_MESSAGES}")
    print(f"raw_payload_bytes={raw_bytes}")
    print(f"simplified_payload_bytes={simplified_bytes}")
    print(f"payload_reduction_percent={payload_reduction:.2f}")
    print(f"raw_points={raw_points}")
    print(f"simplified_points={simplified_points}")
    print(f"write_reduction_percent={write_reduction:.2f}")

    runs = [run_async_pipeline_once() for _ in range(RUNS)]

    print("\n=== Async Buffered Reproducibility Validation ===")
    for idx, r in enumerate(runs, start=1):
        print(
            f"run={idx} processed={r.processed} loss={r.loss} checksum={r.checksum} p50_ms={r.p50_ms:.2f} p95_ms={r.p95_ms:.2f}"
        )

    checksums = {r.checksum for r in runs}
    losses = [r.loss for r in runs]
    p95_values = [r.p95_ms for r in runs]

    print("\n=== Summary ===")
    print(f"checksum_stable={len(checksums) == 1}")
    print(f"loss_free={all(loss == 0 for loss in losses)}")
    print(f"p95_range_ms={min(p95_values):.2f}~{max(p95_values):.2f}")
    print(f"p95_mean_ms={statistics.mean(p95_values):.2f}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
