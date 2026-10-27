import { describe, expect, it } from "vitest";
import { forbiddenLicenses, isAllowed, type LicenseReport } from "./licenses.ts";

const ALLOWED = ["MIT", "Apache-2.0"];

describe("isAllowed", () => {
  it("accepts a license from the list", () => {
    expect(isAllowed("MIT", ALLOWED)).toBe(true);
  });

  it("rejects a license outside the list", () => {
    expect(isAllowed("GPL-3.0", ALLOWED)).toBe(false);
  });

  it("accepts a choice of licenses when one of them is allowed", () => {
    expect(isAllowed("(GPL-3.0 OR MIT)", ALLOWED)).toBe(true);
  });

  it("rejects a choice of licenses when none is allowed", () => {
    expect(isAllowed("(GPL-3.0 OR LGPL-3.0)", ALLOWED)).toBe(false);
  });
});

describe("forbiddenLicenses", () => {
  it("lists every package under a license outside the list", () => {
    const report: LicenseReport = {
      MIT: [{ name: "fine", versions: ["1.0.0"] }],
      "GPL-3.0": [
        { name: "bad", versions: ["2.0.0"] },
        { name: "worse", versions: ["3.0.0", "3.1.0"] },
      ],
    };

    expect(forbiddenLicenses(report, ALLOWED)).toEqual([
      { license: "GPL-3.0", packages: ["bad@2.0.0", "worse@3.0.0", "worse@3.1.0"] },
    ]);
  });

  it("finds nothing in an empty report", () => {
    expect(forbiddenLicenses({}, ALLOWED)).toEqual([]);
  });
});
