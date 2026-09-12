# GitHub Actions — status and unblock procedure (FX-001a)

## Status as of 2026-08-09: Actions is RUNNING again

The handoff (6 Aug 2026) reported dispatched workflows queueing ~15 minutes and being
cancelled without starting, in this repo and in Fylz — the classic signature of exhausted
included minutes against a zero spending limit on a private repo.

Whatever the cause was, it is no longer reproducing. Observed on this repo:

| Run | Result | Wall time |
|---|---|---|
| `31340676799` (PR #8, 2026-08-09 22:57 UTC) | success | ~4 min |
| `31342608811` (PR #8, 2026-08-09 23:43 UTC) | success | ~4 min |
| `31342619343` / `31342591274` (Shared-Libraries-asoc PR #4) | success | ~2 min each |

So FX-001a's premise is stale: **CI is available and gates PRs normally.** The plan's
posture stands regardless — `scripts/verify.sh` is the authoritative local gate and CI
runs the same script, so a future Actions outage degrades verification convenience, not
verification truth.

## If it recurs: check order

1. **Settings → Billing → Spending limits** on the account that owns the repo. A private
   repo meters Actions minutes; a $0 spending limit with exhausted included minutes
   produces queued-then-cancelled with no error anywhere in the workflow UI.
2. **Billing → Actions minutes used** vs the plan's included quota, and the reset date.
3. Whether the repo is under a **personal account or an org** — quotas and limits attach
   to the owner, and the same symptom appearing in two repos (here and Fylz) points at a
   shared owner-level cause, not a repo-level one.
4. Repo visibility: public repos do not meter minutes on GitHub-hosted standard runners;
   if the repo has gone private recently, that is the change.

## Fallback: self-hosted runner

If billing turns out not to be the cause (or the quota is simply too small), a
self-hosted runner keeps the same workflow file working with a two-line change:

```yaml
jobs:
  build:
    runs-on: [self-hosted, linux]   # was: ubuntu-latest
```

Runner setup on any Linux box with JDK 17 and ~15 GB free disk:

```bash
mkdir actions-runner && cd actions-runner
curl -o actions-runner-linux-x64.tar.gz -L \
  https://github.com/actions/runner/releases/latest/download/actions-runner-linux-x64-2.3xx.x.tar.gz
tar xzf actions-runner-linux-x64.tar.gz
./config.sh --url https://github.com/mbaliga/Foto-Xplorr --token <from repo Settings → Actions → Runners>
./run.sh
```

The workflow installs its own Android SDK via `android-actions/setup-android`, so the
runner needs no pre-installed SDK. Keep the runner OFF for pull requests from forks
(Settings → Actions → "Require approval for all outside collaborators") — a self-hosted
runner executing untrusted PR code is an RCE.
