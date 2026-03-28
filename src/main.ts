import { Capacitor } from "@capacitor/core";
import { Directory, Filesystem } from "@capacitor/filesystem";
import { Share } from "@capacitor/share";
import { SplashScreen } from "@capacitor/splash-screen";
import { StatusBar, Style } from "@capacitor/status-bar";
import { ConvertPathNode, type FileData, type FileFormat, type FormatHandler } from "./FormatHandler.js";
import type {
  DevicePerformanceProfile,
  ExecutionPolicy,
  PerformancePreset,
  RouteEstimate
} from "./performanceProfile.js";
import handlers from "./handlers";
import {
  buildExecutionPolicy,
  createHeuristicProfile,
  loadDeviceProfile
} from "./performanceProfile.js";
import {
  estimateRoute,
  getRouteKey,
  recordRouteObservation
} from "./routeEstimator.js";
import normalizeMimeType from "./normalizeMimeType.js";
import { TraversionGraph } from "./TraversionGraph.js";

type StepId = "upload" | "from" | "to" | "tune" | "review";
type ListSide = "input" | "output";
type ToastTone = "neutral" | "success" | "warning" | "danger";
type ThemePreference = "auto" | "light" | "dark";

interface FormatOptionRef {
  mime: string;
  format: string;
  handlerName: string;
  internal: string;
}

interface ExamplePreset {
  id: string;
  title: string;
  description: string;
  sourceUrl: string;
  fileName: string;
  output: {
    format: string;
    mime: string;
  };
}

interface RoutePlanState {
  status: "idle" | "loading" | "ready" | "unavailable";
  path: ConvertPathNode[] | null;
  estimate: RouteEstimate | null;
  error: string | null;
}

interface PreviewState {
  status: "idle" | "loading" | "ready" | "disabled" | "error";
  message: string;
  files: FileData[] | null;
}

const stepOrder: StepId[] = ["upload", "from", "to", "tune", "review"];
const examplePresets: ExamplePreset[] = [
  {
    id: "example-vector",
    title: "Vector to PNG",
    description: "Use a tiny SVG sample and land on a crisp PNG output.",
    sourceUrl: `${import.meta.env.BASE_URL}examples/vector-card.svg`,
    fileName: "vector-card.svg",
    output: { format: "png", mime: "image/png" }
  },
  {
    id: "example-voice",
    title: "Text to WAV",
    description: "Feed a short note and preview a speech-friendly route.",
    sourceUrl: `${import.meta.env.BASE_URL}examples/welcome.txt`,
    fileName: "welcome.txt",
    output: { format: "wav", mime: "audio/wav" }
  },
  {
    id: "example-doc",
    title: "Markdown to DOCX",
    description: "Try a document flow with a simple markdown note.",
    sourceUrl: `${import.meta.env.BASE_URL}examples/notes.md`,
    fileName: "notes.md",
    output: {
      format: "docx",
      mime: "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
  }
];

const preferredOutputsByCategory: Record<string, string[]> = {
  image: ["png", "jpeg", "webp", "gif", "svg", "pdf"],
  video: ["mp4", "webm", "gif", "png", "mp3"],
  audio: ["wav", "mp3", "flac", "ogg", "txt"],
  document: ["pdf", "docx", "html", "txt", "png"],
  text: ["txt", "md", "html", "pdf", "wav", "docx"],
  data: ["json", "csv", "xml", "yaml", "txt"],
  archive: ["zip", "tar", "cbz"],
  font: ["woff2", "otf", "ttf", "png"],
  code: ["txt", "json", "js", "cpp", "cs"],
  presentation: ["pptx", "pdf", "png"],
  spreadsheet: ["xlsx", "csv", "json"],
  default: ["pdf", "png", "txt", "zip", "json"]
};

const THEME_STORAGE_KEY = "convert-to-it.theme-preference.v1";
const PRESET_STORAGE_KEY = "convert-to-it.performance-preset.v1";

let selectedFiles: File[] = [];
let simpleMode = true;
let currentStep: StepId = "upload";
let selectedInputRef: FormatOptionRef | null = null;
let selectedOutputRef: FormatOptionRef | null = null;
let isConverting = false;
let routePlan: RoutePlanState = { status: "idle", path: null, estimate: null, error: null };
let previewState: PreviewState = {
  status: "disabled",
  message: "Pick a source file and target format to unlock preview.",
  files: null
};
let deadEndAttempts: ConvertPathNode[][] = [];
let themePreference: ThemePreference = loadThemePreference();
let performancePreset: PerformancePreset = loadPerformancePreset();
let deviceProfile: DevicePerformanceProfile = createHeuristicProfile();
let executionPolicy: ExecutionPolicy = buildExecutionPolicy(deviceProfile, performancePreset);
let routePlanRequestId = 0;
let previewRequestId = 0;
let activePreviewUrls: string[] = [];
let profileWarmupStarted = false;

const ui = {
  body: document.body,
  appShell: document.querySelector("#app-shell") as HTMLDivElement,
  fileInput: document.querySelector("#file-input") as HTMLInputElement,
  fileSelectArea: document.querySelector("#file-area") as HTMLButtonElement,
  inputList: document.querySelector("#from-list") as HTMLDivElement,
  outputList: document.querySelector("#to-list") as HTMLDivElement,
  inputSearch: document.querySelector("#search-from") as HTMLInputElement,
  outputSearch: document.querySelector("#search-to") as HTMLInputElement,
  selectionTitle: document.querySelector("#selection-title") as HTMLHeadingElement,
  selectionMeta: document.querySelector("#selection-meta") as HTMLParagraphElement,
  selectionKicker: document.querySelector("#selection-kicker") as HTMLParagraphElement,
  selectionList: document.querySelector("#selection-list") as HTMLDivElement,
  sourceInsight: document.querySelector("#source-insight") as HTMLParagraphElement,
  summaryFiles: document.querySelector("#summary-files") as HTMLElement,
  summaryFrom: document.querySelector("#summary-from") as HTMLElement,
  summaryTo: document.querySelector("#summary-to") as HTMLElement,
  summaryMode: document.querySelector("#summary-mode") as HTMLElement,
  summaryPolicy: document.querySelector("#summary-policy") as HTMLElement,
  reviewHint: document.querySelector("#review-hint") as HTMLDivElement,
  routingButtons: Array.from(document.querySelectorAll<HTMLButtonElement>("[data-routing-mode]")),
  themeButtons: Array.from(document.querySelectorAll<HTMLButtonElement>("[data-theme-preference]")),
  performanceButtons: Array.from(document.querySelectorAll<HTMLButtonElement>("[data-performance-preset]")),
  popupBg: document.querySelector("#popup-bg") as HTMLDivElement,
  popup: document.querySelector("#popup") as HTMLDivElement,
  popupContent: document.querySelector("#popup-content") as HTMLDivElement,
  toastRegion: document.querySelector("#toast-region") as HTMLDivElement,
  liveRegion: document.querySelector("#live-region") as HTMLDivElement,
  backButton: document.querySelector("#back-button") as HTMLButtonElement,
  nextButton: document.querySelector("#next-button") as HTMLButtonElement,
  convertButton: document.querySelector("#convert-button") as HTMLButtonElement,
  fromCount: document.querySelector("#from-count") as HTMLSpanElement,
  toCount: document.querySelector("#to-count") as HTMLSpanElement,
  stepButtons: Array.from(document.querySelectorAll<HTMLButtonElement>("[data-step-target]")),
  stages: Array.from(document.querySelectorAll<HTMLElement>(".wizard-stage")),
  commitId: document.querySelector("#commit-id") as HTMLAnchorElement,
  outputSuggestions: document.querySelector("#to-suggestions") as HTMLDivElement,
  exampleList: document.querySelector("#example-list") as HTMLDivElement,
  tuneProfileStatus: document.querySelector("#tune-profile-status") as HTMLSpanElement,
  tuneProfileSummary: document.querySelector("#tune-profile-summary") as HTMLParagraphElement,
  tuneProfileDetail: document.querySelector("#tune-profile-detail") as HTMLParagraphElement,
  tuneEta: document.querySelector("#tune-eta") as HTMLElement,
  tuneIntensity: document.querySelector("#tune-intensity") as HTMLElement,
  tuneCpu: document.querySelector("#tune-cpu") as HTMLElement,
  tuneJobs: document.querySelector("#tune-jobs") as HTMLElement,
  tuneConfidence: document.querySelector("#tune-confidence") as HTMLElement,
  tuneRouteList: document.querySelector("#tune-route-list") as HTMLDivElement,
  tuneWhyList: document.querySelector("#tune-why-list") as HTMLUListElement,
  reviewRoute: document.querySelector("#review-route") as HTMLDivElement,
  reviewPolicy: document.querySelector("#review-policy") as HTMLParagraphElement,
  reviewPreviewStatus: document.querySelector("#review-preview-status") as HTMLParagraphElement,
  reviewPreviewMeta: document.querySelector("#review-preview-meta") as HTMLParagraphElement,
  reviewPreviewFrame: document.querySelector("#review-preview-frame") as HTMLDivElement
};

const allOptions: Array<{ format: FileFormat; handler: FormatHandler }> = [];

window.supportedFormatCache = new Map();
window.traversionGraph = new TraversionGraph();

function loadThemePreference (): ThemePreference {
  const raw = localStorage.getItem(THEME_STORAGE_KEY);
  if (raw === "light" || raw === "dark" || raw === "auto") return raw;
  return "auto";
}

function loadPerformancePreset (): PerformancePreset {
  const raw = localStorage.getItem(PRESET_STORAGE_KEY);
  if (raw === "responsive" || raw === "balanced" || raw === "max") return raw;
  return "balanced";
}

const hideNativeSplash = async () => {
  if (!Capacitor.isNativePlatform()) return;
  try {
    await SplashScreen.hide();
  } catch (error) {
    console.warn("Failed to hide native splash screen.", error);
  }
};

const escapeHtml = (value: string) => value
  .replaceAll("&", "&amp;")
  .replaceAll("<", "&lt;")
  .replaceAll(">", "&gt;")
  .replaceAll("\"", "&quot;")
  .replaceAll("'", "&#39;");

const formatBytes = (byteCount: number) => {
  if (!Number.isFinite(byteCount) || byteCount <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  let value = byteCount;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  return `${value >= 10 || unitIndex === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[unitIndex]}`;
};

const formatMillis = (value: number) => {
  if (value < 1000) return `${Math.max(1, Math.round(value))} ms`;
  if (value < 10_000) return `${(value / 1000).toFixed(1)} s`;
  return `${Math.round(value / 1000)} s`;
};

const splitFileName = (name: string) => {
  const lastDot = name.lastIndexOf(".");
  if (lastDot <= 0) {
    return { stem: name, extension: "" };
  }
  return {
    stem: name.slice(0, lastDot),
    extension: name.slice(lastDot)
  };
};

const sanitizeFileName = (name: string) => {
  const cleaned = name
    .trim()
    .replace(/[<>:"/\\|?*\u0000-\u001f]/g, "-")
    .replace(/\s+/g, " ")
    .replace(/\.+$/, "")
    .trim();
  return cleaned || `converted-${Date.now()}`;
};

const ensureUniqueFileNames = (files: FileData[]) => {
  const usedNames = new Set<string>();
  return files.map(file => {
    const sanitizedName = sanitizeFileName(file.name);
    const { stem, extension } = splitFileName(sanitizedName);
    if (!usedNames.has(sanitizedName)) {
      usedNames.add(sanitizedName);
      return { ...file, name: sanitizedName };
    }

    let suffix = 2;
    let nextName = `${stem}-${suffix}${extension}`;
    while (usedNames.has(nextName)) {
      suffix += 1;
      nextName = `${stem}-${suffix}${extension}`;
    }
    usedNames.add(nextName);
    return { ...file, name: nextName };
  });
};

const bytesToBase64 = (bytes: Uint8Array) => {
  let binary = "";
  const chunkSize = 0x8000;
  for (let index = 0; index < bytes.length; index += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(index, index + chunkSize));
  }
  return btoa(binary);
};

const totalSelectedBytes = () => selectedFiles.reduce((total, file) => total + file.size, 0);

const announce = (message: string) => {
  ui.liveRegion.textContent = "";
  requestAnimationFrame(() => {
    ui.liveRegion.textContent = message;
  });
};

const showToast = (message: string, tone: ToastTone = "neutral") => {
  const toast = document.createElement("div");
  toast.className = "toast";
  toast.dataset.tone = tone;
  toast.textContent = message;
  ui.toastRegion.appendChild(toast);
  announce(message);
  window.setTimeout(() => {
    toast.remove();
  }, 3600);
};

const waitForPaint = () => new Promise(resolve => {
  requestAnimationFrame(() => requestAnimationFrame(resolve));
});

const toFileData = async (file: File): Promise<FileData> => ({
  name: file.name,
  bytes: new Uint8Array(await file.arrayBuffer())
});

const normalizeMessage = (error: unknown) => {
  if (typeof error === "string" && error.trim()) return error;
  if (error instanceof Error && error.message.trim()) return error.message;
  return "Unknown error.";
};

const reportStartupError = async (error: unknown) => {
  const message = normalizeMessage(error);
  console.error("Startup error:", error);
  (window as Window & { showStartupFatal?: (value: string) => void }).showStartupFatal?.(message);
  isConverting = false;
  showToast(`Startup error: ${message}`, "danger");
  window.showPopup(
    `<h2>Startup error</h2>` +
    `<p>${escapeHtml(message)}</p>` +
    `<button type="button" onclick="window.hidePopup()">Close</button>`
  );
  await hideNativeSplash();
};

window.showPopup = (html: string) => {
  ui.popupContent.innerHTML = html;
  ui.popup.hidden = false;
  ui.popupBg.hidden = false;
};

window.hidePopup = () => {
  if (isConverting) return;
  ui.popup.hidden = true;
  ui.popupBg.hidden = true;
};

window.printSupportedFormatCache = () => {
  const entries = [];
  for (const entry of window.supportedFormatCache) {
    entries.push(entry);
  }
  return JSON.stringify(entries, null, 2);
};

const cleanFormatName = (name: string) => name
  .split("(")
  .join(")")
  .split(")")
  .filter((_, index) => index % 2 === 0)
  .filter(Boolean)
  .join(" ")
  .trim();

const createOptionRef = (option: { format: FileFormat; handler: FormatHandler }): FormatOptionRef => ({
  mime: normalizeFormatMime(option.format.mime),
  format: option.format.format,
  handlerName: option.handler.name,
  internal: option.format.internal
});

const optionFromButton = (button: HTMLButtonElement) => {
  const index = Number(button.getAttribute("format-index"));
  return allOptions[index];
};

const optionMatchesSelection = (
  selection: FormatOptionRef | null,
  option: { format: FileFormat; handler: FormatHandler },
  exact: boolean
) => {
  if (!selection) return false;
  if (normalizeFormatMime(option.format.mime) !== selection.mime || option.format.format !== selection.format) {
    return false;
  }
  if (!exact) return true;
  return option.handler.name === selection.handlerName && option.format.internal === selection.internal;
};

const findOptionBySelection = (selection: FormatOptionRef | null) => {
  if (!selection) return undefined;
  return allOptions.find(option => optionMatchesSelection(selection, option, true))
    || allOptions.find(option => optionMatchesSelection(selection, option, false));
};

const getSelectedOption = (side: ListSide) => findOptionBySelection(side === "input" ? selectedInputRef : selectedOutputRef);

const getPrimaryCategory = (format: FileFormat) => {
  if (Array.isArray(format.category)) return format.category[0] || format.mime.split("/")[0];
  return format.category || format.mime.split("/")[0];
};

const getFileExtension = (fileName: string) => fileName.split(".").pop()?.toLowerCase() ?? "";
const normalizeFormatMime = (mime: string) => normalizeMimeType(mime || "");
const isGenericMime = (mime: string) => !mime || mime === "application/octet-stream";

const filesShareCompatibleInputSignature = (files: File[]) => {
  if (files.length <= 1) return true;

  const distinctKnownMimes = new Set(
    files
      .map(file => normalizeFormatMime(file.type))
      .filter(mime => !isGenericMime(mime))
  );

  if (distinctKnownMimes.size <= 1) {
    return true;
  }

  const distinctExtensions = new Set(
    files
      .map(file => getFileExtension(file.name))
      .filter(Boolean)
  );

  return distinctExtensions.size === 1 && distinctExtensions.size > 0;
};

const getSourceInsightText = () => {
  if (selectedFiles.length === 0) {
    return "Try one of the examples or drop your own file to unlock route planning, preview and performance tuning.";
  }

  const leadFile = selectedFiles[0];
  const mime = normalizeMimeType(leadFile.type);
  if (mime.startsWith("image/")) {
    return "Image inputs get fast visual routes, thumbnail-friendly previews and same-medium suggestions first.";
  }
  if (mime.startsWith("audio/")) {
    return "Audio routes emphasize playable targets and will highlight when the path needs a heavier medium jump.";
  }
  if (mime.startsWith("video/")) {
    return "Video inputs can trigger heavier routes, so the Tune step will show CPU impact before you commit.";
  }
  if (mime.startsWith("text/") || mime.includes("json") || mime.includes("xml")) {
    return "Text-like inputs stay easy to browse and can branch into document, data and speech-friendly outputs.";
  }
  return "The app will auto-detect what it can, then surface route cost, CPU budget and preview options before conversion.";
};

const revokePreviewUrls = () => {
  for (const url of activePreviewUrls) {
    URL.revokeObjectURL(url);
  }
  activePreviewUrls = [];
};

const renderReviewPreview = () => {
  ui.reviewPreviewStatus.textContent = previewState.message;
  ui.reviewPreviewFrame.innerHTML = "";
  ui.reviewPreviewMeta.textContent = "";

  if (previewState.status !== "ready" || !previewState.files || previewState.files.length === 0) {
    ui.reviewPreviewFrame.innerHTML = `<div class="preview-placeholder">${escapeHtml(previewState.status === "loading" ? "Generating preview..." : "No live preview yet")}</div>`;
    return;
  }

  const outputOption = getSelectedOption("output");
  if (!outputOption) return;

  const primaryFile = previewState.files[0];
  const blob = new Blob([primaryFile.bytes as BlobPart], { type: outputOption.format.mime });
  const url = URL.createObjectURL(blob);
  activePreviewUrls.push(url);

  if (outputOption.format.mime.startsWith("image/")) {
    const image = document.createElement("img");
    image.className = "preview-image";
    image.src = url;
    image.alt = primaryFile.name;
    ui.reviewPreviewFrame.appendChild(image);
  } else if (outputOption.format.mime.startsWith("audio/")) {
    const audio = document.createElement("audio");
    audio.className = "preview-media";
    audio.controls = true;
    audio.src = url;
    ui.reviewPreviewFrame.appendChild(audio);
  } else if (outputOption.format.mime.startsWith("video/")) {
    const video = document.createElement("video");
    video.className = "preview-media";
    video.controls = true;
    video.muted = true;
    video.src = url;
    ui.reviewPreviewFrame.appendChild(video);
  } else if (
    outputOption.format.mime.startsWith("text/")
    || outputOption.format.mime.includes("json")
    || outputOption.format.mime.includes("xml")
    || outputOption.format.mime.includes("javascript")
  ) {
    const preview = document.createElement("pre");
    preview.className = "preview-text";
    preview.textContent = new TextDecoder().decode(primaryFile.bytes.slice(0, 3200));
    ui.reviewPreviewFrame.appendChild(preview);
    URL.revokeObjectURL(url);
    activePreviewUrls.pop();
  } else if (outputOption.format.mime === "application/pdf") {
    const frame = document.createElement("iframe");
    frame.className = "preview-frame";
    frame.src = url;
    frame.title = primaryFile.name;
    ui.reviewPreviewFrame.appendChild(frame);
  } else {
    const generic = document.createElement("div");
    generic.className = "preview-placeholder";
    generic.textContent = `${previewState.files.length} file${previewState.files.length === 1 ? "" : "s"} generated.`;
    ui.reviewPreviewFrame.appendChild(generic);
  }

  ui.reviewPreviewMeta.textContent =
    `${previewState.files.length} preview file${previewState.files.length === 1 ? "" : "s"} rendered using a single safe job.`;
};

const resetPreviewState = (message = "Preview will appear here after the route is tuned and review is active.") => {
  previewState = {
    status: "disabled",
    message,
    files: null
  };
  revokePreviewUrls();
  renderReviewPreview();
};

const clearRoutePlan = () => {
  routePlanRequestId += 1;
  routePlan = {
    status: "idle",
    path: null,
    estimate: null,
    error: null
  };
  resetPreviewState();
  renderTunePanel();
  renderSummary();
  syncStepUI();
};

const renderSelectionBadges = () => {
  ui.selectionList.innerHTML = "";
  if (selectedFiles.length === 0) {
    const chip = document.createElement("span");
    chip.textContent = "No files";
    ui.selectionList.appendChild(chip);
    return;
  }

  const leadFile = selectedFiles[0];
  const chips = [
    `${selectedFiles.length} file${selectedFiles.length === 1 ? "" : "s"}`,
    formatBytes(totalSelectedBytes()),
    leadFile.type ? normalizeMimeType(leadFile.type) : `.${getFileExtension(leadFile.name)}`
  ];

  for (const value of chips) {
    const chip = document.createElement("span");
    chip.textContent = value;
    ui.selectionList.appendChild(chip);
  }
};

const renderSelectionCard = () => {
  ui.appShell.dataset.hasSelection = selectedFiles.length > 0 ? "true" : "false";
  if (selectedFiles.length === 0) {
    ui.selectionKicker.textContent = "Ready when you are";
    ui.selectionTitle.textContent = "No file selected yet";
    ui.selectionMeta.textContent = "Load a file to unlock route planning, performance tuning and live preview.";
    ui.sourceInsight.textContent = getSourceInsightText();
    renderSelectionBadges();
    return;
  }

  const leadFile = selectedFiles[0];
  ui.selectionKicker.textContent = selectedFiles.length === 1 ? "File loaded" : "Batch loaded";
  ui.selectionTitle.textContent = selectedFiles.length === 1
    ? leadFile.name
    : `${leadFile.name} and ${selectedFiles.length - 1} more`;
  ui.selectionMeta.textContent = selectedFiles.length === 1
    ? `Detected as ${normalizeMimeType(leadFile.type) || `.${getFileExtension(leadFile.name)}`}. You can still override the input format before converting.`
    : "Batch mode is enabled. Compatible steps may fan out across files depending on the selected performance preset.";
  ui.sourceInsight.textContent = getSourceInsightText();
  renderSelectionBadges();
};

const renderThemeUI = () => {
  const resolvedTheme = themePreference === "auto"
    ? (window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light")
    : themePreference;

  document.documentElement.dataset.theme = resolvedTheme;
  document.documentElement.dataset.themePreference = themePreference;
  ui.body.dataset.theme = resolvedTheme;

  for (const button of ui.themeButtons) {
    const selected = button.dataset.themePreference === themePreference;
    button.dataset.selected = selected ? "true" : "false";
  }
};

const renderRoutingUI = () => {
  document.documentElement.dataset.routingMode = simpleMode ? "simple" : "advanced";
  for (const button of ui.routingButtons) {
    const selected = (button.dataset.routingMode === "simple") === simpleMode;
    button.dataset.selected = selected ? "true" : "false";
  }
};

const renderPerformanceUI = () => {
  for (const button of ui.performanceButtons) {
    const selected = button.dataset.performancePreset === performancePreset;
    button.dataset.selected = selected ? "true" : "false";
  }
};

const scoreSuggestedOutput = (
  option: { format: FileFormat; handler: FormatHandler },
  inputOption: { format: FileFormat; handler: FormatHandler },
  preferredFormats: string[]
) => {
  let score = 0;
  const inputCategory = getPrimaryCategory(inputOption.format);
  const outputCategory = getPrimaryCategory(option.format);
  if (option.format.mime === inputOption.format.mime && option.format.format === inputOption.format.format) {
    score -= 12;
  }
  if (inputCategory === outputCategory) score += 22;
  if (preferredFormats.includes(option.format.format)) {
    score += 18 - preferredFormats.indexOf(option.format.format);
  }
  if (option.format.lossless) score += 2;
  if (option.handler.performanceClass === "light") score += 1;
  if (option.handler.performanceClass === "heavy" && outputCategory !== inputCategory) score -= 2;
  return score;
};

const renderExamples = () => {
  ui.exampleList.innerHTML = "";
  for (const preset of examplePresets) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "example-card";
    button.innerHTML =
      `<span class="example-overline">Example</span>` +
      `<strong>${escapeHtml(preset.title)}</strong>` +
      `<span>${escapeHtml(preset.description)}</span>`;
    button.addEventListener("click", () => {
      void loadExamplePreset(preset);
    });
    ui.exampleList.appendChild(button);
  }
};

const renderOutputSuggestions = () => {
  ui.outputSuggestions.innerHTML = "";
  const inputOption = getSelectedOption("input");
  if (!inputOption) {
    const placeholder = document.createElement("p");
    placeholder.className = "inline-note";
    placeholder.textContent = "Input detection will unlock a tighter set of suggested targets here.";
    ui.outputSuggestions.appendChild(placeholder);
    return;
  }

  const preferredFormats = preferredOutputsByCategory[getPrimaryCategory(inputOption.format)]
    || preferredOutputsByCategory.default;

  const candidates = Array.from(ui.outputList.children)
    .filter((child): child is HTMLButtonElement => child instanceof HTMLButtonElement)
    .map(button => ({ button, option: optionFromButton(button) }))
    .filter((entry): entry is { button: HTMLButtonElement; option: { format: FileFormat; handler: FormatHandler } } => Boolean(entry.option))
    .sort((left, right) => scoreSuggestedOutput(right.option, inputOption, preferredFormats) - scoreSuggestedOutput(left.option, inputOption, preferredFormats))
    .slice(0, 6);

  if (candidates.length === 0) {
    const placeholder = document.createElement("p");
    placeholder.className = "inline-note";
    placeholder.textContent = "No suggestions yet. Search the full output list below.";
    ui.outputSuggestions.appendChild(placeholder);
    return;
  }

  for (const candidate of candidates) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "suggestion-card";
    button.innerHTML =
      `<span class="suggestion-overline">${escapeHtml(candidate.option.format.format.toUpperCase())}</span>` +
      `<strong>${escapeHtml(cleanFormatName(candidate.option.format.name))}</strong>` +
      `<span>${escapeHtml(candidate.option.format.mime)}</span>`;
    button.addEventListener("click", () => {
      ui.outputSearch.value = "";
      filterButtonList(ui.outputList, "", ui.toCount, "format");
      candidate.button.click();
    });
    ui.outputSuggestions.appendChild(button);
  }
};

const renderTunePanel = () => {
  ui.tuneProfileStatus.textContent = deviceProfile.source === "benchmark"
    ? `Benchmarked in ${Math.round(deviceProfile.benchmarkDurationMs)} ms`
    : "Hardware fallback profile";
  ui.tuneProfileSummary.textContent =
    `${deviceProfile.hardwareConcurrency} logical thread${deviceProfile.hardwareConcurrency === 1 ? "" : "s"} detected. ` +
    `${performancePreset[0].toUpperCase()}${performancePreset.slice(1)} mode targets ${executionPolicy.targetThreads} threads and up to ${executionPolicy.maxParallelJobs} concurrent job${executionPolicy.maxParallelJobs === 1 ? "" : "s"}.`;
  ui.tuneProfileDetail.textContent = deviceProfile.source === "benchmark"
    ? `Benchmark-first estimate using local compute and memory throughput. Device score ${deviceProfile.overallScore.toFixed(2)}.`
    : "Benchmark is not ready yet or was unavailable, so estimates are currently using hardware hints only.";

  if (routePlan.status === "loading") {
    ui.tuneEta.textContent = "Analyzing...";
    ui.tuneIntensity.textContent = "Pending";
    ui.tuneCpu.textContent = `${Math.round(executionPolicy.cpuFraction * 100)}% budget`;
    ui.tuneJobs.textContent = `${executionPolicy.maxParallelJobs} jobs`;
    ui.tuneConfidence.textContent = deviceProfile.source === "benchmark" ? "Medium confidence" : "Low confidence";
    ui.tuneRouteList.innerHTML = `<p class="inline-note">Searching for the first viable route for this combination...</p>`;
    ui.tuneWhyList.innerHTML = "";
    return;
  }

  if (routePlan.status === "unavailable") {
    ui.tuneEta.textContent = "Unavailable";
    ui.tuneIntensity.textContent = "No route";
    ui.tuneCpu.textContent = "No estimate";
    ui.tuneJobs.textContent = `${executionPolicy.maxParallelJobs} jobs`;
    ui.tuneConfidence.textContent = "Low confidence";
    ui.tuneRouteList.innerHTML = `<p class="inline-note">${escapeHtml(routePlan.error ?? "The planner could not find a valid route.")}</p>`;
    ui.tuneWhyList.innerHTML = "";
    return;
  }

  if (routePlan.status !== "ready" || !routePlan.estimate || !routePlan.path) {
    ui.tuneEta.textContent = "--";
    ui.tuneIntensity.textContent = "--";
    ui.tuneCpu.textContent = `${Math.round(executionPolicy.cpuFraction * 100)}% budget`;
    ui.tuneJobs.textContent = `${executionPolicy.maxParallelJobs} jobs`;
    ui.tuneConfidence.textContent = deviceProfile.source === "benchmark" ? "Medium confidence" : "Low confidence";
    ui.tuneRouteList.innerHTML = `<p class="inline-note">Pick both formats to inspect the route, ETA and CPU cost.</p>`;
    ui.tuneWhyList.innerHTML = "";
    return;
  }

  ui.tuneEta.textContent = routePlan.estimate.etaLabel;
  ui.tuneIntensity.textContent = routePlan.estimate.intensity;
  ui.tuneCpu.textContent = routePlan.estimate.cpuImpactLabel;
  ui.tuneJobs.textContent = `${routePlan.estimate.maxParallelJobs} jobs`;
  ui.tuneConfidence.textContent = routePlan.estimate.confidenceLabel;

  ui.tuneRouteList.innerHTML = "";
  for (const [index, stepEstimate] of routePlan.estimate.stepEstimates.entries()) {
    const card = document.createElement("article");
    card.className = "route-step";
    card.innerHTML =
      `<span class="route-step-index">Step ${index + 1}</span>` +
      `<div class="route-step-main">` +
      `<strong>${escapeHtml(stepEstimate.fromLabel)} -> ${escapeHtml(stepEstimate.toLabel)}</strong>` +
      `<p>${escapeHtml(stepEstimate.handlerName)} | ${escapeHtml(stepEstimate.performanceClass)} | ${escapeHtml(stepEstimate.batchStrategy)}</p>` +
      `</div>` +
      `<div class="route-step-meta">` +
      `<span>${escapeHtml(formatMillis(stepEstimate.estimatedMs))}</span>` +
      `<span>${escapeHtml(stepEstimate.note)}</span>` +
      `</div>`;
    ui.tuneRouteList.appendChild(card);
  }

  ui.tuneWhyList.innerHTML = "";
  for (const reason of routePlan.estimate.reasons) {
    const item = document.createElement("li");
    item.textContent = reason;
    ui.tuneWhyList.appendChild(item);
  }
};

const renderReviewRoute = () => {
  ui.reviewRoute.innerHTML = "";
  if (!routePlan.path) {
    const chip = document.createElement("span");
    chip.className = "path-chip";
    chip.textContent = "Route preview unavailable";
    ui.reviewRoute.appendChild(chip);
    return;
  }

  for (const node of routePlan.path) {
    const chip = document.createElement("span");
    chip.className = "path-chip";
    chip.textContent = node.format.format.toUpperCase();
    ui.reviewRoute.appendChild(chip);
  }
};

const renderSummary = () => {
  const inputOption = getSelectedOption("input");
  const outputOption = getSelectedOption("output");

  ui.summaryFiles.textContent = selectedFiles.length === 0
    ? "No files selected"
    : `${selectedFiles.length} file${selectedFiles.length === 1 ? "" : "s"} | ${formatBytes(totalSelectedBytes())}`;
  ui.summaryFrom.textContent = inputOption
    ? `${inputOption.format.format.toUpperCase()} | ${inputOption.format.mime}`
    : "Pick an input format";
  ui.summaryTo.textContent = outputOption
    ? `${outputOption.format.format.toUpperCase()} | ${outputOption.format.mime}`
    : "Pick an output format";
  ui.summaryMode.textContent = simpleMode ? "Simple routing" : "Advanced routing";
  ui.summaryPolicy.textContent =
    `${performancePreset[0].toUpperCase()}${performancePreset.slice(1)} preset | ${executionPolicy.maxParallelJobs} job${executionPolicy.maxParallelJobs === 1 ? "" : "s"} max`;

  if (selectedFiles.length === 0) {
    ui.reviewHint.textContent = "Select or load a file to begin.";
  } else if (!inputOption) {
    ui.reviewHint.textContent = "Confirm the input format before exploring output targets.";
  } else if (!outputOption) {
    ui.reviewHint.textContent = "Pick an output format to unlock Tune and route planning.";
  } else if (routePlan.status === "loading") {
    ui.reviewHint.textContent = "Analyzing the route, ETA and CPU budget...";
  } else if (routePlan.status === "unavailable") {
    ui.reviewHint.textContent = routePlan.error ?? "No viable route was found for the current format pair.";
  } else if (routePlan.status === "ready" && routePlan.estimate) {
    ui.reviewHint.textContent =
      `${routePlan.estimate.routeSignatureLabel}. Estimated ${routePlan.estimate.etaLabel} with ${routePlan.estimate.cpuImpactLabel.toLowerCase()}.`;
  } else {
    ui.reviewHint.textContent = "Tune the route to inspect performance, then review before converting.";
  }

  ui.reviewPolicy.textContent =
    `Preview and review use the ${performancePreset} preset, but live preview always runs in a single safe job.`;
  renderReviewRoute();
};

const canEnterStep = (step: StepId) => {
  switch (step) {
    case "upload":
      return true;
    case "from":
      return selectedFiles.length > 0;
    case "to":
      return selectedInputRef !== null;
    case "tune":
      return selectedInputRef !== null && selectedOutputRef !== null;
    case "review":
      return selectedInputRef !== null && selectedOutputRef !== null;
  }
};

const isStepComplete = (step: StepId) => {
  switch (step) {
    case "upload":
      return selectedFiles.length > 0;
    case "from":
      return selectedInputRef !== null;
    case "to":
      return selectedOutputRef !== null;
    case "tune":
      return routePlan.status === "ready";
    case "review":
      return canConvert();
  }
};

const nextStepFrom = (step: StepId): StepId | null => {
  const stepIndex = stepOrder.indexOf(step);
  return stepOrder[stepIndex + 1] ?? null;
};

const previousStepFrom = (step: StepId): StepId | null => {
  const stepIndex = stepOrder.indexOf(step);
  return stepOrder[stepIndex - 1] ?? null;
};

function canConvert () {
  return selectedFiles.length > 0
    && selectedInputRef !== null
    && selectedOutputRef !== null
    && routePlan.status === "ready"
    && !isConverting;
}

const syncStepUI = () => {
  for (const stage of ui.stages) {
    const step = stage.dataset.step as StepId;
    stage.dataset.active = step === currentStep ? "true" : "false";
  }

  for (const button of ui.stepButtons) {
    const step = button.dataset.stepTarget as StepId;
    button.dataset.active = step === currentStep ? "true" : "false";
    button.dataset.complete = isStepComplete(step) ? "true" : "false";
    button.disabled = !canEnterStep(step);
  }

  const nextStep = nextStepFrom(currentStep);
  const previousStep = previousStepFrom(currentStep);

  ui.backButton.hidden = previousStep === null;
  ui.backButton.disabled = previousStep === null || isConverting;

  if (currentStep === "review") {
    ui.nextButton.hidden = true;
    ui.convertButton.hidden = false;
  } else {
    ui.nextButton.hidden = false;
    ui.convertButton.hidden = true;
    ui.nextButton.textContent =
      currentStep === "upload" ? "Choose input format" :
      currentStep === "from" ? "Choose output format" :
      currentStep === "to" ? "Tune route" :
      "Review conversion";
    ui.nextButton.disabled =
      nextStep === null
      || !canEnterStep(nextStep)
      || isConverting
      || (currentStep === "tune" && routePlan.status === "loading");
  }

  ui.convertButton.disabled = !canConvert();
};

const setCurrentStep = (step: StepId) => {
  if (!canEnterStep(step) && step !== "upload") return;
  currentStep = step;
  if (step !== "review") {
    previewRequestId += 1;
    if (previewState.status === "loading") {
      resetPreviewState("Preview will resume when you return to Review.");
    } else {
      renderReviewPreview();
    }
  } else {
    void maybeStartPreview();
  }
  syncStepUI();
};

const updateVisibleCount = (list: HTMLDivElement, counter: HTMLSpanElement, label: string) => {
  const visibleCount = Array.from(list.children).filter(child => {
    return child instanceof HTMLButtonElement && child.style.display !== "none";
  }).length;
  counter.textContent = `${visibleCount} ${label}${visibleCount === 1 ? "" : "s"}`;
  return visibleCount;
};

const filterButtonList = (list: HTMLDivElement, value: string, counter: HTMLSpanElement, label: string) => {
  const lowered = value.toLowerCase();
  for (const child of Array.from(list.children)) {
    if (!(child instanceof HTMLButtonElement)) continue;
    const option = optionFromButton(child);
    if (!option) continue;
    const matchesText = child.textContent?.toLowerCase().includes(lowered) ?? false;
    const matchesExt = option.format.extension.toLowerCase().includes(lowered);
    const matchesMime = option.format.mime.toLowerCase().includes(lowered);
    child.style.display = !lowered || matchesText || matchesExt || matchesMime ? "" : "none";
  }
  return updateVisibleCount(list, counter, label);
};

const clearSelection = (side: ListSide) => {
  const list = side === "input" ? ui.inputList : ui.outputList;
  if (side === "input") {
    selectedInputRef = null;
  } else {
    selectedOutputRef = null;
  }

  for (const child of Array.from(list.children)) {
    if (child instanceof HTMLButtonElement) {
      child.classList.remove("selected");
    }
  }
};

const resetConversionFlow = () => {
  clearSelection("input");
  clearSelection("output");
  ui.inputSearch.value = "";
  ui.outputSearch.value = "";
  filterButtonList(ui.inputList, ui.inputSearch.value, ui.fromCount, "format");
  filterButtonList(ui.outputList, ui.outputSearch.value, ui.toCount, "format");
  clearRoutePlan();
  renderOutputSuggestions();
  renderSummary();
  syncStepUI();
};

const restoreSelection = (side: ListSide) => {
  const selection = side === "input" ? selectedInputRef : selectedOutputRef;
  const list = side === "input" ? ui.inputList : ui.outputList;
  const buttons = Array.from(list.children).filter(
    child => child instanceof HTMLButtonElement
  ) as HTMLButtonElement[];

  const exact = buttons.find(button => {
    const option = optionFromButton(button);
    return option ? optionMatchesSelection(selection, option, true) : false;
  });
  const fallback = buttons.find(button => {
    const option = optionFromButton(button);
    return option ? optionMatchesSelection(selection, option, false) : false;
  });

  const match = exact || fallback;
  if (match) {
    applySelection(side, match, false);
    return;
  }

  if (side === "input") {
    selectedInputRef = null;
  } else {
    selectedOutputRef = null;
  }
};

const renderListMeta = (option: { format: FileFormat; handler: FormatHandler }, side: ListSide) => {
  const metaParts = [
    option.format.mime,
    `.${option.format.extension}`
  ];
  if (side === "output" && option.handler.performanceClass) {
    metaParts.push(option.handler.performanceClass);
  }
  if (!simpleMode) metaParts.push(`via ${option.handler.name}`);
  if (option.format.lossless) metaParts.push("lossless");
  return metaParts.join(" | ");
};

const createFormatButton = (
  option: { format: FileFormat; handler: FormatHandler },
  side: ListSide
) => {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "format-option";
  button.setAttribute("format-index", String(allOptions.length - 1));
  button.setAttribute("mime-type", normalizeFormatMime(option.format.mime));

  const overline = document.createElement("span");
  overline.className = "format-overline";
  overline.textContent = option.format.format.toUpperCase();

  const title = document.createElement("span");
  title.className = "format-title";
  title.textContent = simpleMode ? cleanFormatName(option.format.name) : option.format.name;

  const meta = document.createElement("span");
  meta.className = "format-meta";
  meta.textContent = renderListMeta(option, side);

  button.append(overline, title, meta);
  button.addEventListener("click", () => applySelection(side, button));
  return button;
};

async function buildOptionList () {
  const previousInput = selectedInputRef;
  const previousOutput = selectedOutputRef;
  const inputSearchValue = ui.inputSearch.value;
  const outputSearchValue = ui.outputSearch.value;

  allOptions.length = 0;
  ui.inputList.innerHTML = "";
  ui.outputList.innerHTML = "";

  const seenInputs = new Set<string>();
  const seenOutputs = new Set<string>();

  for (const handler of handlers) {
    if (!window.supportedFormatCache.has(handler.name)) {
      try {
        await handler.init();
      } catch {
        continue;
      }
      if (handler.supportedFormats) {
        window.supportedFormatCache.set(handler.name, handler.supportedFormats);
      }
    }

    const supportedFormats = window.supportedFormatCache.get(handler.name);
    if (!supportedFormats) continue;

    for (const format of supportedFormats) {
      if (!format.mime) continue;

      allOptions.push({ format, handler });

      const dedupeKey = `${normalizeFormatMime(format.mime)}::${format.format}`;
      if (format.from && (!simpleMode || !seenInputs.has(dedupeKey))) {
        const button = createFormatButton({ format, handler }, "input");
        ui.inputList.appendChild(button);
        seenInputs.add(dedupeKey);
      }
      if (format.to && (!simpleMode || !seenOutputs.has(dedupeKey))) {
        const button = createFormatButton({ format, handler }, "output");
        ui.outputList.appendChild(button);
        seenOutputs.add(dedupeKey);
      }
    }
  }

  window.traversionGraph.init(window.supportedFormatCache, handlers);

  selectedInputRef = previousInput;
  selectedOutputRef = previousOutput;
  restoreSelection("input");
  restoreSelection("output");

  filterButtonList(ui.inputList, inputSearchValue, ui.fromCount, "format");
  filterButtonList(ui.outputList, outputSearchValue, ui.toCount, "format");
  renderOutputSuggestions();
  renderSummary();
  renderTunePanel();
  syncStepUI();
  window.hidePopup();

  if (selectedInputRef && selectedOutputRef) {
    void refreshRoutePlan();
  }
}

const searchHandler = (event: Event) => {
  const target = event.target;
  if (!(target instanceof HTMLInputElement)) return;

  if (target === ui.inputSearch) {
    filterButtonList(ui.inputList, target.value, ui.fromCount, "format");
  } else {
    filterButtonList(ui.outputList, target.value, ui.toCount, "format");
  }
};

const findMatchingInputButton = (file: File) => {
  const mimeType = normalizeFormatMime(file.type);
  const fileExtension = getFileExtension(file.name);
  const buttons = Array.from(ui.inputList.children).filter(
    child => child instanceof HTMLButtonElement
  ) as HTMLButtonElement[];

  if (mimeType) {
    const matchingMime = buttons.filter(button => button.getAttribute("mime-type") === mimeType);
    const matchingMimeAndExtension = matchingMime.find(button => {
      const option = optionFromButton(button);
      return option?.format.extension.toLowerCase() === fileExtension;
    });
    if (matchingMimeAndExtension) {
      return { button: matchingMimeAndExtension, searchValue: mimeType };
    }
    if (matchingMime[0]) {
      return { button: matchingMime[0], searchValue: mimeType };
    }
  }

  const matchingExtension = buttons.find(button => {
    const option = optionFromButton(button);
    return option?.format.extension.toLowerCase() === fileExtension;
  });

  if (matchingExtension) {
    return {
      button: matchingExtension,
      searchValue: matchingExtension.getAttribute("mime-type") || fileExtension
    };
  }

  return null;
};

const collectTransferFiles = (transfer: DataTransfer | null | undefined): File[] => {
  const directFiles = Array.from(transfer?.files ?? []);
  if (directFiles.length > 0) {
    return directFiles;
  }

  return Array.from(transfer?.items ?? [])
    .map(item => (item.kind === "file" ? item.getAsFile() : null))
    .filter((file): file is File => file instanceof File);
};

const autoSelectInputFormat = (file: File) => {
  const match = findMatchingInputButton(file);
  if (match) {
    ui.inputSearch.value = match.searchValue;
    filterButtonList(ui.inputList, ui.inputSearch.value, ui.fromCount, "format");
    match.button.click();
    return;
  }

  const fallbackSearchValue = getFileExtension(file.name);
  ui.inputSearch.value = fallbackSearchValue;
  const visibleCount = filterButtonList(ui.inputList, ui.inputSearch.value, ui.fromCount, "format");
  if (visibleCount === 0) {
    ui.inputSearch.value = "";
    filterButtonList(ui.inputList, ui.inputSearch.value, ui.fromCount, "format");
  }
  showToast("Input format was not confidently detected. Please pick it manually.", "warning");
};

const applySelection = (side: ListSide, button: HTMLButtonElement, autoAdvance = true) => {
  const option = optionFromButton(button);
  if (!option) return;

  const parent = side === "input" ? ui.inputList : ui.outputList;
  for (const child of Array.from(parent.children)) {
    if (child instanceof HTMLButtonElement) {
      child.classList.remove("selected");
    }
  }
  button.classList.add("selected");

  if (side === "input") {
    const changed = !optionMatchesSelection(selectedInputRef, option, true);
    selectedInputRef = createOptionRef(option);
    if (changed && selectedOutputRef) {
      clearSelection("output");
      clearRoutePlan();
    }
    renderOutputSuggestions();
    if (autoAdvance) setCurrentStep("to");
  } else {
    selectedOutputRef = createOptionRef(option);
    if (autoAdvance) setCurrentStep("tune");
    void refreshRoutePlan();
  }

  renderSummary();
  syncStepUI();
};

const setDragState = (active: boolean) => {
  ui.body.dataset.dragging = active ? "true" : "false";
};

const fileSelectHandler = (event: Event) => {
  let files: File[] = [];
  let sourceInput: HTMLInputElement | null = null;

  if (event instanceof DragEvent) {
    event.preventDefault();
    files = collectTransferFiles(event.dataTransfer);
    ui.body.dataset.dragging = "false";
  } else if (event instanceof ClipboardEvent) {
    files = collectTransferFiles(event.clipboardData);
  } else {
    const target = event.target;
    if (!(target instanceof HTMLInputElement)) return;
    sourceInput = target;
    files = Array.from(target.files ?? []);
  }

  if (sourceInput) sourceInput.value = "";
  if (files.length === 0) return;

  if (!filesShareCompatibleInputSignature(files)) {
    showToast("All selected files must share the same MIME type or file extension.", "danger");
    return;
  }

  files.sort((a, b) => a.name.localeCompare(b.name));
  selectedFiles = files;
  resetConversionFlow();
  window.hidePopup();
  renderSelectionCard();
  renderSummary();
  setCurrentStep("from");
  autoSelectInputFormat(files[0]);
};

const pathMatchesDeadEnd = (path: ConvertPathNode[]) => {
  for (const deadEnd of deadEndAttempts) {
    let matchesDeadEnd = true;
    for (let index = 0; index < deadEnd.length; index += 1) {
      if (path[index] !== deadEnd[index]) {
        matchesDeadEnd = false;
        break;
      }
    }
    if (matchesDeadEnd) return true;
  }
  return false;
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
      await waitForPaint();
    }
  }));

  return results.flat();
};

async function attemptConvertPath (
  files: FileData[],
  path: ConvertPathNode[],
  policy: ExecutionPolicy,
  options: {
    preview?: boolean;
    showProgress?: boolean;
    trackDeadEnds?: boolean;
  } = {}
) {
  const pathString = path.map(node => node.format.format).join(" -> ");

  if (options.trackDeadEnds && pathMatchesDeadEnd(path)) {
    return null;
  }

  if (options.showProgress) {
    window.showPopup(`<h2>Finding conversion route...</h2><p>Trying <b>${escapeHtml(pathString)}</b>.</p>`);
  }

  for (let index = 0; index < path.length - 1; index += 1) {
    const handler = path[index + 1].handler;
    try {
      let supportedFormats = window.supportedFormatCache.get(handler.name);
      if (!handler.ready) {
        await handler.init();
        if (!handler.ready) throw new Error(`Handler "${handler.name}" not ready after init.`);
        if (handler.supportedFormats) {
          window.supportedFormatCache.set(handler.name, handler.supportedFormats);
          supportedFormats = handler.supportedFormats;
        }
      }
      if (!supportedFormats) throw new Error(`Handler "${handler.name}" doesn't support any formats.`);

      const inputFormat = supportedFormats.find(format =>
        format.from
        && format.mime === path[index].format.mime
        && format.format === path[index].format.format
      ) || (handler.supportAnyInput ? path[index].format : undefined);

      if (!inputFormat) {
        throw new Error(`Handler "${handler.name}" doesn't support the "${path[index].format.format}" format.`);
      }

      files = await executeHandlerWithPolicy(
        handler,
        files,
        inputFormat,
        path[index + 1].format,
        undefined,
        policy
      );

      if (files.some(file => !file.bytes.length)) throw new Error("Output is empty.");
      if (!options.preview) {
        await waitForPaint();
      }
    } catch (error) {
      if (options.trackDeadEnds) {
        const deadEndPath = path.slice(0, index + 2);
        deadEndAttempts.push(deadEndPath);
        window.traversionGraph.addDeadEndPath(deadEndPath);
      }
      console.error(handler.name, `${path[index].format.format} -> ${path[index + 1].format.format}`, error);
      return null;
    }
  }

  return { files, path };
}

async function findPlannedPath (from: ConvertPathNode, to: ConvertPathNode) {
  for await (const path of window.traversionGraph.searchPath(from, to, simpleMode)) {
    const finalPath = [...path];
    if (finalPath.at(-1)?.handler === to.handler) {
      finalPath[finalPath.length - 1] = to;
    }
    return finalPath;
  }
  return null;
}

async function tryConvertByTraversingInternal (
  files: FileData[],
  from: ConvertPathNode,
  to: ConvertPathNode,
  policy: ExecutionPolicy,
  preferredPath: ConvertPathNode[] | null = null,
  showProgress = false
) {
  deadEndAttempts = [];
  window.traversionGraph.clearDeadEndPaths();

  if (preferredPath) {
    const preferredAttempt = await attemptConvertPath(files, preferredPath, policy, {
      preview: false,
      showProgress,
      trackDeadEnds: true
    });
    if (preferredAttempt) return preferredAttempt;
  }

  for await (const path of window.traversionGraph.searchPath(from, to, simpleMode)) {
    const candidatePath = [...path];
    if (candidatePath.at(-1)?.handler === to.handler) {
      candidatePath[candidatePath.length - 1] = to;
    }
    const attempt = await attemptConvertPath(files, candidatePath, policy, {
      preview: false,
      showProgress,
      trackDeadEnds: true
    });
    if (attempt) return attempt;
    if (showProgress) {
      window.showPopup("<h2>Finding conversion route...</h2><p>Looking for a valid path...</p>");
      await waitForPaint();
    }
  }
  return null;
}

window.tryConvertByTraversing = async (
  files: FileData[],
  from: ConvertPathNode,
  to: ConvertPathNode
) => tryConvertByTraversingInternal(files, from, to, executionPolicy, null, false);

const getPreviewEligibility = () => {
  if (selectedFiles.length !== 1) {
    return { canPreview: false, message: "Live preview is available for a single source file at a time." };
  }

  if (selectedFiles[0].size > 10_000_000) {
    return { canPreview: false, message: "Preview is paused for files larger than 10 MB to keep the app responsive." };
  }

  const outputOption = getSelectedOption("output");
  if (!outputOption) {
    return { canPreview: false, message: "Pick an output format to unlock preview." };
  }

  const mime = outputOption.format.mime;
  const previewable = mime.startsWith("image/")
    || mime.startsWith("audio/")
    || mime.startsWith("video/")
    || mime.startsWith("text/")
    || mime.includes("json")
    || mime.includes("xml")
    || mime === "application/pdf";

  if (!previewable) {
    return { canPreview: false, message: "This output type does not have an inline preview yet, but the route can still run normally." };
  }

  return { canPreview: true, message: "" };
};

async function maybeStartPreview () {
  if (currentStep !== "review") return;
  if (routePlan.status !== "ready" || !routePlan.path) {
    resetPreviewState("Route planning has to finish before preview can start.");
    return;
  }

  const previewEligibility = getPreviewEligibility();
  if (!previewEligibility.canPreview) {
    resetPreviewState(previewEligibility.message);
    return;
  }

  const requestId = ++previewRequestId;
  previewState = {
    status: "loading",
    message: "Generating live preview on a single safe job...",
    files: null
  };
  renderReviewPreview();

  const inputOption = getSelectedOption("input");
  const outputOption = getSelectedOption("output");
  if (!inputOption || !outputOption) return;

  const previewPolicy: ExecutionPolicy = {
    ...executionPolicy,
    targetThreads: 1,
    maxParallelJobs: 1
  };

  const previewFiles = [await toFileData(selectedFiles[0])];
  const startedAt = performance.now();

  let output = await attemptConvertPath(previewFiles, routePlan.path, previewPolicy, {
    preview: true,
    showProgress: false,
    trackDeadEnds: false
  });

  if (!output) {
    output = await tryConvertByTraversingInternal(
      previewFiles,
      new ConvertPathNode(inputOption.handler, inputOption.format),
      new ConvertPathNode(outputOption.handler, outputOption.format),
      previewPolicy,
      null,
      false
    );
  }

  if (requestId !== previewRequestId || currentStep !== "review") {
    return;
  }

  if (!output) {
    previewState = {
      status: "error",
      message: "Preview could not be generated for this route, but you can still run the full conversion.",
      files: null
    };
    renderReviewPreview();
    return;
  }

  previewState = {
    status: "ready",
    message: `Preview ready via ${output.path.map(node => node.format.format.toUpperCase()).join(" -> ")}.`,
    files: output.files
  };
  renderReviewPreview();
  recordRouteObservation(
    getRouteKey(output.path),
    previewFiles[0].bytes.length,
    performance.now() - startedAt,
    true,
    deviceProfile.signature
  );
}

async function refreshRoutePlan () {
  const inputOption = getSelectedOption("input");
  const outputOption = getSelectedOption("output");
  const requestId = ++routePlanRequestId;
  previewRequestId += 1;

  if (!inputOption || !outputOption) {
    clearRoutePlan();
    return;
  }

  routePlan = {
    status: "loading",
    path: null,
    estimate: null,
    error: null
  };
  renderTunePanel();
  renderSummary();
  syncStepUI();

  const from = new ConvertPathNode(inputOption.handler, inputOption.format);
  const to = new ConvertPathNode(outputOption.handler, outputOption.format);

  try {
    const path = await findPlannedPath(from, to);
    if (requestId !== routePlanRequestId) return;

    if (!path) {
      routePlan = {
        status: "unavailable",
        path: null,
        estimate: null,
        error: "The planner could not find a route for the selected source and target."
      };
      renderTunePanel();
      renderSummary();
      syncStepUI();
      return;
    }

    const estimate = estimateRoute({
      path,
      totalBytes: totalSelectedBytes(),
      fileCount: selectedFiles.length,
      policy: executionPolicy,
      profile: deviceProfile
    });

    routePlan = {
      status: "ready",
      path,
      estimate,
      error: null
    };
    renderTunePanel();
    renderSummary();
    syncStepUI();

    if (currentStep === "review") {
      void maybeStartPreview();
    }
  } catch (error) {
    if (requestId !== routePlanRequestId) return;
    routePlan = {
      status: "unavailable",
      path: null,
      estimate: null,
      error: normalizeMessage(error)
    };
    renderTunePanel();
    renderSummary();
    syncStepUI();
  }
}

async function loadExamplePreset (preset: ExamplePreset) {
  try {
    const response = await fetch(preset.sourceUrl);
    if (!response.ok) throw new Error(`Could not load ${preset.title}.`);
    const blob = await response.blob();
    const file = new File([blob], preset.fileName, {
      type: blob.type || "application/octet-stream"
    });
    selectedFiles = [file];
    resetConversionFlow();
    renderSelectionCard();
    renderSummary();
    setCurrentStep("from");
    autoSelectInputFormat(file);
    await waitForPaint();

    const outputButton = Array.from(ui.outputList.children)
      .filter((child): child is HTMLButtonElement => child instanceof HTMLButtonElement)
      .find(button => {
        const option = optionFromButton(button);
        return option?.format.format === preset.output.format && option.format.mime === preset.output.mime;
      });
    if (outputButton) {
      outputButton.click();
    }

    showToast(`${preset.title} example loaded.`, "success");
  } catch (error) {
    showToast(normalizeMessage(error), "danger");
  }
}

const setRoutingMode = async (nextSimple: boolean) => {
  if (simpleMode === nextSimple) {
    renderRoutingUI();
    return;
  }

  simpleMode = nextSimple;
  renderRoutingUI();
  clearRoutePlan();
  showToast(simpleMode ? "Simple routing enabled." : "Advanced routing enabled.", "success");
  window.showPopup(`<h2>Refreshing routes...</h2><p>Rebuilding the format list for ${simpleMode ? "simple" : "advanced"} mode.</p>`);
  await buildOptionList();
};

const setThemePreference = (nextPreference: ThemePreference) => {
  if (themePreference === nextPreference) return;
  themePreference = nextPreference;
  localStorage.setItem(THEME_STORAGE_KEY, themePreference);
  renderThemeUI();
};

const setPerformancePreset = (nextPreset: PerformancePreset) => {
  if (performancePreset === nextPreset) return;
  performancePreset = nextPreset;
  localStorage.setItem(PRESET_STORAGE_KEY, performancePreset);
  executionPolicy = buildExecutionPolicy(deviceProfile, performancePreset);
  renderPerformanceUI();
  renderSummary();
  renderTunePanel();
  syncStepUI();
  if (selectedInputRef && selectedOutputRef) {
    void refreshRoutePlan();
  }
};

const downloadFile = (bytes: Uint8Array, name: string) => {
  const blob = new Blob([bytes as BlobPart], { type: "application/octet-stream" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.style.display = "none";
  document.body.appendChild(link);
  link.click();
  window.setTimeout(() => {
    link.remove();
    URL.revokeObjectURL(url);
  }, 2000);
};

const exportNativeFiles = async (files: FileData[]) => {
  const batchId = new Date().toISOString().replaceAll(":", "-").replaceAll(".", "-");
  const exportFiles = ensureUniqueFileNames(files);
  const fileUris: string[] = [];

  for (const file of exportFiles) {
    const path = `exports/${batchId}/${file.name}`;
    await Filesystem.writeFile({
      path,
      data: bytesToBase64(file.bytes),
      directory: Directory.Cache,
      recursive: true
    });
    const { uri } = await Filesystem.getUri({
      path,
      directory: Directory.Cache
    });
    fileUris.push(uri);
  }

  const shareAvailable = await Share.canShare();
  if (!shareAvailable.value) {
    throw new Error("Native sharing is unavailable on this device.");
  }

  showToast(
    fileUris.length === 1
      ? "Choose where to save or share the converted file."
      : `Choose where to save or share ${fileUris.length} converted files.`,
    "neutral"
  );

  await Share.share({
    title: fileUris.length === 1 ? exportFiles[0].name : "Converted files",
    text: fileUris.length === 1
      ? "Your converted file is ready."
      : `${fileUris.length} converted files are ready.`,
    files: fileUris,
    dialogTitle: fileUris.length === 1 ? "Save converted file" : "Save converted files"
  });
};

const deliverOutputFiles = async (files: FileData[]) => {
  if (files.length === 0) return;
  if (!Capacitor.isNativePlatform()) {
    for (const file of files) {
      downloadFile(file.bytes, file.name);
    }
    return;
  }
  await exportNativeFiles(files);
};

const finishConversionState = () => {
  isConverting = false;
  syncStepUI();
};

const convert = async () => {
  if (!canConvert()) {
    showToast("Select files, tune the route and wait for planning before converting.", "warning");
    return;
  }

  const inputOption = getSelectedOption("input");
  const outputOption = getSelectedOption("output");
  if (!inputOption || !outputOption) return;

  isConverting = true;
  syncStepUI();

  try {
    const inputFileData: FileData[] = [];
    const passthroughFiles: FileData[] = [];
    for (const inputFile of selectedFiles) {
      const inputBuffer = await inputFile.arrayBuffer();
      const inputBytes = new Uint8Array(inputBuffer);
      if (
        inputOption.format.mime === outputOption.format.mime
        && inputOption.format.format === outputOption.format.format
      ) {
        passthroughFiles.push({ name: inputFile.name, bytes: inputBytes });
        continue;
      }
      inputFileData.push({ name: inputFile.name, bytes: inputBytes });
    }

    if (inputFileData.length === 0) {
      await deliverOutputFiles(passthroughFiles);
      finishConversionState();
      const passthroughCount = passthroughFiles.length;
      showToast(
        passthroughCount === 1
          ? "Source and target already match. File ready."
          : "Source and target already match. Files ready.",
        "success"
      );
      window.showPopup(
        `<h2>No conversion needed</h2>` +
        `<p>${passthroughCount === 1 ? "The original file is ready." : `The ${passthroughCount} original files are ready.`}</p>` +
        `<button type="button" onclick="window.hidePopup()">Done</button>`
      );
      return;
    }

    window.showPopup("<h2>Finding conversion route...</h2><p>Preparing the first route candidate.</p>");
    await waitForPaint();

    const startedAt = performance.now();
    const output = await tryConvertByTraversingInternal(
      inputFileData,
      new ConvertPathNode(inputOption.handler, inputOption.format),
      new ConvertPathNode(outputOption.handler, outputOption.format),
      executionPolicy,
      routePlan.path,
      true
    );
    if (!output) {
      finishConversionState();
      window.hidePopup();
      showToast("Failed to find a valid conversion route.", "danger");
      return;
    }

    const finalFiles = [...passthroughFiles, ...output.files];
    await deliverOutputFiles(finalFiles);

    finishConversionState();
    recordRouteObservation(
      getRouteKey(output.path),
      totalSelectedBytes(),
      performance.now() - startedAt,
      false,
      deviceProfile.signature
    );
    showToast(finalFiles.length === 1 ? "Conversion complete." : `${finalFiles.length} files are ready.`, "success");

    const pathUsed = escapeHtml(output.path.map(node => node.format.format).join(" -> "));
    window.showPopup(
      `<h2>Conversion complete</h2>` +
      `<p>Path used: <b>${pathUsed}</b>.</p>` +
      `${passthroughFiles.length > 0
        ? `<p>${passthroughFiles.length} file${passthroughFiles.length === 1 ? "" : "s"} already matched the target and were included as-is.</p>`
        : ""}` +
      `<button type="button" onclick="window.hidePopup()">Done</button>`
    );
    void refreshRoutePlan();
  } catch (error) {
    console.error(error);
    finishConversionState();
    window.hidePopup();
    showToast(`Unexpected error during conversion: ${normalizeMessage(error)}`, "danger");
  }
};

const syncNativeShell = async () => {
  if (!Capacitor.isNativePlatform()) return;

  try {
    await StatusBar.setStyle({ style: Style.Light });
    await StatusBar.setOverlaysWebView({ overlay: true });
  } catch (error) {
    console.warn("Failed to initialize native shell chrome.", error);
  }
};

const warmDeviceProfile = async () => {
  if (profileWarmupStarted) return;
  profileWarmupStarted = true;
  try {
    const profile = await loadDeviceProfile();
    deviceProfile = profile;
    executionPolicy = buildExecutionPolicy(deviceProfile, performancePreset);
    renderPerformanceUI();
    renderSummary();
    renderTunePanel();
    syncStepUI();
    if (selectedInputRef && selectedOutputRef) {
      void refreshRoutePlan();
    }
  } catch (error) {
    console.warn("Device profile warmup failed.", error);
  }
};

window.addEventListener("error", event => {
  if (!event.error && (!event.message || event.message === "Script error.")) {
    return;
  }
  void reportStartupError(event.error ?? event.message);
});

window.addEventListener("unhandledrejection", event => {
  void reportStartupError(event.reason);
});

ui.inputSearch.addEventListener("input", searchHandler);
ui.outputSearch.addEventListener("input", searchHandler);
ui.fileSelectArea.addEventListener("click", () => {
  ui.fileInput.value = "";
  ui.fileInput.click();
});
ui.fileInput.addEventListener("change", fileSelectHandler);
window.addEventListener("drop", fileSelectHandler);
window.addEventListener("dragover", event => event.preventDefault());
window.addEventListener("dragenter", event => {
  if (event instanceof DragEvent && event.dataTransfer?.types.includes("Files")) {
    setDragState(true);
  }
});
window.addEventListener("dragleave", event => {
  if (event.target === document.documentElement || event.target === document.body) {
    setDragState(false);
  }
});
window.addEventListener("paste", fileSelectHandler);

ui.popupBg.addEventListener("click", () => {
  if (!isConverting) window.hidePopup();
});

for (const button of ui.routingButtons) {
  button.addEventListener("click", () => {
    void setRoutingMode(button.dataset.routingMode === "simple");
  });
}

for (const button of ui.themeButtons) {
  button.addEventListener("click", () => {
    const nextPreference = button.dataset.themePreference as ThemePreference;
    setThemePreference(nextPreference);
  });
}

for (const button of ui.performanceButtons) {
  button.addEventListener("click", () => {
    const nextPreset = button.dataset.performancePreset as PerformancePreset;
    setPerformancePreset(nextPreset);
  });
}

for (const button of ui.stepButtons) {
  button.addEventListener("click", () => {
    const targetStep = button.dataset.stepTarget as StepId;
    setCurrentStep(targetStep);
  });
}

ui.backButton.addEventListener("click", () => {
  const previous = previousStepFrom(currentStep);
  if (previous) setCurrentStep(previous);
});

ui.nextButton.addEventListener("click", () => {
  const next = nextStepFrom(currentStep);
  if (next && canEnterStep(next)) {
    setCurrentStep(next);
  }
});

ui.convertButton.addEventListener("click", () => {
  void convert();
});

window.addEventListener("keydown", event => {
  if (event.key === "Escape" && !isConverting) {
    window.hidePopup();
  }
});

window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", () => {
  if (themePreference === "auto") {
    renderThemeUI();
  }
});

{
  const commitSha = import.meta.env.VITE_COMMIT_SHA;
  if (commitSha) {
    ui.commitId.textContent = `Commit ${commitSha.slice(0, 7)}`;
  }
}

renderExamples();
renderSelectionCard();
renderThemeUI();
renderRoutingUI();
renderPerformanceUI();
renderOutputSuggestions();
renderTunePanel();
renderSummary();
renderReviewPreview();
syncStepUI();

requestAnimationFrame(() => {
  if ("requestIdleCallback" in window) {
    (window as Window & {
      requestIdleCallback?: (callback: IdleRequestCallback, options?: IdleRequestOptions) => number;
    }).requestIdleCallback?.(() => {
      void warmDeviceProfile();
    }, { timeout: 1200 });
  } else {
    globalThis.setTimeout(() => {
      void warmDeviceProfile();
    }, 250);
  }
});

(async () => {
  try {
    await syncNativeShell();
    window.showPopup("<h2>Loading tools...</h2><p>Preparing the conversion graph.</p>");

    const cacheJson = await fetch("cache.json").then(async response => {
      if (!response.ok) throw new Error("cache.json missing");
      return response.json();
    });
    window.supportedFormatCache = new Map(cacheJson);
  } catch {
    console.warn(
      "Missing supported format precache.\n\n" +
      "Consider saving the output of printSupportedFormatCache() to cache.json."
    );
  } finally {
    try {
      await buildOptionList();
      console.log("Built initial format list.");
    } finally {
      await hideNativeSplash();
    }
  }
})().catch(async error => {
  await reportStartupError(error);
});
