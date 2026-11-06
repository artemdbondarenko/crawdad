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
  type ConnectionState,
  type Conversation,
  type ConversationEntry,
  type Socket,
  type SocketEvents,
  type SocketFactory,
} from "./connection";

/** Title shown by a Crawdad client, such as in the window title. */
export function appTitle(clientName: string): string {
  return `Crawdad (${clientName})`;
}
