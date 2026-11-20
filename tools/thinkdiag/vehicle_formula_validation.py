"""Bounded parser for the Android Formula data grammar; no evaluation or Python eval.

Only validates the emitted app expression. It is NOT an EXPRESS translator and
must never be used to infer equivalence with a native CALC script.
"""
import math
import re

TOKEN = re.compile(r'\s*(?:(0[xX][0-9a-fA-F]+|(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+))|([A-Za-z][A-Za-z0-9_]*)|(<<|>>|[+*/%&^|(),-]))')
FUNCTIONS = {'min': 2, 'max': 2, 'abs': 1, 'round': 1, 'floor': 1, 'signed8': 1, 'signed16': 1, 'signed32': 1, 'bit': 2}
PRECEDENCE = {'|': 0, '^': 1, '&': 2, '<<': 3, '>>': 3, '+': 4, '-': 4, '*': 5, '/': 5, '%': 5}


def formula_bytes(text):
    if not isinstance(text, str) or not 1 <= len(text) <= 2048:
        raise ValueError('Missing or oversized app formula')
    tokens = []
    at = 0
    while at < len(text.rstrip()):
        m = TOKEN.match(text, at)
        if m is None: raise ValueError('Invalid app formula token')
        tokens.append(next(g for g in m.groups() if g is not None)); at = m.end()
        if len(tokens) > 512: raise ValueError('Excessive app formula tokens')
    pos, highest, nodes = 0, -1, 0
    def take():
        nonlocal pos
        if pos >= len(tokens): raise ValueError('Truncated app formula')
        t = tokens[pos]; pos += 1; return t
    def expression(minimum=0, depth=0):
        nonlocal highest, nodes
        if depth > 32: raise ValueError('Excessive app formula nesting')
        nodes += 1
        if nodes > 256: raise ValueError('Excessive app formula nodes')
        token = take()
        if token in ('+', '-'):
            expression(6, depth + 1)
        elif token == '(':
            expression(0, depth + 1)
            if take() != ')': raise ValueError('Unclosed app formula grouping')
        elif re.fullmatch('[A-Z]', token):
            highest = max(highest, ord(token) - ord('A'))
        elif token.lower() in FUNCTIONS:
            if take() != '(': raise ValueError('Missing formula call arguments')
            for i in range(FUNCTIONS[token.lower()]):
                if i and take() != ',': raise ValueError('Wrong formula call arity')
                expression(0, depth + 1)
            if take() != ')': raise ValueError('Wrong formula call arity')
        elif re.fullmatch(r'0[xX][0-9a-fA-F]+', token):
            if int(token, 16) > (1 << 63) - 1:
                raise ValueError('Hex literal exceeds the Android signed Long parser')
        elif re.fullmatch(r'(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)', token):
            if not math.isfinite(float(token)):
                raise ValueError('Nonfinite app formula literal')
        else:
            raise ValueError('Unknown app formula name')
        while pos < len(tokens) and PRECEDENCE.get(tokens[pos], -1) >= minimum:
            operator = take(); expression(PRECEDENCE[operator] + 1, depth + 1)
    expression()
    if pos != len(tokens): raise ValueError('Trailing app formula data')
    return highest + 1
