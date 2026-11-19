# Two-instance drill only (deploy/drill/two_instance_check.sh): the real app image, broken only where the
# container has DRILL_BREAK set (the drill sets it on app2 alone). So `app` updates and passes, then `app2`
# fails its health check, and deploy.sh must put BOTH back on the previous tag. Never pushed anywhere.
ARG BASE
FROM ${BASE}
ENTRYPOINT ["sh", "-c", "if [ -n \"$DRILL_BREAK\" ]; then echo 'drill: broken on this instance on purpose' >&2; exit 1; fi; exec java -jar /app/app.jar"]
