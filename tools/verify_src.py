# -*- coding: utf-8 -*-
"""验收③ 四项比对门禁：把「源码工程编译产物」与基线 jar 逐项对齐。

  (a) 顶层类清单        —— 两个 javap dump 里解析出的类名取双向差集，必须为空
  (b) 每类成员签名      —— 「名 + 描述符 + 访问标志」逐类比对
                            对 ACC_SYNTHETIC 成员额外放宽：其**参数顺序与擦除类型**不计入
                            判定（它们是 javac 生成 lambda 体的产物，源码层面无法控制，
                            运行时也不可观测），但仍比对存在性/名称/标志/返回类型。
                            放宽掉的差异不隐藏，记在 informational_diffs 里。
  (c) 资源清单与字节    —— 两个 jar 的所有非 class 条目（跳过 MANIFEST.MF）逐一 SHA256
  (d) invokedynamic 调用点 —— 逐类比对 BootstrapMethods 的「引导方法句柄 + 参数清单」

用法：
    python tools/verify_src.py --jar <基线jar> --classes <编译产物classes目录> --report <报告.json>

可选参数：
    --built-jar <jar>   (c) 用的「产物 jar」；默认自动找 build/libs/*.jar
    --classes 也可传一个 .jar
    --focus <子串>      差异清单按「是否命中该子串」重排，命中的排最前（故障注入用）

设计约定：
  * javap 输出不打印到控制台，重定向到 out-srccompile/verify_src/{baseline,built}.javap.txt；
    每侧按 200 个类名分批追加（736+ 个全限定类名总长会超过 Windows 32 KB 命令行上限）
  * javap 用 `-p -s`（-p 带私有成员，-s 打描述符）；不用 -c，字节码对签名比对没有信息量
  * (b)(d) 的判定数据来自**原始 class 字节**（自己解析常量池/字段/方法/BootstrapMethods），
    不依赖 javap 的渲染：javap 的声明行会因缺 LocalVariableTable 而省略匿名类构造器的
    合成参数，用它当比较值会凭空多出假差异（已实测踩过）
  * 退出码：0 = 全部通过；非 0 = 失败项数
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_JAR = os.path.join(os.path.dirname(PROJ), 'net_music_can_play_bili-0.7.9-beta+neo1.21.jar')
DEFAULT_CLASSES = os.path.join(PROJ, 'out-srccompile', 'classes')
DEFAULT_REPORT = os.path.join(PROJ, 'out-srccompile', 'verify_src', 'report.json')
VERIFY_DIR = os.path.join(PROJ, 'out-srccompile', 'verify_src')
BATCH = 200
MAX_PRINT = 20

ACC_SYNTHETIC = 0x1000

# 唯一一处已知且已解释的差异（合并为一条）：
# jar 里 VideoBillboardState 是包私有，但它的 12 个 public 嵌套类被 4 个以上其它包引用，
# 源码里写不出来，只能声明为 public。类变 public 后其构造器也随之为 public —— 这是同一条
# 放宽的直接后果，不是第二处独立差异。详见 docs/HANDOVER-NCPB-1.21.1-src.md §5.1
WHITELIST_CLASS_ACCESS = {
    'com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardState': {
        'drop_mods': ['public'],
        'label': 'VideoBillboardState#ACC_PUBLIC（类级，以及随之为 public 的构造器）',
    },
}

# ----------------------------------------------------------------- 常量池解析


def u1(b, i):
    return b[i]


def u2(b, i):
    return struct.unpack('>H', b[i:i + 2])[0]


def u4(b, i):
    return struct.unpack('>I', b[i:i + 4])[0]


def parse_cp(b):
    """完整解析常量池，返回 (cp, 常量池结束偏移)。"""
    i = 8
    count = u2(b, i)
    i += 2
    cp = [None] * count
    k = 1
    while k < count:
        tag = b[i]
        i += 1
        e = {'tag': tag}
        if tag == 1:
            ln = u2(b, i)
            i += 2
            e['s'] = b[i:i + ln].decode('utf-8', 'replace')
            i += ln
        elif tag == 3:
            e['value'] = struct.unpack('>i', b[i:i + 4])[0]; i += 4
        elif tag == 4:
            e['value'] = struct.unpack('>f', b[i:i + 4])[0]; i += 4
        elif tag == 5:
            e['value'] = struct.unpack('>q', b[i:i + 8])[0]; i += 8
        elif tag == 6:
            e['value'] = struct.unpack('>d', b[i:i + 8])[0]; i += 8
        elif tag in (7, 8, 16, 19, 20):
            e['idx'] = u2(b, i); i += 2
        elif tag in (9, 10, 11):
            e['class'] = u2(b, i); e['nat'] = u2(b, i + 2); i += 4
        elif tag == 12:
            e['name'] = u2(b, i); e['desc'] = u2(b, i + 2); i += 4
        elif tag == 15:
            e['kind'] = b[i]; e['idx'] = u2(b, i + 1); i += 3
        elif tag in (17, 18):
            e['bsm'] = u2(b, i); e['nat'] = u2(b, i + 2); i += 4
        else:
            raise ValueError('未知常量池 tag %d' % tag)
        cp[k] = e
        if tag in (5, 6):
            k += 1
        k += 1
    return cp, i


def return_type(desc):
    """从方法描述符里取返回类型部分。"""
    i = desc.index(')') + 1
    return desc[i:]


def cp_render(cp, idx, synthetics):
    """把常量池条目渲染成可比较的字符串；synthetics 是本类的合成方法名集合。"""
    if idx == 0 or idx >= len(cp) or cp[idx] is None:
        return '?'
    e = cp[idx]
    t = e['tag']
    if t == 1:
        return e['s']
    if t == 7:
        return cp_render(cp, e['idx'], synthetics)
    if t == 8:
        return '"%s"' % cp_render(cp, e['idx'], synthetics)
    if t == 16:
        return 'MT:' + cp_render(cp, e['idx'], synthetics)
    if t == 15:
        return 'MH:%d:%s' % (e['kind'], cp_render(cp, e['idx'], synthetics))
    if t in (9, 10, 11):
        cls = cp_render(cp, e['class'], synthetics)
        nat = cp[e['nat']]
        name = cp_render(cp, nat['name'], synthetics)
        desc = cp_render(cp, nat['desc'], synthetics)
        if t == 10 and name in synthetics:
            # 合成 lambda 体：忽略参数顺序与擦除类型，只保留返回类型
            desc = '(...)' + return_type(desc)
        return '%s.%s:%s' % (cls, name, desc)
    if t == 12:
        return '%s:%s' % (cp_render(cp, e['name'], synthetics), cp_render(cp, e['desc'], synthetics))
    if t == 3:
        return 'int:%d' % e['value']
    if t in (4, 5, 6):
        return 'num:%s' % e['value']
    if t in (17, 18):
        return 'indy:%s' % cp_render(cp, e['nat'], synthetics)
    if t in (19, 20):
        return 'pkg/mod:%s' % cp_render(cp, e['idx'], synthetics)
    return 'tag%d' % t


def parse_class(data):
    """解析一个 class 文件，返回 {name, access, members, bootstrap}。"""
    cp, i = parse_cp(data)
    access = u2(data, i); i += 2
    this_i = u2(data, i); i += 2
    u2(data, i); i += 2  # super
    name = cp_render(cp, this_i, frozenset())

    ic = u2(data, i); i += 2 + 2 * ic
    members = []

    def read_members(count, kind):
        nonlocal i
        for _ in range(count):
            flags = u2(data, i)
            mname = cp_render(cp, u2(data, i + 2), frozenset())
            mdesc = cp_render(cp, u2(data, i + 4), frozenset())
            ac = u2(data, i + 6)
            i += 8
            for _a in range(ac):
                alen = u4(data, i + 2)
                i += 6 + alen
            members.append({'kind': kind, 'flags': flags, 'name': mname, 'desc': mdesc})

    fc = u2(data, i); i += 2
    read_members(fc, 'f')
    mc = u2(data, i); i += 2
    read_members(mc, 'm')

    synthetics = frozenset(m['name'] for m in members
                           if m['kind'] == 'm' and m['flags'] & ACC_SYNTHETIC)

    bootstrap = []
    ac = u2(data, i); i += 2
    for _ in range(ac):
        aname = cp_render(cp, u2(data, i), synthetics)
        alen = u4(data, i + 2)
        info = i + 6
        if aname == 'BootstrapMethods':
            n = u2(data, info)
            p = info + 2
            for _b in range(n):
                bsm = u2(data, p)
                nargs = u2(data, p + 2)
                p += 4
                args = [u2(data, p + 2 * x) for x in range(nargs)]
                p += 2 * nargs
                rendered = [cp_render(cp, a, synthetics) for a in args]
                # LambdaMetafactory 的第三个参数是 instantiated method type：当实现方法是合成
                # lambda 时它就是该 lambda 参数列表的擦除，与合成成员同规则归一化
                if len(rendered) == 3 and ':(...)' in rendered[1] and rendered[2].startswith('MT:'):
                    d = rendered[2][3:]
                    if ')' in d:
                        rendered[2] = 'MT:(...)' + return_type(d)
                bootstrap.append('%s(%s)' % (cp_render(cp, bsm, synthetics), ', '.join(rendered)))
        i += 6 + alen
    return {'name': name, 'access': access, 'members': members, 'bootstrap': bootstrap}


# ----------------------------------------------------------------- 采集


def find_javap():
    exe = shutil.which('javap')
    if exe:
        return exe
    root = os.environ.get('JAVA_HOME')
    if root:
        for cand in (os.path.join(root, 'bin', 'javap.exe'), os.path.join(root, 'bin', 'javap')):
            if os.path.isfile(cand):
                return cand
    raise SystemExit('找不到 javap：请把 JDK 的 bin 放进 PATH，或设置 JAVA_HOME')


def class_names_from_jar(path):
    with zipfile.ZipFile(path) as z:
        return sorted(n[:-len('.class')].replace('/', '.') for n in z.namelist() if n.endswith('.class'))


def class_names_from_dir(path):
    names = []
    for root, _d, files in os.walk(path):
        for f in files:
            if f.endswith('.class'):
                names.append(os.path.relpath(os.path.join(root, f), path)
                             [:-len('.class')].replace(os.sep, '/').replace('/', '.'))
    return sorted(names)


def read_classes(path):
    """返回 {二进制类名: class 字节}。path 可以是 jar 或 classes 目录。"""
    out = {}
    if os.path.isdir(path):
        for root, _d, files in os.walk(path):
            for f in files:
                if f.endswith('.class'):
                    full = os.path.join(root, f)
                    rel = os.path.relpath(full, path)[:-len('.class')].replace(os.sep, '.').replace('/', '.')
                    with open(full, 'rb') as fh:
                        out[rel] = fh.read()
    else:
        with zipfile.ZipFile(path) as z:
            for n in z.namelist():
                if n.endswith('.class'):
                    out[n[:-len('.class')].replace('/', '.')] = z.read(n)
    return out


def dump_javap(javap, classpath, names, out_path):
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    if os.path.exists(out_path):
        os.remove(out_path)
    errs = []
    with open(out_path, 'w', encoding='utf-8', newline='\n') as fh:
        for i in range(0, len(names), BATCH):
            cmd = [javap, '-p', '-s', '-J-Duser.language=en', '-J-Dstdout.encoding=UTF-8',
                   '-cp', classpath] + names[i:i + BATCH]
            proc = subprocess.run(cmd, capture_output=True)
            fh.write(proc.stdout.decode('utf-8', errors='replace'))
            fh.write('\n')
            err = proc.stderr.decode('utf-8', errors='replace').strip()
            if err:
                errs.append(err.splitlines()[0])
    return errs


CLASS_RE = re.compile(r'^(?P<mods>(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed|strictfp)\s+)*)'
                      r'(?P<kind>@interface|interface|class|enum|record)\s+(?P<name>[\w.$]+)')


def class_names_from_dump(path):
    names = set()
    with open(path, encoding='utf-8', errors='replace') as fh:
        for line in fh:
            m = CLASS_RE.match(line.rstrip('\n'))
            if m and not line.startswith(' '):
                names.add(m.group('name'))
    return names


# ----------------------------------------------------------------- 四项检查


def diff_sort(diffs, focus):
    if not focus:
        return diffs
    f = focus.lower()
    return [d for d in diffs if f in d.lower()] + [d for d in diffs if f not in d.lower()]


def check_top_level(names_base, names_built, focus):
    bt = sorted(n for n in names_base if '$' not in n)
    ut = sorted(n for n in names_built if '$' not in n)
    diffs = ['只在基线 jar: ' + n for n in sorted(set(bt) - set(ut))]
    diffs += ['只在产物: ' + n for n in sorted(set(ut) - set(bt))]
    return {'status': 'pass' if not diffs else 'fail',
            'baseline_count': len(bt), 'built_count': len(ut), 'diffs': diff_sort(diffs, focus)}


def member_key(m, mask=0):
    flags = m['flags'] & ~mask
    if m['kind'] == 'm' and m['flags'] & ACC_SYNTHETIC:
        # 合成方法：忽略参数顺序与擦除类型，只保留返回类型
        return 'm|%04x|%s|ret:%s' % (flags, m['name'], return_type(m['desc']))
    return '%s|%04x|%s|%s' % (m['kind'], flags, m['name'], m['desc'])


def member_key_strict(m, mask=0):
    return '%s|%04x|%s|%s' % (m['kind'], m['flags'] & ~mask, m['name'], m['desc'])


def check_members(base, built, focus):
    diffs, info, hits, new_class_notes = [], [], [], []
    names = sorted(set(base) | set(built))
    for n in names:
        b, u = base.get(n), built.get(n)
        if b is None:
            # 只存在于产物的类：没有基线可比，改用 new_class_manifest.json 逐条校验成员
            if not is_intentional_new_class(n):
                diffs.append('%s: 只在编译产物里存在' % n); continue
            recorded = new_class_manifest().get(n)
            got = sorted(member_key(m, 0) for m in u['members'])
            if recorded is None:
                diffs.append('%s: 有意新增的类没有登记到 tools/new_class_manifest.json' % n)
            elif sorted(recorded) != got:
                diffs.append('%s: 有意新增类的成员与 new_class_manifest.json 不一致\n'
                             '        清单=[%s]\n        产物=[%s]'
                             % (n, ', '.join(sorted(recorded)), ', '.join(got)))
            else:
                new_class_notes.append('%s: 有意新增类，%d 个成员与清单一致' % (n, len(got)))
            continue
        if u is None:
            diffs.append('%s: 只在基线 jar 里存在' % n); continue

        # 类级访问标志（白名单类先按规则取模再比）
        ba, ua = b['access'], u['access']
        wl = WHITELIST_CLASS_ACCESS.get(n)
        mask = 0
        if wl:
            for drop in wl['drop_mods']:
                bit = {'public': 0x0001, 'protected': 0x0004, 'private': 0x0002}.get(drop, 0)
                ba &= ~bit
                ua &= ~bit
                # 同一处放宽也会体现在成员级（类变 public 后构造器随之为 public），
                # 所以同一个掩码同时作用于成员标志位
                mask |= bit
            if b['access'] != u['access']:
                hits.append(wl['label'])
        if ba != ua:
            diffs.append('%s: 类级访问标志 基线=0x%04x 产物=0x%04x' % (n, b['access'], u['access']))

        bmap = {member_key(m, mask): (m, member_key_strict(m, mask)) for m in b['members']}
        umap = {member_key(m, mask): (m, member_key_strict(m, mask)) for m in u['members']}
        for k in sorted(set(bmap) | set(umap)):
            if k not in umap:
                diffs.append('%s: 缺少成员 %s' % (n, bmap[k][1]))
            elif k not in bmap:
                diffs.append('%s: 多出成员 %s' % (n, umap[k][1]))
            elif bmap[k][1] != umap[k][1]:
                # 键相同但严格描述符不同 —— 只可能是合成方法的参数顺序/擦除，
                # 按规则不计入判定，但要如实记下来
                info.append('%s: %s\n        基线=[%s]\n        产物=[%s]' % (
                    n, k, bmap[k][1], umap[k][1]))
    top_b = len([n for n in base if '$' not in n])
    top_u = len([n for n in built if '$' not in n])
    # 已登记的有意分歧**逐条**放行：不计入失败，但完整列出并计数（见 INTENTIONAL_FIX_DIFFS）
    intentional = [d for d in diffs if d in INTENTIONAL_FIX_DIFFS]
    kept = [d for d in diffs if d not in INTENTIONAL_FIX_DIFFS]
    unmatched = sorted(INTENTIONAL_FIX_DIFFS - set(diffs))
    return {'status': 'pass' if not kept else 'fail',
            'baseline_count': top_b, 'built_count': top_u,
            'classes_compared': len(names),
            'diffs': diff_sort(kept, focus),
            'intentional_diffs': diff_sort(intentional, focus),
            'intentional_classes': sorted({d.split(':', 1)[0] for d in intentional}),
            'intentional_unmatched': unmatched,
            'new_class_manifest_hits': new_class_notes,
            'whitelist_hits': sorted(set(hits)),
            'informational_diffs': info,
            'informational_note': 'ACC_SYNTHETIC 成员（javac 生成的 lambda 体）的参数顺序/擦除类型差异，'
                                  '按规则不计入判定；保留在此供审计'}


RFBB_TYPE = 'Lnet/minecraft/network/RegistryFriendlyByteBuf;'

# 已解释的环境差异（不计入判定，但如实记录、可审计）：
# 基线 jar 的编译环境里 MenuType$MenuSupplier.create 是三参 (int, Inventory, RegistryFriendlyByteBuf)，
# 而本机 NeoForge 21.1.252 是两参 (int, Inventory) —— 离线 nf-client 与 MDG 的 merged 两份类路径
# 实测完全一致。ModMenus 用的是构造器引用（MediaToolBindingMenu::new / MediaToolReportMenu::new），
# javac 按目标接口挑构造器，于是基线指向三参构造器、产物指向两参构造器。两边源码相同；
# 产物与 21.1.252 自洽，硬改成三参反而会在 21.1.252 上运行失败。
EXPLAINED_ENV_CLASSES = {'com.zhongbai233.net_music_can_play_bili.init.ModMenus'}
EXPLAINED_ENV_NOTE = ('MenuType$MenuSupplier 元数差异：基线 jar 编译环境为三参 '
                      '(int, Inventory, RegistryFriendlyByteBuf)，本机 NeoForge 21.1.252 为两参 '
                      '(int, Inventory)；构造器引用按目标接口选构造器，故基线指向三参构造器、'
                      '产物指向两参构造器。两边源码相同，产物与 21.1.252 一致。')

# ---------------------------------------------------------------- 已登记的有意分歧
# main 分支的契约是「源码与原 jar 逐成员一致」。修复分支上被改动的类必然出现成员差异，
# 因此按「类 → 原因」逐条登记：命中清单的差异不计入失败，但会在报告里**完整列出并计数**
# （不隐藏），并且报告里同时写明本清单只解释了这些类，清单之外任何一条差异仍然判失败。
# 当前清单对应 fix/iris-yuv-and-local-media 的 Patch A + Patch B。
INTENTIONAL_FIX_CLASSES = {
    'com.zhongbai233.net_music_can_play_bili.client.renderer.video.IrisShaderpackCompat':
        'Patch A：移除 isForceYuvShaderEnabled()，shouldApplyIrisYuvCompatibility() 改为'
        '「光影生效且正在使用自定义 YUV 着色器」，shouldDisableCustomYuvShader() 改读三态策略，'
        '状态变化日志追加 yuvMode 字段',
    'com.zhongbai233.net_music_can_play_bili.client.renderer.video.IrisShaderpackProperties':
        'Patch A：新增 ncpb.video.iris.yuv_mode（auto/always/never，默认 auto）与 YUV_MODE* 常量、'
        'yuvMode()/explicitCustomYuvShaderDisabled()/customYuvShaderDisabledWhen()/explicitBoolean()，'
        '移除旧的 forceYuvShaderEnabled() 与 customYuvShaderDisabled() 两个布尔访问器',
}
INTENTIONAL_FIX_NOTE = ('fix/iris-yuv-and-local-media 的 Patch A（光影下默认让位给 NV12→RGBA 回退）'
                        '与 Patch B（IRIS 警告占位图只在真走 YUV 通路时提交、且不再向镜头方向前压）')
# 同一补丁里**只改了方法体/字面量**的类（成员签名不变，故 ③b 看不到差异，列此备案）：
INTENTIONAL_FIX_BODY_ONLY = {
    'com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoPlaybackPresentation':
        'Patch B：shouldShowIrisWarning() 追加 isCustomYuvShaderAvailable() 前置条件',
    'com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoPipelineProperties':
        'Patch B：iris_warning_placeholder_view_depth_offset 默认值 0.03 → 0.0',
}

# 有意分歧是**逐条**登记的，不是按类放行：清单命中的这一条差异不计入失败，
# 但同一批类里任何**其它**差异（例如后续在 IrisShaderpackProperties 上多删/多加一个成员，
# 或者手误把成员访问标志改掉）依然会判失败。改这几个类就必须同步更新本清单 —— 这是刻意的。
_IFC = 'com.zhongbai233.net_music_can_play_bili.client.renderer.video.IrisShaderpackCompat'
_IFP = 'com.zhongbai233.net_music_can_play_bili.client.renderer.video.IrisShaderpackProperties'
INTENTIONAL_FIX_DIFFS = {
    _IFC + ': 缺少成员 m|0008|isForceYuvShaderEnabled|()Z',
    _IFP + ': 缺少成员 m|0008|forceYuvShaderEnabled|()Z',
    _IFP + ': 缺少成员 m|0008|customYuvShaderDisabled|()Z',
    _IFP + ': 多出成员 f|0018|YUV_MODE|Ljava/lang/String;',
    _IFP + ': 多出成员 f|0018|YUV_MODE_AUTO|Ljava/lang/String;',
    _IFP + ': 多出成员 f|0018|YUV_MODE_ALWAYS|Ljava/lang/String;',
    _IFP + ': 多出成员 f|0018|YUV_MODE_NEVER|Ljava/lang/String;',
    _IFP + ': 多出成员 m|0008|yuvMode|()Ljava/lang/String;',
    _IFP + ': 多出成员 m|0008|explicitCustomYuvShaderDisabled|()Ljava/lang/Boolean;',
    _IFP + ': 多出成员 m|0008|customYuvShaderDisabledWhen|(Z)Z',
    _IFP + ': 多出成员 m|000a|explicitBoolean|(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Boolean;',
}

# --- 阶段 0（本地媒体源识别层）新增的类：③a/③b 会报「只在产物里存在」，按类登记 ---
INTENTIONAL_FIX_NEW_CLASSES = {
    'com.zhongbai233.net_music_can_play_bili.client.media.SourceKind': '阶段 0：媒体源种类枚举',
    'com.zhongbai233.net_music_can_play_bili.client.media.MediaSourceClassifier':
        '阶段 0：媒体源识别（http / 本地视频 / 本地图片 / 不认识）+ 5 秒 TTL 记忆化',
    'com.zhongbai233.net_music_can_play_bili.client.media.LocalMediaProperties':
        '阶段 0：本地媒体开关、白名单根目录、大小上限',
    'com.zhongbai233.net_music_can_play_bili.client.media.LocalMediaPolicy':
        '阶段 0：UNC / 保留设备名 / 真实路径（含目录联接逃逸）/ 大小校验',
    'com.zhongbai233.net_music_can_play_bili.client.media.MediaLogThrottle':
        '阶段 0：每帧路径的日志去重闸门',
}
# --- 阶段 0 的接线只改方法体，但新增字符串拼接 → 新增 indy（makeConcatWithConstants）调用点 ---
# ③d 按「类 → (基线条数, 产物条数)」登记：条数对不上仍然判失败（比按类放行更强）。
INTENTIONAL_FIX_INDY = {
    'com.zhongbai233.net_music_can_play_bili.client.ModernTurntableVideoClient':
        ((32, 33), '阶段 0：日志去重键 "turntable-video-source|" + rawUrl'),
    'com.zhongbai233.net_music_can_play_bili.client.audio.ModernTurntablePlaybackCoordinator':
        ((34, 35), '阶段 0：日志去重键 "turntable-audio-non-http|" + playUrl'),
    'com.zhongbai233.net_music_can_play_bili.client.audio.SyncedMediaPlaybackLauncher':
        ((2, 3), '阶段 0：日志去重键 "audio-non-http|" + playUrl'),
}

_INDY_COUNT_RE = re.compile(r'引导方法条数 基线=(\d+) 产物=(\d+)')
_TOP_LEVEL_NEW_PREFIX = '只在产物: '

# --- 新增类的「成员基线」清单（tools/new_class_manifest.json）---
# 为什么需要它：对「只存在于产物」的新类，③b 没有基线可比，内部成员怎么改都看不出来
# （实测过：往新类里改一个方法描述符，门禁毫无反应）。所以把每个登记新增类的成员键集合
# 固化进清单，产物必须与清单逐条一致；不一致、或登记了却没进清单，都判失败。
NEW_CLASS_MANIFEST_PATH = os.path.join(PROJ, 'tools', 'new_class_manifest.json')
_NEW_CLASS_MANIFEST_CACHE = [None]


def load_new_class_manifest():
    if not os.path.isfile(NEW_CLASS_MANIFEST_PATH):
        return {}
    with open(NEW_CLASS_MANIFEST_PATH, encoding='utf-8') as fh:
        return json.load(fh)


def new_class_manifest():
    if _NEW_CLASS_MANIFEST_CACHE[0] is None:
        _NEW_CLASS_MANIFEST_CACHE[0] = load_new_class_manifest()
    return _NEW_CLASS_MANIFEST_CACHE[0]


def write_new_class_manifest(built):
    """按当前编译产物重新生成新增类成员清单（人工确认后提交）。"""
    manifest = {}
    for name in sorted(INTENTIONAL_FIX_NEW_CLASSES):
        entry = built.get(name)
        if entry is None:
            print('❌ 产物里找不到登记的新类: %s' % name)
            return 2
        manifest[name] = sorted(member_key(m, 0) for m in entry['members'])
    for name in sorted(n for n in built if is_intentional_new_class(n) and n not in manifest):
        manifest[name] = sorted(member_key(m, 0) for m in built[name]['members'])
    with open(NEW_CLASS_MANIFEST_PATH, 'w', encoding='utf-8') as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=1, sort_keys=True)
        fh.write('\n')
    print('✅ 已写出 %s（%d 个类）' % (NEW_CLASS_MANIFEST_PATH, len(manifest)))
    return 0


def diff_subject(check_key, text):
    """取一条差异"说的是哪个类"。③a 的格式是「只在产物: <类>」，类名在冒号**之后**。"""
    if check_key == 'top_level_classes' and text.startswith(_TOP_LEVEL_NEW_PREFIX):
        return text[len(_TOP_LEVEL_NEW_PREFIX):].strip()
    return text.split(':', 1)[0]


def is_intentional_new_class(name):
    if name in INTENTIONAL_FIX_NEW_CLASSES:
        return True
    return any(name.startswith(existing + '$') for existing in INTENTIONAL_FIX_NEW_CLASSES)


def is_intentional_diff(check_key, text, indy_totals):
    """判定一条差异是否属于「已登记的有意分歧」；三项检查粒度不同，这里集中处理。"""
    name = diff_subject(check_key, text)
    if check_key == 'member_signatures':
        # 新增类不在这里放行：它们的成员由 new_class_manifest.json 逐条校验（不一致即失败）
        return text in INTENTIONAL_FIX_DIFFS
    if check_key == 'top_level_classes':
        return text.startswith(_TOP_LEVEL_NEW_PREFIX) and is_intentional_new_class(name)
    if check_key == 'bootstrap_methods':
        entry = INTENTIONAL_FIX_INDY.get(name)
        if entry is None:
            return False
        # 条目差异行本身不带总数：只有该类的条数行确实等于登记值时才算有意分歧；
        # 这样「条数不变但条目被悄悄替换」仍然会判失败。
        return indy_totals.get(name) == entry[0]
    return False


def apply_intentional(checks):
    """把已登记的有意分歧从各检查的 diffs 移到 intentional_diffs，并按剩余差异重算状态。

    只放行登记过的条目；清单之外（含登记类内部的其它改动）照旧判失败。
    被移走的条目仍会完整打印并写进报告，不做隐藏。
    """
    for key, check in checks.items():
        diffs = check.get('diffs') or []
        if not diffs:
            continue
        indy_totals = {}
        for text in diffs:
            match = _INDY_COUNT_RE.search(text)
            if match:
                indy_totals[diff_subject(key, text)] = (int(match.group(1)), int(match.group(2)))
        kept, moved = [], []
        for text in diffs:
            (moved if is_intentional_diff(key, text, indy_totals) else kept).append(text)
        if moved:
            check['diffs'] = kept
            check['intentional_diffs'] = moved
            check['intentional_classes'] = sorted({diff_subject(key, t) for t in moved})
            check['status'] = 'pass' if not kept else 'fail'
    return checks


def _strip_rfbb(text):
    return text.replace(RFBB_TYPE, '')


def check_bootstrap(base, built, focus):
    """按「引导方法句柄 + 参数清单」逐类比对 BootstrapMethods。

    两点归一化，都只针对编译布局而非语义：
      * 表内顺序：表项与引用它的调用点在同一个类里一起被重排，按集合比对；
        顺序不同记入 informational_diffs 供审计，不计入判定。
      * 已解释的环境差异：仅当一条「基线独有且含 RegistryFriendlyByteBuf」的条目，
        与一条「把它里面的该参数类型整体删掉后完全相等」的产物独有条目成对出现时，
        才归入 informational_diffs（可自证的配对，不做宽泛豁免）。
    """
    from collections import Counter
    diffs, info, env = [], [], []
    nb = nu = 0
    for n in sorted(set(base) | set(built)):
        b, u = base.get(n), built.get(n)
        if b is None or u is None:
            continue
        lb, lu = b['bootstrap'], u['bootstrap']
        nb += len(lb)
        nu += len(lu)
        if lb == lu:
            continue
        if sorted(lb) == sorted(lu):
            info.append('%s: BootstrapMethods 顺序不同（%d 条，集合一致）' % (n, len(lb)))
            continue

        cb, cu = Counter(lb), Counter(lu)
        explained = set()
        if n in EXPLAINED_ENV_CLASSES:
            prod_by_norm = {}
            for k in cu:
                if cu[k] > cb[k]:
                    prod_by_norm.setdefault(_strip_rfbb(k), []).append(k)
            for k in cb:
                if cb[k] > cu[k] and RFBB_TYPE in k:
                    for cand in prod_by_norm.get(_strip_rfbb(k), []):
                        explained.add(k)
                        explained.add(cand)
                        env.append('%s: %s' % (n, EXPLAINED_ENV_NOTE))
        for k in sorted(set(cb) | set(cu)):
            if cb[k] == cu[k]:
                continue
            if k in explained:
                continue
            diffs.append('%s: BootstrapMethods 条目差异  基线×%d / 产物×%d\n        条目=[%s]'
                         % (n, cb[k], cu[k], k))
        if len(lb) != len(lu):
            diffs.append('%s: 引导方法条数 基线=%d 产物=%d' % (n, len(lb), len(lu)))
    info.extend(env)
    return {'status': 'pass' if not diffs else 'fail',
            'baseline_count': nb, 'built_count': nu,
            'classes_with_bootstrap': len([n for n in base if base[n]['bootstrap']]),
            'diffs': diff_sort(diffs, focus),
            'explained_env_diffs': env,
            'informational_diffs': info,
            'informational_note': '表内顺序差异（集合一致）+ 已解释的环境差异，均不计入判定'}


def read_resource_hashes(path):
    out = {}
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            if info.is_dir() or info.filename.endswith('.class'):
                continue
            if info.filename == 'META-INF/MANIFEST.MF':
                continue
            out[info.filename] = hashlib.sha256(z.read(info.filename)).hexdigest()
    return out


def check_resources(base_jar, built_jar, focus):
    if not built_jar or not os.path.isfile(built_jar):
        return {'status': 'skipped',
                'reason': '没有找到产物 jar（--built-jar 未给，且 build/libs/*.jar 不存在）',
                'baseline_count': len(read_resource_hashes(base_jar)), 'built_count': 0, 'diffs': []}
    a, b = read_resource_hashes(base_jar), read_resource_hashes(built_jar)
    diffs = []
    for k in sorted(set(a) | set(b)):
        if k not in b:
            diffs.append('产物缺失: %s' % k)
        elif k not in a:
            diffs.append('产物多出: %s' % k)
        elif a[k] != b[k]:
            diffs.append('字节不一致: %s' % k)
    return {'status': 'pass' if not diffs else 'fail',
            'baseline_count': len(a), 'built_count': len(b), 'diffs': diff_sort(diffs, focus)}


def autodetect_built_jar():
    libs = os.path.join(PROJ, 'build', 'libs')
    if not os.path.isdir(libs):
        return None
    jars = [os.path.join(libs, f) for f in os.listdir(libs)
            if f.endswith('.jar') and not f.endswith('-sources.jar')]
    return max(jars, key=os.path.getmtime) if jars else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--jar', default=DEFAULT_JAR)
    ap.add_argument('--classes', default=DEFAULT_CLASSES)
    ap.add_argument('--built-jar', default=None)
    ap.add_argument('--report', default=DEFAULT_REPORT)
    ap.add_argument('--focus', default=None, help='差异清单优先展示命中该子串的条目')
    ap.add_argument('--write-new-class-manifest', action='store_true',
                    help='按当前编译产物重新生成 tools/new_class_manifest.json（人工确认后提交）')
    args = ap.parse_args()

    jar = os.path.abspath(args.jar)
    classes = os.path.abspath(args.classes)
    if not os.path.isfile(jar):
        raise SystemExit('基线 jar 不存在: %s' % jar)
    if not os.path.exists(classes):
        raise SystemExit('编译产物不存在: %s' % classes)
    built_jar = os.path.abspath(args.built_jar) if args.built_jar else autodetect_built_jar()

    javap = find_javap()
    os.makedirs(VERIFY_DIR, exist_ok=True)

    base_bytes = read_classes(jar)
    built_bytes = read_classes(classes)
    if not base_bytes:
        raise SystemExit('基线 jar 里没有 class')

    base_dump = os.path.join(VERIFY_DIR, 'baseline.javap.txt')
    built_dump = os.path.join(VERIFY_DIR, 'built.javap.txt')
    errs = dump_javap(javap, jar, sorted(base_bytes), base_dump)
    errs += dump_javap(javap, classes, sorted(built_bytes), built_dump)

    names_base = class_names_from_dump(base_dump)
    names_built = class_names_from_dump(built_dump)

    base = {n: parse_class(d) for n, d in base_bytes.items()}
    built = {n: parse_class(d) for n, d in built_bytes.items()}

    if args.write_new_class_manifest:
        return write_new_class_manifest(built)

    c_a = check_top_level(names_base, names_built, args.focus)
    c_b = check_members(base, built, args.focus)
    c_c = check_resources(jar, built_jar, args.focus)
    c_d = check_bootstrap(base, built, args.focus)

    checks = {'top_level_classes': c_a, 'member_signatures': c_b,
              'resource_bytes': c_c, 'bootstrap_methods': c_d}
    apply_intentional(checks)
    failed = [k for k, v in checks.items() if v['status'] == 'fail']
    skipped = [k for k, v in checks.items() if v['status'] == 'skipped']

    print('=' * 78)
    print('源码工程 vs 基线 jar —— 四项比对')
    print('  基线 jar   : %s (%d 个 class)' % (jar, len(base_bytes)))
    print('  编译产物   : %s (%d 个 class)' % (classes, len(built_bytes)))
    print('  产物 jar   : %s' % (built_jar or '<未找到，(c) 跳过>'))
    print('  javap dump : %s' % base_dump)
    print('               %s' % built_dump)
    print('=' * 78)

    mark = lambda c: {'pass': '✅', 'fail': '❌', 'skipped': '⚠️ 跳过'}[c['status']]
    print('[③a] 顶层类清单: %d vs %d, 双向差集 %d %s'
          % (c_a['baseline_count'], c_a['built_count'], len(c_a['diffs']), mark(c_a)))
    print('[③b] 成员签名  : %d vs %d（含内部类共 %d 类）, 差异 %d %s'
          % (c_b['baseline_count'], c_b['built_count'], c_b['classes_compared'],
             len(c_b['diffs']), mark(c_b)))
    if c_b['whitelist_hits']:
        print('      白名单命中（已知且已解释）: %s' % ', '.join(c_b['whitelist_hits']))
    if c_b['informational_diffs']:
        print('      不计入判定的合成成员差异: %d 条（见 JSON 的 informational_diffs）'
              % len(c_b['informational_diffs']))
    if c_b.get('new_class_manifest_hits'):
        print('      有意新增类（成员与 new_class_manifest.json 逐条一致）: %d 个'
              % len(c_b['new_class_manifest_hits']))
    print('[③c] 资源字节  : %d vs %d, 差异 %d %s'
          % (c_c['baseline_count'], c_c['built_count'], len(c_c['diffs']), mark(c_c)))
    print('[③d] indy 调用点: %d vs %d 条 BootstrapMethods, 差异 %d %s'
          % (c_d['baseline_count'], c_d['built_count'], len(c_d['diffs']), mark(c_d)))
    if c_d.get('explained_env_diffs'):
        print('      不计入判定的环境差异: %d 条（%s）'
              % (len(c_d['explained_env_diffs']), EXPLAINED_ENV_NOTE.split('：')[0]))
    if c_d.get('informational_diffs'):
        print('      不计入判定的表内顺序差异: %d 个类（见 JSON 的 informational_diffs）'
              % (len([x for x in c_d['informational_diffs'] if '顺序不同' in x])))

    intentional_total = sum(len(checks[k].get('intentional_diffs') or []) for k in checks)
    if intentional_total:
        print('      已登记的有意分歧: %d 条 —— %s' % (intentional_total, INTENTIONAL_FIX_NOTE))
        for key, label in (('top_level_classes', '③a'), ('member_signatures', '③b'),
                           ('bootstrap_methods', '③d')):
            entries = checks[key].get('intentional_diffs') or []
            if not entries:
                continue
            print('        [%s] %d 条 / %d 个类:'
                  % (label, len(entries), len(checks[key].get('intentional_classes', []))))
            for name in checks[key].get('intentional_classes', []):
                reason = INTENTIONAL_FIX_CLASSES.get(name) or INTENTIONAL_FIX_NEW_CLASSES.get(name)
                if reason is None:
                    outer = name.split('$', 1)[0]
                    reason = INTENTIONAL_FIX_CLASSES.get(outer) or INTENTIONAL_FIX_NEW_CLASSES.get(outer)
                if reason is None and name in INTENTIONAL_FIX_INDY:
                    reason = INTENTIONAL_FIX_INDY[name][1]
                print('           · %s ← %s' % (name.split('.')[-1], reason or '（未登记原因）'))
        print('        只改方法体/字面量、签名不变的备案类: %s'
              % ', '.join(sorted(k.split('.')[-1] for k in INTENTIONAL_FIX_BODY_ONLY)))

    for key, label in (('top_level_classes', '③a'), ('member_signatures', '③b'),
                       ('resource_bytes', '③c'), ('bootstrap_methods', '③d')):
        d = checks[key]['diffs']
        if d:
            print('\n--- %s 差异（最多打印 %d 条，完整差异见 JSON 报告）---' % (label, MAX_PRINT))
            for line in d[:MAX_PRINT]:
                print('   ' + line.replace('\n', '\n   '))
            if len(d) > MAX_PRINT:
                print('   … 还有 %d 条' % (len(d) - MAX_PRINT))

    for key, label in (('top_level_classes', '③a'), ('member_signatures', '③b'),
                       ('bootstrap_methods', '③d')):
        entries = checks[key].get('intentional_diffs') or []
        if entries:
            print('\n--- %s 已登记的有意分歧（清单之外任何差异仍判失败）---' % label)
            for line in entries[:MAX_PRINT]:
                print('   ' + line.replace('\n', '\n   '))
            if len(entries) > MAX_PRINT:
                print('   … 还有 %d 条' % (len(entries) - MAX_PRINT))

    if errs:
        print('\n（javap 有 stderr 输出，首行：%s）' % errs[0][:120])

    report = {'summary': {'passed': not failed, 'total_checks': 4,
                          'failed_checks': len(failed), 'skipped_checks': skipped,
                          'informational_diffs': (len(c_b['informational_diffs'])
                                                  + len(c_d.get('informational_diffs', []))),
                          'explained_env_diffs': len(c_d.get('explained_env_diffs', [])),
                          'intentional_fix_diffs': sum(len(checks[k].get('intentional_diffs') or []) for k in checks),
                          'intentional_fix_classes': sorted({name for k in checks
                                                             for name in (checks[k].get('intentional_classes') or [])}),
                          'intentional_fix_note': INTENTIONAL_FIX_NOTE,
                          'intentional_fix_body_only': sorted(INTENTIONAL_FIX_BODY_ONLY),
                          'baseline_jar': jar, 'built_classes': classes, 'built_jar': built_jar,
                          'javap_dumps': {'baseline': base_dump, 'built': built_dump}},
              'checks': checks}
    os.makedirs(os.path.dirname(os.path.abspath(args.report)), exist_ok=True)
    with open(args.report, 'w', encoding='utf-8') as fh:
        json.dump(report, fh, ensure_ascii=False, indent=1)

    print('=' * 78)
    if failed:
        print('❌ %d 项失败，详见 %s' % (len(failed), args.report))
    elif skipped:
        print('✅ 通过（%d 项跳过：%s）—— 报告 %s' % (len(skipped), ', '.join(skipped), args.report))
    else:
        print('✅ 全部通过 —— 报告 %s' % args.report)
    return len(failed)


if __name__ == '__main__':
    sys.exit(main())
