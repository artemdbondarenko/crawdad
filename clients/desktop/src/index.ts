import { ServiceConnection } from "@crawdad/shared";
import { render } from "./main";

const root = document.getElementById("app");
if (root) {
  // The service serves this page, so its WebSocket is on the same host and port.
  render(root, document, new ServiceConnection(location.host));
}
