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

cd fdroiddata
# a pull request's merge commit only exists in this checkout
python3 - <<'EOF'
import os
from ruamel.yaml import YAML

yaml = YAML()
path = "metadata/app.keyholm.yml"
with open(path) as f:
    app = yaml.load(f)
app["Repo"] = os.environ["REPO"]
build = app["Builds"][-1]
build["versionName"] = os.environ["NAME"]
build["versionCode"] = int(os.environ["CODE"])
build["commit"] = os.environ["SHA"]
with open(path, "w") as f:
    yaml.dump(app, f)
EOF

export PYTHONPATH=/tmp/fdroidserver
./tools/check-fastlane.py app.keyholm > fastlane.json
jq -r '.[] | select(.severity == "info") | "::notice title=Fastlane::\(.description)"' fastlane.json
problems=$(jq -r '.[] | select(.severity != "info") | "::error title=Fastlane \(.severity)::\(.description)"' fastlane.json)
if [ -n "$problems" ]; then
  echo "$problems"
  exit 1
fi
