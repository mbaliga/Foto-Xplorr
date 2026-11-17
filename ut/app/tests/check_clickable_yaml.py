#!/usr/bin/env python3
"""Validates ut/app/clickable.yaml and the click manifest/apparmor templates.

Needs `pip install clickable-ut jsonschema pyyaml`; the schema is clickable's OWN
project.schema, so this is a real (if static) check that Clickable will accept the file."""
import glob, json, os, sys
import jsonschema, yaml
import clickable

here = os.path.dirname(os.path.abspath(__file__))
app = os.path.dirname(here)
schema_path = os.path.join(os.path.dirname(clickable.__file__), "config", "project.schema")
schema = json.load(open(schema_path))
cfg = yaml.safe_load(open(os.path.join(app, "clickable.yaml")))
jsonschema.validate(cfg, schema)
fw = cfg["framework"]
from clickable.config.constants import Constants
assert fw in Constants.framework_image_mapping, f"unknown framework {fw}"

# templates: substitute the @VARS@ the way CMake does, then parse as JSON
subs = {"EXEC": "fotoz", "CLICK_ARCH": "arm64", "PROJECT_VERSION": "0.1.0", "CLICK_FRAMEWORK": fw,
        "CLICK_POLICY_VERSION": Constants.default_framework_base_policy_mapping[Constants.framework_image_mapping[fw]]}
def render(name):
    t = open(os.path.join(app, name)).read()
    for k, v in subs.items():
        t = t.replace(f"@{k}@", v)
    assert "@" not in t.replace("OWNER-FILL <owner-fill@invalid.example>", ""), f"unsubstituted var in {name}"
    return t
manifest = json.loads(render("manifest.json.in"))
apparmor = json.loads(render("fotoz.apparmor.in"))
assert manifest["framework"] == fw
assert manifest["hooks"]["fotoz"]["apparmor"] == "fotoz.apparmor" and manifest["hooks"]["fotoz"]["desktop"] == "fotoz.desktop"
assert "networking" not in apparmor["policy_groups"], "offline identity: no networking policy group"
assert "[Desktop Entry]" in render("fotoz.desktop.in")
print("ut/app packaging OK:", fw, "policy", apparmor["policy_version"], apparmor["policy_groups"])
