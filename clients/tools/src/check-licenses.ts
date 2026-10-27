// Fails when a production dependency of any client package uses a license outside the allowed list.
// Run through `pnpm licenses:check`, which provides the path to pnpm in npm_execpath.
import { execFileSync } from "node:child_process";
import { ALLOWED_LICENSES, forbiddenLicenses, type LicenseReport } from "./licenses.ts";

const pnpm = process.env["npm_execpath"];
if (!pnpm) {
  console.error("Run this check through pnpm: pnpm licenses:check");
  process.exit(2);
}

// pnpm is either a JavaScript entry point run by Node or a native executable.
const args = ["licenses", "list", "--recursive", "--prod", "--json"];
const [command, commandArgs] = /\.[cm]?js$/.test(pnpm)
  ? [process.execPath, [pnpm, ...args]]
  : [pnpm, args];
const output = execFileSync(command, commandArgs, { encoding: "utf8" }).trim();
const report: LicenseReport = output ? (JSON.parse(output) as LicenseReport) : {};
const forbidden = forbiddenLicenses(report, ALLOWED_LICENSES);

if (forbidden.length > 0) {
  for (const { license, packages } of forbidden) {
    console.error(`Forbidden license ${license}: ${packages.join(", ")}`);
  }
  process.exit(1);
}
console.log(`Production dependency licenses are allowed (${ALLOWED_LICENSES.join(", ")}).`);
