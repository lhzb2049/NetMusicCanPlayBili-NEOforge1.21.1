# -*- coding: utf-8 -*-
"""阶段 0 离线验收：媒体源识别矩阵（含负例 + 自证「能说不」）。

做什么：
  1. 在 out-srccompile 下造沙盒目录树：白名单内文件、白名单外目录、越界 `..` 路径、
     超大文件、以及**指向白名单外的目录联接**（真逃逸）；
  2. 用 javac 把 tools/java 下的探针编译进 out-srccompile/media_verify；
  3. 用不同 -D 组合跑 5 轮，逐条比对 `id|kind|allowed|reason` 与期望值；
  4. --selftest：先跑正常矩阵（必须 0 失败），再故意翻转一条期望（必须报失败），
     以此证明这套比对本身"能说不"。

用法：
    python tools/verify_local_media.py                # 正常矩阵
    python tools/verify_local_media.py --selftest     # 矩阵 + 自证负例
    python tools/verify_local_media.py --flip-case A:local-abs-video

退出码：0 = 全部符合期望；非 0 = 失败条数。
"""
import argparse
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(PROJ, 'out-srccompile')
CLASSES = os.path.join(OUT, 'classes')
PROBE_OUT = os.path.join(OUT, 'media_verify')
SANDBOX = os.path.join(OUT, 'local_media_sandbox')
PEER = os.path.join(OUT, 'local_media_peer')
FORBIDDEN = os.path.join(OUT, 'local_media_forbidden')
PROBE_SRC = os.path.join(
    PROJ, 'tools', 'java', 'com', 'zhongbai233', 'net_music_can_play_bili',
    'client', 'media', 'VerifyLocalMediaProbe.java')
PROBE_CLASS = 'com.zhongbai233.net_music_can_play_bili.client.media.VerifyLocalMediaProbe'

JAVA_BIN = os.path.join(os.environ.get('APPDATA', ''), '.minecraft', 'runtime', 'java-runtime-delta', 'bin')
JAVAC = os.path.join(JAVA_BIN, 'javac.exe')
JAVA = os.path.join(JAVA_BIN, 'java.exe')

DENY_ROOTS = '白名单根目录'
DENY_MISSING = '文件不存在'
DENY_EXT = '扩展名不在支持列表'
DENY_UNC = 'UNC'
DENY_DEVICE = '保留设备名'
DENY_DISABLED = '未启用'
DENY_SIZE = '超过上限'
NOT_LOCAL = '既不是 http(s)'
ALLOW = '白名单与大小校验通过'

# 每轮：说明 + 系统属性 + 期望表（id -> (kind, allowed, reason 关键字 或 None)）
RUNS = {
    'A': {
        'desc': '白名单命中（roots=沙盒）',
        'props': {'ncpb.local.enabled': 'true', 'ncpb.local.roots': SANDBOX},
        'expect': {
            'http-remote': ('HTTP', True, 'http'),
            'http-loopback': ('HTTP', True, 'http'),
            'local-abs-video': ('LOCAL_VIDEO', True, ALLOW),
            'local-abs-image': ('LOCAL_IMAGE', True, ALLOW),
            'local-uri-path-toUri': ('LOCAL_VIDEO', True, ALLOW),
            'local-uri-file-toURI': ('LOCAL_VIDEO', True, ALLOW),
            'local-with-sync-params': ('LOCAL_VIDEO', True, ALLOW),
            'local-nested-inside': ('LOCAL_VIDEO', True, ALLOW),
            'inside-root-extra': ('LOCAL_VIDEO', True, ALLOW),
            'traversal-inside': ('LOCAL_VIDEO', True, ALLOW),
            'traversal-escape': ('LOCAL_VIDEO', False, DENY_ROOTS),
            'forbidden-root': ('LOCAL_VIDEO', False, DENY_ROOTS),
            'link-escape': ('LOCAL_VIDEO', False, DENY_ROOTS),
            'local-missing': ('LOCAL_VIDEO', False, DENY_MISSING),
            'local-unsupported-ext': ('LOCAL_OTHER', False, DENY_EXT),
            'local-unc': ('LOCAL_VIDEO', False, DENY_UNC),
            'local-device-name': ('LOCAL_VIDEO', False, DENY_DEVICE),
            'keyword-search': ('UNSUPPORTED', False, NOT_LOCAL),
            'relative-path': ('UNSUPPORTED', False, NOT_LOCAL),
            'oversized': ('LOCAL_VIDEO', True, ALLOW),
            'peer-root': ('LOCAL_VIDEO', False, DENY_ROOTS),
            'log-throttle-first': ('true', None, None),
            'log-throttle-repeat': ('false', None, None),
            'log-throttle-changed': ('true', None, None),
            'log-throttle-size': ('true', None, None),
        },
    },
    'B': {
        'desc': '总开关关闭（enabled=false）',
        'props': {'ncpb.local.enabled': 'false', 'ncpb.local.roots': SANDBOX},
        'expect': {
            'http-remote': ('HTTP', True, 'http'),
            'local-abs-video': ('LOCAL_VIDEO', False, DENY_DISABLED),
            'local-abs-image': ('LOCAL_IMAGE', False, DENY_DISABLED),
            'local-missing': ('LOCAL_VIDEO', False, DENY_DISABLED),
            'inside-root-extra': ('LOCAL_VIDEO', False, DENY_DISABLED),
            'forbidden-root': ('LOCAL_VIDEO', False, DENY_DISABLED),
            'traversal-escape': ('LOCAL_VIDEO', False, DENY_DISABLED),
            'link-escape': ('LOCAL_VIDEO', False, DENY_DISABLED),
            # 这两条在白名单/设备名之前判定，所以仍报各自原因（判定顺序被刻意锁定）
            'local-unc': ('LOCAL_VIDEO', False, DENY_UNC),
            'local-device-name': ('LOCAL_VIDEO', False, DENY_DEVICE),
            'keyword-search': ('UNSUPPORTED', False, NOT_LOCAL),
        },
    },
    'C': {
        'desc': '未配置 roots → 默认根目录（游戏目录=沙盒）',
        'props': {'ncpb.local.enabled': 'true'},
        'expect': {
            'local-abs-video': ('LOCAL_VIDEO', True, ALLOW),
            'inside-root-extra': ('LOCAL_VIDEO', True, ALLOW),
            'traversal-inside': ('LOCAL_VIDEO', True, ALLOW),
            'local-nested-inside': ('LOCAL_VIDEO', True, ALLOW),
            'forbidden-root': ('LOCAL_VIDEO', False, DENY_ROOTS),
            'traversal-escape': ('LOCAL_VIDEO', False, DENY_ROOTS),
            'link-escape': ('LOCAL_VIDEO', False, DENY_ROOTS),
            'peer-root': ('LOCAL_VIDEO', False, DENY_ROOTS),
            'local-missing': ('LOCAL_VIDEO', False, DENY_MISSING),
        },
    },
    'D': {
        'desc': '大小上限（max_bytes=1024）',
        'props': {'ncpb.local.enabled': 'true', 'ncpb.local.roots': SANDBOX, 'ncpb.local.max_bytes': '1024'},
        'expect': {
            'local-abs-video': ('LOCAL_VIDEO', True, ALLOW),
            'oversized': ('LOCAL_VIDEO', False, DENY_SIZE),
        },
    },
    'E': {
        'desc': '多根目录（; 分隔）+ 第二个根里的文件',
        'props': {'ncpb.local.enabled': 'true', 'ncpb.local.roots': SANDBOX + ';' + PEER},
        'expect': {
            'peer-root': ('LOCAL_VIDEO', True, ALLOW),
            'forbidden-root': ('LOCAL_VIDEO', False, DENY_ROOTS),
            'traversal-escape': ('LOCAL_VIDEO', False, DENY_ROOTS),
            'link-escape': ('LOCAL_VIDEO', False, DENY_ROOTS),
        },
    },
    'F': {
        'desc': '不设置 enabled → 阶段 1 起默认开启',
        'props': {},
        'expect': {
            'local-abs-video': ('LOCAL_VIDEO', True, ALLOW),
            'local-abs-image': ('LOCAL_IMAGE', True, ALLOW),
            'forbidden-root': ('LOCAL_VIDEO', False, DENY_ROOTS),
        },
    },
    'S': {
        'desc': '阶段 1：本地图片缩放（纯逻辑，不依赖文件系统）',
        'props': {},
        'expect': {
            'scaler-identity-dims': ('8x8', None, None),
            'scaler-identity-same-array': ('true', None, None),
            'scaler-aspect-dims': ('2048x1536', None, None),
            'scaler-box-average': ('ff8a8a8a', None, None),
            'scaler-downscale-dims': ('4x4', None, None),
            'scaler-downscale-length': ('16', None, None),
            'scaler-downscale-pixel': ('ffffffff', None, None),
            'scaler-zero-guard': ('1x1', None, None),
            'scaler-no-upscale': ('4x4', None, None),
            'header-png-dims': ('1920x1080', None, None),
            'header-jpeg-dims': ('640x480', None, None),
            'header-unknown': ('null', None, None),
            'header-exceeds-yes': ('true', None, None),
            'header-exceeds-no': ('false', None, None),
        },
    },
}


def write_file(path, size=64):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as fh:
        fh.write(b'\0' * size)


def setup_sandbox():
    """造沙盒：白名单内文件、白名单外目录、超大文件、指向白名单外的联接。幂等。"""
    write_file(os.path.join(SANDBOX, 'videos', 'a.mp4'))
    write_file(os.path.join(SANDBOX, 'videos', 'x.mkv'))
    write_file(os.path.join(SANDBOX, 'videos', 'sub', 'deep', 'c.mp4'))
    write_file(os.path.join(SANDBOX, 'images', 'b.png'))
    write_file(os.path.join(SANDBOX, 'outside', 'x.mp4'))
    write_file(os.path.join(SANDBOX, 'big', 'large.mp4'), size=2048)
    write_file(os.path.join(PEER, 'd.mp4'))
    write_file(os.path.join(FORBIDDEN, 'x.mp4'))

    link = os.path.join(SANDBOX, 'videos', 'link')
    if os.path.isdir(link):
        # 用 rmdir 摘掉联接本身（不递归、不会删到目标内容），然后按当前目标重建
        subprocess.run(['cmd', '/c', 'rmdir', link], capture_output=True, text=True)
    if os.path.isdir(link):
        return False
    # 目录联接（junction）不需要管理员权限；指向白名单之外的目录，构成真逃逸
    result = subprocess.run(
        ['cmd', '/c', 'mklink', '/J', link, FORBIDDEN],
        capture_output=True, text=True, encoding='utf-8', errors='replace')
    return result.returncode == 0 and os.path.isdir(link)


def compile_probe():
    if os.path.isdir(PROBE_OUT):
        shutil.rmtree(PROBE_OUT)
    os.makedirs(PROBE_OUT)
    cmd = [JAVAC, '-encoding', 'UTF-8', '-nowarn', '-cp', CLASSES, '-d', PROBE_OUT, PROBE_SRC]
    result = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if result.returncode != 0:
        print('❌ 探针编译失败：')
        print(result.stdout)
        print(result.stderr)
        return False
    return True


def run_probe(props):
    # 探针要打印中文原因，必须把 JVM 的编解码显式钉成 UTF-8（Windows 默认是 GBK）
    cmd = [
        JAVA, '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
        '-cp', CLASSES + os.pathsep + PROBE_OUT,
    ]
    merged = {'ncpb.test.sandbox': SANDBOX, 'ncpb.test.peer': PEER, 'ncpb.test.forbidden': FORBIDDEN}
    merged.update(props)
    for key, value in merged.items():
        cmd.append('-D%s=%s' % (key, value))
    cmd.append(PROBE_CLASS)
    result = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if result.returncode != 0:
        return None, (result.stdout or '') + (result.stderr or '')

    actual = {}
    for line in result.stdout.splitlines():
        parts = line.split('|')
        if len(parts) >= 4:
            actual[parts[0]] = (parts[1], parts[2], '|'.join(parts[3:]))
        elif len(parts) == 2:
            actual[parts[0]] = (parts[1], None, None)
    return actual, None


def compare(run_id, actual, flip_case=None, link_ready=True):
    spec = RUNS[run_id]
    failures, skipped, rows = [], [], []
    for case, expect in spec['expect'].items():
        if case == 'link-escape' and not link_ready:
            skipped.append(case)
            continue
        got = actual.get(case)
        if got is None:
            failures.append('%s.%s: 探针没有输出这一条' % (run_id, case))
            continue
        kind_exp, allowed_exp, reason_exp = expect
        if flip_case == '%s:%s' % (run_id, case):
            allowed_exp = (not allowed_exp) if allowed_exp is not None else 'flipped'
        kind_got, allowed_got, reason_got = got
        if allowed_exp is None:
            ok = str(kind_got).lower() == str(kind_exp).lower()
            detail = 'got=%s' % kind_got
        else:
            allowed_got_bool = str(allowed_got).lower() == 'true'
            ok = kind_got == kind_exp and allowed_got_bool == bool(allowed_exp)
            if ok and reason_exp:
                ok = reason_exp in (reason_got or '')
            detail = 'got=%s/%s/%s' % (kind_got, allowed_got, (reason_got or '')[:58])
        rows.append((case, '✅' if ok else '❌', detail))
        if not ok:
            failures.append(
                '%s.%s: 期望 kind=%s allowed=%s 原因含 %r，实际 %s'
                % (run_id, case, kind_exp, allowed_exp, reason_exp, detail))
    return failures, skipped, rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--flip-case', default=None, help='形如 A:forbidden-root，翻转该条期望（证明比对会失败）')
    ap.add_argument('--selftest', action='store_true', help='先跑正常矩阵，再跑翻转版并断言其失败')
    args = ap.parse_args()

    if not os.path.isfile(JAVAC) or not os.path.isfile(JAVA):
        print('找不到 javac/java: %s' % JAVA_BIN)
        return 2
    if not os.path.isdir(CLASSES):
        print('找不到编译产物 %s，请先跑 tools/build_src.py' % CLASSES)
        return 2

    print('=' * 78)
    print('阶段 0 离线验收：媒体源识别矩阵')
    print('  编译产物 : %s' % CLASSES)
    print('  沙盒     : %s' % SANDBOX)
    print('  第二根   : %s' % PEER)
    print('  白名单外 : %s' % FORBIDDEN)
    print('=' * 78)

    link_ready = setup_sandbox()
    if not compile_probe():
        return 2
    if not link_ready:
        print('⚠️ 未能创建目录联接，link-escape（联接逃逸）本条跳过\n')

    all_failures = []
    for run_id in sorted(RUNS):
        spec = RUNS[run_id]
        actual, error = run_probe(spec['props'])
        print('[%s] %s' % (run_id, spec['desc']))
        if actual is None:
            print('    探针运行失败：\n%s' % (error or '')[:800])
            all_failures.append('%s: 探针运行失败' % run_id)
            continue
        failures, skipped, rows = compare(run_id, actual, flip_case=args.flip_case, link_ready=link_ready)
        for case, mark, detail in rows:
            print('    %s %-24s %s' % (mark, case, detail))
        if skipped:
            print('    ⚠️ 跳过: %s' % ', '.join(skipped))
        all_failures.extend(failures)

    print('=' * 78)
    if all_failures:
        for line in all_failures:
            print('❌ ' + line)
        print('❌ %d 条不符合期望' % len(all_failures))
    else:
        print('✅ 全部符合期望（用例 %d 条 / %d 轮）'
              % (sum(len(spec['expect']) for spec in RUNS.values()), len(RUNS)))

    if args.selftest:
        print('-' * 78)
        target = args.flip_case or 'A:forbidden-root'
        print('自证负例：翻转 %s 的期望，比对必须报失败' % target)
        run_id = target.split(':')[0]
        actual, error = run_probe(RUNS[run_id]['props'])
        if actual is None:
            print('❌ 探针运行失败')
            return 3
        failures, _skipped, _rows = compare(run_id, actual, flip_case=target, link_ready=link_ready)
        if failures:
            print('✅ 已按预期报失败（说明这套比对能说不）：%s' % failures[0][:120])
        else:
            print('❌ 翻转后期望仍然"全部通过" —— 比对形同虚设')
            return 3

    return len(all_failures)


if __name__ == '__main__':
    sys.exit(main())
