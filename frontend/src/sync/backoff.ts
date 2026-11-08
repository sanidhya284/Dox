/**
 * Exponential backoff with FULL jitter (ADR-0008), ported from the Java reference client's Backoff:
 * delay = random[0, min(cap, base * 2^attempt)). Full jitter spreads clients that were dropped together.
 */
export class Backoff {
  private attempt = 0;

  constructor(
    private readonly baseMs: number,
    private readonly capMs: number,
    private readonly random: () => number = Math.random,
  ) {
    if (!(baseMs > 0) || capMs < baseMs) {
      throw new Error('need 0 < base <= cap');
    }
  }

  ceiling(attempt: number): number {
    return Math.min(this.capMs, this.baseMs * 2 ** Math.min(attempt, 52));
  }

  nextDelay(): number {
    const ceiling = this.ceiling(this.attempt);
    this.attempt++;
    return this.random() * ceiling;
  }

  reset(): void {
    this.attempt = 0;
  }

  attempts(): number {
    return this.attempt;
  }
}
