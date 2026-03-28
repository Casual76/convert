import type { BatchStrategy, ConvertPathNode, PerformanceClass } from "./FormatHandler.js";
import type {
  DevicePerformanceProfile,
  EstimateConfidence,
  ExecutionPolicy,
  RouteEstimate,
  RouteObservationAggregate,
  RouteStepEstimate
} from "./performanceProfile.js";
import {
  ROUTE_OBSERVATIONS_STORAGE_KEY,
  estimateConfidenceLabel,
  getCpuImpactLabel,
  getEtaLabel,
  getIntensityFromDuration
} from "./performanceProfile.js";

interface ObservationMap {
  [key: string]: RouteObservationAggregate;
}

interface EstimateRouteOptions {
  path: ConvertPathNode[];
  totalBytes: number;
  fileCount: number;
  policy: ExecutionPolicy;
  profile: DevicePerformanceProfile;
}

const STEP_BASE_WEIGHTS: Record<PerformanceClass, number> = {
  light: 0.95,
  medium: 2.3,
  heavy: 5.1
};

const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));

const getBatchStrategy = (node: ConvertPathNode): BatchStrategy => node.handler.batchStrategy ?? "whole-batch";
const getPerformanceClass = (node: ConvertPathNode): PerformanceClass => node.handler.performanceClass ?? "medium";

const getFormatLabel = (node: ConvertPathNode) => node.format.format.toUpperCase();

const getCategoryLabel = (node: ConvertPathNode) => {
  const category = node.format.category;
  if (Array.isArray(category)) return category[0] || node.format.mime.split("/")[0];
  return category || node.format.mime.split("/")[0];
};

export const getRouteKey = (path: ConvertPathNode[]) => path
  .map(node => `${node.handler.name}:${node.format.format}`)
  .join(" -> ");

const getSizeBucket = (totalBytes: number) => {
  const megaBytes = Math.max(1, totalBytes / 1_000_000);
  return clamp(Math.floor(Math.log2(megaBytes + 1)), 0, 10);
};

const readObservationMap = (): ObservationMap => {
  try {
    const raw = localStorage.getItem(ROUTE_OBSERVATIONS_STORAGE_KEY);
    if (!raw) return {};
    return JSON.parse(raw) as ObservationMap;
  } catch {
    return {};
  }
};

const writeObservationMap = (value: ObservationMap) => {
  try {
    localStorage.setItem(ROUTE_OBSERVATIONS_STORAGE_KEY, JSON.stringify(value));
  } catch {
    // Ignore quota issues.
  }
};

export const recordRouteObservation = (
  routeKey: string,
  totalBytes: number,
  durationMs: number,
  preview: boolean,
  profileSignature: string
) => {
  const storageKey = `${routeKey}::${getSizeBucket(totalBytes)}`;
  const observationMap = readObservationMap();
  const existing = observationMap[storageKey];

  if (!existing) {
    observationMap[storageKey] = {
      averageDurationMs: durationMs,
      sampleCount: 1,
      previewSampleCount: preview ? 1 : 0,
      updatedAt: Date.now(),
      profileSignature
    };
  } else {
    const sampleCount = existing.sampleCount + 1;
    observationMap[storageKey] = {
      averageDurationMs: ((existing.averageDurationMs * existing.sampleCount) + durationMs) / sampleCount,
      sampleCount,
      previewSampleCount: existing.previewSampleCount + (preview ? 1 : 0),
      updatedAt: Date.now(),
      profileSignature
    };
  }

  writeObservationMap(observationMap);
};

const getObservation = (routeKey: string, totalBytes: number) => {
  const observationMap = readObservationMap();
  return observationMap[`${routeKey}::${getSizeBucket(totalBytes)}`] ?? null;
};

const createStepEstimate = (
  fromNode: ConvertPathNode,
  toNode: ConvertPathNode,
  options: EstimateRouteOptions
): RouteStepEstimate => {
  const batchStrategy = getBatchStrategy(toNode);
  const performanceClass = getPerformanceClass(toNode);
  const sizeFactor = clamp(Math.log2(options.totalBytes / 350_000 + 1) + 0.85, 0.75, 8);
  const fileFactor = options.fileCount <= 1
    ? 1
    : 1 + (options.fileCount - 1) * (batchStrategy === "per-file" ? 0.46 : 0.84);
  const categoryShift = getCategoryLabel(fromNode) === getCategoryLabel(toNode) ? 1 : 1.18;
  const lossinessFactor = toNode.format.lossless ? 0.92 : 1.08;
  const profileFactor = 1 / clamp(options.profile.overallScore, 0.42, 2.4);
  const parallelBoost = batchStrategy === "per-file"
    ? clamp(Math.min(options.policy.maxParallelJobs, options.fileCount) * 0.82, 1, 4.5)
    : 1;

  const estimatedMs = 620
    * STEP_BASE_WEIGHTS[performanceClass]
    * sizeFactor
    * fileFactor
    * categoryShift
    * lossinessFactor
    * profileFactor
    / parallelBoost;

  const cpuShare = batchStrategy === "per-file"
    ? clamp(options.policy.targetThreads / options.policy.hardwareThreads, 0.4, 0.92)
    : clamp(options.policy.cpuFraction * 0.85, 0.28, 0.86);

  const note = batchStrategy === "per-file"
    ? `Can fan out across up to ${Math.min(options.policy.maxParallelJobs, options.fileCount)} file job(s).`
    : "Runs as a whole-batch step, so scaling is more limited.";

  return {
    id: `${fromNode.handler.name}:${fromNode.format.format}->${toNode.handler.name}:${toNode.format.format}`,
    fromLabel: getFormatLabel(fromNode),
    toLabel: getFormatLabel(toNode),
    handlerName: toNode.handler.name,
    performanceClass,
    batchStrategy,
    estimatedMs,
    cpuShare,
    note
  };
};

export const estimateRoute = (options: EstimateRouteOptions): RouteEstimate => {
  const routeKey = getRouteKey(options.path);
  const stepEstimates = options.path.slice(1).map((node, index) => createStepEstimate(
    options.path[index],
    node,
    options
  ));

  let totalEstimatedMs = stepEstimates.reduce((total, step) => total + step.estimatedMs, 0);
  const observation = getObservation(routeKey, options.totalBytes);
  let confidence: EstimateConfidence = options.profile.confidence === "high" ? "medium" : "low";

  if (observation) {
    const blendedMs = totalEstimatedMs * 0.55 + observation.averageDurationMs * 0.45;
    const ratio = blendedMs / Math.max(totalEstimatedMs, 1);
    totalEstimatedMs = blendedMs;
    for (const stepEstimate of stepEstimates) {
      stepEstimate.estimatedMs *= ratio;
    }
    confidence = options.profile.source === "benchmark" && observation.sampleCount >= 2 ? "high" : "medium";
  } else if (options.profile.source === "benchmark") {
    confidence = "medium";
  }

  const categoryChanges = options.path.slice(1).filter((node, index) =>
    getCategoryLabel(options.path[index]) !== getCategoryLabel(node)
  ).length;
  const wholeBatchSteps = stepEstimates.filter(step => step.batchStrategy === "whole-batch").length;
  const uniqueHandlers = new Set(options.path.slice(1).map(node => node.handler.name));
  const reasons = [
    `${stepEstimates.length} step(s) across ${uniqueHandlers.size} handler(s).`,
    categoryChanges > 0
      ? `${categoryChanges} category jump(s) make the route heavier than a same-medium conversion.`
      : "The route mostly stays inside the same medium, which keeps costs lower.",
    wholeBatchSteps === stepEstimates.length
      ? "This route is mostly whole-batch, so the CPU budget cannot fan out aggressively."
      : `Compatible steps can fan out across up to ${options.policy.maxParallelJobs} parallel file job(s).`,
    observation
      ? `Estimate is blended with ${observation.sampleCount} historical run(s) recorded on this device.`
      : "Estimate is using local hardware profiling with no historical runs yet."
  ];

  return {
    routeKey,
    totalEstimatedMs,
    etaLabel: getEtaLabel(totalEstimatedMs),
    intensity: getIntensityFromDuration(totalEstimatedMs),
    cpuImpactLabel: getCpuImpactLabel(totalEstimatedMs, options.policy),
    confidence,
    confidenceLabel: estimateConfidenceLabel(confidence),
    maxParallelJobs: options.policy.maxParallelJobs,
    stepEstimates,
    reasons,
    routeSignatureLabel: uniqueHandlers.size === 1
      ? `Single-tool route via ${Array.from(uniqueHandlers)[0]}`
      : `${uniqueHandlers.size}-tool route`
  };
};
