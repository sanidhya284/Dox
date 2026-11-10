# AWS deployment runbook (single instance, Free plan)

Written 2026-11-07 for: AWS Free plan, $100 credits, plan ends 2027-04-07. Prices and rules change; where something matters for money, the step says "verify in the console". Anything in angle brackets is yours to fill in.

Rule for this whole document: if a step does not behave as described, STOP and ask. Do not improvise around security groups, billing or secrets.

## This deployment (status as of 2026-11-08, own domain)

| Item | Value | Status |
|---|---|---|
| Region | ap-southeast-2 (Sydney) | done |
| Instance | `chat-prod`, `t3.small`, Ubuntu 24.04 x86, 20 GB | done; verified `t3.small`, IMDSv1 returns 401 |
| Elastic IP | `3.24.141.155` | done |
| Hostname | **`dox-chat.in`** (own domain; A records for `dox-chat.in` and `www.dox-chat.in` → the Elastic IP, no AAAA) | bought 2026-11-08; resolves on 8.8.8.8 and on the ISP resolver that failed `work.gd` |
| Old hostname | `dox.work.gd` (DNSExit) | first deploy ran here 2026-11-08 (`sha-c89219d`, Let's Encrypt `YE2`); now only 308-redirects to `https://dox-chat.in` until it's dropped |
| SSH host key (ED25519) | `SHA256:3Fzi3ki+FgNUEOXITT6MxnEWqv4Ul/7y0pHQScIDpvo` | verified on the server |
| Sections 1-3 | account safety, hostname, launch | done by hand |
| Section 4 | server prep | done 2026-11-08: packages updated (still 24.04.5, no reboot needed), Docker 29.9.3 + compose v5.7.0, log rotation, 2 GB swap (swappiness 10), fail2ban (sshd jail), `deploy` user (docker group, no sudo; verified), host key A = B, `/opt/chat/.env` generated on the server (13 variables, mode 600, `ACME_MODE=staging`) |
| Section 5.2 | repository secrets | done 2026-11-08: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY` (`~/.ssh/chat-deploy`, fingerprint `SHA256:aSCBaspWSmWL8YEGUtPg+Hf/Q63nawA8saYytHq5tEE`), `DEPLOY_KNOWN_HOSTS` (hostname + IP, pinned ED25519 key) |
| Section 6 | repo pipeline | done (PR #10, merged 2026-11-08); see 6.1 |
| Section 5.1 | GitHub environment `production` | done by hand 2026-11-08 |
| Section 7 | first deploy | done 2026-11-08 on `dox.work.gd`: `sha-c89219d`, post-deploy check OK, staging then Let's Encrypt production (`YE2`); CI on the merge commit 7 min 22 s |
| Own domain | `DOMAIN=dox-chat.in`, `REDIRECT_HOSTS=www.dox-chat.in dox.work.gd` | done 2026-11-08 (procedure 7.3), deploy `sha-31bfc4d`. Staging certificates first for `dox-chat.in` and `www.dox-chat.in`, then **Let's Encrypt production** (no ZeroSSL fallback): `dox-chat.in` intermediate `YE1`, `www.dox-chat.in` `YE2`, expiring 2027-02-06. `dox.work.gd` kept its earlier Let's Encrypt `YE2` certificate. Verified from the laptop with TLS checks on: site 200, health 200, prometheus and env 404, `www` and `dox.work.gd` 308 to `https://dox-chat.in` (path and query kept), HSTS, CSP (`wss://dox-chat.in`). From the server: demo2 received demo1's message live over the WebSocket; the refresh cookie is host-only (no `Domain`). The previous `.env` is kept as `/opt/chat/.env.before-dox-chat` (mode 600) |
| Still to confirm by hand | only one Elastic IP in the account; credit specification Standard | pending |

**Hostname caveats (`work.gd`, resolved by the own domain `dox-chat.in` on 2026-11-08; kept for the record):** it is a shared DNSExit domain that is not on the Public Suffix List. (1) Let's Encrypt rate limits are shared with every other `*.work.gd` user, so issuance can fail for reasons outside our control; use the staging CA first, and accept a ZeroSSL fallback from Caddy. (2) Browsers treat other `*.work.gd` sites as same-site, so `SameSite=Strict` does not stop requests from them; the exact Origin checks on refresh, logout and the WebSocket still do. The refresh cookie must be host-only (no `Domain` attribute). If certificates keep failing, buy a cheap domain.

---

## 0. Decisions already made

| Decision | Choice | Why |
|---|---|---|
| Region | ap-southeast-2 (Sydney) | Mumbai was the first choice (closer to India), but the Free plan asked to "activate" it, which may mean upgrading; Sydney works on the Free plan. Higher latency from India, slightly higher prices; acceptable for a demo. |
| Server | one EC2 `t3.small` (x86, 2 vCPU, 2 GB), Ubuntu 24.04 LTS, 20 GB gp3 | Matches the sizing in ADR-0011; x86 because CI builds amd64 images (Arm `t4g` would need multi-arch builds). |
| Database | Postgres inside Docker Compose on the same instance | RDS would burn credits and is not needed for a demo. |
| TLS | Caddy with automatic Let's Encrypt, hostname required | Needs a hostname whose A record points at the server; ports 80 and 443 reachable. |
| Address | one Elastic IP | The address survives stop/start. Costs about $0.005/hour for any public IPv4 (about $3.65/month), also when idle, so release it when you tear down. |
| SSH | port 22 open to the world, key-only, fail2ban, `deploy` user without sudo | GitHub-hosted runners have changing IPs, so "My IP only" would break automatic deploys. Hardening later: SSM or a temporary security-group rule from the workflow. |
| First deploy | manual `workflow_dispatch` with environment approval | Automatic deploys on merge only after you have seen several good ones. |
| Phase 4 (Redis, two instances) | stays local | Two instances plus Redis would burn the credits and does not fit 2 GB. |
| Demo link | temporary, ends with the plan on 2027-04-07 unless you upgrade | Never present it as permanent. |

Credit maths (my estimate, verify): `t3.small` about $0.0208/h in US East (I believe; Sydney is somewhat higher) = ~$15/month, + IPv4 $3.65 + 20 GB disk ~$2 = about $20/month. The plan ends in about 5 months, so with $200 in credits the end date, not the credits, is the limit. At the end you would have roughly $80-100 left, which only helps if you upgrade to Paid (credits expire 12 months after account creation, about October 2027).

---

## 1. Day 1: account safety and the extra $100 (about 1.5 h)

### 1.1 Lock down the root user
1. Console, top-right account menu, Security credentials.
2. Assign MFA device, authenticator app. Save the recovery method.
3. Do not create access keys for root.

### 1.2 Daily-use IAM user
1. IAM, Users, Create user `admin-<yourname>`, tick "Provide user access to the AWS Management Console".
2. Attach policy `AdministratorAccess` directly.
3. Enable MFA for that user. Sign out, sign in as the IAM user from now on.
4. Account settings, "IAM user and role access to Billing information", Activate (needs root once).

### 1.3 Region
Top-right region selector, Asia Pacific (Sydney) `ap-southeast-2`. Check it before every console action. Never click a prompt to "activate" a region or advanced features on the Free plan.

### 1.4 The five onboarding activities ($20 each, up to $100 extra)
Open Console Home and find the "Explore AWS" widget. Each activity earns $20; credits usually show up within about 10 minutes. The EC2, Bedrock and RDS activities spend a little of your credits while they run, so do each one fast and delete what it creates.

Order:
1. **Budget (free).** Billing and Cost Management, Budgets, Create budget, simplified template, Monthly cost budget, amount $15, your email. This is also your real overspend alert after an upgrade.
2. **Lambda.** Follow the widget (function with a Function URL). Open the URL once. Delete the function afterwards.
3. **Bedrock.** Open the text playground, choose a model that works without extra subscription steps, send one very short prompt. If it asks for a payment-method or marketplace subscription, pick another model or stop and ask.
4. **EC2.** Follow the widget: launch the smallest eligible instance, wait until "running", then Terminate. Check the instance state says "terminated" and that no Elastic IP is allocated (EC2, Elastic IPs: the list must be empty).
5. **RDS (last; about 20 to 30 minutes).** Standard create, PostgreSQL, the Free-tier template, smallest class, Single-AZ, no public access. When it shows "Available", delete it: untick "Create final snapshot" and "Retain automated backups", type the confirmation. Check RDS, Databases is empty.

After each activity, Billing, Credits: the total should step up by $20. When done it should read about $200 minus a few dollars spent. If a task does not register after 30 minutes, refresh and look again; if still missing, ask.

### 1.5 Check the notifications
Billing, Billing preferences: make sure the email on the account is one you read daily. AWS sends emails when 50, 25 and 10 percent of credits remain and 15, 7 and 2 days before the plan ends.

Stop-check: Billing home shows Free plan, about $100+ credits (more after the activities), "152 days" counting down to Apr 7, 2027.

---

## 2. Day 1-2: a hostname (decide today, finish after step 3.3)

You need a name like `chat.example.com` whose A record points at the Elastic IP. Route 53 can host DNS (credits cover the hosted zone and queries), but **credits cannot pay for a domain registration there, and the fee is not refundable.** So pick one:

**Option A: buy a cheap domain** at any registrar with a card (check current prices; a plain `.com` is usually the safest). Use the registrar's DNS panel; no DNS API is needed because Caddy uses the HTTP challenge.

**Option B: a free dynamic-DNS subdomain.** Let's Encrypt limits certificates per registered domain, so the provider's domain should be on the Public Suffix List (then every subdomain gets its own limit). Check the provider's domain at publicsuffix.org/list before relying on it. I could not verify DuckDNS; No-IP's domains have been cited as listed. If the domain is not on the list, certificates may fail for reasons outside your control.

Either way, set TTL to 300 seconds and do NOT create the record until you have the Elastic IP (step 3.3).

---

## 3. Day 2: launch the server (about 1 h)

### 3.1 Key pair on your laptop (for your own SSH)
Created in the console during launch (step 3.2). Save the `.pem` to `~/.ssh/chat-prod.pem` and run `chmod 400 ~/.ssh/chat-prod.pem`.

### 3.2 Launch the instance
EC2, Launch instance (region Sydney):
- Name: `chat-prod`
- AMI: Ubuntu Server 24.04 LTS, **64-bit (x86)**, not Arm
- Instance type: `t3.small` (it should carry the "Free tier eligible" label; if it does not, STOP and ask)
- Key pair: create new, type ED25519, `.pem`
- Network settings, Edit: create a new security group `chat-prod-sg` with exactly three inbound rules:
  - SSH TCP 22 from Anywhere (key-only; see section 0 for why)
  - HTTP TCP 80 from Anywhere
  - HTTPS TCP 443 from Anywhere
  Nothing else. **Never open 5432 or 8080.**
- Storage: 20 GiB gp3
- Advanced details: Credit specification **Standard** (t3 defaults to Unlimited, which can spend credits on sustained CPU; Standard throttles instead), IMDSv2 "Required", Termination protection Enable.
- Launch. Wait for "running" and 2/2 checks.

### 3.3 Elastic IP
EC2, Elastic IPs, Allocate, then Associate it with `chat-prod`. Copy the address.

### 3.4 DNS
Create the A record `<hostname> -> <elastic-ip>`, TTL 300. Verify from your laptop:
```
dig +short <hostname>
```
It must print the Elastic IP before you continue (can take minutes).

### 3.5 First login
```
ssh -i ~/.ssh/chat-prod.pem ubuntu@<hostname>
```
Accept the host key. If it times out: the security group, the Elastic IP association, or your network blocking 22.

---

## 4. Day 2: prepare the server (about 1 h)

Run as `ubuntu`:

```
sudo apt-get update && sudo apt-get -y upgrade
sudo reboot        # reconnect after about a minute
```

### 4.1 Docker
```
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo docker run --rm hello-world
```

### 4.2 Log rotation, swap, fail2ban
```
sudo tee /etc/docker/daemon.json >/dev/null <<'EOF'
{ "log-driver": "json-file", "log-opts": { "max-size": "10m", "max-file": "3" } }
EOF
sudo systemctl restart docker

sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swappiness.conf && sudo sysctl --system

sudo apt-get install -y fail2ban
sudo fail2ban-client status sshd        # the sshd jail should be listed
sudo sshd -T | grep -E 'passwordauthentication|permitrootlogin'   # expect: no / without-password (or no)
free -m                                  # swap should show 2047
```

### 4.3 The deploy user and its key
On your laptop (not the server):
```
ssh-keygen -t ed25519 -f ~/.ssh/chat-deploy -C "github-actions-deploy" -N ""
cat ~/.ssh/chat-deploy.pub
```
On the server:
```
sudo adduser --disabled-password --gecos "" deploy
sudo usermod -aG docker deploy      # note: docker group is root-equivalent; accepted for a single-purpose demo server
sudo install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
echo "<paste the ssh-ed25519 line from chat-deploy.pub>" | sudo tee /home/deploy/.ssh/authorized_keys
sudo chown deploy:deploy /home/deploy/.ssh/authorized_keys && sudo chmod 600 /home/deploy/.ssh/authorized_keys
sudo install -d -m 755 -o deploy -g deploy /opt/chat /opt/chat/backups
```
Test from your laptop: `ssh -i ~/.ssh/chat-deploy deploy@<hostname> 'docker ps'` must work and `sudo -n true` must fail.

### 4.4 Pin the host key (for the GitHub secret)
On your laptop:
```
ssh-keyscan -t ed25519 <hostname> | tee /tmp/known_hosts_line
ssh-keygen -lf /tmp/known_hosts_line                     # fingerprint A
```
On the server: `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` (fingerprint B). **A must equal B.** If not, stop.

### 4.5 The app secrets file (never in git)
Create `/opt/chat/.env` as `deploy`, mode 600. Use the exact variable names from `deploy/.env.example` in the repo (the list below is what to expect):
```
openssl rand -base64 48   # JWT_SECRET
openssl rand -base64 24   # each password
```
| Variable | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` (never `dev` or `bench`) |
| `DOMAIN` | `<hostname>` |
| `JWT_SECRET` | the 48-byte value |
| `WS_ALLOWED_ORIGINS` | `https://<hostname>` |
| `METRICS_PASSWORD` | 16+ chars (or leave unset: metrics then stay off) |
| `REGISTRATION_INVITE_CODE` | a code you will share only in applications |
| `DEMO_PASSWORD` | the demo account password (treat demo data as public) |
| database name, user, password | fresh values |
`chmod 600 /opt/chat/.env` and check `ls -l`.

---

## 5. Day 2-3: GitHub side (about 30 min)

1. Settings, Environments, New environment `production`. Add yourself as **Required reviewer** and restrict to the `main` branch.
2. Settings, Secrets and variables, Actions, repository secrets:
   - `DEPLOY_HOST` = `<hostname>`
   - `DEPLOY_USER` = `deploy`
   - `DEPLOY_SSH_KEY` = the full contents of `~/.ssh/chat-deploy` (the private key)
   - `DEPLOY_KNOWN_HOSTS` = the line from `/tmp/known_hosts_line`
3. After the first image push only: GitHub profile, Packages, open each package (`dox-app`, `dox-web`), Package settings, Change visibility, Public. Until then the server cannot pull.
4. Optional: branch protection on `main` requiring the CI check.

---

## 6. What the repo must contain (give this to Claude Code for step 5)

- `workflow_dispatch` trigger for the deploy job; environment `production` with approval; automatic deploy on push to `main` stays OFF until section 9.
- The deploy job uses `appleboy`-free plain `ssh`/`scp` with the pinned known_hosts (no host-key prompts, no `StrictHostKeyChecking=no`).
- `deploy.sh <tag>`: pull, up -d, health gate (health endpoint plus a real login/ticket/socket/message check), automatic rollback to the previous tag, fail the job on failure.
- An `ACME_CA` setting (or equivalent) so the first start can use Let's Encrypt **staging** (certificates untrusted by browsers, but no rate-limit risk), then switch to production. Repeated failures against the production endpoint can lock you out for up to a week.
- Backup script and cron (daily `pg_dump -Fc`, keep 7), a restore script, a teardown section in the runbook.
- Compose `app` memory limit and `-XX:MaxRAMPercentage` as decided (verify with `docker stats`).
- No secrets, no `.env`, nothing from `dev`/`bench` profiles in the images.

---

### 6.1 What the repo contains (as built)
- `.github/workflows/deploy.yml` (manual `workflow_dispatch`):
  - **gate:** the ref is `main`, and all three CI checks are green for that commit;
  - **images:** pushes `ghcr.io/sanidhya284/dox-app` and `dox-web` as `sha-<short>` and `main`;
  - **deploy:** environment `production`; `scp`s `compose.yml` and the `deploy/prod/*` scripts to `/opt/chat`, runs `deploy.sh <tag>`, and installs the backup cron.
- `deploy/prod/deploy.sh`: pull, `up -d`, then `postdeploy_check.py`, which checks health, demo1 login, ticket, socket, send + ack, and REST read-back (in the "Deploy checks" group). On failure it rolls back to `.deployed-tag` and fails the job.
- `deploy/prod/backup.sh` (cron 03:15 UTC, keeps 7) and `deploy/prod/restore.sh <dump>` (a scratch-container drill only).
- TLS: `ACME_MODE=staging` in `/opt/chat/.env` until DNS and ports are confirmed (7.2).

### 6.2 Restore the live database (rare; do the drill first)
As `deploy` on the server:
```
cd /opt/chat
docker compose -f compose.yml stop app web
docker compose -f compose.yml exec -T db dropdb -U chat --force chat
docker compose -f compose.yml exec -T db createdb -U chat chat
docker compose -f compose.yml exec -T db pg_restore -U chat -d chat --no-owner < backups/<file>.dump
docker compose -f compose.yml start app web
python3 postdeploy_check.py --env .env
```

## 7. First deploy and verification (day 3 or later; about 1 h)

### 7.0 GitHub clicks before the first deploy
1. **Settings → Environments → New environment** `production` → **Required reviewers**: add yourself → **Deployment branches and tags**: "Selected branches and tags" → add the rule `main` → Save.
2. Merge the Phase 6 PR into `main` (CI must be green; `deploy.yml` must be on `main` to be dispatchable).
3. **Actions → Deploy → Run workflow** (branch `main`). `gate` and `images` run; `deploy` then waits for your review.
4. **While it waits:** your profile → **Packages** → `dox-app` → **Package settings** → **Change visibility** → **Public**; the same for `dox-web`. Without this the server can't pull.
5. Back in the run: **Review deployments** → tick `production` → **Approve and deploy**.

### 7.2 Switch from Let's Encrypt staging to production (after the first good deploy)
The first deploy serves a **staging** certificate: browsers warn, by design. Once `dig +short dox.work.gd` equals the Elastic IP and the staging certificate was issued (`docker compose -f /opt/chat/compose.yml logs web | grep -i "certificate obtained"`), as `deploy`:
```
sed -i 's/^ACME_MODE=staging$/ACME_MODE=default/' /opt/chat/.env
cd /opt/chat && IMAGE_TAG=$(cat .deployed-tag) docker compose -f compose.yml up -d web
docker compose -f compose.yml logs web | grep -iE '"issuer"|certificate obtained|acme'   # which CA issued it
```
Record which CA issued the certificate (Let's Encrypt, or ZeroSSL as Caddy's fallback; both are acceptable). Don't loop retries against the production CA.

### 7.3 Move to a new domain (done once: `dox.work.gd` → `dox-chat.in`)
1. Point A records for the new name and `www.` at the Elastic IP, no AAAA. Check `dig +short @8.8.8.8 <name>` and also your ISP's resolver.
2. In `/opt/chat/.env` (as `deploy`):
   - `DOMAIN=<new>`;
   - `WS_ALLOWED_ORIGINS=https://<new>`;
   - `REDIRECT_HOSTS=www.<new> <old>`;
   - `ACME_MODE=staging`.
3. Run **Actions → Deploy** (the new web image carries the redirect block). The post-deploy check uses the new `DOMAIN`.
4. Once staging certificates exist for every name, do 7.2 (`ACME_MODE=default`) and record the issuing CA.
5. Verify:
   - the new name loads;
   - health is 200, and prometheus and env are 404;
   - a live message passes between two demo users;
   - `curl -sI https://<old>/` gives `308` with `location: https://<new>/`.
6. **Effects of the move:** the refresh cookie is host-only, so sessions on the old name don't carry over and users log in once on the new name. Old HSTS entries for the old name stay harmless (it still serves HTTPS).


1. CI green on the step 5 PR. Merge it (merge commit).
2. Actions, the deploy workflow, Run workflow, branch `main`. Approve the environment. Watch every job.
3. From your laptop (replace `<h>`):
```
curl -sS -o /dev/null -w "%{http_code}\n" https://<h>/actuator/health        # 200
curl -sS -o /dev/null -w "%{http_code}\n" https://<h>/actuator/prometheus    # 404
curl -sS -o /dev/null -w "%{http_code}\n" https://<h>/actuator/env           # 404
curl -sI https://<h> | grep -i -E 'strict-transport|x-content-type|referrer'
```
4. In two browser windows: log in as two demo users, send messages both ways, refresh one window mid-conversation, confirm it recovers.
5. On the server (as `deploy`):
```
docker compose -f /opt/chat/compose.yml ps
docker stats --no-stream      # chat-app memory well under its limit
free -m ; df -h /
sudo grep -c 'ticket=' <caddy access log path>      # must be 0
```
6. If the certificate fails: check `dig +short <hostname>` equals the Elastic IP, ports 80/443 in the security group, and the Caddy logs. Use staging while debugging.

---

## 8. Drills (do each once; record the date in the runbook)

1. **Backup and restore:** run the backup script, restore into a scratch container, check row counts. An untested backup is only a hope.
2. **Rollback:** **Actions → Deploy → Run workflow**, branch `main`, tick **drill**. The workflow builds `dox-app:drill-broken-<sha>` (the real image with an entrypoint that exits at once; never tagged `main`) and deploys it. The check must fail, `deploy.sh` must roll back to `.deployed-tag`, the check must pass on that, and the job must fail. Expect a few minutes of downtime: the 180 s health wait, then the restart. Then do the migration-aware variant described in the repo's runbook (what you can test only on the real server).
3. **Off-AWS copy:** from your laptop, `scp deploy@<hostname>:/opt/chat/backups/<latest>.dump ~/backups/`. The Free plan account closes at the end of the plan, and data in it is kept only for 90 days.
4. **Fail-closed check:** on a scratch container, start the image with a missing secret and confirm it refuses to start.
5. **Uptime monitor:** register a free external monitor on `https://<hostname>/actuator/health` (check the current free limits first), alert to your email.

### Drill log
| Drill | Date (UTC) | Result |
|---|---|---|
| 8.1 Backup and restore | 2026-11-08 16:40 | **Pass.** `backup.sh` → `chat-20261108T164018Z.dump` (19,622 bytes), restored into a scratch `postgres:16.4-alpine` with `pg_restore` exit 0. Row counts for all 7 public tables were equal to the live DB (`conversation_participants` 5, `conversations` 2, `flyway_schema_history` 6, `messages` 5, `refresh_tokens` 11, `users` 3, `ws_tickets` 0). The scratch container was removed, and the live DB was only read. |
| 8.2 Rollback | 2026-11-08 17:10–17:14 | **Pass.** Run 42503224914 deployed `drill-broken-012b6b6`: `compose up` failure logged, check failed after the 180 s health wait, rolled back to `sha-31bfc4d`, and the check passed on it (health, login, socket, send + ack seq 5, read-back). The job failed as intended. Checked independently: app and web on `sha-31bfc4d`, `.deployed-tag` unchanged, public health 200. Downtime about 3 min 20 s. **Migration-aware variant:** not drillable on production, because a real new migration can't be undone. Covered instead in CI by `FutureMigrationToleranceTest` (an older release still validates against a newer schema) and `check_migrations.py` (additive-only rule). **Prerequisite found and fixed first:** `deploy.sh` ran `compose up -d` under `set -e`. When the new app never becomes healthy, `up` itself fails (web waits for a healthy app), so the script ended **before** the rollback. Shown locally: `main`'s `deploy.sh` left the broken image running. The fixed script logs the `up` failure and lets the post-deploy check decide; locally it rolled back and the check passed on the old tag. |
| 8.3 Off-AWS copy | 2026-11-08 17:49 | **Pass.** `scp` (by the owner) of `chat-20261108T164018Z.dump` to `~/backups/`: 19,622 bytes on both sides, the same SHA-256 (`43d6a42a…c04b543`). It also restored into a local scratch container (users 3, conversations 2, participants 5, messages 5). That restore exposed a race in `restore.sh` (`pg_isready` passing against the image's temporary init server), now fixed: it waits for "init process complete". |
| 8.4 Uptime monitor | 2026-11-09 | **Pass.** UptimeRobot (free) keyword monitor on `https://dox-chat.in/actuator/health` (`"status":"UP"`, 5 min, email alerts), set up by the owner. Confirmed from our side: 35 `UptimeRobot/2.0` requests in 3 h, about every 5 min, all 200. |

---

## 9. Automatic deploys (on since 2026-11-09)

Turned on after the drills above passed (8.1–8.4) and three manual deploys (`sha-c89219d`, `sha-31bfc4d`, and the 8.2 drill).
- **Trigger:** every **push to `main`** whose **CI run succeeds** (`workflow_run` on `CI`) deploys that exact commit. There's no approval step.
- **Docs-only pushes:** they don't run CI (path filters), so they never deploy.
- **Manual dispatch** stays available: a redeploy of `main`, or the 8.2 drill checkbox.
- **Unchanged safety nets:**
  - one deploy at a time; a running deploy is never cancelled (a newer pending one replaces an older pending one);
  - the gate re-checks all four CI checks for that commit;
  - `deploy.sh` runs the real-path check (health, login, ticket, socket, send + ack, read-back) and rolls back on failure.
- **One GitHub setting must match:** **Settings → Environments → production → Required reviewers** must be **empty**, or automatic deploys wait for approval. The environment stays, for the deployment history and its `main`-only rule.
- **Migrations must be additive (expand/contract)** so a rollback still works against a newer schema. CI enforces it (job "Migrations are additive (expand/contract)", `.github/scripts/check_migrations.py`):
  - no existing migration modified, deleted or renamed;
  - new migrations above V6 contain no DROP, RENAME, column type change, SET NOT NULL, TRUNCATE or DELETE, and no NOT NULL column without a DEFAULT.
- **A deliberate contract step** (removing something no deployed or rollback-target release uses) needs a line `-- contract: <why it's safe>` in the migration. Ship it only one release after the code stopped using the object.
- **Turning automatic deploys off again:** in `deploy.yml`, remove the `workflow_run` trigger (or add a required reviewer back).
- **Check what's live:** `curl -s https://dox-chat.in/api/version`, or the "build <commit>" line at the bottom of every page.

### 9.1 Proof (2026-11-09)
- **The first automatic deploy (`4270fd8`, PR #14) was cancelled:** it started while the `production` environment still had a required reviewer, and removing that rule cancelled the waiting approval. Remove the reviewer **before** merging the PR that turns automatic deploys on.
- **PR #15 (`GET /api/version` + footer) went live with no manual step**, measured from outside by polling `/api/version`:

| Time (UTC) | Step | Duration |
|---|---|---|
| 19:27:41 | Merged (`6e92930`) | |
| 19:27:45 → 19:35:11 | CI on `main` (4 checks) | 7 min 26 s |
| 19:35:16 | Deploy started (`workflow_run`, 0 approvals) | |
| 19:35:20 → 19:36:47 | Gate 5 s, images 37 s, deploy and post-deploy check 37 s | about 1.5 min |
| 19:36:51 | `/api/version` reports `6e92930` | **merge to live: 9 min 10 s** (±10 s polling) |

- **Where the time goes:** CI is about 81% of it; the e2e job waits for the backend job. Running them in parallel is the main lever if it ever needs to be faster; that hasn't been done.
- **Check of the docs-only rule:** this runbook update is a docs-only merge, so it must not start CI or a deploy.

---

## 9a. Remove an abusive account (ADR-0012)

The same removal step the guest cleanup uses: conversations with only system accounts or guests are deleted, every other conversation is left (messages stay, shown as "Deleted user"), the account is anonymized, its refresh tokens and tickets are revoked, and its sockets close with `4002`. It can't be undone, so take a backup first.

```bash
cd /opt/chat
./backup.sh                                   # or check that today's 03:15 backup exists
TAG=$(curl -s https://dox-chat.in/api/version | sed -E 's/.*"shortCommit":"([0-9a-f]+)".*/sha-\1/')
IMAGE_TAG=$TAG docker compose run --rm --no-deps app --remove-account=<username>
```

- It starts a second, short-lived app process (same image and `.env`, no published port), removes the account, logs `Removed account <username> (runbook)` and exits with `0`; with no such live account it logs `Not removed` and exits with `1`.
- It needs about as much memory as the app itself for a minute; on the 2 GB server, run it when the swap isn't already in use.
- Check afterwards: the username can't log in (401), and `docker compose ps` still shows the real app healthy.

## 10. Operating until April

- **Weekly (5 minutes):** Billing, Credits (burn rate vs the estimate); `df -h /`; `docker compose ps`; the monitor's history; `sudo apt list --upgradable`.
- **After 24 hours and 7 days:** compare real spend with the estimate. If it is much higher, look at Cost Explorer grouped by service (data transfer and CPU credits are the usual surprises).
- **Do not** run the benchmark harness against this instance, and do not run Phase 4 here.
- **Resume rule:** add the live link only after it has been stable for several days, and make the screen recording the main demo.
- **By March 1, decide:** (a) upgrade to Paid with the $15 budget alert and a spend cap in mind (leftover credits then apply until they expire), (b) let the plan end and rely on the video, or (c) move the same stack to another VPS (the stack is portable; the backup is already off AWS).

### Teardown (when you stop)
1. Take a final backup and copy it off AWS.
2. EC2, disable termination protection, terminate `chat-prod`.
3. EC2, Elastic IPs: release the address. Elastic Block Store: delete leftover volumes and snapshots.
4. Remove the DNS record (and the hosted zone if you created one).
5. Check Billing the next day: no running charges.

---

## Appendix: quick troubleshooting

| Symptom | First checks |
|---|---|
| `ssh` times out | security group rule for 22; Elastic IP associated; instance running |
| Browser warns about the certificate | you are on Let's Encrypt staging; or DNS not pointing at the Elastic IP yet |
| Caddy cannot get a certificate | `dig`, ports 80/443, Caddy logs; stay on staging until it works |
| Deploy job cannot pull images | GHCR packages still private; wrong tag; disk full |
| App exits on start | `docker compose logs app`: a missing or weak secret (`JWT_SECRET`, `WS_ALLOWED_ORIGINS`, profile) is a deliberate refusal |
| Out-of-memory kills | `docker stats`, `free -m`; check swap; lower heap percentage |
| Credits burn faster than expected | Cost Explorer by service; CPU credit surplus (set Standard); data transfer |
