import { ConvertPathNode, type FileData, type FileFormat, type FormatHandler } from "./FormatHandler.js";
import handlers from "./handlers/index.js";
import normalizeMimeType from "./normalizeMimeType.js";
import { TraversionGraph } from "./TraversionGraph.js";
import {
  buildExecutionPolicy,
  createHeuristicProfile,
  estimateConfidenceLabel,
  type DevicePerformanceProfile,
  type ExecutionPolicy,
  type PerformancePreset as RuntimePerformancePreset,
  type RouteEstimate
} from "./performanceProfile.js";
import { estimateRoute, getRouteKey } from "./routeEstimator.js";

type AndroidPerformancePreset = "BATTERY" | "BALANCED" | "PERFORMANCE";
type RuntimeKind = "BRIDGE";

interface BridgeFormatDescriptor {
  id: string;
  displayName: string;
  shortLabel: string;
  extension: string;
  mimeType: string;
  categories: string[];
  supportsInput: boolean;
  supportsOutput: boolean;
  handlerName: string;
  lossless: boolean;
  availableRuntimeKinds: RuntimeKind[];
  nativePreferred: boolean;
}

interface BridgeRouteStep {
  fromFormatId: string;
  toFormatId: string;
  handlerName: string;
  performanceClass: string;
  batchStrategy: string;
  note: string;
  runtimeKind: RuntimeKind;
}

interface BridgeRoutePreview {
  routeKey: string;
  routeToken: string;
  steps: BridgeRouteStep[];
  etaLabel: string;
  cpuImpactLabel: string;
  confidenceLabel: string;
  reasons: string[];
  previewSupported: boolean;
  runtimeKind: RuntimeKind;
}

interface BridgeConversionPreview {
  supported: boolean;
  kind: "NONE" | "IMAGE_PROXY" | "TEXT" | "DOCUMENT";
  headline: string;
  body: string;
  proxyUri?: string | null;
  textPreview?: string | null;
}

interface BridgeRunInput {
  url: string;
  name: string;
  mimeType?: string | null;
}

interface BridgePlanPayload {
  sourceFormatId: string;
  targetFormatId: string;
  fileCount: number;
  totalBytes: number;
  performancePreset: AndroidPerformancePreset;
  maxParallelJobs: number;
  batteryFriendlyMode: boolean;
}

interface BridgePreviewPayload extends BridgePlanPayload {
  input: BridgeRunInput;
  routeToken?: string | null;
  previewLimitBytes: number;
}

interface BridgeRunPayload extends BridgePlanPayload {
  inputs: BridgeRunInput[];
  routeToken?: string | null;
  callId: string;
}

interface BridgeDiagnostics {
  runtimeKind: RuntimeKind;
  catalogFormatCount: number;
  inputFormatCount: number;
  outputFormatCount: number;
  handlerCount: number;
  disabledHandlers: string[];
  cacheSource: "cache" | "live";
}

interface BridgeOption {
  format: FileFormat;
  handler: FormatHandler;
}

interface RouteTokenPayload {
  runtimeKind: RuntimeKind;
  path: Array<{
    handlerName: string;
    format: FileFormat;
  }>;
}

interface AndroidBridgeHost {
  onRuntimeReady?(): void;
  deliverResult(callId: string, payloadJson: string): void;
  emitOutputChunk(
    callId: string,
    fileIndex: number,
    fileName: string,
    mimeType: string,
    chunkIndex: number,
    isLastChunk: boolean,
    base64Chunk: string
  ): void;
}

declare global {
  interface Window {
    AndroidBridgeHost?: AndroidBridgeHost;
    __androidBridgeHandle?: (callId: string, method: string, payloadJson: string) => Promise<void>;
    __legacyBridge?: {
      ensureReady: () => Promise<void>;
      printSupportedFormatCache: () => string | Promise<string>;
    };
    printSupportedFormatCache: () => string | Promise<string>;
    __bridgeRuntimeReady?: boolean;
  }
}

const supportedFormatCache = new Map<string, FileFormat[]>();
const graph = new TraversionGraph();
const catalogById = new Map<string, BridgeFormatDescriptor>();
const fromOptionsById = new Map<string, BridgeOption>();
const toOptionsById = new Map<string, BridgeOption>();
const routeCache = new Map<string, RouteTokenPayload | null>();
const disabledHandlers = new Set<string>();
let initialized = false;
let cacheSource: "cache" | "live" = "cache";
let runtimeProfile: DevicePerformanceProfile = createHeuristicProfile();

const formatId = (format: Pick<FileFormat, "mime" | "format">) =>
  `${normalizeMimeType(format.mime)}(${format.format.toLowerCase()})`;

const formatCategories = (format: FileFormat) => {
  const raw = format.category ?? format.mime.split("/")[0];
  return (Array.isArray(raw) ? raw : [raw])
    .map(category => String(category).trim())
    .filter(Boolean);
};

const upperLabel = (value: string) =>
  value.length <= 5 ? value.toUpperCase() : value.slice(0, 1).toUpperCase() + value.slice(1);

const extensionAliasGroups = [
  new Set(["jpg", "jpeg", "jpe"]),
  new Set(["tif", "tiff"]),
  new Set(["htm", "html"]),
  new Set(["md", "markdown", "mdown"]),
  new Set(["yaml", "yml"]),
  new Set(["oga", "ogg"]),
  new Set(["mpg", "mpeg"]),
  new Set(["3gp", "3gpp"]),
  new Set(["7z", "7zip"])
];

const mimeAliases = new Map<string, string>([
  ["image/jpg", "image/jpeg"],
  ["image/pjpeg", "image/jpeg"],
  ["image/x-png", "image/png"],
  ["application/x-pdf", "application/pdf"],
  ["audio/x-wav", "audio/wav"],
  ["audio/wave", "audio/wav"],
  ["text/x-markdown", "text/markdown"],
  ["text/x-web-markdown", "text/markdown"],
  ["application/x-markdown", "text/markdown"],
  ["application/x-zip-compressed", "application/zip"]
]);

const aliasGroupFor = (token: string) =>
  extensionAliasGroups.find(group => group.has(token)) ?? new Set([token]);

const normalizeBridgeMime = (value?: string | null) => {
  const raw = value?.split(";")[0]?.trim().toLowerCase();
  if (!raw) return "";
  return mimeAliases.get(raw) ?? normalizeMimeType(raw);
};

const buildDetectionNameCandidates = (fileName?: string | null) => {
  const candidates = new Set<string>();
  if (fileName?.trim()) {
    candidates.add(fileName.trim().toLowerCase());
  }
  return [...candidates];
};

const formatTokenFromId = (id: string) => {
  const start = id.lastIndexOf("(");
  const end = id.lastIndexOf(")");
  if (start === -1 || end === -1 || end <= start + 1) return "";
  return id.slice(start + 1, end).trim().toLowerCase();
};

const scoreDetection = (
  descriptor: BridgeFormatDescriptor,
  normalizedMime: string,
  names: string[]
) => {
  let score = 0;
  const descriptorMime = normalizeBridgeMime(descriptor.mimeType);
  const tokens = new Set([
    descriptor.extension.toLowerCase(),
    descriptor.shortLabel.toLowerCase(),
    formatTokenFromId(descriptor.id)
  ]);

  if (normalizedMime) {
    if (descriptorMime === normalizedMime) {
      score += 90;
    } else if (mimeAliases.get(normalizedMime) === descriptorMime) {
      score += 82;
    } else if (normalizedMime.split("/")[0] === descriptorMime.split("/")[0]) {
      score += 8;
    }

    const subtype = normalizedMime.split("/")[1]?.split("+")[0] ?? "";
    if (subtype && (descriptor.extension.toLowerCase() === subtype || aliasGroupFor(descriptor.extension.toLowerCase()).has(subtype))) {
      score += 34;
    }
  }

  names.forEach(name => {
    const directExtension = descriptor.extension.toLowerCase();
    if (name.endsWith(`.${directExtension}`)) {
      score += directExtension.includes(".") ? 78 : 64;
    } else if ([...aliasGroupFor(directExtension)].some(alias => name.endsWith(`.${alias}`))) {
      score += 56;
    }

    if ([...tokens].filter(Boolean).some(token => typeof token === "string" && token.length > 1 && name.includes(`.${token}`))) {
      score += 10;
    }
  });

  return score;
};

const toDescriptor = (format: FileFormat, handlerName: string): BridgeFormatDescriptor => ({
  id: formatId(format),
  displayName: format.name,
  shortLabel: upperLabel(format.format),
  extension: format.extension,
  mimeType: normalizeMimeType(format.mime),
  categories: formatCategories(format),
  supportsInput: format.from,
  supportsOutput: format.to,
  handlerName,
  lossless: Boolean(format.lossless),
  availableRuntimeKinds: ["BRIDGE"],
  nativePreferred: false
});

const dedupeCatalog = () => {
  catalogById.clear();
  fromOptionsById.clear();
  toOptionsById.clear();
  routeCache.clear();

  for (const handler of handlers) {
    const supportedFormats = supportedFormatCache.get(handler.name);
    if (!supportedFormats) continue;

    for (const format of supportedFormats) {
      if (!format.mime) continue;
      const descriptor = toDescriptor(format, handler.name);
      const id = descriptor.id;
      const current = catalogById.get(id);
      if (!current) {
        catalogById.set(id, descriptor);
      } else {
        catalogById.set(id, {
          ...current,
          categories: Array.from(new Set([...current.categories, ...descriptor.categories])),
          supportsInput: current.supportsInput || descriptor.supportsInput,
          supportsOutput: current.supportsOutput || descriptor.supportsOutput,
          lossless: current.lossless || descriptor.lossless
        });
      }

      if (format.from && !fromOptionsById.has(id)) {
        fromOptionsById.set(id, { format, handler });
      }
      if (format.to && !toOptionsById.has(id)) {
        toOptionsById.set(id, { format, handler });
      }
    }
  }

  graph.init(supportedFormatCache, handlers);
};

const readCacheFromDisk = async () => {
  const response = await fetch("./cache.json");
  if (!response.ok) {
    throw new Error("cache.json missing");
  }
  const raw = await response.json() as Array<[string, FileFormat[]]>;
  supportedFormatCache.clear();
  for (const [handlerName, formats] of raw) {
    supportedFormatCache.set(handlerName, formats);
  }
  cacheSource = "cache";
};

const buildSupportedFormatCacheLive = async () => {
  supportedFormatCache.clear();
  cacheSource = "live";

  for (const handler of handlers) {
    try {
      if (handler.supportedFormats?.length) {
        supportedFormatCache.set(handler.name, handler.supportedFormats);
        continue;
      }
      if (!handler.ready) {
        await handler.init();
      }
      if (handler.supportedFormats?.length) {
        supportedFormatCache.set(handler.name, handler.supportedFormats);
      }
    } catch (error) {
      disabledHandlers.add(handler.name);
      console.warn(`Skipping handler ${handler.name} during live cache build.`, error);
    }
  }
};

const ensureInitialized = async () => {
  if (initialized) return;
  try {
    await readCacheFromDisk();
  } catch (error) {
    console.warn("Falling back to live supported-format discovery.", error);
    await buildSupportedFormatCacheLive();
  }
  dedupeCatalog();
  runtimeProfile = createHeuristicProfile();
  initialized = true;
  window.__bridgeRuntimeReady = true;
};

const findPlannedPath = async (from: ConvertPathNode, to: ConvertPathNode) => {
  for await (const path of graph.searchPath(from, to, true)) {
    return [...path];
  }
  return null;
};

const routeKeyForIds = (sourceFormatId: string, targetFormatId: string) =>
  `${sourceFormatId}=>${targetFormatId}`;

const parseRouteToken = (routeToken?: string | null): RouteTokenPayload | null => {
  if (!routeToken) return null;
  try {
    const parsed = JSON.parse(routeToken) as RouteTokenPayload;
    if (parsed.runtimeKind !== "BRIDGE" || !Array.isArray(parsed.path)) return null;
    return parsed;
  } catch {
    return null;
  }
};

const serializeRouteToken = (path: ConvertPathNode[]) =>
  JSON.stringify({
    runtimeKind: "BRIDGE",
    path: path.map(node => ({
      handlerName: node.handler.name,
      format: node.format
    }))
  } satisfies RouteTokenPayload);

const findBestPath = async (
  sourceFormatId: string,
  targetFormatId: string,
  routeToken?: string | null
) => {
  const explicit = parseRouteToken(routeToken);
  if (explicit?.path.length) {
    return explicit.path.map(step => {
      const handler = handlers.find(candidate => candidate.name === step.handlerName);
      if (!handler) {
        throw new Error(`Missing handler "${step.handlerName}" in legacy runtime.`);
      }
      return new ConvertPathNode(handler, step.format);
    });
  }

  const cached = routeCache.get(routeKeyForIds(sourceFormatId, targetFormatId));
  if (cached) {
    return cached.path.map(step => {
      const handler = handlers.find(candidate => candidate.name === step.handlerName);
      if (!handler) {
        throw new Error(`Missing handler "${step.handlerName}" in cached route.`);
      }
      return new ConvertPathNode(handler, step.format);
    });
  }
  if (cached === null) return null;

  const fromOption = fromOptionsById.get(sourceFormatId);
  const toOption = toOptionsById.get(targetFormatId);
  if (!fromOption || !toOption) {
    routeCache.set(routeKeyForIds(sourceFormatId, targetFormatId), null);
    return null;
  }

  const path = await findPlannedPath(
    new ConvertPathNode(fromOption.handler, fromOption.format),
    new ConvertPathNode(toOption.handler, toOption.format)
  );

  routeCache.set(
    routeKeyForIds(sourceFormatId, targetFormatId),
    path ? JSON.parse(serializeRouteToken(path)) as RouteTokenPayload : null
  );

  return path;
};

const applyExecutionPolicyOverrides = (
  performancePreset: AndroidPerformancePreset,
  maxParallelJobs: number,
  batteryFriendlyMode: boolean
) => {
  const presetMap: Record<AndroidPerformancePreset, RuntimePerformancePreset> = {
    BATTERY: "responsive",
    BALANCED: "balanced",
    PERFORMANCE: "max"
  };
  const basePolicy = buildExecutionPolicy(runtimeProfile, presetMap[performancePreset]);
  const targetThreads = batteryFriendlyMode ? 1 : basePolicy.targetThreads;
  const boundedParallelJobs = Math.max(
    1,
    Math.min(
      batteryFriendlyMode ? 1 : maxParallelJobs,
      basePolicy.maxParallelJobs
    )
  );

  return {
    ...basePolicy,
    targetThreads,
    maxParallelJobs: boundedParallelJobs
  } satisfies ExecutionPolicy;
};

const resolveInputFormat = async (
  handler: FormatHandler,
  expected: FileFormat
) => {
  let supportedFormats = supportedFormatCache.get(handler.name);
  if (!handler.ready) {
    await handler.init();
    if (handler.supportedFormats) {
      supportedFormatCache.set(handler.name, handler.supportedFormats);
      supportedFormats = handler.supportedFormats;
      dedupeCatalog();
    }
  }
  if (!supportedFormats) {
    throw new Error(`Handler "${handler.name}" does not expose supported formats.`);
  }
  return supportedFormats.find(format =>
    format.from
      && normalizeMimeType(format.mime) === normalizeMimeType(expected.mime)
      && format.format === expected.format
  ) || (handler.supportAnyInput ? expected : null);
};

const executeHandlerWithPolicy = async (
  handler: FormatHandler,
  files: FileData[],
  inputFormat: FileFormat,
  outputFormat: FileFormat,
  args: string[] | undefined,
  policy: ExecutionPolicy
) => {
  if ((handler.batchStrategy ?? "whole-batch") !== "per-file" || files.length <= 1 || policy.maxParallelJobs <= 1) {
    return handler.doConvert(files, inputFormat, outputFormat, args);
  }

  const results: FileData[][] = new Array(files.length);
  const workerCount = Math.min(policy.maxParallelJobs, files.length);
  let nextIndex = 0;

  await Promise.all(Array.from({ length: workerCount }, async () => {
    while (true) {
      const currentIndex = nextIndex;
      nextIndex += 1;
      if (currentIndex >= files.length) return;
      results[currentIndex] = await handler.doConvert([files[currentIndex]], inputFormat, outputFormat, args);
    }
  }));

  return results.flat();
};

const attemptConvertPath = async (
  files: FileData[],
  path: ConvertPathNode[],
  policy: ExecutionPolicy
) => {
  for (let index = 0; index < path.length - 1; index += 1) {
    const handler = path[index + 1].handler;
    try {
      const inputFormat = await resolveInputFormat(handler, path[index].format);
      if (!inputFormat) {
        throw new Error(`Handler "${handler.name}" does not accept "${path[index].format.format}".`);
      }
      files = await executeHandlerWithPolicy(
        handler,
        files,
        inputFormat,
        path[index + 1].format,
        undefined,
        policy
      );
      if (files.some(file => !file.bytes.length)) {
        throw new Error(`Handler "${handler.name}" produced empty output.`);
      }
    } catch (error) {
      disabledHandlers.add(handler.name);
      console.error(handler.name, `${path[index].format.format} -> ${path[index + 1].format.format}`, error);
      return null;
    }
  }
  return { files, path };
};

const fetchInputFile = async (input: BridgeRunInput): Promise<FileData> => {
  const response = await fetch(input.url);
  if (!response.ok) {
    throw new Error(`Could not read ${input.name}.`);
  }
  const bytes = new Uint8Array(await response.arrayBuffer());
  return {
    name: input.name,
    bytes
  };
};

const listTargets = async (sourceFormatId: string) => {
  await ensureInitialized();
  const source = catalogById.get(sourceFormatId);
  if (!source?.supportsInput) return [];

  const targets: BridgeFormatDescriptor[] = [];
  for (const descriptor of catalogById.values()) {
    if (!descriptor.supportsOutput || descriptor.id === sourceFormatId) continue;
    const path = await findBestPath(sourceFormatId, descriptor.id);
    if (path?.length) {
      targets.push(descriptor);
    }
  }
  return targets.sort((left, right) => left.displayName.localeCompare(right.displayName));
};

const buildRoutePreview = (
  path: ConvertPathNode[],
  estimate: RouteEstimate
): BridgeRoutePreview => ({
  routeKey: getRouteKey(path),
  routeToken: serializeRouteToken(path),
  steps: estimate.stepEstimates.map((step, index) => ({
    fromFormatId: formatId(path[index].format),
    toFormatId: formatId(path[index + 1].format),
    handlerName: step.handlerName,
    performanceClass: step.performanceClass,
    batchStrategy: step.batchStrategy,
    note: step.note,
    runtimeKind: "BRIDGE"
  })),
  etaLabel: estimate.etaLabel,
  cpuImpactLabel: estimate.cpuImpactLabel,
  confidenceLabel: estimateConfidenceLabel(estimate.confidence),
  reasons: estimate.reasons,
  previewSupported: true,
  runtimeKind: "BRIDGE"
});

const inferPreview = async (payload: BridgePreviewPayload): Promise<BridgeConversionPreview> => {
  await ensureInitialized();
  const target = catalogById.get(payload.targetFormatId);
  if (!target) {
    return {
      supported: false,
      kind: "NONE",
      headline: "Preview unavailable",
      body: "The requested target format is not available in the legacy runtime."
    };
  }
  if (payload.previewLimitBytes > 0 && payload.input && payload.totalBytes > payload.previewLimitBytes) {
    return {
      supported: false,
      kind: "NONE",
      headline: "Preview skipped",
      body: "The selected file is above the configured preview size limit."
    };
  }
  if (target.mimeType.startsWith("image/")) {
    return {
      supported: true,
      kind: "IMAGE_PROXY",
      headline: "Image route ready",
      body: "The compatibility runtime found a valid image route. The first input is shown as a safe proxy preview.",
      proxyUri: payload.input.url
    };
  }
  if (target.mimeType.startsWith("text/") || target.categories.some(category => category.toLowerCase() === "text")) {
    return {
      supported: true,
      kind: "TEXT",
      headline: "Text route ready",
      body: "This route can be previewed lightly in the native shell before export."
    };
  }
  if (target.mimeType === "application/pdf" || target.categories.some(category => category.toLowerCase() === "document")) {
    return {
      supported: true,
      kind: "DOCUMENT",
      headline: "Document route ready",
      body: "The legacy runtime can generate this document on-device when the job starts."
    };
  }
  return {
    supported: false,
    kind: "NONE",
    headline: "Preview unavailable",
    body: "This route is valid, but it does not expose a lightweight preview path."
  };
};

const toBase64Chunks = (bytes: Uint8Array, chunkSize = 96 * 1024) => {
  const chunks: string[] = [];
  for (let start = 0; start < bytes.length; start += chunkSize) {
    const end = Math.min(bytes.length, start + chunkSize);
    const slice = bytes.subarray(start, end);
    let binary = "";
    for (let index = 0; index < slice.length; index += 1) {
      binary += String.fromCharCode(slice[index]);
    }
    chunks.push(btoa(binary));
  }
  return chunks;
};

const emitOutputsToAndroid = (callId: string, files: FileData[]) => {
  const host = window.AndroidBridgeHost;
  if (!host) return;

  files.forEach((file, fileIndex) => {
    const mimeType = file.name.includes(".")
      ? (catalogById.get(formatId({
        mime: normalizeMimeType(file.name.endsWith(".json") ? "application/json" : `application/${file.name.split(".").pop() ?? "octet-stream"}`),
        format: file.name.split(".").pop() ?? "bin"
      }))?.mimeType ?? "application/octet-stream")
      : "application/octet-stream";
    const chunks = toBase64Chunks(file.bytes);
    chunks.forEach((chunk, chunkIndex) => {
      host.emitOutputChunk(
        callId,
        fileIndex,
        file.name,
        mimeType,
        chunkIndex,
        chunkIndex === chunks.length - 1,
        chunk
      );
    });
  });
};

const determineMimeType = (fileName: string, fallback: string) => {
  const extension = fileName.includes(".")
    ? fileName.split(".").pop()?.toLowerCase()
    : null;
  if (!extension) return fallback;
  for (const descriptor of catalogById.values()) {
    if (descriptor.extension.toLowerCase() === extension) {
      return descriptor.mimeType;
    }
  }
  return fallback;
};

const runConversion = async (payload: BridgeRunPayload) => {
  await ensureInitialized();
  const path = await findBestPath(payload.sourceFormatId, payload.targetFormatId, payload.routeToken);
  if (!path) {
    throw new Error("The compatibility runtime could not find a valid route.");
  }

  const policy = applyExecutionPolicyOverrides(
    payload.performancePreset,
    payload.maxParallelJobs,
    payload.batteryFriendlyMode
  );

  const inputFiles = await Promise.all(payload.inputs.map(fetchInputFile));
  const output = await attemptConvertPath(inputFiles, path, policy);
  if (!output) {
    throw new Error("The compatibility runtime failed while executing the planned route.");
  }

  emitOutputsToAndroid(payload.callId, output.files.map(file => ({
    ...file,
    name: file.name
  })));

  return {
    status: "COMPLETED",
    message: `Created ${output.files.length} converted file${output.files.length === 1 ? "" : "s"} in the compatibility runtime.`,
    outputCount: output.files.length,
    runtimeKind: "BRIDGE" as RuntimeKind,
    routePreview: buildRoutePreview(
      output.path,
      estimateRoute({
        path: output.path,
        totalBytes: payload.totalBytes,
        fileCount: payload.fileCount,
        policy,
        profile: runtimeProfile
      })
    )
  };
};

const diagnostics = async (): Promise<BridgeDiagnostics> => {
  await ensureInitialized();
  return {
    runtimeKind: "BRIDGE",
    catalogFormatCount: catalogById.size,
    inputFormatCount: Array.from(catalogById.values()).filter(descriptor => descriptor.supportsInput).length,
    outputFormatCount: Array.from(catalogById.values()).filter(descriptor => descriptor.supportsOutput).length,
    handlerCount: handlers.length,
    disabledHandlers: Array.from(disabledHandlers).sort(),
    cacheSource
  };
};

const api = {
  async ensureReady() {
    await ensureInitialized();
  },
  async printSupportedFormatCache() {
    await ensureInitialized();
    return JSON.stringify(Array.from(supportedFormatCache.entries()));
  },
  async loadCatalog() {
    await ensureInitialized();
    return Array.from(catalogById.values()).sort((left, right) => left.displayName.localeCompare(right.displayName));
  },
  async detectFormat(payload: { fileName?: string | null; mimeType?: string | null }) {
    await ensureInitialized();
    const normalizedMime = normalizeBridgeMime(payload.mimeType);
    const names = buildDetectionNameCandidates(payload.fileName);
    const ranked = Array.from(catalogById.values())
      .filter(descriptor => descriptor.supportsInput)
      .map(descriptor => ({
        descriptor,
        score: scoreDetection(descriptor, normalizedMime, names)
      }))
      .sort((left, right) => right.score - left.score || Number(right.descriptor.nativePreferred) - Number(left.descriptor.nativePreferred));

    return ranked[0]?.score > 0 ? ranked[0].descriptor : null;
  },
  async listTargets(payload: { sourceFormatId: string }) {
    return listTargets(payload.sourceFormatId);
  },
  async planRoute(payload: BridgePlanPayload) {
    await ensureInitialized();
    const path = await findBestPath(payload.sourceFormatId, payload.targetFormatId);
    if (!path) return null;
    const policy = applyExecutionPolicyOverrides(
      payload.performancePreset,
      payload.maxParallelJobs,
      payload.batteryFriendlyMode
    );
    const estimate = estimateRoute({
      path,
      totalBytes: payload.totalBytes,
      fileCount: payload.fileCount,
      policy,
      profile: runtimeProfile
    });
    return buildRoutePreview(path, estimate);
  },
  async generatePreview(payload: BridgePreviewPayload) {
    return inferPreview(payload);
  },
  async runConversion(payload: BridgeRunPayload) {
    return runConversion(payload);
  },
  async diagnostics() {
    return diagnostics();
  }
};

const deliverResult = (callId: string, ok: boolean, result: unknown) => {
  window.AndroidBridgeHost?.deliverResult(
    callId,
    JSON.stringify({ ok, result })
  );
};

window.__androidBridgeHandle = async (callId: string, method: string, payloadJson: string) => {
  try {
    const payload = payloadJson ? JSON.parse(payloadJson) : undefined;
    const target = api[method as keyof typeof api];
    if (typeof target !== "function") {
      throw new Error(`Unknown bridge method "${method}".`);
    }
    const result = await target(payload as never);
    deliverResult(callId, true, result ?? null);
  } catch (error) {
    deliverResult(callId, false, {
      message: error instanceof Error ? error.message : String(error)
    });
  }
};

window.__legacyBridge = {
  ensureReady: api.ensureReady,
  printSupportedFormatCache: api.printSupportedFormatCache
};
window.printSupportedFormatCache = api.printSupportedFormatCache;

ensureInitialized()
  .then(() => {
    console.info("Legacy Android bridge runtime ready.");
    window.AndroidBridgeHost?.onRuntimeReady?.();
  })
  .catch(error => {
    console.error("Legacy Android bridge runtime failed to initialize.", error);
  });
