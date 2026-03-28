const createBuffer = (size: number) => {
  const buffer = new Uint32Array(size);
  for (let index = 0; index < buffer.length; index += 1) {
    buffer[index] = (index * 2654435761) >>> 0;
  }
  return buffer;
};

const runComputeBenchmark = () => {
  const buffer = createBuffer(96_000);
  let checksum = 0;
  let operations = 0;
  const startedAt = performance.now();

  while (performance.now() - startedAt < 110) {
    for (let index = 0; index < buffer.length; index += 1) {
      const value = buffer[index];
      checksum = (checksum + ((value ^ (index * 33)) * 17)) >>> 0;
      operations += 1;
    }
  }

  const durationMs = performance.now() - startedAt;
  return {
    durationMs,
    throughput: operations / Math.max(durationMs, 1)
  };
};

const runMemoryBenchmark = () => {
  const source = createBuffer(256_000);
  const target = new Uint32Array(source.length);
  let bytesMoved = 0;
  const startedAt = performance.now();

  while (performance.now() - startedAt < 70) {
    target.set(source);
    bytesMoved += source.byteLength;
    source[0] = (source[0] + target[target.length - 1]) >>> 0;
  }

  const durationMs = performance.now() - startedAt;
  return {
    durationMs,
    throughput: bytesMoved / 1_000_000 / Math.max(durationMs, 1)
  };
};

self.addEventListener("message", () => {
  const benchStartedAt = performance.now();
  const compute = runComputeBenchmark();
  const memory = runMemoryBenchmark();

  self.postMessage({
    computeThroughput: compute.throughput,
    memoryThroughput: memory.throughput,
    durationMs: performance.now() - benchStartedAt
  });
});

