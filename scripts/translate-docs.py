#!/usr/bin/env python3
"""
Translate Spanish documentation to English using DeepL API.

Markdown-aware: preserves code blocks, inline code, links, tables,
and ASCII diagrams. Uses a SHA-256 cache to skip unchanged files.

Usage:
    DEEPL_API_KEY=your-key python scripts/translate-docs.py
    DEEPL_API_KEY=your-key python scripts/translate-docs.py --force
    DEEPL_API_KEY=your-key python scripts/translate-docs.py --dry-run
"""

import argparse
import hashlib
import json
import os
import re
import sys
import time
from pathlib import Path

import requests

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

DEEPL_API_URL = "https://api-free.deepl.com/v2/translate"
DEEPL_USAGE_URL = "https://api-free.deepl.com/v2/usage"
SOURCE_LANG = "ES"
TARGET_LANG = "EN"
MAX_BATCH_CHARS = 40_000  # stay well under DeepL's 128KiB limit per request
RETRY_ATTEMPTS = 3
RETRY_DELAY = 3  # seconds

DOCS_DIR = Path("docs")
EN_DOCS_DIR = Path("docs/en")
CACHE_FILE = EN_DOCS_DIR / ".translation-cache.json"
SCRIPT_VERSION = "1.7.0"

# Spanish filename -> English filename
FILENAME_MAP: dict[str, str] = {
    "00-indice.md": "00-index.md",
    "01-arquitectura.md": "01-architecture.md",
    "02-gradle-dependencias.md": "02-gradle-dependencies.md",
    "03-entidades-jpa.md": "03-jpa-entities.md",
    "04-repositorios.md": "04-repositories.md",
    "05-dtos-mapstruct.md": "05-dtos-mapstruct.md",
    "06-servicios.md": "06-services.md",
    "07-controladores.md": "07-controllers.md",
    "08-seguridad.md": "08-security.md",
    "09-cliente-http.md": "09-http-client.md",
    "10-sincronizacion.md": "10-synchronization.md",
    "11-perfiles.md": "11-profiles.md",
    "12-testing.md": "12-testing.md",
    "13-docker-k8s.md": "13-docker-k8s.md",
    "14-cicd.md": "14-cicd.md",
}

CROSSREF_MAP = {es: en for es, en in FILENAME_MAP.items()}

# Placeholder using Unicode mathematical angle brackets (U+27E8/U+27E9).
# Non-alphabetic characters prevent DeepL from modifying the placeholder.
PH_PREFIX = "\u27e8"
PH_SUFFIX = "\u27e9"

# ---------------------------------------------------------------------------
# Translation Cache
# ---------------------------------------------------------------------------


class TranslationCache:
    """Tracks SHA-256 hashes of source files to skip unchanged ones."""

    def __init__(self, path: Path):
        self.path = path
        self.data: dict = {}
        if path.exists():
            with open(path) as f:
                self.data = json.load(f)
        if self.data.get("script_version") != SCRIPT_VERSION:
            self.data = {"script_version": SCRIPT_VERSION}

    def file_hash(self, filepath: Path) -> str:
        content = filepath.read_bytes()
        return f"sha256:{hashlib.sha256(content).hexdigest()}"

    def is_changed(self, filepath: Path) -> bool:
        current = self.file_hash(filepath)
        return self.data.get(filepath.name) != current

    def update(self, filepath: Path) -> None:
        self.data[filepath.name] = self.file_hash(filepath)

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with open(self.path, "w") as f:
            json.dump(self.data, f, indent=2)
            f.write("\n")


# ---------------------------------------------------------------------------
# DeepL Client
# ---------------------------------------------------------------------------


class DeepLTranslator:
    """Translates text using DeepL API."""

    def __init__(self, api_key: str):
        self.api_key = api_key
        self.chars_used = 0

    def translate_batch(self, texts: list[str]) -> list[str]:
        """Translate a list of text segments with batching."""
        if not texts:
            return []

        results: list[str] = []
        batch: list[str] = []
        batch_size = 0

        for text in texts:
            if batch_size + len(text) > MAX_BATCH_CHARS and batch:
                results.extend(self._call_api(batch))
                batch = []
                batch_size = 0
            batch.append(text)
            batch_size += len(text)

        if batch:
            results.extend(self._call_api(batch))

        return results

    def _call_api(self, texts: list[str]) -> list[str]:
        """Make a single API call with retries."""
        payload = {
            "text": texts,
            "source_lang": SOURCE_LANG,
            "target_lang": TARGET_LANG,
            "split_sentences": "nonewlines",
            "preserve_formatting": True,
        }
        headers = {
            "Authorization": f"DeepL-Auth-Key {self.api_key}",
            "Content-Type": "application/json",
        }

        for attempt in range(1, RETRY_ATTEMPTS + 1):
            try:
                resp = requests.post(
                    DEEPL_API_URL,
                    json=payload,
                    headers=headers,
                    timeout=60,
                )
                if resp.status_code == 200:
                    data = resp.json()
                    translated = [t["text"] for t in data["translations"]]
                    self.chars_used += sum(len(t) for t in texts)
                    return translated
                elif resp.status_code == 456:
                    print("  ERROR: DeepL quota exceeded", file=sys.stderr)
                    sys.exit(1)
                elif resp.status_code == 429:
                    wait = RETRY_DELAY * attempt
                    print(f"  Rate limited, waiting {wait}s (attempt {attempt}/{RETRY_ATTEMPTS})")
                    time.sleep(wait)
                else:
                    print(f"  DeepL error {resp.status_code}: {resp.text}", file=sys.stderr)
                    if attempt == RETRY_ATTEMPTS:
                        sys.exit(1)
                    time.sleep(RETRY_DELAY * attempt)
            except requests.exceptions.RequestException as e:
                print(f"  Request error: {e}", file=sys.stderr)
                if attempt == RETRY_ATTEMPTS:
                    sys.exit(1)
                time.sleep(RETRY_DELAY * attempt)

        return texts  # fallback

    def get_usage(self) -> dict:
        """Get current API usage."""
        headers = {"Authorization": f"DeepL-Auth-Key {self.api_key}"}
        resp = requests.get(DEEPL_USAGE_URL, headers=headers, timeout=10)
        return resp.json()


GOOGLE_TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single"


class GoogleTranslator:
    """Translates text using Google Translate free endpoint (fallback)."""

    def __init__(self) -> None:
        self.chars_used = 0

    def translate_batch(self, texts: list[str]) -> list[str]:
        """Translate a list of text segments one by one."""
        if not texts:
            return []
        results: list[str] = []
        for text in texts:
            translated = self._call_api(text)
            results.append(translated)
            self.chars_used += len(text)
        return results

    def _call_api(self, text: str) -> str:
        """Translate a single text with retries."""
        params = {
            "client": "gtx",
            "sl": SOURCE_LANG.lower(),
            "tl": TARGET_LANG.lower(),
            "dt": "t",
            "q": text,
        }
        for attempt in range(1, RETRY_ATTEMPTS + 1):
            try:
                resp = requests.get(
                    GOOGLE_TRANSLATE_URL,
                    params=params,
                    timeout=30,
                )
                if resp.status_code == 200:
                    data = resp.json()
                    return "".join(seg[0] for seg in data[0])
                elif resp.status_code == 429:
                    wait = RETRY_DELAY * attempt * 2
                    print(f"  Rate limited, waiting {wait}s (attempt {attempt}/{RETRY_ATTEMPTS})")
                    time.sleep(wait)
                else:
                    print(f"  Google Translate error {resp.status_code}", file=sys.stderr)
                    if attempt == RETRY_ATTEMPTS:
                        sys.exit(1)
                    time.sleep(RETRY_DELAY * attempt)
            except requests.exceptions.RequestException as e:
                print(f"  Request error: {e}", file=sys.stderr)
                if attempt == RETRY_ATTEMPTS:
                    sys.exit(1)
                time.sleep(RETRY_DELAY * attempt)
            # Small delay between requests to avoid rate limiting
            time.sleep(0.5)
        return text  # fallback


# ---------------------------------------------------------------------------
# OpenAI Client (GPT-5.2 fallback)
# ---------------------------------------------------------------------------

OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
OPENAI_MODEL = "gpt-5.2"
OPENAI_SYSTEM_PROMPT = (
    "You are a professional Spanish-to-English translator. "
    "Translate the user's text from Spanish to English. "
    "Rules:\n"
    "- Preserve ALL placeholder tokens like \u27e80\u27e9, \u27e81\u27e9, \u27e82\u27e9 exactly as they appear\n"
    "- Preserve all markdown formatting (headings, lists, bold, links, tables)\n"
    "- Preserve all technical terms (class names, method names, URLs, code)\n"
    "- Output ONLY the translated text, nothing else — no preamble, no notes\n"
    "- If the input is a single technical term that doesn't need translation, output it unchanged"
)


class OpenAITranslator:
    """Translates text using OpenAI Chat Completions API (GPT-5.2)."""

    def __init__(self, api_key: str):
        self.api_key = api_key
        self.chars_used = 0

    def translate_batch(self, texts: list[str]) -> list[str]:
        """Translate a list of text segments one by one."""
        if not texts:
            return []
        results: list[str] = []
        for text in texts:
            translated = self._call_api(text)
            results.append(translated)
            self.chars_used += len(text)
        return results

    def _call_api(self, text: str) -> str:
        """Translate a single text via OpenAI Chat Completions."""
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        payload = {
            "model": OPENAI_MODEL,
            "messages": [
                {"role": "developer", "content": OPENAI_SYSTEM_PROMPT},
                {"role": "user", "content": text},
            ],
            "temperature": 0.1,
        }

        for attempt in range(1, RETRY_ATTEMPTS + 1):
            try:
                resp = requests.post(
                    OPENAI_API_URL,
                    json=payload,
                    headers=headers,
                    timeout=120,
                )
                if resp.status_code == 200:
                    data = resp.json()
                    return data["choices"][0]["message"]["content"].strip()
                elif resp.status_code == 429:
                    wait = RETRY_DELAY * attempt * 2
                    print(f"  Rate limited, waiting {wait}s (attempt {attempt}/{RETRY_ATTEMPTS})")
                    time.sleep(wait)
                else:
                    print(f"  OpenAI error {resp.status_code}: {resp.text}", file=sys.stderr)
                    if attempt == RETRY_ATTEMPTS:
                        sys.exit(1)
                    time.sleep(RETRY_DELAY * attempt)
            except requests.exceptions.RequestException as e:
                print(f"  Request error: {e}", file=sys.stderr)
                if attempt == RETRY_ATTEMPTS:
                    sys.exit(1)
                time.sleep(RETRY_DELAY * attempt)
            time.sleep(0.3)  # small delay between requests
        return text  # fallback


# ---------------------------------------------------------------------------
# Markdown Protection & Translation (plain-text placeholders)
# ---------------------------------------------------------------------------

RE_INLINE_CODE = re.compile(r"`([^`]+)`")
RE_LINK_FULL = re.compile(r"\[([^\]]*)\]\(([^)]+)\)")
RE_HEADING = re.compile(r"^(#{1,6}\s+)")
RE_TABLE_SEP = re.compile(r"^\|[-\s|:]+\|$")
RE_ASCII_ART = re.compile(r"^[\s]*[│├└┌┐┘┤┬┴┼─|+\-\\/><=*]{3,}")
RE_BLOCKQUOTE = re.compile(r"^(\s*>\s*)")


def is_ascii_art_line(line: str) -> bool:
    """Check if a line is an ASCII art/diagram line."""
    stripped = line.strip()
    if not stripped:
        return False
    if RE_ASCII_ART.match(line):
        return True
    special = sum(1 for c in stripped if c in "│├└┌┐┘┤┬┴┼─|+\\/><=")
    if len(stripped) > 0 and special / len(stripped) > 0.4:
        return True
    return False


RE_BOLD_LINK = re.compile(r"\*\*\[([^\]]*)\]\(([^)]+)\)\*\*")
RE_BOLD_TEXT = re.compile(r"\*\*(.+?)\*\*")


def make_placeholder(idx: int) -> str:
    """Create a unique placeholder that DeepL won't translate."""
    return f"{PH_PREFIX}{idx}{PH_SUFFIX}"


def protect_inline_elements(text: str) -> tuple[str, dict[str, str]]:
    """Replace inline code, links, and bold links with placeholders.

    Returns (protected_text, {placeholder: original_content}).
    """
    placeholders: dict[str, str] = {}
    counter = [0]

    def next_ph() -> str:
        idx = counter[0]
        counter[0] += 1
        return make_placeholder(idx)

    # 1. Protect bold links as single units: **[text](url)** -> placeholder
    #    Prevents DeepL from tripling the content of bold link patterns.
    def replace_bold_link(m: re.Match) -> str:
        ph = next_ph()
        placeholders[ph] = m.group(0)
        return ph

    protected = RE_BOLD_LINK.sub(replace_bold_link, text)

    # 2. Protect remaining links with paired placeholders.
    #    Replaces [text](url) with ⟨N⟩text⟨M⟩ where ⟨N⟩="[" and ⟨M⟩="](url)".
    #    This hides markdown bracket syntax from DeepL while keeping
    #    the display text visible for translation.
    def replace_link(m: re.Match) -> str:
        display_text = m.group(1)
        url = m.group(2)
        ph_open = next_ph()
        placeholders[ph_open] = "["
        ph_close = next_ph()
        placeholders[ph_close] = f"]({url})"
        return f"{ph_open}{display_text}{ph_close}"

    protected = RE_LINK_FULL.sub(replace_link, protected)

    # 3. Protect inline code
    def replace_inline_code(m: re.Match) -> str:
        ph = next_ph()
        placeholders[ph] = m.group(0)  # includes backticks
        return ph

    protected = RE_INLINE_CODE.sub(replace_inline_code, protected)

    # 4. Protect bold markers: **text** → ⟨N⟩text⟨M⟩
    #    Hides ** from DeepL which otherwise duplicates or mangles bold text.
    def replace_bold(m: re.Match) -> str:
        inner = m.group(1)
        ph_open = next_ph()
        placeholders[ph_open] = "**"
        ph_close = next_ph()
        placeholders[ph_close] = "**"
        return f"{ph_open}{inner}{ph_close}"

    protected = RE_BOLD_TEXT.sub(replace_bold, protected)

    return protected, placeholders


def restore_placeholders(text: str, placeholders: dict[str, str]) -> str:
    """Restore all placeholders with their original content.

    Sorts by longest key first to avoid partial replacement issues.
    After restoration, removes any spurious placeholders that DeepL
    invented by extending numbered sequences (e.g., seeing ⟨0⟩, ⟨1⟩
    and inventing ⟨2⟩ when the source had "etc.").
    """
    result = text
    for ph, original in sorted(placeholders.items(), key=lambda x: -len(x[0])):
        result = result.replace(ph, original)

    # Clean up spurious placeholders invented by DeepL
    known = set(placeholders.keys())
    ph_re = re.compile(re.escape(PH_PREFIX) + r"\d+" + re.escape(PH_SUFFIX))

    def clean_spurious(m: re.Match) -> str:
        if m.group(0) in known:
            return m.group(0)  # real unrestored placeholder — keep for validation
        return ""  # DeepL-invented — remove

    result = ph_re.sub(clean_spurious, result)
    # Clean double commas / leading commas left after removal
    result = re.sub(r",\s*,", ",", result)
    result = re.sub(r",\s*\)", ")", result)

    return result


def translate_markdown_file(
    source_path: Path,
    translator: DeepLTranslator,
) -> str:
    """Translate a single markdown file, preserving all formatting."""
    content = source_path.read_text(encoding="utf-8")
    lines = content.split("\n")

    # Phase 1: Classify each line
    in_code_block = False
    line_types: list[str] = []  # "code", "translatable", "pass-through"

    for line in lines:
        stripped = line.strip()

        if stripped.startswith("```"):
            in_code_block = not in_code_block
            line_types.append("code")
        elif in_code_block:
            line_types.append("code")
        elif not stripped:
            line_types.append("pass-through")
        elif RE_TABLE_SEP.match(stripped):
            line_types.append("pass-through")
        elif is_ascii_art_line(line):
            line_types.append("pass-through")
        else:
            line_types.append("translatable")

    # Phase 2: Extract translatable segments
    segments: list[dict] = []

    for i, (line, ltype) in enumerate(zip(lines, line_types)):
        if ltype != "translatable":
            continue

        prefix = ""
        text = line

        # Heading prefix
        heading_match = RE_HEADING.match(text)
        if heading_match:
            prefix = heading_match.group(1)
            text = text[len(prefix):]

        # Blockquote prefix
        bq_match = RE_BLOCKQUOTE.match(text)
        if bq_match:
            prefix += bq_match.group(1)
            text = text[len(bq_match.group(1)):]

        # List marker
        list_match = re.match(r"^(\s*[-*+]\s+|\s*\d+\.\s+)", text)
        if list_match:
            prefix += list_match.group(1)
            text = text[len(list_match.group(1)):]

        # Table row
        if text.strip().startswith("|") and text.strip().endswith("|"):
            cells = text.split("|")
            cell_segments = []
            for cell in cells:
                stripped_cell = cell.strip()
                if stripped_cell:
                    protected, phs = protect_inline_elements(stripped_cell)
                    cell_segments.append({
                        "text": protected,
                        "placeholders": phs,
                    })
                else:
                    cell_segments.append({"text": "", "placeholders": {}})
            segments.append({
                "line_idx": i,
                "type": "table_row",
                "cells": cell_segments,
                "prefix": prefix,
            })
            continue

        if not text.strip():
            continue

        protected, phs = protect_inline_elements(text)
        segments.append({
            "line_idx": i,
            "type": "text",
            "protected_text": protected,
            "placeholders": phs,
            "prefix": prefix,
        })

    # Phase 3: Collect translatable texts for batch API call
    texts_to_translate: list[str] = []
    text_indices: list[tuple[int, str]] = []

    for seg_idx, seg in enumerate(segments):
        if seg["type"] == "text":
            texts_to_translate.append(seg["protected_text"])
            text_indices.append((seg_idx, "text"))
        elif seg["type"] == "table_row":
            for cell_idx, cell in enumerate(seg["cells"]):
                if cell["text"]:
                    texts_to_translate.append(cell["text"])
                    text_indices.append((seg_idx, f"cell:{cell_idx}"))

    # Phase 4: Translate
    if texts_to_translate:
        translated_texts = translator.translate_batch(texts_to_translate)
    else:
        translated_texts = []

    # Phase 5: Map translations back
    for (seg_idx, key), translated in zip(text_indices, translated_texts):
        seg = segments[seg_idx]
        if key == "text":
            seg["translated"] = translated
        elif key.startswith("cell:"):
            cell_idx = int(key.split(":")[1])
            seg["cells"][cell_idx]["translated"] = translated

    # Phase 6: Reconstruct
    output_lines = list(lines)

    for seg in segments:
        i = seg["line_idx"]
        if seg["type"] == "text":
            translated = seg.get("translated", seg["protected_text"])
            restored = restore_placeholders(translated, seg["placeholders"])
            output_lines[i] = seg["prefix"] + restored
        elif seg["type"] == "table_row":
            cells = seg["cells"]
            rebuilt_cells = []
            for cell in cells:
                if cell["text"]:
                    translated = cell.get("translated", cell["text"])
                    restored = restore_placeholders(translated, cell["placeholders"])
                    rebuilt_cells.append(f" {restored} ")
                else:
                    rebuilt_cells.append("")
            output_lines[i] = "|".join(rebuilt_cells)
            if not output_lines[i].startswith("|"):
                output_lines[i] = "|" + output_lines[i]
            if not output_lines[i].endswith("|"):
                output_lines[i] = output_lines[i] + "|"

    result = "\n".join(output_lines)

    # Post-process: fix emphasis spacing introduced by translator.
    # Translators add/move spaces around ** markers. Instead of trying to
    # distinguish opening/closing ** with regex (impossible), we match full
    # **content** spans and move any internal leading/trailing spaces outside.
    def _fix_bold_span(m: re.Match) -> str:
        content = m.group(1)
        prefix = ""
        suffix = ""
        if content and content[0] in " \t":
            prefix = content[0]
            content = content.lstrip(" \t")
        if content and content[-1] in " \t":
            suffix = content[-1]
            content = content.rstrip(" \t")
        if not content:
            return m.group(0)  # empty bold, leave as-is
        return prefix + "**" + content + "**" + suffix

    # Process line-by-line to avoid matching across lines.
    fixed_lines = []
    for line in result.split("\n"):
        line = re.sub(r"\*\*(.+?)\*\*", _fix_bold_span, line)
        fixed_lines.append(line)
    result = "\n".join(fixed_lines)

    # Safety net: ensure list markers have space before opening bold.
    # Handles cases where translator removes space: "-**" → "- **", "N.**" → "N. **"
    result = re.sub(r"^(\s*[-*+])\*\*", r"\1 **", result, flags=re.MULTILINE)
    result = re.sub(r"^(\s*\d+\.)\*\*", r"\1 **", result, flags=re.MULTILINE)

    return result


# ---------------------------------------------------------------------------
# Link Rewriting
# ---------------------------------------------------------------------------


def rewrite_cross_references(content: str) -> str:
    """Rewrite inter-document links: 01-arquitectura.md -> 01-architecture.md"""
    for es_name, en_name in CROSSREF_MAP.items():
        content = content.replace(f"]({es_name})", f"]({en_name})")
        content = content.replace(f"]({es_name}#", f"]({en_name}#")
    return content


def rewrite_source_links(content: str) -> str:
    """Adjust source code links: ../src/ -> ../../src/ (one level deeper)."""
    content = content.replace("](../src/", "](../../src/")
    content = content.replace("](../build.gradle.kts)", "](../../build.gradle.kts)")
    content = content.replace("](../Dockerfile)", "](../../Dockerfile)")
    content = content.replace("](../.github/", "](../../.github/")
    content = content.replace("](../k8s/", "](../../k8s/")
    content = content.replace("](../scripts/", "](../../scripts/")
    return content


def text_to_anchor(text: str) -> str:
    """Convert heading text to GitHub-style anchor ID.

    Matches GitHub's algorithm: strip markdown syntax, lowercase,
    remove non-alphanum (keep hyphens and spaces), spaces to hyphens.
    Does NOT collapse consecutive hyphens (GitHub preserves them).
    """
    text = re.sub(r"`([^`]+)`", r"\1", text)  # strip backticks
    text = re.sub(r"\[([^\]]*)\]\([^)]+\)", r"\1", text)  # links → text
    text = re.sub(r"\*+", "", text)  # strip bold/italic
    text = text.strip().lower()
    text = re.sub(r"[^a-z0-9\s-]", "", text)  # keep letters, digits, space, hyphen
    text = text.replace(" ", "-")  # each space → one hyphen (preserves double hyphens)
    return text


def fix_heading_anchors(source_content: str, translated_content: str) -> str:
    """Rewrite TOC anchor fragments to match translated heading anchors.

    Spanish headings generate Spanish anchors (#que-es) but translated
    headings generate English anchors (#what-is-it). This function maps
    old → new and rewrites all fragment links.
    """
    heading_re = re.compile(r"^(#{1,6})\s+(.+)$", re.MULTILINE)

    src_headings = heading_re.findall(source_content)
    tgt_headings = heading_re.findall(translated_content)

    if len(src_headings) != len(tgt_headings):
        return translated_content  # safety: don't modify if heading counts differ

    anchor_map: dict[str, str] = {}
    for (_, src_text), (_, tgt_text) in zip(src_headings, tgt_headings):
        src_anchor = text_to_anchor(src_text)
        tgt_anchor = text_to_anchor(tgt_text)
        if src_anchor and tgt_anchor and src_anchor != tgt_anchor:
            anchor_map[src_anchor] = tgt_anchor

    result = translated_content
    # Sort by longest key first to avoid partial replacements
    for src_anchor, tgt_anchor in sorted(anchor_map.items(), key=lambda x: -len(x[0])):
        result = result.replace(f"](#{src_anchor})", f"](#{tgt_anchor})")

    return result


def add_language_toggle(content: str, is_readme: bool = False) -> str:
    """Add language toggle link after the first heading.

    Inserts on the line immediately after the heading with a blank
    line separator, matching the pattern in README.md.
    """
    if is_readme:
        lines = content.split("\n")
        for i, line in enumerate(lines):
            if line.startswith("# "):
                # Insert blank line + toggle after heading
                # Check if next line is already blank
                if i + 1 < len(lines) and lines[i + 1].strip() == "":
                    lines.insert(i + 2, '> **[Leer en espanol](README.md)**')
                else:
                    lines.insert(i + 1, "")
                    lines.insert(i + 2, '> **[Leer en espanol](README.md)**')
                break
        return "\n".join(lines)
    return content


# ---------------------------------------------------------------------------
# Post-Translation Validation
# ---------------------------------------------------------------------------


def validate_translation(source: str, translated: str, filename: str) -> list[str]:
    """Validate that translation preserved structural elements."""
    errors: list[str] = []

    src_blocks = source.count("```")
    tgt_blocks = translated.count("```")
    if src_blocks != tgt_blocks:
        errors.append(f"{filename}: code block count mismatch (source={src_blocks}, translated={tgt_blocks})")

    src_headings = len(re.findall(r"^#{1,6}\s", source, re.MULTILINE))
    tgt_headings = len(re.findall(r"^#{1,6}\s", translated, re.MULTILINE))
    if src_headings != tgt_headings:
        errors.append(f"{filename}: heading count mismatch (source={src_headings}, translated={tgt_headings})")

    src_links = len(RE_LINK_FULL.findall(source))
    tgt_links = len(RE_LINK_FULL.findall(translated))
    if src_links != tgt_links:
        errors.append(f"{filename}: link count mismatch (source={src_links}, translated={tgt_links})")

    src_lines = source.count("\n")
    tgt_lines = translated.count("\n")
    if src_lines > 0:
        ratio = tgt_lines / src_lines
        if ratio < 0.8 or ratio > 1.2:
            errors.append(f"{filename}: line count ratio {ratio:.2f} outside [0.8, 1.2] (source={src_lines}, translated={tgt_lines})")

    # Check no leftover placeholders
    leftover = re.findall(rf"{re.escape(PH_PREFIX)}\d+{re.escape(PH_SUFFIX)}", translated)
    if leftover:
        errors.append(f"{filename}: {len(leftover)} unrestored placeholders found")

    return errors


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(description="Translate docs ES -> EN via DeepL")
    parser.add_argument("--force", action="store_true", help="Ignore cache, re-translate all")
    parser.add_argument("--dry-run", action="store_true", help="Show what would be translated")
    parser.add_argument("--backend", choices=["deepl", "openai", "google"], default="deepl",
                        help="Translation backend (default: deepl, auto-fallback to openai/google)")
    args = parser.parse_args()

    backend_name = args.backend

    if backend_name == "deepl":
        api_key = os.environ.get("DEEPL_API_KEY")
        if not api_key:
            print("ERROR: DEEPL_API_KEY environment variable is required", file=sys.stderr)
            sys.exit(1)
        translator = DeepLTranslator(api_key)
        try:
            usage = translator.get_usage()
            used = usage.get("character_count", 0)
            limit = usage.get("character_limit", 500000)
            pct = used * 100 // limit if limit else 100
            print(f"DeepL API usage: {used:,}/{limit:,} chars ({pct}%)")
            if pct >= 100:
                print("DeepL quota exceeded, switching to fallback...")
                openai_key = os.environ.get("OPEN_AI_API_KEY")
                if openai_key:
                    translator = OpenAITranslator(openai_key)
                    backend_name = "openai"
                    print(f"Using OpenAI {OPENAI_MODEL} backend")
                else:
                    translator = GoogleTranslator()
                    backend_name = "google"
                    print("Using Google Translate backend (fallback)")
        except Exception as e:
            print(f"Warning: could not fetch API usage: {e}")
    elif backend_name == "openai":
        openai_key = os.environ.get("OPEN_AI_API_KEY")
        if not openai_key:
            print("ERROR: OPEN_AI_API_KEY environment variable is required", file=sys.stderr)
            sys.exit(1)
        translator = OpenAITranslator(openai_key)
        print(f"Using OpenAI {OPENAI_MODEL} backend")
    else:
        translator = GoogleTranslator()
        print("Using Google Translate backend (fallback)")

    cache = TranslationCache(CACHE_FILE)

    EN_DOCS_DIR.mkdir(parents=True, exist_ok=True)

    all_errors: list[str] = []
    translated_count = 0
    skipped_count = 0

    # --- Translate docs ---
    print(f"\n=== Translating {len(FILENAME_MAP)} documentation files ===\n")

    for es_name, en_name in sorted(FILENAME_MAP.items()):
        source_path = DOCS_DIR / es_name
        target_path = EN_DOCS_DIR / en_name

        if not source_path.exists():
            print(f"  SKIP {es_name} (file not found)")
            continue

        if not args.force and not cache.is_changed(source_path):
            print(f"  SKIP {es_name} -> {en_name} (unchanged)")
            skipped_count += 1
            continue

        if args.dry_run:
            print(f"  WOULD translate {es_name} -> {en_name}")
            continue

        print(f"  Translating {es_name} -> {en_name} ...", end=" ", flush=True)

        source_content = source_path.read_text(encoding="utf-8")
        translated = translate_markdown_file(source_path, translator)
        translated = rewrite_cross_references(translated)
        translated = rewrite_source_links(translated)
        translated = fix_heading_anchors(source_content, translated)

        errors = validate_translation(source_content, translated, en_name)
        if errors:
            for err in errors:
                print(f"\n  WARNING: {err}")
            all_errors.extend(errors)

        target_path.write_text(translated, encoding="utf-8")
        cache.update(source_path)
        translated_count += 1
        print("OK")

    # --- Translate README ---
    readme_path = Path("README.md")
    readme_en_path = Path("README.en.md")

    if readme_path.exists():
        if args.force or cache.is_changed(readme_path):
            if args.dry_run:
                print(f"\n  WOULD translate README.md -> README.en.md")
            else:
                print(f"\n  Translating README.md -> README.en.md ...", end=" ", flush=True)
                source_content = readme_path.read_text(encoding="utf-8")
                translated = translate_markdown_file(readme_path, translator)

                for es_name, en_name in CROSSREF_MAP.items():
                    translated = translated.replace(f"docs/{es_name}", f"docs/en/{en_name}")

                # Validate BEFORE adding language toggle (toggle adds 1 link)
                errors = validate_translation(source_content, translated, "README.en.md")

                translated = add_language_toggle(translated, is_readme=True)
                if errors:
                    for err in errors:
                        print(f"\n  WARNING: {err}")
                    all_errors.extend(errors)

                readme_en_path.write_text(translated, encoding="utf-8")
                cache.update(readme_path)
                translated_count += 1
                print("OK")
        else:
            print(f"\n  SKIP README.md (unchanged)")
            skipped_count += 1

    if not args.dry_run:
        cache.save()

    print(f"\n=== Summary ===")
    print(f"  Backend: {backend_name}")
    print(f"  Translated: {translated_count} files")
    print(f"  Skipped (cached): {skipped_count} files")
    print(f"  Chars processed this run: {translator.chars_used:,}")
    if all_errors:
        print(f"\n  WARNINGS ({len(all_errors)}):")
        for err in all_errors:
            print(f"    - {err}")


if __name__ == "__main__":
    main()
