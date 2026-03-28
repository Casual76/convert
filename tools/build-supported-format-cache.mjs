import fs from "node:fs/promises";
import path from "node:path";
import http from "node:http";
import puppeteer from "puppeteer";

const targetDir = path.resolve(process.argv[2] || "android/app/src/main/assets/legacy");
const outputPath = path.join(targetDir, "cache.json");
const browserCandidates = [
  "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
  "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
  "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe"
];

const serveFile = async (req, res) => {
  const url = new URL(req.url || "/", "http://localhost");
  let relativePath = url.pathname.replace(/^\/+/, "");
  if (!relativePath || relativePath.endsWith("/")) {
    relativePath += "android-bridge.html";
  }
  const normalizedPath = path.normalize(relativePath).replace(/^(\.\.[/\\])+/, "");
  const filePath = path.join(targetDir, normalizedPath);

  try {
    const file = await fs.readFile(filePath);
    const extension = path.extname(filePath).toLowerCase();
    const contentTypes = {
      ".html": "text/html; charset=utf-8",
      ".js": "text/javascript; charset=utf-8",
      ".json": "application/json; charset=utf-8",
      ".wasm": "application/wasm"
    };
    res.writeHead(200, {
      "Content-Type": contentTypes[extension] || "application/octet-stream",
      "Cache-Control": "no-store"
    });
    res.end(file);
  } catch {
    res.writeHead(404);
    res.end("Not Found");
  }
};

await fs.mkdir(targetDir, { recursive: true });
await fs.rm(outputPath, { force: true });

const server = http.createServer((req, res) => {
  void serveFile(req, res);
});

await new Promise(resolve => server.listen(0, resolve));
const address = server.address();
if (!address || typeof address === "string") {
  throw new Error("Could not start cache build server.");
}

const baseUrl = `http://127.0.0.1:${address.port}/android-bridge.html`;
const executablePath = (
  await Promise.all(browserCandidates.map(async candidate => {
    try {
      await fs.access(candidate);
      return candidate;
    } catch {
      return null;
    }
  }))
).find(Boolean);

const browser = await puppeteer.launch({
  headless: "new",
  executablePath: executablePath ?? undefined,
  protocolTimeout: 900000,
  args: ["--no-sandbox", "--disable-setuid-sandbox"]
});

try {
  const page = await browser.newPage();
  page.setDefaultTimeout(300000);
  try {
    await page.goto(baseUrl, { waitUntil: "load" });
    await page.waitForFunction(() => window.__bridgeRuntimeReady === true, { timeout: 300000 });
    const cacheJson = await page.evaluate(async () => {
      return await window.printSupportedFormatCache?.();
    });
    if (!cacheJson) {
      throw new Error("Legacy runtime did not expose supported format cache.");
    }
    await fs.writeFile(outputPath, cacheJson, "utf8");
  } catch (error) {
    console.warn("Skipping legacy supported-format cache build; runtime will fall back to live discovery.", error);
  }
} finally {
  await browser.close();
  await new Promise(resolve => server.close(resolve));
}
