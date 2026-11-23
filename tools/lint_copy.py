#!/usr/bin/env python3
"""Fails when copy breaks the text rules of Fonia (the same rules as fonia-app/tools/lint_copy.py on iOS).

    python3 tools/lint_copy.py [--root REPO]

Checks the shared content (content/json) and every *.kt and values XML under */src/main. Cedilla letters and
em dashes are refused anywhere in a file, except as the text a `.replace("...")` call is cleaning away. Banned
words are looked for in JSON values, Kotlin string literals and XML strings only, because names are code, not
copy, and file names are skipped. A Kotlin line that has to keep a flagged word can end in `// lint-copy: ignore`.
"""

import argparse
import json
import re
import sys
from pathlib import Path

CEDILLA = "ŞşŢţ"
EM_DASH = "—"
IGNORE_MARK = "lint-copy: ignore"
REPLACED_AWAY = re.compile(r'\.replace\(\s*"[^"]*"')
FILE_NAME = re.compile(r"^[\w.-]+\.\w{2,5}$")

# Same list as iOS; keep the two in step.
BANNED_PATTERNS = [
    # Words to replace.
    r"feedback\w*",
    r"obiectiv\w*",
    r"experien[țt]\w*",
    r"călători[ea]\w*",
    r"descoperă",
    r"deblochează",
    r"debloca\w*",
    r"antren\w*",
    r"valid(ează|are|ată|at|ez)\w*",
    r"acces(a|ează|at)\w*",
    r"select(a|ează|at)\w*",
    r"set(a|ează|at)",
    r"se asigur\w*",
    r"ne asigurăm",
    r"asigură-te",
    r"dispozitiv\w*",
    r"în timp real",
    r"soluți\w*",
    r"bună treabă",
    r"uimitor",
    r"ține-o tot așa",
    r"puncte de experiență",
    r"face sens",
    r"adresa o problemă",
    r"aplica pentru",
    r"locați[ei]\w*",
    r"suport",
    r"suportă",
    r"a suporta",
    # Stock phrases.
    r"transformă-ți",
    r"ești pe drumul cel bun",
    r"într-o lume în care",
    r"în era digitală",
    r"indiferent dacă",
    r"cu ușurință",
    r"fără efort",
    r"putern\w*",
    r"inteligent\w*",
    r"personalizat\w*",
    r"de neegalat",
    r"revoluționar\w*",
    r"o gamă largă de",
    r"în cadrul",
    r"la nivel de",
    r"realizarea",
    r"efectuarea",
    r"vă rugăm",
    r"ne pare rău",
    r"cu succes",
    # "Nu e X, e Y" and its relatives.
    r"nu e doar",
    r"nu e[^.!?\n]*?,\s*(ci\s+)?e",
    # Claims Fonia must not make.
    r"logopedul din buzunar",
    r"dislali\w*",
    r"terapi\w*",
    r"severitate\w*",
    r"vindecă",
    r"tratează",
    r"demonstrat clinic",
    r"funcționează fără logoped",
    r"am simțit emoția",
    r"expresivitate\w*",
    # English stock words.
    r"delve\w*",
    r"landscape",
    r"tapestry",
    r"leverage\w*",
    r"harness\w*",
    r"seamless\w*",
    r"robust\w*",
    r"comprehensive\w*",
    r"unlock\w*",
    r"empower\w*",
    r"it's worth noting",
    r"in today's",
]
BANNED = re.compile(r"\b(?:" + "|".join(BANNED_PATTERNS) + r")\b", re.IGNORECASE)


def check_characters(path: Path, text: str, problems: list[str]) -> None:
    for number, line in enumerate(text.splitlines(), 1):
        for character in REPLACED_AWAY.sub("", line):
            if character in CEDILLA:
                problems.append(f"{path}:{number}: cedilla letter {character!r}, use comma-below")
            elif character == EM_DASH:
                problems.append(f"{path}:{number}: em dash")


def check_copy(path: Path, where: str, text: str, problems: list[str]) -> None:
    for match in BANNED.finditer(text):
        problems.append(f"{path}:{where}: banned wording {match.group(0)!r} in {text!r}")


def check_json(path: Path, problems: list[str]) -> None:
    text = path.read_text(encoding="utf-8")
    check_characters(path, text, problems)

    def walk(node, where: str) -> None:
        if isinstance(node, str):
            check_copy(path, where, node, problems)
            if ";" in node:
                problems.append(f"{path}:{where}: semicolon in {node!r}")
        elif isinstance(node, dict):
            for key, value in node.items():
                walk(value, f"{where}/{key}")
        elif isinstance(node, list):
            for index, value in enumerate(node):
                walk(value, f"{where}/{index}")

    walk(json.loads(text), "")


def kotlin_string_literals(source: str) -> list[tuple[int, str]]:
    """String literals with their line numbers. Comments, char literals and template code are left out."""
    literals: list[tuple[int, str]] = []
    index, line, length = 0, 1, len(source)
    while index < length:
        character = source[index]
        if character == "\n":
            line += 1
            index += 1
        elif source.startswith("//", index):
            index = source.find("\n", index)
            index = length if index < 0 else index
        elif source.startswith("/*", index):
            # Kotlin block comments nest.
            depth, index = 1, index + 2
            while index < length and depth:
                if source.startswith("/*", index):
                    depth, index = depth + 1, index + 2
                elif source.startswith("*/", index):
                    depth, index = depth - 1, index + 2
                else:
                    line += source[index] == "\n"
                    index += 1
        elif character == "'":
            index += 1
            while index < length and source[index] != "'":
                index += 2 if source[index] == "\\" else 1
            index += 1
        elif character == '"':
            raw = source.startswith('"""', index)
            quote = '"""' if raw else '"'
            index += len(quote)
            start_line, parts = line, []
            while index < length and not source.startswith(quote, index):
                if source.startswith("${", index):
                    index += 2
                    depth = 1
                    while index < length and depth:
                        depth += {"{": 1, "}": -1}.get(source[index], 0)
                        line += source[index] == "\n"
                        index += 1
                    parts.append(" ")
                elif source[index] == "$" and index + 1 < length and (source[index + 1].isalpha() or source[index + 1] == "_"):
                    index += 1
                    while index < length and (source[index].isalnum() or source[index] == "_"):
                        index += 1
                    parts.append(" ")
                elif not raw and source[index] == "\\":
                    index += 2
                else:
                    line += source[index] == "\n"
                    parts.append(source[index])
                    index += 1
            index += len(quote)
            literals.append((start_line, "".join(parts)))
        else:
            index += 1
    return literals


def check_kotlin(path: Path, problems: list[str]) -> None:
    source = path.read_text(encoding="utf-8")
    check_characters(path, source, problems)
    lines = source.splitlines()
    for number, literal in kotlin_string_literals(source):
        if FILE_NAME.match(literal) or (number <= len(lines) and IGNORE_MARK in lines[number - 1]):
            continue
        check_copy(path, str(number), literal, problems)


def check_xml(path: Path, problems: list[str]) -> None:
    text = path.read_text(encoding="utf-8")
    check_characters(path, text, problems)
    for number, line in enumerate(text.splitlines(), 1):
        for value in re.findall(r"<string[^>]*>(.*?)</string>", line):
            check_copy(path, str(number), value, problems)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    root = parser.parse_args().root

    problems: list[str] = []
    json_files = sorted((root / "content/json").glob("*.json"))
    kotlin_files = sorted(root.glob("*/src/main/**/*.kt"))
    xml_files = sorted(root.glob("*/src/main/res/values*/*.xml"))
    for path in json_files:
        check_json(path, problems)
    for path in kotlin_files:
        check_kotlin(path, problems)
    for path in xml_files:
        check_xml(path, problems)

    for problem in problems:
        print(problem)
    print(f"Checked {len(json_files)} JSON, {len(kotlin_files)} Kotlin and {len(xml_files)} XML files: {len(problems)} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
