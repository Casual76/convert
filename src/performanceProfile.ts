import type { BatchStrategy, PerformanceClass } from "./FormatHandler.js";

export type PerformancePreset = "responsive" | "balanced" | "max";
export type EstimateConfidence = "low" | "medium" | "high";
export type RouteIntensity = "Light" | "Moderate" | "Heavy" | "Very heavy";

export interface DevicePerformanceProfile {
  version: number;
  signature: string;
  measuredAt: number;
  benchmarkDurationMs: number;
  hardwareConcurrency: number;
  deviceMemory: number | null;
  computeThroughput: number;
  memoryThroughput: number;
  computeScore: number;
  memoryScore: number;
  overallScore: number;
  source: "benchmark" | "heuristic";
  confidence: EstimateConfidence;
}

export interface ExecutionPolicy {
  preset: PerformancePreset;
  cpuFraction: number;
  reservedThreads: number;
  targetThreads: number;
  maxParallelJobs: number;
  hardwareThreads: number;
}

export interface RouteObservationAggregate {
  averageDurationMs: number;
  sampleCount: number;
  previewSampleCount: number;
  updatedAt: number;
  profileSignature: string;
}

export interface RouteStepEstimate {
  id: string;
  fromLabel: string;
  toLabel: string;
  handlerName: string;
  performanceClass: PerformanceClass;
  batchStrategy: BatchStrategy;
  estimatedMs: number;
  cpuShare: number;
  note: string;
}

export interface RouteEstimate {
  routeKey: string;
  totalEstimatedMs: number;
  etaLabel: string;
  intensity: RouteIntensity;
  cpuImpactLabel: string;
  confidence: EstimateConfidence;
  confidenceLabel: string;
  maxParallelJobs: number;
  stepEstimates: RouteStepEstimate[];
  reasons: string[];
  routeSignatureLabel: string;
}

export interface ProfilerWorkerResult {
  computeThroughput: number;
  memoryThroughput: number;
  durationMs: number;
}

export const PROFILE_STORAGE_KEY = "convert-to-it.device-profile.v2";
export const ROUTE_OBSERVATIONS_STORAGE_KEY = "convert-to-it.route-observations.v1";
export const PROFILE_CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000;
export const PROFILE_VERSION = 2;

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));

const getNavigatorDeviceMemory = () => {
  const value = (navigator as Navigator & { deviceMemory?: number }).deviceMemory;
  return typeof value === "number" && Number.isFinite(value) ? value : null;
};

interface NavigatorUserAgentBrand {
  brand: string;
  version: string;
}

interface NavigatorWithUserAgentData extends Navigator {
  userAgentData?: {
    platform?: string;
    brands?: NavigatorUserAgentBrand[];
  };
}

export const getHardwareHints = () => ({
  hardwareConcurrency: Math.max(1, navigator.hardwareConcurrency || 4),
  deviceMemory: getNavigatorDeviceMemory()
});

export const getDeviceSignature = () => {
  const hints = getHardwareHints();
  const navigatorWithHints = navigator as NavigatorWithUserAgentData;
  const platform = navigatorWithHints.userAgentData?.platform || navigator.platform || "unknown-platform";
  const agent = navigatorWithHints.userAgentData?.brands?.map(brand => `${brand.brand}/${brand.version}`).join(",")
    || navigator.userAgent;
  const isolation = globalThis.crossOriginIsolated ? "isolated" : "standard";
  return [
    `v=${PROFILE_VERSION}`,
    `platform=${platform}`,
    `cores=${hints.hardwareConcurrency}`,
    `memory=${hints.deviceMemory ?? "na"}`,
    `isolation=${isolation}`,
    `agent=${agent}`
  ].join("|");
};

const normalizeComputeScore = (throughput: number) => clamp(throughput / 105_000, 0.35, 2.4);
const normalizeMemoryScore = (throughput: number) => clamp(throughput / 0.95, 0.35, 2.4);

const createProfileFromBenchmarks = (result: ProfilerWorkerResult): DevicePerformanceProfile => {
  const hints = getHardwareHints();
  const computeScore = normalizeComputeScore(result.computeThroughput);
  const memoryScore = normalizeMemoryScore(result.memoryThroughput);
  const coreScore = clamp(hints.hardwareConcurrency / 8, 0.55, 1.4);
  const overallScore = clamp(
    computeScore * 0.48 + memoryScore * 0.22 + coreScore * 0.3,
    0.35,
    2.4
  );

  return {
    version: PROFILE_VERSION,
    signature: getDeviceSignature(),
    measuredAt: Date.now(),
    benchmarkDurationMs: result.durationMs,
    hardwareConcurrency: hints.hardwareConcurrency,
    deviceMemory: hints.deviceMemory,
    computeThroughput: result.computeThroughput,
    memoryThroughput: result.memoryThroughput,
    computeScore,
    memoryScore,
    overallScore,
    source: "benchmark",
    confidence: "high"
  };
};

export const createHeuristicProfile = (): DevicePerformanceProfile => {
  const hints = getHardwareHints();
  const memoryScore = hints.deviceMemory === null ? 1 : clamp(hints.deviceMemory / 4, 0.45, 2.1);
  const computeScore = clamp(hints.hardwareConcurrency / 6, 0.45, 2.1);
  return {
    version: PROFILE_VERSION,
    signature: getDeviceSignature(),
    measuredAt: Date.now(),
    benchmarkDurationMs: 0,
    hardwareConcurrency: hints.hardwareConcurrency,
    deviceMemory: hints.deviceMemory,
    computeThroughput: 0,
    memoryThroughput: 0,
    computeScore,
    memoryScore,
    overallScore: clamp(computeScore * 0.72 + memoryScore * 0.28, 0.45, 2),
    source: "heuristic",
    confidence: "low"
  };
};

const readCachedProfile = () => {
  try {
    const raw = localStorage.getItem(PROFILE_STORAGE_KEY);
    if (!raw) return null;
    const cached = JSON.parse(raw) as DevicePerformanceProfile;
    if (cached.version !== PROFILE_VERSION) return null;
    if (cached.signature !== getDeviceSignature()) return null;
    if (Date.now() - cached.measuredAt > PROFILE_CACHE_TTL_MS) return null;
    return cached;
  } catch {
    return null;
  }
};

const writeCachedProfile = (profile: DevicePerformanceProfile) => {
  try {
    localStorage.setItem(PROFILE_STORAGE_KEY, JSON.stringify(profile));
  } catch {
    // Ignore storage quota failures.
  }
};

let activeProfilePromise: Promise<DevicePerformanceProfile> | null = null;

const runProfilerInWorker = async (): Promise<DevicePerformanceProfile> => {
  const worker = new Worker(new URL("./performanceProfiler.worker.ts", import.meta.url), { type: "module" });
  try {
    const profile = await new Promise<DevicePerformanceProfile>((resolve, reject) => {
      const timeout = window.setTimeout(() => {
        reject(new Error("Device performance profiler timed out."));
      }, 4000);

      worker.addEventListener("message", event => {
        window.clearTimeout(timeout);
        resolve(createProfileFromBenchmarks(event.data as ProfilerWorkerResult));
      }, { once: true });

      worker.addEventListener("error", event => {
        window.clearTimeout(timeout);
        reject(event.error ?? new Error("Device performance profiler failed."));
      }, { once: true });

      worker.postMessage({ type: "run" });
    });

    writeCachedProfile(profile);
    return profile;
  } finally {
    worker.terminate();
  }
};

export const loadDeviceProfile = async (force = false) => {
  if (!force) {
    const cached = readCachedProfile();
    if (cached) return cached;
  }

  if (!activeProfilePromise) {
    activeProfilePromise = runProfilerInWorker()
      .catch(() => createHeuristicProfile())
      .finally(() => {
        activeProfilePromise = null;
      });
  }

  return activeProfilePromise;
};

export const buildExecutionPolicy = (
  profile: DevicePerformanceProfile,
  preset: PerformancePreset
): ExecutionPolicy => {
  const hardwareThreads = Math.max(1, profile.hardwareConcurrency || 4);
  const cpuFractionMap: Record<PerformancePreset, number> = {
    responsive: 0.56,
    balanced: 0.76,
    max: 0.88
  };
  const cpuFraction = cpuFractionMap[preset];
  const reservedThreads = hardwareThreads <= 2 ? 1 : hardwareThreads >= 8 ? 2 : 1;
  const threadBudgetCap = Math.max(1, hardwareThreads - reservedThreads);
  let targetThreads = Math.floor(hardwareThreads * cpuFraction);
  if (preset === "balanced" && hardwareThreads >= 6) {
    targetThreads = Math.max(targetThreads, hardwareThreads - 2);
  }
  targetThreads = clamp(targetThreads, 1, threadBudgetCap);

  const memoryCap =
    profile.deviceMemory !== null && profile.deviceMemory <= 2 ? 2
      : profile.deviceMemory !== null && profile.deviceMemory <= 4 ? 3
        : 6;
  const performanceCap =
    profile.overallScore >= 1.65 ? 6
      : profile.overallScore >= 1.15 ? 4
        : 3;

  let maxParallelJobs = Math.min(targetThreads, threadBudgetCap, memoryCap, performanceCap);
  if (profile.source === "heuristic") {
    maxParallelJobs = Math.max(1, maxParallelJobs - 1);
  }

  return {
    preset,
    cpuFraction,
    reservedThreads,
    targetThreads,
    maxParallelJobs: Math.max(1, maxParallelJobs),
    hardwareThreads
  };
};

export const estimateConfidenceLabel = (confidence: EstimateConfidence) => {
  switch (confidence) {
    case "high":
      return "High confidence";
    case "medium":
      return "Medium confidence";
    default:
      return "Low confidence";
  }
};

export const getEtaLabel = (estimatedMs: number) => {
  if (estimatedMs < 5_000) return "<5s";
  if (estimatedMs < 15_000) return "5-15s";
  if (estimatedMs < 45_000) return "15-45s";
  if (estimatedMs < 120_000) return "1-2m";
  return "2m+";
};

export const getIntensityFromDuration = (estimatedMs: number): RouteIntensity => {
  if (estimatedMs < 5_000) return "Light";
  if (estimatedMs < 18_000) return "Moderate";
  if (estimatedMs < 60_000) return "Heavy";
  return "Very heavy";
};

export const getCpuImpactLabel = (estimatedMs: number, policy: ExecutionPolicy) => {
  const budgetRatio = policy.targetThreads / policy.hardwareThreads;
  if (budgetRatio <= 0.55 && estimatedMs < 12_000) return "Low impact";
  if (budgetRatio <= 0.72 && estimatedMs < 30_000) return "Moderate impact";
  if (budgetRatio <= 0.84 || estimatedMs < 90_000) return "High impact";
  return "Very high impact";
};
