export REPO="$PWD"
export SHA="$GITHUB_SHA"
export NAME=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
export CODE=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)

# the workspace is owned by the runner user, not the container's root
git config --global --add safe.directory "$REPO/.git"

cd /tmp
git clone --depth 1 https://gitlab.com/fdroid/fdroidserver
git clone --depth 1 https://gitlab.com/fdroid/fdroiddata
apt-get update
apt-get install -qy --no-install-recommends jq python3-markdown-it
curl -fsSLo /usr/local/bin/yq https://github.com/mikefarah/yq/releases/download/v4.54.1/yq_linux_amd64
chmod +x /usr/local/bin/yq

cd fdroiddata
# a pull request's merge commit only exists in this checkout
yq -i '
  .Repo = strenv(REPO) |
  .Builds[-1].versionName = strenv(NAME) |
  .Builds[-1].versionCode = env(CODE) |
  .Builds[-1].commit = strenv(SHA)
' metadata/app.keyholm.yml

export PYTHONPATH=/tmp/fdroidserver
./tools/check-fastlane.py app.keyholm > fastlane.json
jq -r '.[] | select(.severity == "info") | "::notice title=Fastlane::\(.description)"' fastlane.json
problems=$(jq -r '.[] | select(.severity != "info") | "::error title=Fastlane \(.severity)::\(.description)"' fastlane.json)
if [ -n "$problems" ]; then
  echo "$problems"
  exit 1
fi
