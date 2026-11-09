import { useEffect, useState } from 'react';
import type { FetchLike } from '../api/auth';

const browserFetch: FetchLike = (i, init) => fetch(i, init); // stable identity: the effect runs once

/** A small footer line with the running release's commit, from the public GET /api/version (ADR-0011). */
export function BuildInfo({ fetchImpl = browserFetch }: { fetchImpl?: FetchLike }) {
  const [commit, setCommit] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchImpl('/api/version')
      .then((r) => (r.ok ? r.json() : null))
      .then((v) => {
        if (!cancelled && v?.shortCommit) {
          setCommit(String(v.shortCommit));
        }
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [fetchImpl]);

  return commit ? <footer className="build-info">build {commit}</footer> : null;
}
