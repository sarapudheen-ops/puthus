#!/usr/bin/env bash
# Turns Kotlin compiler errors, lint errors, failed unit tests and Gradle failures from build.log into
# GitHub Actions error annotations, so the cause of a failed build shows up directly on the PR.
# Usage: annotate-build-failures.sh [build.log]
set -u
log="${1:-build.log}"
[ -f "$log" ] || exit 0
ws="${GITHUB_WORKSPACE:-$PWD}/"

# Joins stdin into one annotation message: strips workspace paths, escapes % and newlines.
oneline() {
  sed -e "s#file://##" -e "s#${ws}##g" | sed -e ':a' -e 'N' -e '$!ba' -e 's/%/%25/g' -e 's/\r//g' -e 's/\n/%0A/g'
}

# Summaries first: GitHub keeps only the first 10 error annotations per step.
gradle=$(grep -A15 '^\* What went wrong' "$log" | head -60 | oneline)
[ -n "$gradle" ] && echo "::error title=Gradle::${gradle}"

kotlin=$(grep -E '^e: ' "$log" | head -40 | oneline)
[ -n "$kotlin" ] && echo "::error title=Kotlin compilation failed::${kotlin}"

tests=$(grep -E ' > .* FAILED$' -A4 "$log" | head -80 | oneline)
[ -n "$tests" ] && echo "::error title=Unit tests failed::${tests}"

lint=$(grep -E ': Error: ' "$log" | head -40 | oneline)
[ -n "$lint" ] && echo "::error title=Lint errors::${lint}"

# Then a few inline annotations on the offending lines.
grep -E '^e: ' "$log" | head -4 | sed -nE "s#^e: (file://)?${ws}([^:]+):([0-9]+):([0-9]+) (.*)#::error file=\2,line=\3,col=\4::\5#p"
grep -E ': Error: ' "$log" | head -2 | sed -nE "s#^(${ws})?([^: ]+):([0-9]+): Error: (.*)#::error file=\2,line=\3::\4#p"
exit 0
