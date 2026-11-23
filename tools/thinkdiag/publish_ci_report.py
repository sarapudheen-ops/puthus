#!/usr/bin/env python3
"""Publish a CI report (and an optional failure log) to a release and to PR comments.

This is the only place a probe step's evidence leaves CI. It never touches decoded
source data, never writes a pack and never decides enablement; the calling step is
the one that carries `continue-on-error`, so a publication failure cannot mark a
measurement step red.
"""
import argparse
import datetime
import json
import os
import pathlib
import subprocess
import sys

CHUNK = 18000
LOG_TAIL = 200
LOG_BOUND = 40000


def chunk_text(text, limit=CHUNK):
    if limit < 1:
        raise ValueError('chunk limit must be positive')
    return [text[index:index + limit] for index in range(0, len(text), limit)] or ['(empty report)']


def header(title, part, total, head, stamp, outcome, payload):
    line = f'# {title} - part {part}/{total}'
    if part != 1:
        return line
    detail = [f'Head: {head}', f'UTC: {stamp}', f'Step conclusion: {outcome}']
    if isinstance(payload, dict) and payload.get('elapsedSeconds') is not None:
        detail.append(f"Measured duration: {payload['elapsedSeconds']}s")
    return line + '\n\n' + '\n'.join(detail)


def build_comments(title, text, head, stamp, outcome, payload, limit=CHUNK):
    chunks = chunk_text(text, limit)
    return [header(title, position, len(chunks), head, stamp, outcome, payload) + '\n\n' + chunk
            for position, chunk in enumerate(chunks, 1)]


def failure_comment(title, log_path, head, outcome):
    tail = 'Step failed before writing its log; see the failing step output.'
    path = pathlib.Path(log_path) if log_path else None
    if path is not None and path.is_file():
        tail = '\n'.join(path.read_text(errors='replace').splitlines()[-LOG_TAIL:])
    return f'# {title} FAILURE log\n\nHead: {head}\nStep conclusion: {outcome}\n\n````text\n{tail[-LOG_BOUND:]}\n````'


def post(repo, pr, body):
    if not pr:
        print('No pull request for this ref; comment skipped')
        return
    subprocess.run(['gh', 'api', '--method', 'POST', f'repos/{repo}/issues/{pr}/comments',
                    '-F', f'body={body}'], check=True, stdout=subprocess.DEVNULL)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--title', required=True)
    parser.add_argument('--report', required=True)
    parser.add_argument('--json-output')
    parser.add_argument('--log')
    parser.add_argument('--release', required=True)
    args = parser.parse_args()

    repo = os.environ['GH_REPO']
    pr = os.environ.get('PR_NUMBER') or ''
    head = os.environ.get('GITHUB_SHA', 'NOT ESTABLISHED')
    outcome = os.environ.get('STEP_OUTCOME', 'NOT ESTABLISHED')
    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
    report = pathlib.Path(args.report)
    payload = {}
    if report.is_file():
        artefacts = [str(report)]
        if args.json_output and pathlib.Path(args.json_output).is_file():
            artefacts.append(args.json_output)
            try:
                payload = json.loads(pathlib.Path(args.json_output).read_text())
            except (OSError, ValueError) as error:
                print(f'Result JSON unreadable: {type(error).__name__}')
        subprocess.run(['gh', 'release', 'upload', args.release, *artefacts, '--clobber'], check=False)
        comments = build_comments(args.title, report.read_text(), head, stamp, outcome, payload)
        for body in comments:
            post(repo, pr, body)
        print(f'Posted {len(comments)} {args.title} comment(s)')
    if outcome != 'success':
        post(repo, pr, failure_comment(args.title, args.log, head, outcome))
        print('Posted the failure log')
    return 0


if __name__ == '__main__':
    sys.exit(main())
