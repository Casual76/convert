const runtimeOrigin = globalThis.location?.origin;
const runtimeLocation = runtimeOrigin && runtimeOrigin !== "null"
  ? `${runtimeOrigin}/`
  : (globalThis.location?.href ?? "http://localhost/");
const runtimeBaseUrl = new URL(import.meta.env.BASE_URL, runtimeLocation);

export const assetUrl = (relativePath: string) => {
  const normalizedPath = relativePath.replace(/^\/+/, "");
  return new URL(normalizedPath, runtimeBaseUrl).toString();
};
