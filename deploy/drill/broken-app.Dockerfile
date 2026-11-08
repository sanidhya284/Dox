# Rollback drill only (runbook section 8.2): the real app image with an entrypoint that exits at once,
# so the app never becomes healthy, the post-deploy check fails, and deploy.sh must roll back.
# Built and pushed by `Deploy` with drill=true as tag drill-broken-<sha>; never tagged `main`.
ARG BASE
FROM ${BASE}
ENTRYPOINT ["sh", "-c", "echo 'rollback drill: this image is broken on purpose' >&2; exit 1"]
