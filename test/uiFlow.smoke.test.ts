import { afterAll, beforeAll, expect, test } from "bun:test";
import puppeteer from "puppeteer";

let server: ReturnType<typeof Bun.serve>;
let browser: Awaited<ReturnType<typeof puppeteer.launch>>;
let page: Awaited<ReturnType<typeof browser.newPage>>;
let baseUrl = "";

beforeAll(async () => {
  server = Bun.serve({
    port: 0,
    async fetch(req) {
      let path = new URL(req.url).pathname.replace("/convert/", "");
      if (!path || path.endsWith("/")) path += "index.html";
      path = path.replace(/^\/+/, "").replaceAll("..", "");
      const file = Bun.file(new URL(`../dist/${path}`, import.meta.url));
      if (!(await file.exists())) {
        return new Response("Not Found", { status: 404 });
      }
      return new Response(file);
    }
  });

  baseUrl = `http://localhost:${server.port}/convert/index.html`;
  browser = await puppeteer.launch({
    headless: true,
    args: ["--no-sandbox", "--disable-setuid-sandbox"]
  });
  page = await browser.newPage();

  await Promise.all([
    new Promise(resolve => {
      page.on("console", msg => {
        if (msg.text() === "Built initial format list.") {
          resolve(null);
        }
      });
    }),
    page.goto(baseUrl)
  ]);
}, 120000);

afterAll(async () => {
  await browser?.close();
  server?.stop();
});

test("example flow reaches Tune and Review with the new wizard shell", async () => {
  const stepCount = await page.$$eval("[data-step-target]", elements => elements.length);
  expect(stepCount).toBe(5);

  await page.click(".example-card");
  await page.waitForFunction(() => {
    return document.querySelector('[data-step="tune"]')?.getAttribute("data-active") === "true";
  }, { timeout: 90000 });

  const tuneStatus = await page.$eval("#tune-profile-summary", element => element.textContent || "");
  expect(tuneStatus.length).toBeGreaterThan(0);

  await page.waitForFunction(() => {
    const reviewButton = document.querySelector<HTMLButtonElement>('[data-step-target="review"]');
    return Boolean(reviewButton && !reviewButton.disabled);
  }, { timeout: 90000 });

  await page.click('[data-step-target="review"]');
  await page.waitForFunction(() => {
    return document.querySelector('[data-step="review"]')?.getAttribute("data-active") === "true";
  }, { timeout: 90000 });

  const summaryTo = await page.$eval("#summary-to", element => element.textContent || "");
  const hint = await page.$eval("#review-hint", element => element.textContent || "");

  expect(summaryTo).not.toContain("Pick an output format");
  expect(hint.length).toBeGreaterThan(0);
  expect(await page.$("#review-preview-frame")).toBeTruthy();
}, { timeout: 120000 });

test("drop upload accepts same-extension batches even with generic MIME types", async () => {
  await page.evaluate(() => {
    const transfer = new DataTransfer();
    transfer.items.add(new File(["# alpha"], "alpha.md", { type: "" }));
    transfer.items.add(new File(["# beta"], "beta.md", { type: "text/markdown" }));

    window.dispatchEvent(new DragEvent("drop", {
      bubbles: true,
      cancelable: true,
      dataTransfer: transfer
    }));
  });

  await page.waitForFunction(() => {
    return document.querySelector("#selection-title")?.textContent?.includes("alpha.md and 1 more") === true;
  }, { timeout: 90000 });

  await page.waitForFunction(() => {
    return document.querySelector('[data-step="from"]')?.getAttribute("data-active") === "true";
  }, { timeout: 90000 });

  const badges = await page.$$eval("#selection-list span", elements => elements.map(element => element.textContent || ""));

  expect(badges).toContain("2 files");
  expect(badges).toContain(".md");
});
