/** Output of `pnpm licenses list --json`: packages grouped by license. */
export type LicenseReport = Record<string, { name: string; versions: string[] }[]>;

export interface ForbiddenLicense {
  license: string;
  packages: string[];
}

/** Licenses allowed for what ships with Crawdad (ADR-0005). */
export const ALLOWED_LICENSES = [
  "MIT",
  "MIT-0",
  "Apache-2.0",
  "BSD-2-Clause",
  "BSD-3-Clause",
  "0BSD",
  "ISC",
];

/**
 * Whether a license, or an SPDX choice such as "(MIT OR GPL-3.0)", is allowed.
 * A choice is allowed when at least one of its options is.
 */
export function isAllowed(license: string, allowed: readonly string[]): boolean {
  return license
    .replace(/[()]/g, "")
    .split(" OR ")
    .some((option) => allowed.includes(option.trim()));
}

/** Licenses in the report that are not allowed, with the packages that use them. */
export function forbiddenLicenses(
  report: LicenseReport,
  allowed: readonly string[],
): ForbiddenLicense[] {
  return Object.entries(report)
    .filter(([license]) => !isAllowed(license, allowed))
    .map(([license, packages]) => ({
      license,
      packages: packages.flatMap((pkg) => pkg.versions.map((version) => `${pkg.name}@${version}`)),
    }));
}
