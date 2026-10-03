# -*- coding: utf-8 -*-
"""离线源码构建：把 src/main/java 的全部源码对着**游戏实例真实类路径**编译，并产出错误台账。

为什么不用 Gradle：本机没有网络、没有 Gradle 缓存（`maven.neoforged.net` 与 `repo1.maven.org`
都不可达），MDG/NeoGradle 一步都跑不动。但游戏安装自带完整依赖，因此可以像
`port-1.21.1/t4/build_mixins.py` 那样直接调 javac —— 编译类路径一律从**本实例启动日志里的
真实类路径**里挑（`libraries/` 目录与别的 MC 版本共用，按文件名乱扫会拿到错版本）。

用法：
    python tools/build_src.py                  # 编译 + 台账
    python tools/build_src.py --maxerrs 5000   # 少打点错（默认 100000）
    python tools/build_src.py --mc "D:\\...\\.minecraft"   # 覆盖游戏目录

退出码：0 = 0 错误；非 0 = 错误类别数（便于脚本判断"有没有变好"）。
"""
import argparse
import collections
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_ROOT = os.path.join(PROJ, 'src', 'main', 'java')
OUT = os.path.join(PROJ, 'out-srccompile')
CLASSES = os.path.join(OUT, 'classes')
INSTANCE = '1.21.1-NeoForge_21.1.252'

ap = argparse.ArgumentParser()
ap.add_argument('--mc', default=None, help='游戏目录（含 versions/ 与 libraries/）')
ap.add_argument('--maxerrs', type=int, default=100000)
args = ap.parse_args()

# 唯一一处与机器相关的默认值：本机游戏目录名是中文，用码点拼出来（可被 --mc / NCPB_MC_DIR 覆盖）
CJK = ''.join(chr(c) for c in (0x822A, 0x7A7A, 0x5B66))
MC = args.mc or os.environ.get('NCPB_MC_DIR') or ('D:\\' + CJK + '\\.minecraft')
MODS = os.path.join(MC, 'versions', INSTANCE, 'mods')
LOGDIR = os.path.join(MC, 'versions', INSTANCE, 'logs')
JAVAC = os.path.join(os.environ.get('APPDATA', ''), r'.minecraft\runtime\java-runtime-delta\bin\javac.exe')

FAIL = []


def fail(m):
    FAIL.append(m)
    print('  [FAIL] ' + m)


def ok(m):
    print('  [ ok ] ' + m)


print('=' * 84)
print('离线源码构建（javac + 真实 1.21.1-NeoForge 类路径）')
print('=' * 84)

# ------------------------------------------------------------------ [1] 类路径
print('\n[1] 编译类路径')
if not os.path.isfile(JAVAC):
    raise SystemExit('找不到 javac: %s' % JAVAC)
launch = []
dbg = os.path.join(LOGDIR, 'debug.log')
if os.path.isfile(dbg):
    with open(dbg, encoding='utf-8', errors='replace') as fh:
        for line in fh:
            if 'Located paths when launch context was created' in line:
                mm = re.search(r'\[(.*)\]', line)
                if mm:
                    launch = [x.strip() for x in mm.group(1).split(',') if x.strip()]
                break
launch = [x for x in launch if os.path.isfile(x)]
if launch:
    ok('启动日志里的真实类路径 %d 条（%s）' % (len(launch), os.path.basename(dbg)))
else:
    fail('启动日志里没找到类路径（%s）—— 依赖解析会大面积失败' % dbg)

# NeoForge 自身不在上面那份列表里（FML 以 transforming jar 挂载），必须显式加。
# 顺序关键：neoforge-client 是打过补丁的原版类，必须排在 srg 之前。
nf_ver = INSTANCE.split('NeoForge_')[-1]
NF_DIR = os.path.join(MC, 'libraries', 'net', 'neoforged', 'neoforge', nf_ver)
NF_CLIENT = os.path.join(NF_DIR, 'neoforge-%s-client.jar' % nf_ver)
NF_UNIVERSAL = os.path.join(NF_DIR, 'neoforge-%s-universal.jar' % nf_ver)
srg_dir = os.path.join(MC, 'libraries', 'net', 'minecraft', 'client')
SRG = None
if os.path.isdir(srg_dir):
    for d in sorted(os.listdir(srg_dir), reverse=True):
        cand = os.path.join(srg_dir, d, 'client-%s-srg.jar' % d)
        if os.path.isfile(cand):
            SRG = cand
            break
for label, p in (('nf-client', NF_CLIENT), ('nf-universal', NF_UNIVERSAL), ('srg', SRG)):
    if p and os.path.isfile(p):
        ok('  %-12s %s' % (label, os.path.basename(p)))
    else:
        fail('缺少 %s（%s）' % (label, p))
mods = []
if os.path.isdir(MODS):
    mods = [os.path.join(MODS, f) for f in sorted(os.listdir(MODS)) if f.endswith('.jar')]
ok('  依赖 mod jar %d 个（netmusic 等）' % len(mods))

cp = []
# AT 补丁目录必须排在**最前面**：真构建也是"先应用 AT 再编译"，
# 排后面会被原始 jar 里的 protected 成员盖住（那正是 13 个 RenderStateShard 假错误的来源）。
ATED = os.path.join(OUT, 'ated')
if os.path.isdir(ATED) and any(os.scandir(ATED)):
    cp.append(ATED)
    ok('  %-12s %s（先跑 tools/apply_at.py 生成）' % ('ated', ATED))
else:
    fail('缺少 AT 补丁目录 %s —— 先跑 tools/apply_at.py，否则会有一堆假的 protected 访问错误' % ATED)
for p in [NF_CLIENT, NF_UNIVERSAL, SRG] + launch + mods:
    if p and os.path.isfile(p) and p not in cp:
        cp.append(p)
print('  最终 classpath: %d 个 jar' % len(cp))

# ------------------------------------------------------------------ [2] 源码
print('\n[2] 源码清单')
sources = []
for root, dirs, files in os.walk(SRC_ROOT):
    for f in files:
        if f.endswith('.java'):
            sources.append(os.path.join(root, f))
sources.sort()
print('  %d 个 .java' % len(sources))
if len(sources) < 100:
    fail('源文件只有 %d 个，明显不对（期望 700+）' % len(sources))
if FAIL:
    raise SystemExit('\n门禁失败，未编译。')

# ------------------------------------------------------------------ [3] 编译
print('\n[3] javac')
os.makedirs(OUT, exist_ok=True)
shutil.rmtree(CLASSES, ignore_errors=True)
os.makedirs(CLASSES, exist_ok=True)
argfile = os.path.join(OUT, 'sources.txt')
with open(argfile, 'w', encoding='utf-8') as fh:
    fh.write('\n'.join(sources) + '\n')
cmd = [JAVAC,
       '-J-Duser.language=en', '-J-Duser.country=US',   # 诊断固定英文，避免本地化文本解析
       '-J-Dstdout.encoding=UTF-8', '-J-Dstderr.encoding=UTF-8',
       '-nowarn', '-proc:none', '-encoding', 'UTF-8',
       '-Xmaxerrs', str(args.maxerrs),
       '-cp', ';'.join(cp), '-d', CLASSES, '@' + argfile]
t0 = time.time()
proc = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
raw = (proc.stdout or '') + (proc.stderr or '')
elapsed = time.time() - t0
log = os.path.join(OUT, 'javac.log')
with open(log, 'w', encoding='utf-8') as fh:
    fh.write(raw)
print('  rc=%d  %.1fs  输出 %d 字符  -> %s' % (proc.returncode, elapsed, len(raw), log))

# ------------------------------------------------------------------ [4] 台账
print('\n[4] 错误台账')

DIAG = re.compile(r'^(?P<file>.+?\.java):(?P<line>\d+): (?:error|错误): (?P<msg>.+)$', re.M)
errs = [m.groupdict() for m in DIAG.finditer(raw)]
summary = re.search(r'^(\d+) error', raw, re.M)
if not errs and proc.returncode != 0 and 'error' in raw:
    fail('rc!=0 但一条错误都没解析出来 —— 台账会假报"干净"（诊断格式变了？）')
if summary and int(summary.group(1)) != len(errs):
    # javac 会在 -Xmaxerrs 截断时只报部分；这里只提示，不当失败
    print('  注意：javac 汇总说 %s 个错误，台账解析到 %d 条（可能被 -Xmaxerrs 截断）'
          % (summary.group(1), len(errs)))


def norm(m):
    m = re.sub(r"'[^']*'", "'X'", m)
    m = re.sub(r'"[^"]*"', '"X"', m)
    m = re.sub(r'\bvar\d+[a-z]*\b', 'varN', m)
    return m.strip()[:120]


by_kind = collections.Counter(norm(e['msg']) for e in errs)
by_file = collections.Counter(e['file'] for e in errs)
rel = lambda p: os.path.relpath(p, SRC_ROOT)
print('  错误总数: %d   涉及文件: %d / %d' % (len(errs), len(by_file), len(sources)))
print('  --- 按类别 top 20 ---')
for k, v in by_kind.most_common(20):
    print('   %6d  %s' % (v, k))
print('  --- 按文件 top 15 ---')
for k, v in by_file.most_common(15):
    print('   %6d  %s' % (v, rel(k)))

ledger = {
    'sources': len(sources),
    'errors': len(errs),
    'files_with_errors': len(by_file),
    'rc': proc.returncode,
    'seconds': round(elapsed, 1),
    'classpath_jars': len(cp),
    'by_kind': by_kind.most_common(),
    'by_file': [(rel(k), v) for k, v in by_file.most_common()],
    'detail': [{'file': rel(e['file']), 'line': int(e['line']), 'msg': e['msg']} for e in errs],
}
with open(os.path.join(OUT, 'errors.json'), 'w', encoding='utf-8') as fh:
    json.dump(ledger, fh, ensure_ascii=False, indent=1)
with open(os.path.join(OUT, 'errors.md'), 'w', encoding='utf-8') as fh:
    fh.write('# 编译错误台账\n\n源文件 %d，错误 %d，涉及文件 %d，耗时 %.1fs\n\n'
             % (len(sources), len(errs), len(by_file), elapsed))
    fh.write('## 按类别\n\n| 条数 | 类别 |\n| --- | --- |\n')
    for k, v in by_kind.most_common():
        fh.write('| %d | `%s` |\n' % (v, k))
    fh.write('\n## 按文件\n\n| 条数 | 文件 |\n| --- | --- |\n')
    for k, v in by_file.most_common():
        fh.write('| %d | `%s` |\n' % (v, rel(k)))
print('\n  台账: %s / %s' % (os.path.join(OUT, 'errors.md'), os.path.join(OUT, 'errors.json')))

print('\n' + '=' * 84)
if not errs and proc.returncode == 0:
    print('编译通过：%d 个源文件 0 错误' % len(sources))
    sys.exit(0)
print('编译失败：%d 条错误（类别 %d）' % (len(errs), len(by_kind)))
sys.exit(max(1, len(by_kind)))
