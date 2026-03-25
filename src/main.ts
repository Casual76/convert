import { Capacitor } from "@capacitor/core";
import { SplashScreen } from "@capacitor/splash-screen";
import { StatusBar, Style } from "@capacitor/status-bar";
import type { ConvertPathNode, FileData, FileFormat, FormatHandler } from "./FormatHandler.js";
import handlers from "./handlers";
import normalizeMimeType from "./normalizeMimeType.js";
import { TraversionGraph } from "./TraversionGraph.js";

type StepId = "upload" | "from" | "to" | "review";
type ListSide = "input" | "output";
type ToastTone = "neutral" | "success" | "warning" | "danger";

interface FormatOptionRef {
  mime: string;
  format: string;
  handlerName: string;
  internal: string;
}

const stepOrder: StepId[] = ["upload", "from", "to", "review"];

let selectedFiles: File[] = [];
let simpleMode = true;
let currentStep: StepId = "upload";
let selectedInputRef: FormatOptionRef | null = null;
let selectedOutputRef: FormatOptionRef | null = null;
let isConverting = false;
let deadEndAttempts: ConvertPathNode[][] = [];

const desktopMediaQuery = window.matchMedia("(min-width: 961px)");

const ui = {
  body: document.body,
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
  summaryFiles: document.querySelector("#summary-files") as HTMLElement,
  summaryFrom: document.querySelector("#summary-from") as HTMLElement,
  summaryTo: document.querySelector("#summary-to") as HTMLElement,
  summaryMode: document.querySelector("#summary-mode") as HTMLElement,
  reviewHint: document.querySelector("#review-hint") as HTMLDivElement,
  modeButton: document.querySelector("#mode-button") as HTMLButtonElement,
  modeSheet: document.querySelector("#mode-sheet") as HTMLElement,
  modeSheetBackdrop: document.querySelector("#mode-sheet-backdrop") as HTMLDivElement,
  modeOptionButtons: Array.from(document.querySelectorAll<HTMLButtonElement>("[data-routing-mode]")),
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
  commitId: document.querySelector("#commit-id") as HTMLAnchorElement
};

const allOptions: Array<{ format: FileFormat; handler: FormatHandler }> = [];

window.supportedFormatCache = new Map();
window.traversionGraph = new TraversionGraph();

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

const reportStartupError = async (error: unknown) => {
  const message = error instanceof Error ? error.message : String(error);
  console.error("Startup error:", error);
  (window as any).showStartupFatal?.(message);
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

const isDesktopLayout = () => desktopMediaQuery.matches;

const cleanFormatName = (name: string) => name
  .split("(")
  .join(")")
  .split(")")
  .filter((_, index) => index % 2 === 0)
  .filter(Boolean)
  .join(" ")
  .trim();

const createOptionRef = (option: { format: FileFormat; handler: FormatHandler }): FormatOptionRef => ({
  mime: option.format.mime,
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
  if (option.format.mime !== selection.mime || option.format.format !== selection.format) {
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

const renderSelectionBadges = () => {
  ui.selectionList.innerHTML = "";
  if (selectedFiles.length === 0) {
    const chip = document.createElement("span");
    chip.textContent = "No files";
    ui.selectionList.appendChild(chip);
    return;
  }

  const chips = [
    `${selectedFiles.length} file${selectedFiles.length === 1 ? "" : "s"}`,
    formatBytes(totalSelectedBytes())
  ];

  const mime = normalizeMimeType(selectedFiles[0].type);
  if (mime) chips.push(mime);

  for (const value of chips) {
    const chip = document.createElement("span");
    chip.textContent = value;
    ui.selectionList.appendChild(chip);
  }
};

const renderSelectionCard = () => {
  if (selectedFiles.length === 0) {
    ui.selectionKicker.textContent = "Ready when you are";
    ui.selectionTitle.textContent = "No file selected yet";
    ui.selectionMeta.textContent = "Choose a file to unlock the format steps and build a conversion route.";
    renderSelectionBadges();
    return;
  }

  const leadFile = selectedFiles[0];
  ui.selectionKicker.textContent = selectedFiles.length === 1 ? "File loaded" : "Batch loaded";
  ui.selectionTitle.textContent = selectedFiles.length === 1
    ? leadFile.name
    : `${leadFile.name} and ${selectedFiles.length - 1} more`;
  ui.selectionMeta.textContent = "Input format can be auto-detected, but you can always change it before converting.";
  renderSelectionBadges();
};

const renderSummary = () => {
  const inputOption = getSelectedOption("input");
  const outputOption = getSelectedOption("output");

  ui.summaryFiles.textContent = selectedFiles.length === 0
    ? "No files selected"
    : `${selectedFiles.length} file${selectedFiles.length === 1 ? "" : "s"} (${formatBytes(totalSelectedBytes())})`;
  ui.summaryFrom.textContent = inputOption
    ? `${inputOption.format.format.toUpperCase()} / ${inputOption.format.mime}`
    : "Pick an input format";
  ui.summaryTo.textContent = outputOption
    ? `${outputOption.format.format.toUpperCase()} / ${outputOption.format.mime}`
    : "Pick an output format";
  ui.summaryMode.textContent = simpleMode ? "Simple mode" : "Advanced mode";

  if (selectedFiles.length === 0) {
    ui.reviewHint.textContent = "Select a file to begin.";
  } else if (!inputOption) {
    ui.reviewHint.textContent = "Confirm the input format before moving on.";
  } else if (!outputOption) {
    ui.reviewHint.textContent = "Pick an output format to unlock conversion.";
  } else {
    ui.reviewHint.textContent = `Ready to search for a route from ${inputOption.format.format.toUpperCase()} to ${outputOption.format.format.toUpperCase()}.`;
  }
};

const canEnterStep = (step: StepId) => {
  switch (step) {
    case "upload":
      return true;
    case "from":
      return selectedFiles.length > 0;
    case "to":
      return selectedInputRef !== null;
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
    case "review":
      return canConvert();
  }
};

const setCurrentStep = (step: StepId) => {
  if (!canEnterStep(step) && step !== "upload") return;
  currentStep = step;
  syncStepUI();
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
  return selectedFiles.length > 0 && selectedInputRef !== null && selectedOutputRef !== null && !isConverting;
}

const updateVisibleCount = (list: HTMLDivElement, counter: HTMLSpanElement, label: string) => {
  const visibleCount = Array.from(list.children).filter(child => {
    return child instanceof HTMLButtonElement && child.style.display !== "none";
  }).length;
  counter.textContent = `${visibleCount} ${label}${visibleCount === 1 ? "" : "s"}`;
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
  updateVisibleCount(list, counter, label);
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
    selectedInputRef = createOptionRef(option);
    if (autoAdvance && !isDesktopLayout()) setCurrentStep("to");
  } else {
    selectedOutputRef = createOptionRef(option);
    if (autoAdvance && !isDesktopLayout()) setCurrentStep("review");
  }

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

const createFormatButton = (
  option: { format: FileFormat; handler: FormatHandler },
  side: ListSide
) => {
  const button = document.createElement("button");
  button.type = "button";
  button.setAttribute("format-index", String(allOptions.length - 1));
  button.setAttribute("mime-type", option.format.mime);

  const overline = document.createElement("span");
  overline.className = "format-overline";
  overline.textContent = option.format.format.toUpperCase();

  const title = document.createElement("span");
  title.className = "format-title";
  title.textContent = simpleMode ? cleanFormatName(option.format.name) : option.format.name;

  const meta = document.createElement("span");
  meta.className = "format-meta";
  const metaParts = [
    option.format.mime,
    `.${option.format.extension}`
  ];
  if (!simpleMode) metaParts.push(`via ${option.handler.name}`);
  if (option.format.lossless) metaParts.push("lossless");
  meta.textContent = metaParts.join(" | ");

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

      const dedupeKey = `${format.mime}::${format.format}`;
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
  renderSummary();
  syncStepUI();
  window.hidePopup();
}

const setRoutingMode = async (nextSimple: boolean) => {
  if (simpleMode === nextSimple) {
    updateModeUI();
    return;
  }

  simpleMode = nextSimple;
  updateModeUI();
  showToast(simpleMode ? "Simple routing enabled." : "Advanced routing enabled.", "success");
  window.showPopup(`<h2>Refreshing routes...</h2><p>Rebuilding the format list for ${simpleMode ? "simple" : "advanced"} mode.</p>`);
  await buildOptionList();
};

const updateModeUI = () => {
  document.documentElement.dataset.routingMode = simpleMode ? "simple" : "advanced";
  ui.modeButton.textContent = `Routing: ${simpleMode ? "Simple" : "Advanced"}`;
  ui.summaryMode.textContent = simpleMode ? "Simple mode" : "Advanced mode";
  for (const button of ui.modeOptionButtons) {
    const isSelected = (button.dataset.routingMode === "simple") === simpleMode;
    button.dataset.selected = isSelected ? "true" : "false";
  }
};

const openModeSheet = () => {
  ui.modeSheet.hidden = false;
  ui.modeSheetBackdrop.hidden = false;
};

const closeModeSheet = () => {
  ui.modeSheet.hidden = true;
  ui.modeSheetBackdrop.hidden = true;
};

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
      "Review conversion";
    ui.nextButton.disabled = nextStep === null || !canEnterStep(nextStep) || isConverting;
  }

  ui.convertButton.disabled = !canConvert();
};

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
  const mimeType = normalizeMimeType(file.type);
  const fileExtension = file.name.split(".").pop()?.toLowerCase() ?? "";
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

const autoSelectInputFormat = (file: File) => {
  const match = findMatchingInputButton(file);
  if (match) {
    ui.inputSearch.value = match.searchValue;
    filterButtonList(ui.inputList, ui.inputSearch.value, ui.fromCount, "format");
    match.button.click();
    return;
  }

  ui.inputSearch.value = file.name.split(".").pop()?.toLowerCase() ?? "";
  filterButtonList(ui.inputList, ui.inputSearch.value, ui.fromCount, "format");
  showToast("Input format was not confidently detected. Please pick it manually.", "warning");
};

const fileSelectHandler = (event: Event) => {
  let inputFiles: FileList | null | undefined;

  if (event instanceof DragEvent) {
    event.preventDefault();
    inputFiles = event.dataTransfer?.files;
    ui.body.dataset.dragging = "false";
  } else if (event instanceof ClipboardEvent) {
    inputFiles = event.clipboardData?.files;
  } else {
    const target = event.target;
    if (!(target instanceof HTMLInputElement)) return;
    inputFiles = target.files;
  }

  if (!inputFiles) return;
  const files = Array.from(inputFiles);
  if (files.length === 0) return;

  if (files.some(file => file.type !== files[0].type)) {
    showToast("All selected files must share the same MIME type.", "danger");
    return;
  }

  files.sort((a, b) => a.name.localeCompare(b.name));
  selectedFiles = files;
  renderSelectionCard();
  renderSummary();
  setCurrentStep("from");
  autoSelectInputFormat(files[0]);
};

const setDragState = (active: boolean) => {
  ui.body.dataset.dragging = active ? "true" : "false";
};

async function attemptConvertPath (files: FileData[], path: ConvertPathNode[]) {
  const pathString = path.map(node => node.format.format).join(" -> ");

  for (const deadEnd of deadEndAttempts) {
    let matchesDeadEnd = true;
    for (let index = 0; index < deadEnd.length; index += 1) {
      if (path[index] !== deadEnd[index]) {
        matchesDeadEnd = false;
        break;
      }
    }
    if (matchesDeadEnd) {
      return null;
    }
  }

  window.showPopup(`<h2>Finding conversion route...</h2><p>Trying <b>${escapeHtml(pathString)}</b>.</p>`);

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

      files = (await Promise.all([
        handler.doConvert(files, inputFormat, path[index + 1].format),
        new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))
      ]))[0];

      if (files.some(file => !file.bytes.length)) throw new Error("Output is empty.");
    } catch (error) {
      const deadEndPath = path.slice(0, index + 2);
      deadEndAttempts.push(deadEndPath);
      window.traversionGraph.addDeadEndPath(deadEndPath);

      window.showPopup("<h2>Finding conversion route...</h2><p>Looking for a valid path...</p>");
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));

      console.error(handler.name, `${path[index].format.format} -> ${path[index + 1].format.format}`, error);
      return null;
    }
  }

  return { files, path };
}

window.tryConvertByTraversing = async (
  files: FileData[],
  from: ConvertPathNode,
  to: ConvertPathNode
) => {
  deadEndAttempts = [];
  window.traversionGraph.clearDeadEndPaths();
  for await (const path of window.traversionGraph.searchPath(from, to, simpleMode)) {
    if (path.at(-1)?.handler === to.handler) {
      path[path.length - 1] = to;
    }
    const attempt = await attemptConvertPath(files, path);
    if (attempt) return attempt;
  }
  return null;
};

const downloadFile = (bytes: Uint8Array, name: string) => {
  const blob = new Blob([bytes as BlobPart], { type: "application/octet-stream" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 2000);
};

const convert = async () => {
  if (!canConvert()) {
    showToast("Select files and both formats before converting.", "warning");
    return;
  }

  const inputOption = getSelectedOption("input");
  const outputOption = getSelectedOption("output");
  if (!inputOption || !outputOption) return;

  isConverting = true;
  syncStepUI();

  try {
    const inputFileData: FileData[] = [];
    for (const inputFile of selectedFiles) {
      const inputBuffer = await inputFile.arrayBuffer();
      const inputBytes = new Uint8Array(inputBuffer);
      if (
        inputOption.format.mime === outputOption.format.mime
        && inputOption.format.format === outputOption.format.format
      ) {
        downloadFile(inputBytes, inputFile.name);
        continue;
      }
      inputFileData.push({ name: inputFile.name, bytes: inputBytes });
    }

    if (inputFileData.length === 0) {
      showToast("Source and target match. Original file downloaded.", "success");
      return;
    }

    window.showPopup("<h2>Finding conversion route...</h2><p>Preparing the first route candidate.</p>");
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));

    const output = await window.tryConvertByTraversing(inputFileData, inputOption, outputOption);
    if (!output) {
      isConverting = false;
      window.hidePopup();
      syncStepUI();
      showToast("Failed to find a valid conversion route.", "danger");
      return;
    }

    for (const file of output.files) {
      downloadFile(file.bytes, file.name);
    }

    isConverting = false;
    syncStepUI();
    showToast("Conversion complete.", "success");

    const pathUsed = escapeHtml(output.path.map(node => node.format.format).join(" -> "));
    window.showPopup(
      `<h2>Conversion complete</h2>` +
      `<p>Path used: <b>${pathUsed}</b>.</p>` +
      `<button type="button" onclick="window.hidePopup()">Done</button>`
    );
  } catch (error) {
    console.error(error);
    isConverting = false;
    window.hidePopup();
    syncStepUI();
    showToast(`Unexpected error during conversion: ${error}`, "danger");
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
ui.fileSelectArea.addEventListener("click", () => ui.fileInput.click());
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

ui.modeButton.addEventListener("click", openModeSheet);
ui.modeSheetBackdrop.addEventListener("click", closeModeSheet);
for (const button of ui.modeOptionButtons) {
  button.addEventListener("click", async () => {
    closeModeSheet();
    await setRoutingMode(button.dataset.routingMode === "simple");
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
  if (event.key !== "Escape") return;
  closeModeSheet();
  if (!isConverting) window.hidePopup();
});

const handleDesktopMediaChange = () => {
  syncStepUI();
};

if (typeof desktopMediaQuery.addEventListener === "function") {
  desktopMediaQuery.addEventListener("change", handleDesktopMediaChange);
} else {
  desktopMediaQuery.addListener(handleDesktopMediaChange);
}

{
  const commitSha = import.meta.env.VITE_COMMIT_SHA;
  if (commitSha) {
    ui.commitId.textContent = `Commit ${commitSha.slice(0, 7)}`;
  }
}

renderSelectionCard();
renderSummary();
updateModeUI();
syncStepUI();

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
