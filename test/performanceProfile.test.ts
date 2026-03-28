import { afterEach, beforeEach, expect, test } from "bun:test";
import {
  buildExecutionPolicy,
  createHeuristicProfile,
  loadDeviceProfile,
  PROFILE_STORAGE_KEY,
  PROFILE_VERSION,
  type DevicePerformanceProfile
} from "../src/performanceProfile.ts";

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
const originalWorker = globalThis.Worker;

const setNavigator = (overrides: Partial<Navigator> & { deviceMemory?: number } = {}) => {
  Object.defineProperty(globalThis, "navigator", {
    configurable: true,
    value: {
      hardwareConcurrency: 12,
      deviceMemory: 16,
      platform: "TestOS",
      userAgent: "TestAgent/1.0",
      ...overrides
    }
  });
};

const setLocalStorage = (storage: MemoryStorage) => {
  Object.defineProperty(globalThis, "localStorage", {
    configurable: true,
    value: storage
  });
};

beforeEach(() => {
  setNavigator();
  setLocalStorage(new MemoryStorage());
  Object.defineProperty(globalThis, "Worker", {
    configurable: true,
    value: undefined
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
  Object.defineProperty(globalThis, "Worker", {
    configurable: true,
    value: originalWorker
  });
});

test("buildExecutionPolicy maps presets to distinct conservative budgets", () => {
  const profile: DevicePerformanceProfile = {
    ...createHeuristicProfile(),
    source: "benchmark",
    confidence: "high",
    hardwareConcurrency: 12,
    deviceMemory: 16,
    benchmarkDurationMs: 180,
    computeThroughput: 128000,
    memoryThroughput: 1.4,
    computeScore: 1.5,
    memoryScore: 1.35,
    overallScore: 1.62
  };

  const responsive = buildExecutionPolicy(profile, "responsive");
  const balanced = buildExecutionPolicy(profile, "balanced");
  const max = buildExecutionPolicy(profile, "max");

  expect(responsive.targetThreads).toBeLessThan(balanced.targetThreads);
  expect(balanced.targetThreads).toBeLessThanOrEqual(max.targetThreads);
  expect(max.targetThreads).toBeLessThan(profile.hardwareConcurrency);
  expect(responsive.maxParallelJobs).toBeLessThanOrEqual(balanced.maxParallelJobs);
  expect(balanced.maxParallelJobs).toBeLessThanOrEqual(max.maxParallelJobs);
  expect(max.maxParallelJobs).toBeLessThanOrEqual(max.targetThreads);
});

test("loadDeviceProfile returns a valid cached profile when available", async () => {
  const cachedProfile: DevicePerformanceProfile = {
    ...createHeuristicProfile(),
    version: PROFILE_VERSION,
    measuredAt: Date.now(),
    benchmarkDurationMs: 192,
    computeThroughput: 119000,
    memoryThroughput: 1.32,
    computeScore: 1.42,
    memoryScore: 1.24,
    overallScore: 1.36,
    source: "benchmark",
    confidence: "high"
  };

  localStorage.setItem(PROFILE_STORAGE_KEY, JSON.stringify(cachedProfile));

  const loaded = await loadDeviceProfile();

  expect(loaded).toEqual(cachedProfile);
});

test("loadDeviceProfile falls back to a heuristic profile when the worker is unavailable", async () => {
  const loaded = await loadDeviceProfile(true);

  expect(loaded.source).toBe("heuristic");
  expect(loaded.hardwareConcurrency).toBe(12);
  expect(loaded.confidence).toBe("low");
});
