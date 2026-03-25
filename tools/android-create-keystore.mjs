import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { join } from "node:path";
import process from "node:process";

const workdir = process.cwd();
const androidDir = join(workdir, "android");
const keystorePath = join(androidDir, "convert-release.jks");
const propertiesPath = join(androidDir, "keystore.properties");
const alias = "convert-release";
const dname = "CN=Convert to it!, OU=Mobile, O=PortalRunner, L=Rome, S=RM, C=IT";

const resolveKeytool = () => {
  const javaHome = process.env.JAVA_HOME;
  if (javaHome) {
    const executable = join(javaHome, "bin", process.platform === "win32" ? "keytool.exe" : "keytool");
    if (existsSync(executable)) return executable;
  }
  return process.platform === "win32" ? "keytool.exe" : "keytool";
};

const parseProperties = (content) => {
  const entries = new Map();
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const separatorIndex = line.indexOf("=");
    if (separatorIndex === -1) continue;
    entries.set(line.slice(0, separatorIndex).trim(), line.slice(separatorIndex + 1).trim());
  }
  return entries;
};

if (!existsSync(androidDir)) {
  throw new Error("Missing android project. Run `npx cap add android` first.");
}

if (existsSync(propertiesPath) && existsSync(keystorePath)) {
  const properties = parseProperties(readFileSync(propertiesPath, "utf8"));
  if (properties.get("storeFile") && properties.get("storePassword")) {
    if (properties.get("keyPassword") !== properties.get("storePassword")) {
      writeFileSync(
        propertiesPath,
        [
          "# Auto-generated local signing config for Android release builds.",
          `storeFile=${properties.get("storeFile")}`,
          `storePassword=${properties.get("storePassword")}`,
          `keyAlias=${properties.get("keyAlias") ?? alias}`,
          `keyPassword=${properties.get("storePassword")}`,
          ""
        ].join("\n"),
        "utf8"
      );
    }
    console.log(`Using existing Android release keystore at ${keystorePath}`);
    process.exit(0);
  }
}

mkdirSync(androidDir, { recursive: true });

const storePassword = randomBytes(18).toString("base64url");
const keyPassword = storePassword;
const keytool = resolveKeytool();
const result = spawnSync(
  keytool,
  [
    "-genkeypair",
    "-v",
    "-storetype",
    "PKCS12",
    "-keystore",
    keystorePath,
    "-alias",
    alias,
    "-keyalg",
    "RSA",
    "-keysize",
    "2048",
    "-validity",
    "9125",
    "-storepass",
    storePassword,
    "-keypass",
    keyPassword,
    "-dname",
    dname
  ],
  {
    cwd: androidDir,
    stdio: "inherit"
  }
);

if (result.status !== 0) {
  process.exit(result.status ?? 1);
}

writeFileSync(
  propertiesPath,
  [
    "# Auto-generated local signing config for Android release builds.",
    "storeFile=convert-release.jks",
    `storePassword=${storePassword}`,
    `keyAlias=${alias}`,
    `keyPassword=${keyPassword}`,
    ""
  ].join("\n"),
  "utf8"
);

console.log(`Created Android release keystore at ${keystorePath}`);
