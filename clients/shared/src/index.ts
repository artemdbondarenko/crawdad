export type {
  AgentMessageDelta,
  AgentMessageEnd,
  AgentMessageStart,
  CrawdadProtocolMessage as ProtocolMessage,
  Error as ErrorMessage,
  MessageId,
  SessionId,
  SessionStarted,
  UserMessage,
} from "./generated/protocol";

export {
  ServiceConnection,
  browserSocket,
  serviceUrl,
  type ConnectionDependencies,
  type ConnectionState,
  type Conversation,
  type ConversationEntry,
  type DeviceFetch,
  type DeviceResponse,
  type PageLocation,
  type Socket,
  type SocketEvents,
  type SocketFactory,
  type Timers,
} from "./connection";

export {
  pairDevice,
  pairingCodeFromHash,
  type PairFetch,
  type PairResponse,
  type PairingResult,
} from "./pairing";

/** Title shown by a Crawdad client, such as in the window title. */
export function appTitle(clientName: string): string {
  return `Crawdad (${clientName})`;
}
