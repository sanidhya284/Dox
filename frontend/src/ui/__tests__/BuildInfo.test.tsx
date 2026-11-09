import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { BuildInfo } from '../BuildInfo';

describe('BuildInfo footer', () => {
  it('shows the short commit of the running release', async () => {
    const fetchImpl = async () => new Response(JSON.stringify({ commit: 'abc1234def', shortCommit: 'abc1234' }),
      { status: 200, headers: { 'Content-Type': 'application/json' } });
    render(<BuildInfo fetchImpl={fetchImpl} />);
    expect(await screen.findByText('build abc1234')).toBeTruthy();
  });

  it('shows nothing when the version is unavailable', async () => {
    const fetchImpl = async () => new Response('', { status: 503 });
    const { container } = render(<BuildInfo fetchImpl={fetchImpl} />);
    await new Promise((r) => setTimeout(r, 10));
    expect(container.textContent).toBe('');
  });
});
