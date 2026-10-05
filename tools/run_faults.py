# -*- coding: utf-8 -*-
"""验收④ 故障注入：证明 verify_src.py 的每一项都会说「不」。

六组注入，每组都必须让 verify_src.py **非零退出**，且失败的检查项与注入意图对得上：

  A  把某个类文件的 access_flags 里的 ACC_PUBLIC 清掉（public -> 包私有）
        -> 期望 member_signatures 失败
  B  从产物 jar 里删掉一个资源条目
        -> 期望 resource_bytes 失败
  C  把某个类的方法描述符 `()V` 原地改成 `()I`（同长度，可原地补丁）
        -> 期望 member_signatures 失败
  D  删掉一个顶层类的 .class
        -> 期望 top_level_classes 失败
  E  把某个类 BootstrapMethods 引用的引导方法句柄（MethodHandle）重定向到另一个
     Methodref —— 不改变任何字节长度、不动成员签名
        -> 期望 bootstrap_methods 失败
  F  把某个合成 lambda 的返回类型 V 改成 I（同长度原地补丁）
        -> 期望 member_signatures 失败。合成成员的「参数顺序/擦除类型」按规则不计入判定，
           但存在性/名称/标志/返回类型仍必须一致 —— 这一组就是证明过滤器只滤噪音、
           不放过真正的结构改变（所以这里刻意不用"改捕获顺序"：那正是被过滤的那一类）

每组注入前后都做哈希核对：
  * 注入后、调用 verify 之后哈希不变  -> 证明 verify_src.py 是只读的
  * 还原后哈希 == 注入前哈希          -> 证明产物被完整恢复

调用 verify 时带 --focus <注入靶子>，让「命中注入点的差异」排在清单最前面。
完整输出全部重定向到 out-srccompile/verify_src/faults/ 下，控制台只打印每组结论和总结。

用法：
    python tools/run_faults.py [--jar <基线jar>] [--classes <classes目录>] [--built-jar <产物jar>]
退出码：0 = 六组都按预期失败；非 0 = 有组别不符合预期
"""
import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.dirname(os.path.abspath(__file__))
DEFAULT_JAR = os.path.join(os.path.dirname(PROJ), 'net_music_can_play_bili-0.7.9-beta+neo1.21.jar')
DEFAULT_CLASSES = os.path.join(PROJ, 'out-srccompile', 'classes')
FAULTS_DIR = os.path.join(PROJ, 'out-srccompile', 'verify_src', 'faults')
BACKUP_DIR = os.path.join(FAULTS_DIR, 'backup')

ACC_PUBLIC = 0x0001
ACC_SYNTHETIC = 0x1000
CP_SIZES = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4,
            15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}


def u2(b, i):
    return struct.unpack('>H', b[i:i + 2])[0]


def u4(b, i):
    return struct.unpack('>I', b[i:i + 4])[0]


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def parse_cp(b):
    """完整解析常量池：{索引: {'tag','start',...}}，start 是该条目的起始偏移。"""
    i = 8
    count = u2(b, i)
    i += 2
    cp = [None] * count
    k = 1
    while k < count:
        start = i
        tag = b[i]
        i += 1
        e = {'tag': tag, 'start': start}
        if tag == 1:
            ln = u2(b, i)
            i += 2
            e['s'] = b[i:i + ln].decode('utf-8', 'replace')
            i += ln
        elif tag in CP_SIZES:
            if tag == 7 or tag == 8 or tag == 16 or tag == 19 or tag == 20:
                e['idx'] = u2(b, i)
            elif tag in (9, 10, 11):
                e['class'] = u2(b, i); e['nat'] = u2(b, i + 2)
            elif tag == 12:
                e['name'] = u2(b, i); e['desc'] = u2(b, i + 2)
            elif tag == 15:
                e['kind'] = b[i]; e['idx'] = u2(b, i + 1)
            elif tag in (17, 18):
                e['bsm'] = u2(b, i); e['nat'] = u2(b, i + 2)
            i += CP_SIZES[tag]
        else:
            raise ValueError('未知常量池 tag %d' % tag)
        cp[k] = e
        if tag in (5, 6):
            k += 1
        k += 1
    return cp, i


def cp_name(cp, idx):
    e = cp[idx]
    return e['s'] if e and e['tag'] == 1 else '?'


def skip_members(b, i):
    """i 指向 fields_count/methods_count；返回跳过该段后、下一段 count 的偏移。"""
    count = u2(b, i)
    i += 2
    for _ in range(count):
        ac = u2(b, i + 6)
        i += 8
        for _a in range(ac):
            i += 6 + u4(b, i + 2)
    return i


def bootstrap_handle_indices(b):
    """返回 BootstrapMethods 里所有 bootstrap_method_ref 的常量池索引。"""
    cp, i = parse_cp(b)
    i += 6  # access_flags / this / super
    ic = u2(b, i)
    i += 2 + 2 * ic
    i = skip_members(b, i)   # fields -> methods_count
    i = skip_members(b, i)   # methods -> attributes_count
    handles = []
    ac = u2(b, i)
    i += 2
    for _ in range(ac):
        aname = cp_name(cp, u2(b, i))
        alen = u4(b, i + 2)
        info = i + 6
        if aname == 'BootstrapMethods':
            n = u2(b, info)
            p = info + 2
            for _b in range(n):
                handles.append(u2(b, p))
                nargs = u2(b, p + 2)
                p += 4 + 2 * nargs
        i += 6 + alen
    return cp, handles


# ----------------------------------------------------------------- 注入实现


def find_class_for_injection(classes_dir):
    preferred = os.path.join(classes_dir, 'com', 'zhongbai233', 'net_music_can_play_bili', 'Config.class')
    if os.path.isfile(preferred):
        return preferred
    for root, _dirs, files in os.walk(classes_dir):
        for f in sorted(files):
            if f.endswith('.class') and '$' not in f:
                return os.path.join(root, f)
    raise SystemExit('classes 目录里没有可用的 .class')


def find_bootstrap_target(classes_dir):
    """找一个含 BootstrapMethods、且其引导句柄能重定向到别的 Methodref 的类。"""
    for root, _dirs, files in os.walk(classes_dir):
        for f in sorted(files):
            if not f.endswith('.class'):
                continue
            path = os.path.join(root, f)
            with open(path, 'rb') as fh:
                data = fh.read()
            try:
                cp, handles = bootstrap_handle_indices(data)
            except Exception:
                continue
            for h in handles:
                e = cp[h] if h < len(cp) else None
                if e and e['tag'] == 15 and e['kind'] in (5, 6, 7, 8, 9):
                    for other in range(1, len(cp)):
                        o = cp[other]
                        if o and o['tag'] == 10 and other != e['idx']:
                            return path, handles
    return None, None


def inject_clear_public(path):
    with open(path, 'rb') as fh:
        b = bytearray(fh.read())
    cp, i = parse_cp(bytes(b))
    flags = u2(b, i)
    if not flags & ACC_PUBLIC:
        raise SystemExit('靶子类本来就不是 public，换一个')
    struct.pack_into('>H', b, i, flags & ~ACC_PUBLIC)
    with open(path, 'wb') as fh:
        fh.write(b)
    return 'access_flags 0x%04x -> 0x%04x（清掉 ACC_PUBLIC）' % (flags, flags & ~ACC_PUBLIC)


def inject_descriptor(path, old='()V', new='()I'):
    assert len(old) == len(new), '同长度才能原地补丁'
    with open(path, 'rb') as fh:
        b = bytearray(fh.read())
    cp, _i = parse_cp(bytes(b))
    for e in cp:
        if e and e['tag'] == 1 and e.get('s') == old:
            off = e['start'] + 3          # tag(1) + 长度(2) 之后是字符串数据
            b[off:off + len(old)] = new.encode('utf-8')
            with open(path, 'wb') as fh:
                fh.write(b)
            return '常量池 Utf8 `%s` -> `%s`' % (old, new)
    raise SystemExit('靶子里找不到 Utf8 `%s`' % old)


def inject_lambda_return_type(path):
    """把一个合成 lambda 方法的返回类型 V 改成 I —— 同长度，可原地补丁。

    合成成员的参数顺序/擦除类型按规则不计入判定，但返回类型是保留项，所以这一刀必须被抓到。
    """
    import verify_src as V
    with open(path, 'rb') as fh:
        data = bytearray(fh.read())
    cls = V.parse_class(bytes(data))
    cp, _cp_end = parse_cp(bytes(data))   # 用本模块的解析器：它的条目带 start 偏移
    for m in cls['members']:
        if not (m['kind'] == 'm' and m['flags'] & ACC_SYNTHETIC and m['desc'].endswith('V')):
            continue
        if len([x for x in cls['members'] if x['desc'] == m['desc']]) != 1:
            continue          # 描述符被别的成员共用，避免连带改动
        hits = [e for e in cp if e and e['tag'] == 1 and e.get('s') == m['desc']]
        if len(hits) != 1:
            continue
        e = hits[0]
        off = e['start'] + 3 + len(m['desc']) - 1     # tag(1)+长度(2) 之后是字符串数据
        data[off] = ord('I')
        with open(path, 'wb') as fh:
            fh.write(data)
        return '合成 lambda %s 返回类型 V -> I（描述符 %s）' % (m['name'], m['desc'])
    raise SystemExit('找不到合适的合成 lambda 靶子')


def find_lambda_target(classes_dir):
    """找第一个含"描述符未被共用、以 V 结尾"的合成方法的类。"""
    import verify_src as V
    for root, _dirs, files in os.walk(classes_dir):
        for f in sorted(files):
            if not f.endswith('.class'):
                continue
            path = os.path.join(root, f)
            try:
                with open(path, 'rb') as fh:
                    cls = V.parse_class(fh.read())
            except Exception:
                continue
            for m in cls['members']:
                if (m['kind'] == 'm' and m['flags'] & ACC_SYNTHETIC and m['desc'].endswith('V')
                        and len([x for x in cls['members'] if x['desc'] == m['desc']]) == 1):
                    return path
    return None


def inject_bootstrap_handle(path):
    """把 BootstrapMethods 用到的某个 MethodHandle 重定向到另一个 Methodref（同长度原地补丁）。"""
    with open(path, 'rb') as fh:
        b = bytearray(fh.read())
    cp, handles = bootstrap_handle_indices(bytes(b))
    for h in handles:
        e = cp[h] if h < len(cp) else None
        if not (e and e['tag'] == 15 and e['kind'] in (5, 6, 7, 8, 9)):
            continue
        old_ref = e['idx']
        old = cp[old_ref]
        for other in range(1, len(cp)):
            o = cp[other]
            if not o or o['tag'] != 10 or other == old_ref:
                continue
            if old and o.get('nat') == old.get('nat') and o.get('class') == old.get('class'):
                continue
            struct.pack_into('>H', b, e['start'] + 2, other)   # tag(1)+kind(1) 之后是 reference_index
            with open(path, 'wb') as fh:
                fh.write(b)
            return 'BootstrapMethods 引导句柄 MethodHandle#%d: Methodref#%d -> #%d' % (h, old_ref, other)
    raise SystemExit('找不到可重定向的引导句柄')


def inject_remove_jar_entry(jar, entry):
    tmp = jar + '.tmp'
    removed = False
    with zipfile.ZipFile(jar) as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            if info.filename == entry:
                removed = True
                continue
            zout.writestr(info, zin.read(info.filename))
    if not removed:
        os.remove(tmp)
        raise SystemExit('产物 jar 里没有条目 %s' % entry)
    os.replace(tmp, jar)
    return '删除 jar 条目 %s' % entry


def pick_resource_entry(jar):
    with zipfile.ZipFile(jar) as z:
        names = [n for n in sorted(z.namelist()) if not n.endswith('/') and not n.endswith('.class')
                 and n != 'META-INF/MANIFEST.MF']
    for n in names:
        if n.endswith('.json') and 'lang' in n:
            return n
    if names:
        return names[0]
    raise SystemExit('产物 jar 里找不到资源条目')


def backup(path, tag):
    os.makedirs(BACKUP_DIR, exist_ok=True)
    dst = os.path.join(BACKUP_DIR, tag + '__' + os.path.basename(path))
    shutil.copy2(path, dst)
    return dst


def run_verify(jar, classes, built_jar, report, log, focus):
    cmd = [sys.executable, os.path.join(TOOLS, 'verify_src.py'),
           '--jar', jar, '--classes', classes, '--report', report]
    if built_jar:
        cmd += ['--built-jar', built_jar]
    if focus:
        cmd += ['--focus', focus]
    with open(log, 'w', encoding='utf-8') as fh:
        proc = subprocess.run(cmd, stdout=fh, stderr=subprocess.STDOUT)
    return proc.returncode


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--jar', default=DEFAULT_JAR)
    ap.add_argument('--classes', default=DEFAULT_CLASSES)
    ap.add_argument('--built-jar', default=None)
    args = ap.parse_args()

    jar = os.path.abspath(args.jar)
    classes = os.path.abspath(args.classes)
    built_jar = os.path.abspath(args.built_jar) if args.built_jar else None
    if built_jar is None:
        libs = os.path.join(PROJ, 'build', 'libs')
        if os.path.isdir(libs):
            jars = [os.path.join(libs, f) for f in os.listdir(libs) if f.endswith('.jar')]
            built_jar = max(jars, key=os.path.getmtime) if jars else None

    target_class = find_class_for_injection(classes)
    boot_class, _h = find_bootstrap_target(classes)
    lambda_class = find_lambda_target(classes)
    # 已登记有意分歧清单（verify_src.INTENTIONAL_FIX_DIFFS）里的类：用来证明「逐条放行」
    # 不等于「按类放行」—— 在这个类内部制造一条清单之外的新差异，门禁必须照样报错。
    allowlisted_class = os.path.join(
        classes, 'com', 'zhongbai233', 'net_music_can_play_bili',
        'client', 'renderer', 'video', 'IrisShaderpackProperties.class')
    # 阶段 0 登记为「有意新增」的类：用来证明新增类白名单只放行「这个类存在」这一条，
    # 它**内部**的成员差异仍然必须判失败。
    new_class_target = os.path.join(
        classes, 'com', 'zhongbai233', 'net_music_can_play_bili',
        'client', 'media', 'MediaSourceClassifier.class')
    os.makedirs(FAULTS_DIR, exist_ok=True)

    def simple(p):
        return os.path.basename(p)[:-len('.class')] if p else '<未找到>'

    print('=' * 78)
    print('故障注入 —— 证明 verify_src.py 的每一项都会说「不」')
    print('  基线 jar : %s' % jar)
    print('  classes  : %s' % classes)
    print('  产物 jar : %s' % (built_jar or '<未找到>'))
    print('  注入靶子 : %s' % os.path.relpath(target_class, classes))
    print('  ③d 靶子  : %s' % (os.path.relpath(boot_class, classes) if boot_class else '<未找到>'))
    print('  合成靶子 : %s' % (os.path.relpath(lambda_class, classes) if lambda_class else '<未找到>'))
    print('  清单内靶子: %s' % (os.path.relpath(allowlisted_class, classes)
                              if os.path.isfile(allowlisted_class) else '<未找到>'))
    print('  新增类靶子: %s' % (os.path.relpath(new_class_target, classes)
                              if os.path.isfile(new_class_target) else '<未找到>'))
    print('=' * 78)

    groups = [
        ('A', 'member_signatures', target_class, inject_clear_public, simple(target_class),
         '清掉某个类的 ACC_PUBLIC'),
        ('B', 'resource_bytes', built_jar, None, None, '删掉产物 jar 里的一个资源条目'),
        ('C', 'member_signatures', target_class, inject_descriptor, simple(target_class),
         '把方法描述符 ()V 改成 ()I'),
        ('D', 'top_level_classes', target_class, None, simple(target_class), '删掉一个顶层类'),
        ('E', 'bootstrap_methods', boot_class, inject_bootstrap_handle, simple(boot_class),
         '重定向 invokedynamic 的引导方法句柄'),
        ('F', 'member_signatures', lambda_class, inject_lambda_return_type, simple(lambda_class),
         '改合成 lambda 的返回类型 V -> I'),
        ('G', 'member_signatures', allowlisted_class, inject_descriptor, simple(allowlisted_class),
         '在「已登记有意分歧」的类内部制造清单外新差异'),
        ('H', 'member_signatures', new_class_target, inject_descriptor, simple(new_class_target),
         '在「有意新增」的类内部制造成员差异'),
    ]

    results = []
    for tag, expect, artifact, injector, focus, desc in groups:
        if artifact is None or not os.path.isfile(artifact):
            print('[注入%s] ⚠️ 跳过：目标不存在（%s）' % (tag, desc))
            results.append((tag, False, '目标不存在'))
            continue
        orig = sha256_file(artifact)
        bak = backup(artifact, tag)
        report = os.path.join(FAULTS_DIR, 'fault_%s.json' % tag)
        log = os.path.join(FAULTS_DIR, 'fault_%s.log' % tag)

        if tag == 'B':
            entry = pick_resource_entry(artifact)
            detail = inject_remove_jar_entry(artifact, entry)
            focus = os.path.basename(entry)
        elif tag == 'D':
            os.remove(artifact)
            detail = '删除 %s' % os.path.relpath(artifact, classes)
        else:
            detail = injector(artifact)

        injected = sha256_file(artifact) if os.path.isfile(artifact) else '<已删除>'
        rc = run_verify(jar, classes, built_jar, report, log, focus)
        after = sha256_file(artifact) if os.path.isfile(artifact) else '<已删除>'

        first_diff = ''
        try:
            with open(report, encoding='utf-8') as fh:
                rep = json.load(fh)
            expected_failed = rep['checks'][expect]['status'] == 'fail'
            for k, v in rep['checks'].items():
                if v['status'] == 'fail' and v.get('diffs'):
                    first_diff = v['diffs'][0]
                    break
        except Exception as exc:
            expected_failed = False
            first_diff = '报告读取失败: %s' % exc

        shutil.copy2(bak, artifact)
        restored = sha256_file(artifact)

        ok_rc = rc != 0
        ok_reason = expected_failed
        ok_readonly = (after == injected)
        ok_restored = (restored == orig)
        ok = ok_rc and ok_reason and ok_readonly and ok_restored
        results.append((tag, ok, detail))

        print('[注入%s] %s' % (tag, desc))
        print('        注入: %s' % detail)
        print('        rc=%d（应为非 0）%s   ' % (rc, '✅' if ok_rc else '❌')
              + '期望 %s 失败: %s   ' % (expect, '✅' if ok_reason else '❌')
              + 'verify 只读: %s   ' % ('✅' if ok_readonly else '❌')
              + '已还原: %s' % ('✅' if ok_restored else '❌'))
        if first_diff:
            print('        命中注入点的差异: %s' % first_diff.splitlines()[0][:110])
        print('        完整输出: %s' % log)

    bad = [t for t, ok, _ in results if not ok]
    print('=' * 78)
    if bad:
        print('❌ %d/%d 组不符合预期（%s），详见 %s' % (len(bad), len(results), ', '.join(bad), FAULTS_DIR))
    else:
        print('✅ %d/%d 组故障注入全部按预期失败（四项检查都会说「不」）' % (len(results), len(results)))
    return len(bad)


if __name__ == '__main__':
    sys.exit(main())
