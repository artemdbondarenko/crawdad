import { describe, expect, it } from "vitest";
import { appTitle } from "./index";

describe("appTitle", () => {
  it("names the product and the client", () => {
    expect(appTitle("desktop")).toBe("Crawdad (desktop)");
  });
});
