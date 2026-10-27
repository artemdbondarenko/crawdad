import { describe, expect, it } from "vitest";
import { render } from "./main";

describe("render", () => {
  it("shows the client title in the page and in the window title", () => {
    const root = document.createElement("div");

    render(root, document);

    expect(root.querySelector("h1")?.textContent).toBe("Crawdad (desktop)");
    expect(document.title).toBe("Crawdad (desktop)");
  });
});
