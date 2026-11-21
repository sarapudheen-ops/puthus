#!/usr/bin/env python3
"""Split a census Markdown report at heading boundaries for GitHub comments."""
import argparse
from pathlib import Path

MARKER = "<!-- toyota-census -->"


def split_sections(text: str, limit: int = 55_000) -> list[str]:
    if limit < 256:
        raise ValueError("Comment limit is too small")
    sections, current = [], []
    for line in text.splitlines():
        if line.startswith("#") and current:
            sections.append("\n".join(current).strip())
            current = []
        current.append(line)
    if current:
        sections.append("\n".join(current).strip())
    chunks, body = [], ""
    for section in sections:
        candidate = section if not body else body + "\n\n" + section
        # Reserve space for the marker and part header.
        if len(candidate) + 128 > limit:
            if body:
                chunks.append(body)
                body = section
            else:
                raise ValueError(f"A single Markdown section is {len(section)} chars; cannot split on section boundaries under {limit}")
            if len(body) + 128 > limit:
                raise ValueError(f"A single Markdown section is {len(body)} chars; cannot split on section boundaries under {limit}")
        else:
            body = candidate
    if body:
        chunks.append(body)
    total = len(chunks)
    return [f"{MARKER}\n# Census part {i}/{total}\n\n{chunk}" for i, chunk in enumerate(chunks, 1)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("markdown", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    chunks = split_sections(args.markdown.read_text())
    for i, chunk in enumerate(chunks, 1):
        (args.output / f"census-part-{i:03}.md").write_text(chunk + "\n")
    print(f"Split census into {len(chunks)} Markdown-section-aligned comments (max {max(map(len, chunks), default=0)} chars).")

if __name__ == "__main__": main()
