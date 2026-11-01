#!/usr/bin/env python3
"""Idempotent lang helper: report en/zh key gaps.

Does NOT rewrite Patchouli layout. Requires json5.
  python tools/sync_lang_keys.py          # report only
  python tools/sync_lang_keys.py --check  # exit 1 if gaps or empty/TODO in en
"""
from __future__ import annotations
import argparse, sys
from pathlib import Path

try:
    import json5
except ImportError:
    sys.exit("need json5: uv pip install json5")

ROOT = Path(__file__).resolve().parents[1]
EN = ROOT / "common/src/main/resources/assets/hexguide/lang/en_us.flatten.json5"
ZH = ROOT / "common/src/main/resources/assets/hexguide/lang/zh_cn.flatten.json5"
SEP = "\x1f"

def flatten(obj, prefix=""):
    out = {}
    if isinstance(obj, dict):
        for k, v in obj.items():
            key = f"{prefix}{SEP}{k}" if prefix else str(k)
            out.update(flatten(v, key))
    else:
        out[prefix] = obj
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    en = json5.loads(EN.read_text(encoding="utf-8"))
    zh = json5.loads(ZH.read_text(encoding="utf-8"))
    fen, fzh = flatten(en), flatten(zh)
    missing = sorted(set(fzh) - set(fen))
    extra = sorted(set(fen) - set(fzh))
    bad = [k for k, v in fen.items() if isinstance(v, str) and (not v.strip() or v.strip().startswith("TODO"))]
    print(f"en={len(fen)} zh={len(fzh)} missing_in_en={len(missing)} extra_in_en={len(extra)} empty_or_todo={len(bad)}")
    for k in missing[:20]:
        print("  missing", k.replace(SEP, "."))
    for k in bad[:20]:
        print("  bad", k.replace(SEP, "."), repr(fen[k])[:60])
    if args.check and (missing or bad):
        return 1
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
