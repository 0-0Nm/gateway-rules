#!/usr/bin/env python3
"""Fail CI on credential-shaped literals; never print matched secret values."""
import re,subprocess,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
tracked=subprocess.run(['git','ls-files','-z'],cwd=root,capture_output=True,check=True).stdout.decode().split('\0')
pattern=re.compile(r'(?:github_pat_|ghp_|sk-)[A-Za-z0-9_-]{16,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----')
fail=[]
for name in tracked:
 p=root/name
 if not p.is_file() or p.suffix.lower() not in ('.py','.kt','.kts','.java','.md','.json','.yml','.yaml','.xml','.sh','.properties'):continue
 for number,line in enumerate(p.read_text(errors='replace').splitlines(),1):
  if pattern.search(line):fail.append(f'{name}:{number}')
for location in fail:print('SECRET_LITERAL',location)
print(f'secret_scan findings={len(fail)} (matched values suppressed)')
sys.exit(1 if fail else 0)
