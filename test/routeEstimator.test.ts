import { afterEach, beforeEach, expect, test } from "bun:test";
import CommonFormats from "../src/CommonFormats.ts";
import { ConvertPathNode, type FormatHandler } from "../src/FormatHandler.ts";
import { buildExecutionPolicy, type DevicePerformanceProfile } from "../src/performanceProfile.ts";
import { estimateRoute, getRouteKey, recordRouteObservation } from "../src/routeEstimator.ts";

class MemoryStorage {
  private readonly store = new Map<string, string>();

  getItem(key: string) {
    return this.store.has(key) ? this.store.get(key)! : null;
  }

  setItem(key: string, value: string) {
    this.store.set(key, value);
  }

  removeItem(key: string) {
    this.store.delete(key);
  }

  clear() {
    this.store.clear();
  }
}

const originalNavigator = globalThis.navigator;
const originalLocalStorage = globalThis.localStorage;

const setNavigator = () => {
  Object.defineProperty(globalThis, "navigator", {
    configurable: true,
    value: {
      hardwareConcurrency: 8,
      deviceMemory: 8,
      platform: "TestOS",
      userAgent: "TestAgent/1.0"
    }
  });
};

const makeHandler = (
  name: string,
  performanceClass: "light" | "medium" | "heavy",
  batchStrategy: "whole-batch" | "per-file"
): FormatHandler => ({
  name,
  ready: true,
  performanceClass,
  batchStrategy,
  async init() {},
  async doConvert(files) {
    return files;
  }
});

const benchmarkProfile: DevicePerformanceProfile = {
  version: 2,
  signature: "test-device-signature",
  measuredAt: Date.now(),
  benchmarkDurationMs: 180,
  hardwareConcurrency: 8,
  deviceMemory: 8,
  computeThroughput: 110000,
  memoryThroughput: 1.15,
  computeScore: 1.2,
  memoryScore: 1.1,
  overallScore: 1.18,
  source: "benchmark",
  confidence: "high"
};

beforeEach(() => {
  setNavigator();
  Object.defineProperty(globalThis, "localStorage", {
    configurable: true,
    value: new MemoryStorage()
  });
});

afterEach(() => {
  Object.defineProperty(globalThis, "navigator", {
    configurable: true,
    value: originalNavigator
  });
  Object.defineProperty(globalThis, "localStorage", {
    configurable: true,
    value: originalLocalStorage
  });
});

test("estimateRoute reflects handler metadata and policy limits", () => {
  const ingest = makeHandler("ingest", "light", "whole-batch");
  const transform = makeHandler("transform", "heavy", "per-file");
  const exportHandler = makeHandler("export", "medium", "whole-batch");
  const path = [
    new ConvertPathNode(ingest, CommonFormats.PNG.supported("png", true, true, true)),
    new ConvertPathNode(transform, CommonFormats.WAV.supported("wav", true, true, true)),
    new ConvertPathNode(exportHandler, CommonFormats.MP3.supported("mp3", true, true, false))
  ];
  const policy = buildExecutionPolicy(benchmarkProfile, "balanced");

  const estimate = estimateRoute({
    path,
    totalBytes: 5_500_000,
    fileCount: 4,
    policy,
    profile: benchmarkProfile
  });

  expect(estimate.stepEstimates).toHaveLength(2);
  expect(estimate.stepEstimates[0].performanceClass).toBe("heavy");
  expect(estimate.stepEstimates[0].batchStrategy).toBe("per-file");
  expect(estimate.maxParallelJobs).toBe(policy.maxParallelJobs);
  expect(estimate.reasons[0]).toContain("step");
  expect(estimate.etaLabel.length).toBeGreaterThan(0);
});

test("historical observations blend the estimate and can raise confidence", () => {
  const ingest = makeHandler("canvas", "light", "whole-batch");
  const transform = makeHandler("ffmpeg", "heavy", "per-file");
  const path = [
    new ConvertPathNode(ingest, CommonFormats.PNG.supported("png", true, true, true)),
    new ConvertPathNode(transform, CommonFormats.MP4.supported("mp4", true, true, true))
  ];
  const policy = buildExecutionPolicy(benchmarkProfile, "balanced");
  const totalBytes = 12_000_000;

  const baseline = estimateRoute({
    path,
    totalBytes,
    fileCount: 3,
    policy,
    profile: benchmarkProfile
  });

  const observedDuration = baseline.totalEstimatedMs * 2.2;
  const routeKey = getRouteKey(path);
  recordRouteObservation(routeKey, totalBytes, observedDuration, false, benchmarkProfile.signature);
  recordRouteObservation(routeKey, totalBytes, observedDuration, false, benchmarkProfile.signature);

  const blended = estimateRoute({
    path,
    totalBytes,
    fileCount: 3,
    policy,
    profile: benchmarkProfile
  });

  expect(blended.totalEstimatedMs).toBeGreaterThan(baseline.totalEstimatedMs);
  expect(blended.confidence).toBe("high");
  expect(blended.reasons[3]).toContain("historical run");
});
