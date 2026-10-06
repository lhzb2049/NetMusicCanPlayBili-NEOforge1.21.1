# -*- coding: utf-8 -*-
"""阶段 2 离线验收：本地视频（progressive MP4 → 两条单轨 fMP4 + 回环 http 源）。

分层验收，每一层都能独立说「不」：
  1. **合成源**：用 tools/local_mp4.py 手工拼出真实形态的 progressive MP4
     （mdat 在前、moov 在尾、音视频块交织、带 stss/ctts/edts、音频 stts 两条目），
     以及一批**必须被拒绝**的变体（hvc1 / encv / 分片无样本表 / 截断 / 白名单外 / 超上限）；
  2. **产品自身解析器**：探针把产物喂给 Fmp4RangeSeekSupport / Fmp4ToMp4Converter /
     Fmp4StreamParser / AudioPipelineFactory / Fmp4VideoDecoderConfigParser —— 
     「原生解码器与音频管线能不能吃」这件事由产品自己的代码回答；
  3. **回环 http 源**：探针用产品的 ChunkPrefetchInputStream 整文件读回并比 sha256，
     再逐条验证 206 尾段裁剪 / 416 / 未知 token 404；
  4. **另一套实现**：tools/local_mp4.py 独立解析源文件与产物，逐样本比对字节、时长、
     CTS、tfdt 连续性、sidx 引用与 data_offset —— 与产品代码零共享；
  5. --selftest：翻转一条期望，证明上面这套比对真的会报失败。

用法：
    python tools/verify_local_video.py
    python tools/verify_local_video.py --selftest
    python tools/verify_local_video.py --flip-case A:prepared|durationMillis
    python tools/verify_local_video.py --realvideo "D:\\某个\\真实录制.mp4"   # 可选：拿真文件再跑一轮
退出码：0 = 全部通过；非 0 = 失败轮数/条数。
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

import local_mp4 as mp4

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(PROJ, 'out-srccompile')
CLASSES = os.path.join(OUT, 'classes')
PROBE_OUT = os.path.join(OUT, 'video_verify')
SANDBOX = os.path.join(OUT, 'local_video_sandbox')
SRC_DIR = os.path.join(SANDBOX, 'src')
BLOCKED = os.path.join(OUT, 'local_video_blocked')
JAVA_BIN = os.path.join(os.environ.get('APPDATA', ''), '.minecraft', 'runtime', 'java-runtime-delta', 'bin')
JAVAC = os.path.join(JAVA_BIN, 'javac.exe')
JAVA = os.path.join(JAVA_BIN, 'java.exe')
TOOLS_JAVA = os.path.join(PROJ, 'tools', 'java')
PROBE_CLASS = 'com.zhongbai233.net_music_can_play_bili.client.media.VerifyLocalVideoProbe'
INSTANCE = '1.21.1-NeoForge_21.1.252'

MAIN_SRC = os.path.join(SRC_DIR, 'main.mp4')
VIDEO_ONLY = os.path.join(SRC_DIR, 'video-only.mp4')
AUDIO_ONLY = os.path.join(SRC_DIR, 'audio-only.mp4')
HEVC_SRC = os.path.join(SRC_DIR, 'hevc.mp4')
ENC_SRC = os.path.join(SRC_DIR, 'encrypted.mp4')
FRAG_SRC = os.path.join(SRC_DIR, 'fragmented.mp4')
TRUNC_SRC = os.path.join(SRC_DIR, 'truncated.mp4')
BLOCKED_SRC = os.path.join(BLOCKED, 'blocked.mp4')


# ----------------------------------------------------------------- 类路径 / 探针

def mc_classpath():
    """与 tools/build_src.py 完全一致的口径：只信启动日志里的真实类路径 + FML 挂载的三支 jar。"""
    cjk = ''.join(chr(c) for c in (0x822A, 0x7A7A, 0x5B66))
    mc = os.environ.get('NCPB_MC_DIR') or ('D:\\' + cjk + '\\.minecraft')
    mods = os.path.join(mc, 'versions', INSTANCE, 'mods')
    dbg = os.path.join(mc, 'versions', INSTANCE, 'logs', 'debug.log')
    launch = []
    if os.path.isfile(dbg):
        with open(dbg, encoding='utf-8', errors='replace') as fh:
            for line in fh:
                if 'Located paths when launch context was created' in line:
                    mm = re.search(r'\[(.*)\]', line)
                    if mm:
                        launch = [x.strip() for x in mm.group(1).split(',') if x.strip()]
                    break
    launch = [x for x in launch if os.path.isfile(x)]
    version = INSTANCE.split('NeoForge_')[-1]
    nf_dir = os.path.join(mc, 'libraries', 'net', 'neoforged', 'neoforge', version)
    srg = None
    srg_dir = os.path.join(mc, 'libraries', 'net', 'minecraft', 'client')
    if os.path.isdir(srg_dir):
        for d in sorted(os.listdir(srg_dir), reverse=True):
            cand = os.path.join(srg_dir, d, 'client-%s-srg.jar' % d)
            if os.path.isfile(cand):
                srg = cand
                break
    cp = [os.path.join(OUT, 'ated'),
          os.path.join(nf_dir, 'neoforge-%s-client.jar' % version),
          os.path.join(nf_dir, 'neoforge-%s-universal.jar' % version), srg] + launch
    if os.path.isdir(mods):
        # 排除本模组自己：实例里装的那份 jar 会把「刚编译出来的 classes」整类盖掉
        # （实测：滑动续期那一版被旧 jar 影子掉，探针跑的是旧实现，白白失败一轮）。
        cp += [os.path.join(mods, f) for f in sorted(os.listdir(mods))
               if f.endswith('.jar') and not f.startswith('net_music_can_play_bili')]
    # 编译产物必须排在**最前面**：探针要验的是当前源码，而不是实例里的 jar。
    cp = [CLASSES] + cp
    return [p for p in cp if p and os.path.exists(p)]


def compile_probe(cp):
    if os.path.isdir(PROBE_OUT):
        shutil.rmtree(PROBE_OUT)
    os.makedirs(PROBE_OUT)
    sources = []
    for root, _dirs, files in os.walk(TOOLS_JAVA):
        sources += [os.path.join(root, f) for f in files if f.endswith('.java')
                    and ('VerifyLocalVideoProbe' in f or 'VerifyVideoConfigProbe' in f)]
    cmd = [JAVAC, '-encoding', 'UTF-8', '-nowarn', '-proc:none', '-cp', ';'.join(cp), '-d', PROBE_OUT] + sources
    result = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
    if result.returncode != 0:
        print('❌ 探针编译失败：')
        print(result.stdout)
        print(result.stderr)
        return False
    return True


def run_probe(cp, args, props=None):
    # log4j 的 contextSelector 必须显式改掉：classpath 上的 neoforge log4j2 配置默认用
    # modlauncher 的 MLClassLoaderContextSelector，而它依赖 FML 自己挂载的
    # cpw.mods.cl.ModuleClassLoader（不在启动日志的类路径里）。产品类里有 LogUtils.getLogger()，
    # 于是离线跑探针会在日志初始化那一步炸掉。换成 Basic 选择器即可绕开，与产品行为无关。
    cmd = [JAVA, '-Dlog4j2.contextSelector=org.apache.logging.log4j.core.selector.BasicContextSelector',
           '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
           '-Dncpb.test.gamedir=%s' % SANDBOX,
           '-cp', ';'.join(cp + [PROBE_OUT])]
    for key, value in (props or {}).items():
        cmd.append('-D%s=%s' % (key, value))
    cmd += [PROBE_CLASS] + args
    result = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
    parsed = {}
    for line in result.stdout.splitlines():
        if '|' not in line:
            continue
        parts = line.strip().split('|')
        parsed.setdefault(parts[0], []).append(parts[1:])
    parsed['__rc'] = [str(result.returncode)]
    parsed['__err'] = [(result.stderr or '')[-400:]]
    return parsed


def probe_lines(parsed, key):
    return ['|'.join(v) for v in parsed.get(key, [])]


# ----------------------------------------------------------------- 沙盒（合成源）

def write_file(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as fh:
        fh.write(data)


def build_sandbox():
    """造合成源与必须被拒绝的变体；返回主样例的事实（期望值由这里推导，而不是抄产品输出）。"""
    for path in (SRC_DIR, BLOCKED):
        if os.path.isdir(path):
            shutil.rmtree(path, ignore_errors=True)
    os.makedirs(SRC_DIR, exist_ok=True)
    os.makedirs(BLOCKED, exist_ok=True)

    # 视频：60 样本 @30000，每样本 1000（30fps），关键帧在第 1/16/31/46 个；带 ctts 与 edts
    video = []
    for i in range(60):
        key = i % 15 == 0
        if key:
            nal = b'\x65' + bytes([0xA0 + (i % 8)]) * 9
        else:
            nal = b'\x41' + bytes([0x10 + (i % 8)]) * 4
        video.append((mp4.u32(len(nal)) + nal, 1000))
    # 音频：94 样本 @48000，前 47 个 1024、后 47 个 1023（两条 stts 条目，逼出逐样本时长展开）
    audio = [(mp4.u32(0x21000000 | (i & 0xFF))[:4] + bytes([i & 0xFF]) * 8, 1024) for i in range(47)]
    audio += [(mp4.u32(0x21000000 | (i & 0xFF))[:4] + bytes([0x80 | (i & 0x7F)]) * 8, 1023) for i in range(47)]

    main_data, facts = mp4.build_progressive_mp4(
        video, audio, width=320, height=240,
        ctts_entries=[(20, 1000), (20, 0), (20, 1000)],
        stss=[1, 16, 31, 46],
        edit_list=[0],
    )
    write_file(MAIN_SRC, main_data)

    video_only, video_only_facts = mp4.build_progressive_mp4(video, [], width=320, height=240, stss=[1, 16, 31, 46])
    write_file(VIDEO_ONLY, video_only)

    audio_only, audio_only_facts = mp4.build_progressive_mp4([], audio)
    write_file(AUDIO_ONLY, audio_only)

    hevc, _f = mp4.build_progressive_mp4(video, [], width=320, height=240, video_codec='hvc1', stss=[1])
    write_file(HEVC_SRC, hevc)

    enc, _f = mp4.build_progressive_mp4(video, [], width=320, height=240, encrypted=True, stss=[1])
    write_file(ENC_SRC, enc)

    frag, _f = mp4.build_progressive_mp4(video, [], width=320, height=240, stss=[1], fragment=True)
    write_file(FRAG_SRC, frag)

    write_file(TRUNC_SRC, main_data[:len(main_data) - 2000])
    write_file(BLOCKED_SRC, main_data)

    facts['video_only'] = video_only_facts
    facts['audio_only'] = audio_only_facts
    facts['fragments_expected'] = 2        # 默认 1000ms 目标：60 样本 = 2s → 在样本 31 处切一刀
    facts['fragments_expected_200ms'] = 4  # 200ms 目标：关键帧 1/16/31/46 → 4 段
    return facts


def artifact_paths(work_dir):
    video = audio = None
    if os.path.isdir(work_dir):
        for name in sorted(os.listdir(work_dir)):
            if name.endswith('-v.mp4'):
                video = os.path.join(work_dir, name)
            elif name.endswith('-a.mp4'):
                audio = os.path.join(work_dir, name)
    return video, audio


# ----------------------------------------------------------------- 独立比对

def verify_artifacts(src_path, work_dir, errors, expected_video_frags):
    """用另一套实现核对产物：样本字节、时长、CTS、tfdt、sidx、data_offset、分片起点。"""
    video_path, audio_path = artifact_paths(work_dir)
    if video_path is None:
        errors.append('产物目录里没有 -v.mp4: %s' % work_dir)
        return

    src = open(src_path, 'rb').read()
    tracks = mp4.source_tracks(src)
    video_src = [t for t in tracks.values() if t['handler'] == 'vide'][0]
    audio_src = [t for t in tracks.values() if t['handler'] == 'soun']
    audio_src = audio_src[0] if audio_src else None

    vdata = open(video_path, 'rb').read()
    video = mp4.parse_fmp4(vdata, 'video')
    if video['codec'] != video_src['codec']:
        errors.append('video: 编码 %s != 源 %s' % (video['codec'], video_src['codec']))
    if video['config'] != 'avcC':
        errors.append('video: 缺 avcC（实际 %s）' % video['config'])
    _compare_track('video', video, video_src, src, errors)
    if expected_video_frags is not None and len(video['frags']) != expected_video_frags:
        errors.append('video: 分片数 %d != 期望 %d' % (len(video['frags']), expected_video_frags))

    if audio_src is not None:
        if audio_path is None:
            errors.append('源有音频轨但没有 -a.mp4 产物')
        else:
            adata = open(audio_path, 'rb').read()
            audio = mp4.parse_fmp4(adata, 'audio')
            if audio['config'] != 'esds':
                errors.append('audio: 缺 esds（实际 %s）' % audio['config'])
            _compare_track('audio', audio, audio_src, src, errors)


def _compare_track(label, parsed, src_track, src_bytes, errors):
    flat = [(dur, size, cts) for frag in parsed['frags'] for (dur, size, cts) in frag['samples']]
    if len(flat) != len(src_track['sizes']):
        errors.append('%s: 样本数 %d != 源 %d' % (label, len(flat), len(src_track['sizes'])))
        return
    for i, (dur, size, cts) in enumerate(flat):
        if size != src_track['sizes'][i]:
            errors.append('%s: 样本 %d 字节数 %d != 源 %d' % (label, i, size, src_track['sizes'][i]))
            return
        if dur != src_track['durations'][i]:
            errors.append('%s: 样本 %d 时长 %d != 源 %d' % (label, i, dur, src_track['durations'][i]))
            return
        expected_cts = src_track['cts'][i]
        if expected_cts and (cts or 0) != expected_cts:
            errors.append('%s: 样本 %d CTS %s != 源 %d' % (label, i, cts, expected_cts))
            return
    expected_base = 0
    for index, frag in enumerate(parsed['frags']):
        if frag['base'] != expected_base:
            errors.append('%s: 分片 %d tfdt=%d != %d' % (label, index, frag['base'], expected_base))
            return
        for dur, _size, _cts in frag['samples']:
            expected_base += dur
    if len(parsed['refs']) != len(parsed['frags']):
        errors.append('%s: sidx 条目 %d != 分片 %d' % (label, len(parsed['refs']), len(parsed['frags'])))
    else:
        for i, ((size, _dur, _sap), frag) in enumerate(zip(parsed['refs'], parsed['frags'])):
            if size != frag['moof_size'] + frag['mdat_size']:
                errors.append('%s: sidx[%d] 长度 %d != %d' % (label, i, size, frag['moof_size'] + frag['mdat_size']))
                break
        if parsed['earliest'] != 0 or parsed['first_offset'] != 0:
            errors.append('%s: sidx earliest=%d first_offset=%d 应为 0' % (label, parsed['earliest'], parsed['first_offset']))
        if parsed['sidx_ts'] != parsed['timescale']:
            errors.append('%s: sidx timescale %d != %d' % (label, parsed['sidx_ts'], parsed['timescale']))
    if sum(frag['mdat_size'] - 8 for frag in parsed['frags']) != sum(src_track['sizes']):
        errors.append('%s: mdat 总字节不符' % label)
    for index, frag in enumerate(parsed['frags']):
        if frag['data_offset'] != frag['moof_size'] + 8:
            errors.append('%s: 分片 %d data_offset=%s != %d' % (label, index, frag['data_offset'], frag['moof_size'] + 8))
            break
    out = mp4.fmp4_sample_bytes(parsed)
    src_samples = mp4.source_sample_bytes(src_bytes, src_track)
    if [mp4.sha256(b) for b in out] != [mp4.sha256(b) for b in src_samples]:
        errors.append('%s: 样本字节与源不一致' % label)
    if label == 'video':
        index = 0
        for i, frag in enumerate(parsed['frags']):
            if (index + 1) not in src_track['sync']:
                errors.append('video: 分片 %d 起点样本 %d 不是关键帧' % (i, index))
            if not parsed['refs'][i][2]:
                errors.append('video: sidx[%d] 未标 SAP' % i)
            index += len(frag['samples'])


# ----------------------------------------------------------------- 轮次

def rounds(facts):
    realvideo = os.environ.get('NCPB_REALVIDEO', '')
    table = [
        ('A', '合成源默认参数：重封装 + 产品解析器 + 回环 http', {}, [
            ('prepare', MAIN_SRC, facts),
            ('route', MAIN_SRC, facts, True),
            ('consumers', None, None),
            ('http', None, None),
        ]),
        ('B', '源大小超过上限（max_source_bytes=1024）', {'ncpb.local.video.max_source_bytes': '1024'}, [
            ('prepare_fail', MAIN_SRC, '超过重封装上限'),
        ]),
        ('C', '关掉音频重封装（audio=off）', {'ncpb.local.video.audio': 'off'}, [
            ('prepare', VIDEO_ONLY, facts['video_only']),
        ]),
        ('D', '总开关关闭（enabled=false）', {'ncpb.local.video.enabled': 'false'}, [
            ('prepare_fail', MAIN_SRC, '未启用'),
        ]),
        ('E', '白名单外的文件', {}, [
            ('prepare_fail', BLOCKED_SRC, '白名单'),
        ]),
        ('F', '不支持的视频编码 hvc1', {}, [
            ('prepare_fail', HEVC_SRC, '不支持的视频编码'),
        ]),
        ('G', '加密轨道 encv', {}, [
            ('prepare_fail', ENC_SRC, '已加密'),
        ]),
        ('H', '分片 MP4 没有样本表', {}, [
            ('prepare_fail', FRAG_SRC, 'stsz'),
        ]),
        ('I', '只有音频轨的 mp4', {}, [
            ('prepare_fail', AUDIO_ONLY, '没有视频轨'),
            ('route_none', AUDIO_ONLY, None),
        ]),
        ('J', '只有视频轨', {}, [
            ('prepare', VIDEO_ONLY, facts['video_only']),
        ]),
        ('K', '截断的文件', {}, [
            ('prepare_fail', TRUNC_SRC, ''),
        ]),
        ('L', '分片目标 200ms：切出更多片段', {'ncpb.local.video.fragment_ms': '200'}, [
            ('prepare', MAIN_SRC, facts),
        ]),
        ('M', 'token 过期与滑动续期（TTL=1200ms，请求间隔 400ms×5）', {}, [
            ('expiry', MAIN_SRC, None),
        ]),
    ]
    if realvideo and os.path.isfile(realvideo):
        table.append(('R', '真实录制文件：%s' % os.path.basename(realvideo), {}, [
            ('prepare', realvideo, 'skip'),
        ]))
    return table


def check_round(cp, tag, desc, props, actions, facts, flip_case, errors):
    work = os.path.join(SANDBOX, 'work', tag)
    if os.path.isdir(work):
        shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work, exist_ok=True)
    round_errors = []

    for action in actions:
        if action[0] == 'prepare':
            _source, facts_override = action[1], action[2]
            parsed = run_probe(cp, ['prepare', _source, SANDBOX, work], props)
            lines = probe_lines(parsed, 'prepare')
            if flip_case == '%s:prepare' % tag:
                lines = ['failed|翻转期望用的假结果']
            if not lines or not lines[0].startswith('ok'):
                round_errors.append('%s: prepare 未成功: %s %s' % (tag, lines, probe_lines(parsed, '__err') or parsed.get('__err')))
            else:
                expected_facts = None if facts_override == 'skip' else (facts_override or facts)
                if expected_facts:
                    prepared_lines = _flatten(parsed, 'prepared')
                    _expect_value(round_errors, tag, 'prepared|durationMillis', prepared_lines,
                                  expected_facts['duration_ms'], flip_case)
                    if expected_facts.get('video'):
                        _expect_value(round_errors, tag, 'prepared|width', prepared_lines,
                                      expected_facts['video']['width'], flip_case)
                        _expect_value(round_errors, tag, 'prepared|fps', prepared_lines, 30, flip_case)
                        _expect_value(round_errors, tag, 'prepared|codecId', prepared_lines, 7, flip_case)
                has_audio = any('hasAudio|true' in line for line in probe_lines(parsed, 'prepared'))
                _video_file, audio_file = artifact_paths(work)
                if has_audio != (audio_file is not None):
                    round_errors.append('%s: hasAudio=%s 与产物里的音频文件是否一致不符' % (tag, has_audio))
                if not any('127.0.0.1' in line for line in probe_lines(parsed, 'prepared')):
                    round_errors.append('%s: 回环地址不是 127.0.0.1: %s' % (tag, probe_lines(parsed, 'prepared')))
                if not any(line == 'prepare|cached|true' for line in _flatten(parsed, 'prepare')):
                    round_errors.append('%s: 第二次 prepare 没有命中缓存' % tag)
                expected_files = 2 if has_audio else 1
                if not any(line == 'prepare|fileCount|%d' % expected_files for line in _flatten(parsed, 'prepare')):
                    round_errors.append('%s: 产物文件数不是 %d: %s' % (tag, expected_files, probe_lines(parsed, 'prepare')))
                if props.get('ncpb.local.video.fragment_ms') == '200':
                    expected_frags = facts['fragments_expected_200ms']
                elif expected_facts:
                    expected_frags = facts['fragments_expected']
                else:
                    expected_frags = None
                verify_artifacts(_source, work, round_errors, expected_frags)

        elif action[0] == 'prepare_fail':
            _source, needle = action[1], action[2]
            parsed = run_probe(cp, ['prepare', _source, SANDBOX, work], props)
            lines = probe_lines(parsed, 'prepare')
            if not lines or not lines[0].startswith('failed'):
                round_errors.append('%s: 期望被拒绝，实际 %s' % (tag, lines))
            elif needle and needle not in lines[0]:
                round_errors.append('%s: 拒绝原因里没有 %r: %s' % (tag, needle, lines[0]))

        elif action[0] == 'route':
            _source = action[1]
            parsed = run_probe(cp, ['route', _source, _source, SANDBOX, work], props)
            if not any(line == 'route|handled|true' for line in _flatten(parsed, 'route')):
                round_errors.append('%s: 音频路由未接管: %s' % (tag, probe_lines(parsed, 'route')))
            if len(action) > 3 and action[3]:
                if not any(line == 'route|audioAvailable|true' for line in _flatten(parsed, 'route')):
                    round_errors.append('%s: 音频路由说没有可用音频: %s' % (tag, probe_lines(parsed, 'route')))
                if not any(line == 'route|urlHost|127.0.0.1' for line in _flatten(parsed, 'route')):
                    round_errors.append('%s: 音频地址不是回环地址: %s' % (tag, probe_lines(parsed, 'route')))

        elif action[0] == 'route_none':
            _source = action[1]
            parsed = run_probe(cp, ['route', _source, _source, SANDBOX, work], props)
            if not any(line == 'route|handled|true' for line in _flatten(parsed, 'route')):
                round_errors.append('%s: 音频路由未接管（应接管但报告无音频）' % tag)
            if not any(line == 'route|audioAvailable|false' for line in _flatten(parsed, 'route')):
                round_errors.append('%s: 期望 audioAvailable=false: %s' % (tag, probe_lines(parsed, 'route')))

        elif action[0] == 'consumers':
            video_path, audio_path = artifact_paths(work)
            if video_path is None:
                round_errors.append('%s: 没有可检查的产物（prepare 大概失败了）%s' % (tag, probe_lines({'': []}, '')))
                continue
            parsed = run_probe(cp, ['consumers', video_path, audio_path if audio_path else '-'], props)
            _check_consumers(round_errors, tag, parsed, facts)

        elif action[0] == 'http':
            video_path, _audio = artifact_paths(work)
            if video_path is None:
                round_errors.append('%s: 没有可检查的产物（prepare 大概失败了）' % tag)
                continue
            parsed = run_probe(cp, ['http', video_path, 'video/mp4'], props)
            _check_http(round_errors, tag, parsed, video_path)

        elif action[0] == 'expiry':
            parsed = run_probe(cp, ['expiry', action[1], 'video/mp4', '1200'], props)
            flat = _flatten(parsed, 'expiry')
            if not any(x == 'expiry|fresh|206' for x in flat):
                round_errors.append('%s: 刚发布就取不到: %s' % (tag, flat))
            if not any(x == 'expiry|expired|404' for x in flat):
                round_errors.append('%s: 超过 TTL 静置后仍然可读（过期没生效）: %s' % (tag, flat))
            sliding = [x for x in flat if x.startswith('expiry|sliding|')]
            codes = sliding[0].split('|')[2].split(',') if sliding else []
            if codes != ['206'] * 5:
                round_errors.append('%s: 滑动续期失败（长视频会播到一半 404）: %s' % (tag, sliding))

    return round_errors


def _flatten(parsed, key):
    return ['|'.join([key] + v) for v in parsed.get(key, [])]


def _expect_value(errors, tag, name, lines, expected, flip_case):
    expect = 'prepared|%s|%s' % (name.split('|')[1], expected)
    if flip_case == '%s:%s' % (tag, name):
        expect = expect + '-翻转'
    if expect not in lines:
        errors.append('%s: 期望 %s，实际 %s' % (tag, expect, lines))


def _check_consumers(errors, tag, parsed, facts):
    def line(key, contains=None):
        return [x for x in _flatten(parsed, key) if contains is None or contains in x]

    if not line('init', 'video|ok'):
        errors.append('%s: 产品 init 提取器拒绝了视频 init: %s' % (tag, _flatten(parsed, 'init')))
    if not line('init', 'audio|ok'):
        errors.append('%s: 产品 init 提取器拒绝了音频 init: %s' % (tag, _flatten(parsed, 'init')))
    if not line('init', 'corrupt|fail'):
        errors.append('%s: 破坏 moov 后 init 提取仍然成功（这一项检查形同虚设）' % tag)
    video_bytes = facts['video']['bytes']
    if not any('samples=%d' % facts['video']['samples'] in x and 'bytes=%d' % video_bytes in x and 'monotonicPts=true' in x
               for x in _flatten(parsed, 'sampletable')):
        errors.append('%s: 分片样本表与源不一致: %s' % (tag, _flatten(parsed, 'sampletable')))
    if not any('kind=FMP4' in x and 'moov=true' in x and 'samples=%d' % facts['video']['samples'] in x
               and 'mdatBytes=%d' % video_bytes in x for x in _flatten(parsed, 'stream')):
        errors.append('%s: 产品流解析器读数与源不一致: %s' % (tag, _flatten(parsed, 'stream')))
    if not any(x.startswith('decoderconfig|video|4/') for x in _flatten(parsed, 'decoderconfig')):
        errors.append('%s: avcC → Annex-B 前缀提取失败: %s' % (tag, _flatten(parsed, 'decoderconfig')))
    if not any(x.startswith('pipeline|audio|supported|fMP4/aac') for x in _flatten(parsed, 'pipeline')):
        errors.append('%s: 音频管线工厂没有接受 AAC init: %s' % (tag, _flatten(parsed, 'pipeline')))
    sidx = _flatten(parsed, 'sidx')
    if not sidx or any('moofStarts=' in x and 'entries=' in x and _counts(x)[0] != _counts(x)[1] for x in sidx):
        errors.append('%s: sidx 的每个条目都必须指向真的 moof: %s' % (tag, sidx))


def _counts(line):
    entries = int(re.search(r'entries=(\d+)', line).group(1))
    moofs = int(re.search(r'moofStarts=(\d+)', line).group(1))
    return entries, moofs


def _check_http(errors, tag, parsed, video_path):
    size = os.path.getsize(video_path)
    flat = ['|'.join([k] + v) for k, vs in parsed.items() if not k.startswith('__') for v in vs]
    if not any(x == 'http|host|127.0.0.1' for x in flat):
        errors.append('%s: 回环服务主机不是 127.0.0.1' % tag)
    if not any(x == 'http|publishedLength|%d' % size for x in flat):
        errors.append('%s: 发布长度不是 %d' % (tag, size))
    if not any(x == 'http|prefetchMatches|true' for x in flat):
        errors.append('%s: 产品预取客户端整文件读回与磁盘不一致: %s' % (tag, flat))
    if not any(x.startswith('http|range0|206|') for x in flat):
        errors.append('%s: 首个 range 不是 206: %s' % (tag, flat))
    tail = [x for x in flat if x.startswith('http|tail|')]
    if not tail or tail[0].split('|')[2] != '206' or tail[0].split('|')[3] != str(max(0, size - 10)) \
            or tail[0].split('|')[4] != str(size - 1) or tail[0].split('|')[5] != str(size):
        errors.append('%s: 尾段 Range 没有裁到文件末尾: %s' % (tag, tail))
    if not any(x == 'http|beyond|416' for x in flat):
        errors.append('%s: 越界 Range 不是 416: %s' % (tag, flat))
    if not any(x == 'http|unknownToken|404' for x in flat):
        errors.append('%s: 未知 token 不是 404: %s' % (tag, flat))


# ----------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--selftest', action='store_true', help='跑完正常矩阵后再翻转一条期望，证明比对会失败')
    ap.add_argument('--flip-case', default=None, help='形如 A:prepare 或 A:prepared|durationMillis')
    ap.add_argument('--realvideo', default=None, help='可选：拿一个真实录制文件再跑一轮')
    ap.add_argument('--only', default=None, help='只跑某一轮（A/L/R）')
    args = ap.parse_args()

    if not os.path.isfile(JAVAC) or not os.path.isfile(JAVA):
        print('找不到 javac/java: %s' % JAVA_BIN)
        return 2
    if not os.path.isdir(CLASSES):
        print('找不到编译产物 %s，请先跑 tools/build_src.py' % CLASSES)
        return 2
    if args.realvideo:
        os.environ['NCPB_REALVIDEO'] = args.realvideo

    cp = mc_classpath()
    if len(cp) < 10:
        print('类路径只有 %d 项，明显不对（先跑一次游戏生成 debug.log？）' % len(cp))
        return 2

    print('=' * 78)
    print('阶段 2 离线验收：本地视频（progressive MP4 → 单轨 fMP4 → 回环 http 源）')
    print('  编译产物 : %s' % CLASSES)
    print('  类路径   : %d 项' % len(cp))
    print('  沙盒     : %s' % SANDBOX)
    print('=' * 78)

    if not compile_probe(cp):
        return 2
    facts = build_sandbox()
    print('合成源: %s（视频 %d 样本 %d 字节；音频 %d 样本 %d 字节；时长 %dms）'
          % (os.path.relpath(MAIN_SRC, PROJ), facts['video']['samples'], facts['video']['bytes'],
             facts['audio']['samples'], facts['audio']['bytes'], facts['duration_ms']))

    all_errors = []
    executed = 0
    for tag, desc, props, actions in rounds(facts):
        if args.only and tag != args.only:
            continue
        executed += 1
        print('\n[%s] %s' % (tag, desc))
        errs = check_round(cp, tag, desc, props, actions, facts, args.flip_case, [])
        if errs:
            for e in errs:
                print('    ❌ ' + e)
        else:
            print('    ✅ %d 项动作全部符合期望（含独立实现逐样本比对）' % len(actions))
        all_errors.extend(errs)

    print('\n' + '=' * 78)
    if all_errors:
        print('❌ %d 条不符合期望（%d 轮）' % (len(all_errors), executed))
    else:
        print('✅ 全部符合期望（%d 轮：产品解析器 + 回环 Range + 独立实现逐样本比对）' % executed)

    if args.selftest:
        print('-' * 78)
        target = args.flip_case or 'A:prepare'
        tag = target.split(':')[0]
        print('自证负例：翻转 %s 的期望，比对必须报失败' % target)
        flipped = []
        for rtag, desc, props, actions in rounds(facts):
            if rtag != tag:
                continue
            flipped = check_round(cp, rtag, desc, props, actions, facts, target, [])
        if flipped:
            print('✅ 已按预期报失败（说明这套比对能说不）：%s' % flipped[0][:120])
        else:
            print('❌ 翻转后期望仍然全部通过 —— 比对形同虚设')
            return 3

    return len(all_errors)


if __name__ == '__main__':
    sys.exit(main())
