#!/usr/bin/env bash
set -euo pipefail

# Base packages needed to add the trivy apt repo below (gnupg for the key,
# apt-transport-https/lsb-release for the repo itself, curl to fetch it).
sudo apt-get update
sudo apt-get install -y --no-install-recommends \
  curl ca-certificates apt-transport-https gnupg lsb-release

# Development helpers, each pulled in for a specific reason:
#   jq      -- inspecting/crafting stdio protocol JSON Lines by hand
#   socat   -- exercising the socket transport (.claude/rules/architecture.md,
#              .claude/rules/connectivity.md) against a real san-db-ox process
#   openssh-client/openssh-server -- exercising the direct-connect-over-SSH
#              path (.claude/rules/connectivity.md) locally, both as the
#              client (`ssh user@host san-db-ox --serve-stdio`) and as the
#              server, to actually run an authorized_keys forced-command
#              entry against a local sshd rather than only reading the
#              syntax off documentation (.claude/rules/testing.md: "検証
#              できない例ほど、書いた時点で手元で一度実行して確かめる
#              こと"). sshd is not started here -- it's brought up by hand
#              only when that verification is being done.
#   ShellCheck -- backs `make shellcheck`
sudo apt-get install -y --no-install-recommends \
  jq socat openssh-client openssh-server shellcheck

# ShellCheck note: do not start a comment line with a lowercase "# shellcheck"
# anywhere in this repo's scripts -- ShellCheck itself tries to parse it as an
# inline directive and fails with SC1072/SC1073 if it isn't one (hit for real
# in san-db-ox's own postCreate.sh; see .claude/rules/testing.md there).
# Capitalizing ("ShellCheck") avoids the misparse, as done above and below.

# trivy: not available as a devcontainer feature, and its apt repo isn't a
# one-liner, so it is installed here rather than via "features" in
# devcontainer.json (.claude/rules/testing.md: "features は公式のみ" applies
# to devcontainer.json's features block, not to what postCreate.sh installs).
curl -fsSL https://aquasecurity.github.io/trivy-repo/deb/public.key \
  | gpg --dearmor | sudo tee /usr/share/keyrings/trivy.gpg > /dev/null
echo "deb [signed-by=/usr/share/keyrings/trivy.gpg] https://aquasecurity.github.io/trivy-repo/deb generic main" \
  | sudo tee /etc/apt/sources.list.d/trivy.list > /dev/null
sudo apt-get update
sudo apt-get install -y --no-install-recommends trivy

# gh (GitHub CLI): same reasoning as trivy above -- not in Debian
# bookworm's own repo at a usable version, installed from GitHub's own
# apt repo instead. Needed to file issues/PRs against upstream
# san-db-ox when a spec gap or implementation bug turns up there
# (CLAUDE.md's "まず本体の仕様書を直すことを提案する" workflow starts
# with an upstream issue). The key file GitHub publishes is already a
# binary keyring, unlike trivy's ASCII-armored one above, so no
# `gpg --dearmor` here.
curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
  | sudo tee /usr/share/keyrings/githubcli.gpg > /dev/null
echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli.gpg] https://cli.github.com/packages stable main" \
  | sudo tee /etc/apt/sources.list.d/github-cli.list > /dev/null
sudo apt-get update
sudo apt-get install -y --no-install-recommends gh
