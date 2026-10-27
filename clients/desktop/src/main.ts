import { appTitle } from "@crawdad/shared";

/** Renders the desktop client into `root` and sets the window title. */
export function render(root: HTMLElement, doc: Document): void {
  const title = appTitle("desktop");
  doc.title = title;

  const heading = doc.createElement("h1");
  heading.textContent = title;
  root.replaceChildren(heading);
}
