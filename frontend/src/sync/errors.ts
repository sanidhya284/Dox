/**
 * Error frame codes (ADR-0008): terminal ones drop the send; anything else, including unknown codes, retries.
 * RECIPIENT_UNAVAILABLE (ADR-0012): the DM peer blocked you, you blocked them, or their account was removed.
 * RATE_LIMITED is retryable, but only after the frame's retryAfterMs (handled in the client).
 */
const TERMINAL = new Set(['VALIDATION_ERROR', 'CONVERSATION_NOT_FOUND', 'IDEMPOTENCY_KEY_CONFLICT',
  'RECIPIENT_UNAVAILABLE']);

export function isTerminalError(code: string): boolean {
  return TERMINAL.has(code);
}

/** What the client does when the server closes the socket (ADR-0008 table; 4007 ADR-0006 2a; 4008 ADR-0012). */
export type CloseAction =
  | 'reconnect' | 'logged_out' | 'client_bug' | 'server_bug' | 'too_many_sockets' | 'guest_expired';

export function closeAction(code: number): CloseAction {
  switch (code) {
    case 4002:
      return 'logged_out'; // credentials revoked (refresh-token replay): log in again
    case 4007:
      return 'too_many_sockets'; // per-user socket cap: never auto-reconnect
    case 4008:
      return 'guest_expired'; // the guest account was removed: offer a new demo or sign-up
    case 1007:
    case 4004:
    case 4005:
      return 'client_bug'; // our own protocol bug: never loop
    case 1008:
      return 'server_bug';
    default:
      return 'reconnect'; // 4001 lifetime, 4003 slow consumer, 4006 idle, 1001 deploy/restart, 1006 drop
  }
}
