#!/usr/bin/env python3
"""Audit bundled localization without Android, network access or modifying resources.

Run ``python tools/check_localization.py`` after generating translations. During
generation, ``--allow-incomplete`` tolerates missing languages/keys but still fails
for damaged translations. ``--json PATH`` optionally writes a reviewable report.

This is a static resource/coverage check, not a substitute for Android resource
selection tests, screenshots, or review by speakers of the translated languages.
"""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
from difflib import SequenceMatcher
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ANDROID = "{http://schemas.android.com/apk/res/android}"
EXPECTED_PLAY_LOCALES = 86
PRINTF = re.compile(r"%(?:[1-9][0-9]*\$)?[-#+ 0,(<]*[0-9]*(?:\.[0-9]+)?(?:[tT][A-Za-z]|[A-Za-z%])")
NUMBERED_FORMAT = re.compile(r"%[1-9][0-9]*\$[-#+ 0,(<]*[0-9]*(?:\.[0-9]+)?(?:[tT][A-Za-z]|[A-Za-z])")
UNRESOLVED = re.compile(r"__\s*(?:PH|NL|URL|KEEP|TOKEN|FMT|LIT)\s*[0-9_ ]*__|\[\s*[0-9]{4}\s*\]", re.I)
URL = re.compile(r"https?://[^\s<>\"\\]+")
IDENTITIES = (
    "Diet Diary", "ru.dietdiary.offline", "Efreitor2001",
    "Дневник диеты: подсчёт калорий", "Дневник диеты",
)
BRANDS = (
    *IDENTITIES, "Google Drive", "Google Play", "Google Account", "Google", "Yandex",
    "Android Keystore", "Android", "App Ad Analytics", "AppMetrica", "OAuth", "PKCE",
    "JSON", "USDA SR Legacy", "USDA", "FDC", "AppAuth", "SHA-256", "App Open",
    "AppGallery", "RuStore", "appDataFolder", "GitHub", "Health Connect", "SDK",
    "HTTPS/TLS", "HTTPS", "HTTP", "TLS", "ID", "BIA", "OAID",
)


def decode_android(raw: str) -> str:
    """Remove Android outer quotes before interpreting escapes (XML is already decoded)."""
    value = raw.strip()
    quoted = len(value) >= 2 and value[0] == '"' and value[-1] == '"' and not value.endswith('\\"')
    if quoted:
        value = value[1:-1]
    else:
        # Actual newlines/indentation collapse in an unquoted Android string. Escaped
        # line breaks are decoded afterwards and therefore remain line breaks.
        value = re.sub(r"\s+", " ", value)
    escapes = {"n": "\n", "t": "\t", "r": "\r"}
    return re.sub(
        r"\\(u[0-9a-fA-F]{4}|n|t|r|\"|'|\\|@|\?)",
        lambda m: chr(int(m[1][1:], 16)) if m[1].startswith("u") else escapes.get(m[1], m[1]),
        value,
    )


@dataclass(frozen=True)
class StringValue:
    value: str
    formatted: bool
    path: Path


class Audit:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.issues: list[dict[str, str]] = []

    def issue(self, kind: str, scope: str, message: str) -> None:
        self.issues.append({"kind": kind, "scope": scope, "message": message})

    def strings(self, directory: Path) -> dict[str, StringValue]:
        result: dict[str, StringValue] = {}
        for path in sorted(directory.glob("*.xml")):
            try:
                tree = ET.parse(path)
            except (ET.ParseError, OSError) as error:
                self.issue("error", str(path.relative_to(self.root)), f"Cannot read XML: {error}")
                continue
            for node in tree.getroot():
                if node.tag != "string":
                    continue
                key = node.get("name", "")
                scope = f"{directory.name}/{key}"
                if not key or not re.fullmatch(r"[a-z][a-z0-9_]*", key):
                    self.issue("error", scope, "Invalid string resource name")
                if key in result:
                    self.issue("error", scope, f"Duplicate key in {result[key].path.name} and {path.name}")
                    continue
                flag = node.get("formatted", "true")
                if flag not in ("true", "false"):
                    self.issue("error", scope, "formatted must be true or false")
                value = decode_android("".join(node.itertext()))
                result[key] = StringValue(value, flag != "false", path)
                if not value.strip():
                    self.issue("error", scope, "Empty localized string")
                if UNRESOLVED.search(value):
                    self.issue("error", scope, "Unresolved translation marker")
                if NUMBERED_FORMAT.search(value) and flag == "false":
                    self.issue("error", scope, "A parameterized string disables format validation")
                if "%" in value and not NUMBERED_FORMAT.search(value) and flag != "false":
                    self.issue("error", scope, "A literal percent sign requires formatted=false")
        return result


def normalize_tag(tag: str) -> str:
    pieces = tag.replace("_", "-").split("-")
    aliases = {"iw": "he", "in": "id", "tl": "fil", "nb": "no"}
    pieces[0] = aliases.get(pieces[0].lower(), pieces[0].lower())
    for i in range(1, len(pieces)):
        if len(pieces[i]) == 4:
            pieces[i] = pieces[i].title()
        elif len(pieces[i]) in (2, 3):
            pieces[i] = pieces[i].upper()
    return "-".join(pieces)


def selected_tag(tag: str) -> str:
    """Mirror only the documented language/script normalization in LocalePolicy."""
    tag = normalize_tag(tag)
    parts = tag.split("-")
    if parts[0] != "zh":
        return tag
    script = next((p for p in parts[1:] if len(p) == 4), "")
    country = next((p for p in parts[1:] if len(p) in (2, 3)), "")
    traditional = script == "Hant" or (script != "Hans" and country in ("TW", "HK", "MO"))
    return ("zh-Hant-HK" if country in ("HK", "MO") else "zh-Hant-TW") if traditional else "zh-Hans-CN"


def directory_tag(name: str) -> str | None:
    if name == "values":
        return "en"
    if name.startswith("values-b+"):
        return normalize_tag(name[len("values-b+"):].replace("+", "-"))
    match = re.fullmatch(r"values-([a-z]{2,3})(?:-r([A-Z]{2}|[0-9]{3}))?", name)
    if match:
        return normalize_tag(match[1] + ("-" + match[2] if match[2] else ""))
    return None


def candidates(tag: str) -> list[str]:
    """Language, then script/region, then exact qualifiers, least to most specific."""
    parts = selected_tag(tag).split("-")
    language = parts[0]
    script = next((p for p in parts[1:] if len(p) == 4), None)
    country = next((p for p in parts[1:] if len(p) in (2, 3)), None)
    result = [language]
    if country:
        result.append(f"{language}-{country}")
    if script:
        result.append(f"{language}-{script}")
    if script and country:
        result.append(f"{language}-{script}-{country}")
    return list(dict.fromkeys(result))


def format_tokens(item: StringValue) -> Counter[str]:
    return Counter(PRINTF.findall(item.value)) if item.formatted else Counter()


def prose_words(value: str) -> list[str]:
    text = URL.sub(" ", value)
    text = PRINTF.sub(" ", text)
    for brand in sorted(BRANDS, key=len, reverse=True):
        text = re.sub(re.escape(brand), " ", text, flags=re.I)
    return re.findall(r"[A-Za-z]+(?:['’][A-Za-z]+)?", text.lower())


def long_english_fallback(source: str, translated: str) -> str | None:
    original = prose_words(source)
    target = prose_words(translated)
    if len(original) >= 6 and len(" ".join(original)) >= 40 and original == target:
        return "Long text is unchanged from English"
    # Finds a substantial untranslated fragment in an otherwise translated paragraph.
    # Short food names, shared cognates and product/service names cannot trigger this.
    longest = SequenceMatcher(None, original, target, autojunk=False).find_longest_match()
    if longest.size >= 12:
        fragment = original[longest.a:longest.a + longest.size]
        if len(" ".join(fragment)) >= 60:
            return "Long English fragment remains: " + " ".join(fragment[:14])
    return None


def check_urls(audit: Audit, scope: str, source: str, translated: str) -> None:
    urls = URL.findall(source)
    found = URL.findall(translated)
    if Counter(urls) != Counter(found):
        audit.issue("error", scope, "URL text or trailing boundary changed")
    # Check the other side too: a translated word may become attached before https://.
    for url in urls:
        for match in re.finditer(re.escape(url), translated):
            left = translated[match.start() - 1:match.start()] if match.start() else ""
            right = translated[match.end():match.end() + 1]
            if (left and not left.isspace()) or (right and not right.isspace()):
                audit.issue("error", scope, "URL must be separated from surrounding text by spaces")
                return


def compare_strings(audit: Audit, scope: str, source: dict[str, StringValue], target: dict[str, StringValue], language: str) -> None:
    missing = sorted(source.keys() - target.keys())
    extra = sorted(target.keys() - source.keys())
    if missing:
        audit.issue("missing", scope, f"{len(missing)} untranslated keys: " + ", ".join(missing[:8]))
    if extra:
        audit.issue("error", scope, f"{len(extra)} unknown keys: " + ", ".join(extra[:8]))
    for key in source.keys() & target.keys():
        original, translation = source[key], target[key]
        name = f"{scope}/{key}"
        if format_tokens(original) != format_tokens(translation):
            audit.issue("error", name, "Format placeholders/types/counts differ from English")
        if original.value.count("\n") != translation.value.count("\n"):
            audit.issue("error", name, "Line break count differs from English")
        check_urls(audit, name, original.value, translation.value)
        if language not in ("en", "ru"):
            fallback = long_english_fallback(original.value, translation.value)
            if fallback:
                audit.issue("error", name, fallback)
            if key.startswith("data_privacy_paragraph_"):
                for identity in IDENTITIES:
                    if original.value.count(identity) != translation.value.count(identity):
                        audit.issue("error", name, "Privacy identity changed: " + identity)


def check_original_russian(audit: Audit, russian: dict[str, StringValue], main: Path) -> None:
    if russian.get("app_name", StringValue("", False, main)).value != "Дневник диеты":
        audit.issue("error", "values-ru/app_name", "The original Russian app name changed")
    try:
        products = json.loads((main / "assets/products.json").read_text(encoding="utf-8"))
        for product in products:
            key = "data_food_" + product["id"]
            if key not in russian or russian[key].value != product["name"]:
                audit.issue("error", "values-ru/" + key, "Original catalog food name changed")
        canonical = re.split(r"\r?\n\s*\r?\n", (main / "assets/privacy-policy.txt").read_text(encoding="utf-8").strip())
        for index, paragraph in enumerate(canonical, 1):
            key = f"data_privacy_paragraph_{index}"
            if key not in russian or russian[key].value != paragraph:
                audit.issue("error", "values-ru/" + key, "Original offline privacy paragraph changed")
        count = len([k for k in russian if k.startswith("data_privacy_paragraph_")])
        if count != len(canonical):
            audit.issue("error", "values-ru", "Privacy paragraph count differs from original asset")
    except (OSError, json.JSONDecodeError, KeyError) as error:
        audit.issue("error", "original-russian", f"Cannot verify original assets: {error}")


def run(root: Path) -> dict:
    audit = Audit(root)
    main = root / "app/src/main"
    res = main / "res"
    source = audit.strings(res / "values")
    if not source:
        audit.issue("error", "values", "No default English strings found")
    elif source.get("app_name", StringValue("", False, main)).value != "Diet Diary":
        audit.issue("error", "values/app_name", "Default resources must use the English app name")

    tables: dict[str, dict[str, StringValue]] = {"en": source}
    folders: dict[str, str] = {"en": "values"}
    for directory in sorted(res.glob("values*")):
        tag = directory_tag(directory.name)
        if tag is None or directory.name == "values":
            continue
        if directory.name in ("values-b+he", "values-b+id"):
            audit.issue("error", directory.name,
                        "Android resource lookup requires the legacy folder values-iw / values-in; modern aliases fall back to English")
        items = audit.strings(directory)
        if tag in tables:
            audit.issue("error", directory.name, f"Duplicate normalized locale {tag}")
        else:
            tables[tag] = items
            folders[tag] = directory.name
        compare_strings(audit, directory.name, source, items, tag.split("-")[0])
    russian = tables.get("ru", {})
    if not russian:
        audit.issue("missing", "values-ru", "Original Russian resources are missing")
    else:
        check_original_russian(audit, russian, main)

    play_tags: list[str] = []
    declared_tags: list[str] = []
    try:
        document = json.loads((root / "docs/localization-locales.json").read_text(encoding="utf-8"))
        play_tags = document["playLocales"]
        declared_tags = document["androidLocales"]
        if document.get("default") != "en-US":
            audit.issue("error", "locale-inventory", "Default locale must be en-US")
        if len(play_tags) != EXPECTED_PLAY_LOCALES or len(set(play_tags)) != EXPECTED_PLAY_LOCALES:
            audit.issue("error", "locale-inventory", "Expected exactly 86 distinct Google Play locale variants")
        config = ET.parse(res / "xml/locales_config.xml").getroot()
        configured = [node.get(ANDROID + "name", "") for node in config if node.tag == "locale"]
        expected = [normalize_tag(t) for t in play_tags]
        if declared_tags != expected or configured != expected:
            audit.issue("error", "locale-inventory", "Play inventory, Android inventory and locale-config differ")
        policy = (main / "java/ru/dietdiary/offline/LocalePolicy.kt").read_text(encoding="utf-8")
        supported_block = re.search(r"val languages = setOf\((.*?)\n\s*\)", policy, re.S)
        supported = set(re.findall(r'"([a-z]+)"', supported_block[1])) if supported_block else set()
        required = {normalize_tag(t).split("-")[0] for t in play_tags}
        if supported != required:
            audit.issue("error", "LocalePolicy", "Language allow-list differs from the 86-variant inventory")
    except (OSError, ET.ParseError, json.JSONDecodeError, KeyError) as error:
        audit.issue("error", "locale-inventory", f"Cannot validate inventory: {error}")

    coverage = []
    for tag in play_tags:
        layers = [candidate for candidate in candidates(tag) if candidate in tables]
        effective: dict[str, StringValue] = {}
        for layer in layers:
            effective.update(tables[layer])
        missing = sorted(source.keys() - effective.keys())
        coverage.append({"playLocale": tag, "selectedLocale": selected_tag(tag),
                         "resourceFolders": [folders[layer] for layer in layers],
                         "keys": len(source.keys() & effective.keys()), "missingKeys": len(missing)})
        if missing:
            audit.issue("missing", tag, f"{len(missing)}/{len(source)} keys would fall back to English")

    errors = sum(issue["kind"] == "error" for issue in audit.issues)
    incomplete = sum(issue["kind"] == "missing" for issue in audit.issues)
    return {
        "status": "FAIL" if errors else "INCOMPLETE" if incomplete else "PASS",
        "defaultLocale": "en-US", "canonicalKeys": len(source), "resourceLocales": len(tables),
        "playVariants": len(play_tags), "completeVariants": sum(c["missingKeys"] == 0 for c in coverage),
        "errors": errors, "incompleteChecks": incomplete, "coverage": coverage, "issues": audit.issues,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--allow-incomplete", action="store_true", help="Allow only missing translations during generation")
    parser.add_argument("--json", type=Path, help="Optional report output path; resources are never modified")
    parser.add_argument("--max-issues", type=int, default=40)
    args = parser.parse_args()
    report = run(args.root.resolve())
    print(f"{report['status']}: {report['canonicalKeys']} English keys; "
          f"{report['completeVariants']}/{report['playVariants']} Play variants fully covered; "
          f"{report['resourceLocales']} resource locales; {report['errors']} errors; "
          f"{report['incompleteChecks']} incomplete checks")
    for issue in report["issues"][:max(0, args.max_issues)]:
        print(f"{issue['kind'].upper()} {issue['scope']}: {issue['message']}")
    omitted = len(report["issues"]) - max(0, args.max_issues)
    if omitted > 0:
        print(f"... {omitted} additional findings (use --json to save all)")
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 1 if report["errors"] or (report["incompleteChecks"] and not args.allow_incomplete) else 0


if __name__ == "__main__":
    sys.exit(main())
