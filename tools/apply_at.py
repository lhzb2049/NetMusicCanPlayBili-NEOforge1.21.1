# -*- coding: utf-8 -*-
"""离线应用 NeoForge 的 access transformer（AT），给 javac 造一份"打过 AT 的类路径"。

为什么必须要它：真实构建（Gradle + MDG/NeoGradle）在编译前会把 NeoForge 自带的
`META-INF/accesstransformer.cfg` 应用到 Minecraft/NeoForge 类上，很多原版 `protected` 成员
在那份"AT 后的 jar"里已经变 public。本机没有 Gradle、没有网络，所以我在这里自己应用一遍；
否则会看到一堆**假**编译错误，例如：

    ShaderStateShard has protected access in RenderStateShard
    NO_CULL has protected access in RenderStateShard

对应的 AT 规则就在 `neoforge-<ver>-universal.jar!META-INF/accesstransformer.cfg` 里
（本项目实测该文件有 519 条规则，其中一条正是 `public ...RenderStateShard *`）。

做法：只改 class 文件里的 u2 访问标志位，**不重新序列化**（字节长度不变，所以可以原地补丁），
把补丁后的 class 释放到 `out-srccompile/ated/`，编译时把它排在类路径最前面。

用法：
    python tools/apply_at.py                # 应用 AT
    python tools/apply_at.py --fault=noat   # 故意不应用（编译必须重新报 RenderStateShard 错）
退出码：0 = 成功；非 0 = 失败（应用不上的规则数超阈值或环境缺失）。
"""
import argparse
import os
import re
import shutil
import struct
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(PROJ, 'out-srccompile')
ATED = os.path.join(OUT, 'ated')
INSTANCE = '1.21.1-NeoForge_21.1.252'
CJK = ''.join(chr(c) for c in (0x822A, 0x7A7A, 0x5B66))
MC = os.environ.get('NCPB_MC_DIR') or ('D:\\' + CJK + '\\.minecraft')

ap = argparse.ArgumentParser()
ap.add_argument('--mc', default=MC)
ap.add_argument('--fault', default=None)
args = ap.parse_args()
MC = args.mc

ACC_PUBLIC, ACC_PRIVATE, ACC_PROTECTED, ACC_FINAL = 0x0001, 0x0002, 0x0004, 0x0010
ACCESS_BITS = ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED


# ------------------------------------------------------------------ 最小 class 解析
def parse_cp(b):
    """返回 (常量池列表, 常量池结束偏移)。条目只在需要时解析出名字。"""
    assert b[:4] == b'\xca\xfe\xba\xbe', 'not a class file'
    i = 8
    count = struct.unpack('>H', b[i:i + 2])[0]
    i += 2
    cp = [None] * count
    k = 1
    while k < count:
        tag = b[i]
        i += 1
        if tag == 1:                      # Utf8
            ln = struct.unpack('>H', b[i:i + 2])[0]
            cp[k] = (1, b[i + 2:i + 2 + ln].decode('utf-8', 'replace'))
            i += 2 + ln
        elif tag in (7, 8, 16, 19, 20):   # Class/String/MethodType/Module/Package
            cp[k] = (tag, struct.unpack('>H', b[i:i + 2])[0])
            i += 2
        elif tag == 15:                   # MethodHandle
            cp[k] = (tag,)
            i += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            cp[k] = (tag,)
            i += 4
        elif tag in (5, 6):               # Long/Double 占两个槽
            cp[k] = (tag,)
            i += 8
            k += 1
        else:
            raise ValueError('unknown constant pool tag %d' % tag)
        k += 1
    return cp, i


def utf8(cp, idx):
    """Class 条目（tag=7）-> 内部名。"""
    e = cp[idx]
    return cp[e[1]][1] if e and e[0] == 7 else None


def utf8_name(cp, idx):
    """Utf8 条目（tag=1）-> 字符串。属性名用的是这个（踩过：拿 utf8() 读属性名永远返回 None）。"""
    e = cp[idx]
    return e[1] if e and e[0] == 1 else None


def skip_attributes(b, i):
    n = struct.unpack('>H', b[i:i + 2])[0]
    i += 2
    for _ in range(n):
        ln = struct.unpack('>I', b[i + 2:i + 6])[0]
        i += 6 + ln
    return i


def member_flags(b, cp, i):
    """列出 (访问标志偏移, 名字, 描述符)。"""
    out = []
    count = struct.unpack('>H', b[i:i + 2])[0]
    i += 2
    for _ in range(count):
        flags_off = i
        name = utf8(cp, struct.unpack('>H', b[i + 2:i + 4])[0])
        desc = utf8(cp, struct.unpack('>H', b[i + 4:i + 6])[0])
        out.append((flags_off, name, desc))
        i += 6
        i = skip_attributes(b, i)
    return out, i


def patch_inner_classes(b, cp, wanted, rules_by_name):
    """同步 InnerClasses 属性里的嵌套类访问标志。

    为什么必须做：javac 判断 `Outer.Inner` 能不能从别的包访问时，读的是**外层类**的
    InnerClasses 表（不是 Inner 自己的头标志）。只改 Inner 自己的 flags，javac 依然会报
    "ShaderStateShard has protected access in RenderStateShard"。AT 的官方实现同样会同步这张表。
    wanted: 需要改的嵌套类内部名集合；rules_by_name: 名字 -> (bits, final_add, final_del)
    """
    changed = 0
    # 重新定位：常量池 -> class 头 -> 接口 -> 字段 -> 方法 -> 属性
    j = 8
    count = struct.unpack('>H', b[j:j + 2])[0]
    j += 2
    k = 1
    while k < count:
        tag = b[j]
        j += 1
        if tag == 1:
            ln = struct.unpack('>H', b[j:j + 2])[0]
            j += 2 + ln
        elif tag in (7, 8, 16, 19, 20):
            j += 2
        elif tag == 15:
            j += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            j += 4
        elif tag in (5, 6):
            j += 8
            k += 1
        k += 1
    j += 6                                  # access_flags, this_class, super_class
    ifc = struct.unpack('>H', b[j:j + 2])[0]
    j += 2 + 2 * ifc
    for _ in range(2):                      # fields, methods
        cnt = struct.unpack('>H', b[j:j + 2])[0]
        j += 2
        for _ in range(cnt):
            j += 6
            j = skip_attributes(b, j)
    acount = struct.unpack('>H', b[j:j + 2])[0]
    j += 2
    for _ in range(acount):
        name = utf8_name(cp, struct.unpack('>H', b[j:j + 2])[0])
        ln = struct.unpack('>I', b[j + 2:j + 6])[0]
        body = j + 6
        if name == 'InnerClasses':
            n = struct.unpack('>H', b[body:body + 2])[0]
            pos = body + 2
            for _ in range(n):
                inner_idx = struct.unpack('>H', b[pos:pos + 2])[0]
                inner_name = utf8(cp, inner_idx)
                if inner_name in wanted:
                    bits, fa, fd = rules_by_name[inner_name]
                    cur = struct.unpack('>H', b[pos + 6:pos + 8])[0]
                    new = (cur & ~ACCESS_BITS) | bits
                    if fa:
                        new |= ACC_FINAL
                    if fd:
                        new &= ~ACC_FINAL
                    if new != cur:
                        b[pos + 6:pos + 8] = struct.pack('>H', new)
                        changed += 1
                pos += 8
        j = body + ln
    return changed


# ------------------------------------------------------------------ AT 解析
def parse_at(text):
    """解析 AT 文本 -> [(access, class, member 或 None)]，member 形如 ('field', name) / ('method', name, desc) / ('*',)"""
    rules = []
    for raw in text.splitlines():
        line = raw.split('#')[0].strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        access, cls = parts[0], parts[1]
        member = None
        if len(parts) >= 3 and parts[2] != '*':
            spec = parts[2]
            if '(' in spec:
                m = re.match(r'^(.+?)(\(.*)$', spec)
                member = ('method', m.group(1), m.group(2))
            else:
                member = ('field', spec)
        elif len(parts) >= 3 and parts[2] == '*':
            member = ('*',)
        rules.append((access, cls, member))
    return rules


def access_bits(token):
    tok = token
    final_add = final_del = False
    if tok.endswith('-f'):
        final_del = True
        tok = tok[:-2]
    elif tok.endswith('+f'):
        final_add = True
        tok = tok[:-2]
    tok = tok.replace('-f', '').replace('+f', '')
    bits = {'public': ACC_PUBLIC, 'protected': ACC_PROTECTED, 'private': ACC_PRIVATE, 'default': 0}.get(tok)
    if bits is None:
        return None, False, False
    return bits, final_add, final_del


# ------------------------------------------------------------------ 主流程
print('=' * 84)
print('离线应用 NeoForge access transformer')
print('=' * 84)
NF_DIR = os.path.join(MC, 'libraries', 'net', 'neoforged', 'neoforge', INSTANCE.split('NeoForge_')[-1])
NFU = os.path.join(NF_DIR, 'neoforge-%s-universal.jar' % INSTANCE.split('NeoForge_')[-1])
if not os.path.isfile(NFU):
    raise SystemExit('找不到 %s' % NFU)
with zipfile.ZipFile(NFU) as z:
    if 'META-INF/accesstransformer.cfg' not in z.namelist():
        raise SystemExit('%s 里没有 META-INF/accesstransformer.cfg' % NFU)
    at_text = z.read('META-INF/accesstransformer.cfg').decode('utf-8', 'replace')
rules = parse_at(at_text)
print('[1] AT 规则 %d 条（来自 %s）' % (len(rules), os.path.basename(NFU)))
bad_tokens = [r[0] for r in rules if access_bits(r[0])[0] is None]
if bad_tokens:
    print('    警告：无法识别的 access 记法 %s' % sorted(set(bad_tokens))[:8])

# 搜索类所在的 jar：只扫描会含 net.minecraft.* 的那几个，避免全盘扫描
srg_dir = os.path.join(MC, 'libraries', 'net', 'minecraft', 'client')
srg = None
for d in sorted(os.listdir(srg_dir), reverse=True):
    c = os.path.join(srg_dir, d, 'client-%s-srg.jar' % d)
    if os.path.isfile(c):
        srg = c
        break
NF_DIR2 = os.path.join(MC, 'libraries', 'net', 'neoforged', 'neoforge', INSTANCE.split('NeoForge_')[-1])
# ⚠️ 顺序至关重要：ATED 会排在编译类路径最前面，所以里面每一份字节都必须是**优先级最高**的那个来源。
# 踩过：用 srg（未打补丁的原版）生成 ATED 时，它把 neoforge-client 里 NeoForge 补丁过的原版类
# （`BlockGetter.getCapability` / `CreativeModeTab.builder()` 这类扩展方法）整片遮住，
# 编译立刻报 "cannot find symbol: method getCapability"、"builder 不能应用于给定类型"。
# 正确优先级与 build_mixins.py 的 CP 一致：neoforge-client > neoforge-universal > srg。
SOURCES = [p for p in [os.path.join(NF_DIR2, 'neoforge-%s-client.jar' % INSTANCE.split('NeoForge_')[-1]),
                       NFU, srg]
           if p and os.path.isfile(p)]
print('[2] 待改写类所在的 jar: %s' % ', '.join(os.path.basename(p) for p in SOURCES))

shutil.rmtree(ATED, ignore_errors=True)
os.makedirs(ATED, exist_ok=True)
if args.fault == 'noat':
    print('[FAULT] 故意不应用 AT（编译必须重新报 RenderStateShard protected 错误）')
    print('\n结果：故障模式，未写出任何 AT 产物')
    sys.exit(0)

patched_classes = 0
patched_members = 0
matched_members = 0
patched_names = set()
rules_by_name = {}
missing_classes = []
unmatched_members = []
found_member_classes = set()
# ⚠️ 关键：**同一个类的所有规则必须作用在同一份字节上**。
# 踩过：原来每条规则都 `z.read(entry)` 重新读一遍，于是 `...RenderStateShard *` 补好的字段，
# 被后面 `...RenderStateShard setupGlintTexturing(F)V` 那条用原始字节重写回 protected
# （症状：AT 跑完 javap 看字段还是 protected，编译照旧报 protected access）。
buffers = {}      # entry -> bytearray（累积所有规则）
dirty = set()     # entry 需要落盘
for p in SOURCES:
    with zipfile.ZipFile(p) as z:
        names = set(z.namelist())
        for access, cls, member in rules:
            entry = cls.replace('.', '/') + '.class'
            if entry not in names:
                continue
            bits, final_add, final_del = access_bits(access)
            if bits is None:
                continue
            if entry not in buffers:
                buffers[entry] = bytearray(z.read(entry))
            b = buffers[entry]
            cp, flags_off = parse_cp(b)          # access_flags 紧跟常量池
            changed = [False]

            def bump(off, kind):
                cur = struct.unpack('>H', b[off:off + 2])[0]
                new = (cur & ~ACCESS_BITS) | bits
                if final_add:
                    new |= ACC_FINAL
                if final_del:
                    new &= ~ACC_FINAL
                if new != cur:
                    b[off:off + 2] = struct.pack('>H', new)
                    changed[0] = True

            if member is None or member[0] == '*':
                # 类级规则：类本身 + 它的全部字段与方法一起改
                bump(flags_off, 'class')
                j = flags_off + 6
                ifc = struct.unpack('>H', b[j:j + 2])[0]
                j += 2 + 2 * ifc
                for _section in ('field', 'method'):
                    cnt = struct.unpack('>H', b[j:j + 2])[0]
                    j += 2
                    for _ in range(cnt):
                        bump(j, _section)
                        j += 6
                        j = skip_attributes(b, j)
            else:
                # 逐成员规则：字段按名字，方法按 名字+描述符
                j = flags_off + 6
                ifc = struct.unpack('>H', b[j:j + 2])[0]
                j += 2 + 2 * ifc
                matched = False
                for _ in range(2):                 # fields 然后 methods
                    cnt = struct.unpack('>H', b[j:j + 2])[0]
                    j += 2
                    for _ in range(cnt):
                        off = j
                        # 字段/方法名与描述符都是 **Utf8** 条目（用 utf8_name，不是 utf8！）
                        name = utf8_name(cp, struct.unpack('>H', b[j + 2:j + 4])[0])
                        desc = utf8_name(cp, struct.unpack('>H', b[j + 4:j + 6])[0])
                        if member[0] == 'field' and name == member[1] and not desc.startswith('('):
                            bump(off, 'field')
                            matched = True
                        elif member[0] == 'method' and name == member[1] and desc == member[2]:
                            bump(off, 'method')
                            matched = True
                        j += 6
                        j = skip_attributes(b, j)
                if matched:
                    matched_members += 1
                else:
                    unmatched_members.append((cls, member))
            if changed[0]:
                dirty.add(entry)
            # ⚠️ 只要规则**命中**了类就要登记（不管头标志是否真的变了），而且这一句必须在
            # `if changed[0]:` **之外**：有些嵌套类的头标志本来就是 public，而外层类 InnerClasses
            # 表里却是 protected —— 那种情况 changed=False，但若不登记，第二趟就不会同步那张表，
            # javac 依旧报 "X has protected access in Y"。
            # （本工具在这上面连踩两次：先是漏登记，后来又把这段误缩进进 if changed[0]: 里，
            #   结果 TexturingStateShard 一直没被登记，编译始终剩 2 条 protected access 错误。）
            if member is None or member[0] == '*':
                patched_names.add(cls.replace('.', '/'))
                rules_by_name[cls.replace('.', '/')] = (bits, final_add, final_del)
            else:
                found_member_classes.add(cls.replace('.', '/'))

# 统一落盘（累积了所有规则的补丁）
for entry in sorted(dirty):
    dst = os.path.join(ATED, *entry.split('/'))
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, 'wb') as fh:
        fh.write(bytes(buffers[entry]))
    patched_classes += 1

# 规则命中情况报告：没命中的规则要能看见（否则"AT 应用成功"只是自我感觉）
why = {}
for access, cls, member in rules:
    entry = cls.replace('.', '/') + '.class'
    hit = False
    for p in SOURCES:
        with zipfile.ZipFile(p) as z:
            if entry in z.namelist():
                hit = True
                break
    if not hit:
        why.setdefault('类在三个 jar 里都找不到', []).append(cls)
print('[3a] 规则命中报告：类级/通配 %d 个类已登记；成员级命中 %d 条；成员级未命中 %d 条；类找不到 %d 条'
      % (len(patched_names), matched_members, len(unmatched_members), len(why.get('类在三个 jar 里都找不到', []))))
for cls, member in unmatched_members[:6]:
    print('     未命中成员规则: %s %s' % (cls, member))
for cls in why.get('类在三个 jar 里都找不到', [])[:6]:
    print('     类找不到: %s' % cls)

# 第二趟：把嵌套类的访问标志同步进**所有提到它的 class 文件**的 InnerClasses 表。
# 为什么不能只改外层类：javac 判断 `Outer.Inner` 的可访问性时，用的是**它加载到的那份 class 里**
# 的 InnerClasses 表；嵌套类自己的文件、以及任何引用它的类里都可能有这张表。
# 踩过：只改外层，`TexturingStateShard` 依旧报 protected（那个条目出现在别的文件里）。
inner_fixed = 0
inner_files = 0
wanted_bytes = [n.encode('ascii') for n in patched_names]
seen_entries = set()
for p in SOURCES:
    with zipfile.ZipFile(p) as z:
        for entry in z.namelist():
            if not entry.endswith('.class') or entry in seen_entries:
                continue
            raw = z.read(entry)
            if not any(wb in raw for wb in wanted_bytes):
                continue
            seen_entries.add(entry)
            local = os.path.join(ATED, *entry.split('/'))
            b = bytearray(open(local, 'rb').read()) if os.path.isfile(local) else bytearray(raw)
            cp, _ = parse_cp(b)
            n = patch_inner_classes(b, cp, set(patched_names), rules_by_name)
            if n:
                os.makedirs(os.path.dirname(local), exist_ok=True)
                with open(local, 'wb') as fh:
                    fh.write(bytes(b))
                inner_fixed += n
                inner_files += 1
print('[3b] InnerClasses 同步：改了 %d 个条目，涉及 %d 个 class 文件' % (inner_fixed, inner_files))
if os.environ.get('NCPB_AT_DEBUG'):
    probe = 'net/minecraft/client/renderer/RenderStateShard$TexturingStateShard'
    print('    [DBG] %s 在 patched_names 里: %s' % (probe, probe in patched_names))
    print('    [DBG] 扫描过的候选文件: %s' % sorted(seen_entries)[:6])
    print('    [DBG] patched_names 大小=%d 样例=%s' % (len(patched_names), sorted(patched_names)[:3]))
    print('    [DBG] Offset 版在集合里: %s'
          % ('net/minecraft/client/renderer/RenderStateShard$OffsetTexturingStateShard' in patched_names))
    print('    [DBG] Shader 版在集合里: %s'
          % ('net/minecraft/client/renderer/RenderStateShard$ShaderStateShard' in patched_names))
    for _a, _c, _m in rules:
        if 'TexturingStateShard' in _c:
            _e = _c.replace('.', '/') + '.class'
            _found = []
            for _p in SOURCES:
                with zipfile.ZipFile(_p) as _z:
                    if _e in _z.namelist():
                        _found.append(os.path.basename(_p)[:20])
            print('    [DBG] 规则 cls=%s member=%s access=%s 在 jar 里找到=%s'
                  % (_c, _m, _a, _found))
if inner_fixed == 0:
    print('  [FAIL] 一条 InnerClasses 都没同步 —— RenderStateShard$* 的跨包访问错误不会被解决')
    sys.exit(4)

print('[3] 已写出补丁类 %d 个 -> %s' % (patched_classes, ATED))
if patched_classes < 20:
    print('  [FAIL] 只补丁了 %d 个类，明显不对（RendererStateShard 等规则应该匹配到几十个）'
          % patched_classes)
    sys.exit(2)
# 自检：RenderStateShard 必须真的被改（这是本次要解决的编译错误的直接依据）
rs = os.path.join(ATED, 'net', 'minecraft', 'client', 'renderer', 'RenderStateShard.class')
inner = os.path.join(ATED, 'net', 'minecraft', 'client', 'renderer', 'RenderStateShard$ShaderStateShard.class')
print('[4] 自检')
for label, p in (('RenderStateShard', rs), ('RenderStateShard$ShaderStateShard', inner)):
    if os.path.isfile(p):
        b = open(p, 'rb').read()
        cp, i = parse_cp(b)
        flags = struct.unpack('>H', b[i:i + 2])[0]
        print('  [ ok ] %s 已补丁（class flags=0x%04X public=%s）' % (label, flags, bool(flags & ACC_PUBLIC)))
    else:
        print('  [FAIL] %s 没有被补丁 —— 那 13 个 RenderStateShard 编译错误不会被解决' % label)
        sys.exit(3)
print('\n结果：AT 应用完成（补齐 %d 个类）' % patched_classes)
