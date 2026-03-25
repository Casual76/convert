import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { join } from "node:path";
import process from "node:process";

const workdir = process.cwd();
const androidDir = join(workdir, "android");
const wrapperPath = join(androidDir, process.platform === "win32" ? "gradlew.bat" : "gradlew");

if (!existsSync(wrapperPath)) {
  console.error("Missing Android Gradle wrapper. Run `npx cap add android` first.");
  process.exit(1);
}

const command = process.platform === "win32" ? "cmd.exe" : "./gradlew";
const args = process.platform === "win32"
  ? ["/c", "gradlew.bat", "assembleRelease"]
  : ["assembleRelease"];

const child = spawn(command, args, {
  cwd: androidDir,
  stdio: "inherit"
});

child.on("exit", code => {
  process.exit(code ?? 1);
});
