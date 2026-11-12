#!/usr/bin/env bash
# Applies the repository's GitHub settings: rulesets, merge settings, security features,
# Actions policy and deployment environments. Idempotent; safe to re-run.
#
# Usage: tools/github/apply.sh [owner/repo]     (needs `gh` logged in as a repository admin)
set -euo pipefail

repo="${1:-damienmiheev/monobank-kotlin-sdk}"
owner="${repo%%/*}"
dir="$(cd "$(dirname "$0")" && pwd)"
api() { gh api -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" "$@"; }

echo "== Repository settings"
api -X PATCH "repos/$repo" --silent --input - <<JSON
{
  "description": "Kotlin/Java SDK for monobank's public APIs: acquiring, personal, corporate and installments",
  "homepage": "https://central.sonatype.com/namespace/io.github.damienmiheev",
  "has_wiki": false,
  "has_projects": false,
  "has_discussions": false,
  "allow_squash_merge": true,
  "allow_merge_commit": false,
  "allow_rebase_merge": false,
  "allow_auto_merge": false,
  "allow_update_branch": true,
  "delete_branch_on_merge": true,
  "squash_merge_commit_title": "PR_TITLE",
  "squash_merge_commit_message": "PR_BODY",
  "web_commit_signoff_required": false,
  "security_and_analysis": {
    "secret_scanning": { "status": "enabled" },
    "secret_scanning_push_protection": { "status": "enabled" }
  }
}
JSON
api -X PUT "repos/$repo/topics" --silent --input - <<'JSON'
{ "names": ["monobank", "kotlin", "java", "sdk", "payments", "acquiring", "ukraine", "killbill"] }
JSON

echo "== Security features"
api -X PUT "repos/$repo/vulnerability-alerts" --silent
api -X PUT "repos/$repo/automated-security-fixes" --silent
api -X PUT "repos/$repo/private-vulnerability-reporting" --silent

echo "== Immutable releases"
api -X PUT "repos/$repo/immutable-releases" --silent

echo "== Actions policy"
api -X PUT "repos/$repo/actions/permissions" --silent --input - <<'JSON'
{ "enabled": true, "allowed_actions": "selected", "sha_pinning_required": true }
JSON
api -X PUT "repos/$repo/actions/permissions/selected-actions" --silent --input - <<'JSON'
{ "github_owned_allowed": true, "verified_allowed": false, "patterns_allowed": ["gradle/actions/*", "ossf/scorecard-action@*"] }
JSON
api -X PUT "repos/$repo/actions/permissions/workflow" --silent --input - <<'JSON'
{ "default_workflow_permissions": "read", "can_approve_pull_request_reviews": false }
JSON
api -X PUT "repos/$repo/actions/permissions/fork-pr-contributor-approval" --silent --input - <<'JSON'
{ "approval_policy": "all_external_contributors" }
JSON

echo "== Environments"
owner_id="$(api "users/$owner" --jq .id)"
api -X PUT "repos/$repo/environments/maven-central" --silent --input - <<JSON
{
  "reviewers": [{ "type": "User", "id": $owner_id }],
  "prevent_self_review": false,
  "deployment_branch_policy": { "protected_branches": false, "custom_branch_policies": true }
}
JSON
if ! api "repos/$repo/environments/maven-central/deployment-branch-policies" --jq '.branch_policies[].name' | grep -qx 'v*'; then
  api -X POST "repos/$repo/environments/maven-central/deployment-branch-policies" --silent -f name='v*' -f type=tag
fi
api -X PUT "repos/$repo/environments/monobank-test" --silent --input - <<JSON
{ "reviewers": [{ "type": "User", "id": $owner_id }], "prevent_self_review": false }
JSON

echo "== Rulesets"
# Rulesets renamed since the first version of this script.
old="$(api "repos/$repo/rulesets" --jq '.[] | select(.name == "release tags") | .id')"
if [ -n "$old" ]; then api -X DELETE "repos/$repo/rulesets/$old" --silent; echo "removed old: release tags"; fi
for file in "$dir"/ruleset-*.json; do
  name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["name"])' "$file")"
  id="$(api "repos/$repo/rulesets" --jq ".[] | select(.name == \"$name\") | .id")"
  if [ -n "$id" ]; then
    api -X PUT "repos/$repo/rulesets/$id" --silent --input "$file"
    echo "updated: $name"
  else
    api -X POST "repos/$repo/rulesets" --silent --input "$file"
    echo "created: $name"
  fi
done

echo "Done. Review: https://github.com/$repo/settings/rules"
