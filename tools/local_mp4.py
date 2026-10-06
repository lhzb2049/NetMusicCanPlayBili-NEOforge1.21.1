# -*- coding: utf-8 -*-
"""阶段 2 验收用的「另一套实现」：手工造 progressive MP4，并独立解析 fMP4。

为什么要有这个文件：如果验证只调用产品自己的解析器，那么「产品写错了」和「验证期望写错了」
会一起通过。这里的构造器与解析器**完全不引用产品代码**（纯 struct 拼盒 / 读盒），
于是「源文件样本 ←→ 产物样本」的逐字节比对才是两个独立实现互相校对。
"""
import hashlib
import struct

# ----------------------------------------------------------------- 造盒

def u32(value):
    return struct.pack('>I', value & 0xFFFFFFFF)


def box(typ, payload):
    return u32(8 + len(payload)) + typ.encode('latin1') + payload


def full(typ, version, flags, payload):
    return box(typ, bytes([version]) + struct.pack('>I', flags)[1:] + payload)


def descriptor(tag, payload):
    """MPEG-4 描述符（长度用单字节，够用且解析器两种都支持）。"""
    return bytes([tag, len(payload)]) + payload


def esds_payload(asc):
    dsi = descriptor(0x05, asc)
    dcd = descriptor(0x04, bytes([0x40, 0x15]) + b'\x00\x00\x00' + u32(128000) + u32(128000) + dsi)
    sl = descriptor(0x06, b'\x02')
    return b'\x00\x00\x00\x00' + descriptor(0x03, b'\x00\x01' + b'\x00' + dcd + sl)


def avc_config(profile=0x64, level=0x1F, sps=b'\x67\x64\x00\x1F', pps=b'\x68\xEE\x3C'):
    payload = bytes([1, profile, 0x00, level, 0xFF, 0xE1]) + struct.pack('>H', len(sps)) + sps
    payload += bytes([1]) + struct.pack('>H', len(pps)) + pps
    return payload


def visual_sample_entry(typ, width, height, config_typ, config, depth=0x18):
    payload = b'\x00' * 6 + b'\x00\x01'          # reserved + data_reference_index
    payload += b'\x00' * 16                       # pre_defined/reserved
    payload += struct.pack('>HH', width, height)  # width/height
    payload += u32(0x00480000) + u32(0x00480000)  # 72dpi
    payload += u32(0) + struct.pack('>H', 1)      # reserved + frame_count
    payload += b'\x00' * 32                       # compressorname
    payload += struct.pack('>H', depth) + struct.pack('>h', -1)
    payload += box(config_typ, config)
    return box(typ, payload)


def audio_sample_entry(typ, channels, sample_rate, asc, extra=b''):
    payload = b'\x00' * 6 + b'\x00\x01'                    # reserved + data_reference_index
    payload += struct.pack('>HH', 0, 0) + u32(0)           # version/revision/vendor
    payload += struct.pack('>HH', channels, 16)            # channelcount / samplesize
    payload += struct.pack('>HH', 0, 0)                    # pre_defined / reserved
    payload += u32(sample_rate << 16)                      # samplerate (16.16)
    payload += box('esds', esds_payload(asc)) + extra
    return box(typ, payload)


def build_trak(track_id, handler, sample_entry, timescale, duration, samples, chunk_offsets, stsc_entries,
               stts_entries, ctts_entries=None, stss=None, width=0, height=0, edit_list=None, language=0x55C4):
    """samples: [(size, offset)]；chunk_offsets/stsc_entries 由 mdat 布局决定。"""
    stsd = full('stsd', 0, 0, u32(1) + sample_entry)
    stts = full('stts', 0, 0, u32(len(stts_entries)) + b''.join(struct.pack('>II', c, d) for c, d in stts_entries))
    stsc = full('stsc', 0, 0, u32(len(stsc_entries)) + b''.join(struct.pack('>III', a, b, 1) for a, b in stsc_entries))
    stsz = full('stsz', 0, 0, u32(0) + u32(len(samples)) + b''.join(u32(s) for s, _ in samples))
    stco = full('stco', 0, 0, u32(len(chunk_offsets)) + b''.join(u32(o) for o in chunk_offsets))
    stbl_children = [stsd, stts]
    if ctts_entries is not None:
        stbl_children.append(full('ctts', 0, 0, u32(len(ctts_entries)) + b''.join(struct.pack('>II', c, o) for c, o in ctts_entries)))
    stbl_children += [stsc, stsz, stco]
    if stss is not None:
        stbl_children.append(full('stss', 0, 0, u32(len(stss)) + b''.join(u32(n) for n in stss)))
    stbl = box('stbl', b''.join(stbl_children))
    if handler == 'vide':
        media_header = full('vmhd', 0, 1, struct.pack('>HHHH', 0, 0, 0, 0))
    else:
        media_header = full('smhd', 0, 0, struct.pack('>HH', 0, 0))
    dref = full('dref', 0, 0, u32(1) + full('url ', 0, 1, b''))
    minf = box('minf', media_header + box('dinf', dref) + stbl)
    hdlr = full('hdlr', 0, 0, u32(0) + handler.encode('latin1') + b'\x00' * 12 + b'Handler\x00')
    mdia_children = [full('mdhd', 0, 0, u32(0) + u32(0) + u32(timescale) + u32(duration) + struct.pack('>HH', language, 0)), hdlr, minf]
    mdia = box('mdia', b''.join(mdia_children))
    tkhd_payload = u32(0) + u32(0) + u32(track_id) + u32(0) + u32(duration) + u32(0) + u32(0)
    tkhd_payload += struct.pack('>HHHH', 0, 0, 0x0100 if handler == 'soun' else 0, 0)
    tkhd_payload += u32(0x00010000) + u32(0) + u32(0) + u32(0) + u32(0x00010000) + u32(0) + u32(0) + u32(0) + u32(0x40000000)
    tkhd_payload += u32(width << 16) + u32(height << 16)
    trak_children = [full('tkhd', 0, 7, tkhd_payload)]
    if edit_list is not None:
        segments = b''.join(struct.pack('>IIhh', 0, mt, 1, 0) for mt in edit_list)
        trak_children.append(box('edts', full('elst', 0, 0, u32(len(edit_list)) + segments)))
    trak_children.append(mdia)
    return box('trak', b''.join(trak_children))


def build_progressive_mp4(video_samples, audio_samples, video_timescale=30000, audio_timescale=48000,
                          width=320, height=240, video_codec='avc1', encrypted=False,
                          video_stts=None, audio_stts=None, ctts_entries=None, stss=None, edit_list=None,
                          interleave=(3, 4), moov_at_end=True, fragment=False):
    """按真实录制文件的形态拼一个 MP4：mdat 在前、moov 在尾，音视频块交织。

    video_samples/audio_samples 为 [(bytes, duration)]；返回 (文件字节, 事实字典)。
    """
    timescale_video_duration = sum(d for _, d in video_samples) if video_samples else 0
    timescale_audio_duration = sum(d for _, d in audio_samples) if audio_samples else 0

    # --- mdat：按 (视频块, 音频块) 交织排布
    mdat = bytearray()
    video_offsets, audio_offsets = [], []
    video_chunk_starts, audio_chunk_starts = [], []
    video_chunk_counts, audio_chunk_counts = [], []
    iv = ia = 0
    base = 8  # mdat 头之后的偏移会在最后整体平移
    while iv < len(video_samples) or ia < len(audio_samples):
        if iv < len(video_samples):
            video_chunk_starts.append(base + len(mdat))
            taken = 0
            for _ in range(interleave[0]):
                if iv >= len(video_samples):
                    break
                data, _d = video_samples[iv]
                video_offsets.append(base + len(mdat))
                mdat += data
                iv += 1
                taken += 1
            video_chunk_counts.append(taken)
        if ia < len(audio_samples):
            audio_chunk_starts.append(base + len(mdat))
            taken = 0
            for _ in range(interleave[1]):
                if ia >= len(audio_samples):
                    break
                data, _d = audio_samples[ia]
                audio_offsets.append(base + len(mdat))
                mdat += data
                ia += 1
                taken += 1
            audio_chunk_counts.append(taken)

    ftyp = box('ftyp', b'isom' + u32(0x200) + b'isomiso2mp41')
    # 先按 mdat 在偏移 0 的假设排出偏移，最后统一平移 ftyp 的长度
    traks = []
    if video_samples:
        entry = visual_sample_entry('encv' if encrypted else video_codec, width, height,
                                    'avcC', avc_config())
        traks.append(build_trak(
            1, 'vide', entry, video_timescale, timescale_video_duration,
            [(len(d), o) for (d, _dur), o in zip(video_samples, video_offsets)],
            video_chunk_starts, list(zip(range(1, len(video_chunk_starts) + 1), video_chunk_counts)),
            video_stts or [(len(video_samples), video_samples[0][1] if video_samples else 1024)],
            ctts_entries=ctts_entries, stss=stss, width=width, height=height, edit_list=edit_list))
    if audio_samples:
        entry = audio_sample_entry('mp4a', 2, audio_timescale, b'\x11\x90')
        traks.append(build_trak(
            2, 'soun', entry, audio_timescale, timescale_audio_duration,
            [(len(d), o) for (d, _dur), o in zip(audio_samples, audio_offsets)],
            audio_chunk_starts, list(zip(range(1, len(audio_chunk_starts) + 1), audio_chunk_counts)),
            audio_stts or [(len(audio_samples), audio_samples[0][1] if audio_samples else 1024)]))
    mvhd = full('mvhd', 0, 0, u32(0) + u32(0) + u32(1000) + u32(1000) + u32(0x00010000) + struct.pack('>H', 0x0100)
                + b'\x00' * 10 + u32(0x00010000) + u32(0) + u32(0) + u32(0) + u32(0x00010000) + u32(0) + u32(0) + u32(0)
                + u32(0x40000000) + b'\x00' * 24 + u32(3))
    if fragment:
        # 分片 MP4：trak 里只留 stsd，media 数据挂在 moof/mdat 上
        stripped = []
        for trak in traks:
            stripped.append(_strip_stbl_tables(trak))
        traks = stripped
    moov = box('moov', mvhd + b''.join(traks))

    shift = len(ftyp) + len(moov)
    if moov_at_end:
        data = ftyp + box('mdat', bytes(mdat)) + moov
    else:
        data = ftyp + moov + box('mdat', bytes(mdat))

    if fragment:
        moof = box('moof', full('mfhd', 0, 0, u32(1))
                   + box('traf', full('tfhd', 0, 0x020000, u32(1))
                         + full('tfdt', 1, 0, struct.pack('>Q', 0))
                         + full('trun', 0, 0x301, u32(len(video_samples)) + u32(0)
                                + b''.join(struct.pack('>II', d, len(b)) for b, d in video_samples))))
        data = ftyp + moov + moof + box('mdat', bytes(mdat))

    # 修正所有块偏移（stco 里记的是「mdat 盒从 0 开始」时的偏移，实际前面还有 ftyp/moov）
    data = _shift_chunk_offsets(data, shift)
    facts = {
        'video': {
            'samples': len(video_samples),
            'bytes': sum(len(b) for b, _d in video_samples),
            'duration_units': timescale_video_duration,
            'timescale': video_timescale,
            'duration_ms': round(timescale_video_duration * 1000.0 / video_timescale),
            'width': width,
            'height': height,
            'codec': video_codec,
        } if video_samples else None,
        'audio': {
            'samples': len(audio_samples),
            'bytes': sum(len(b) for b, _d in audio_samples),
            'duration_units': timescale_audio_duration,
            'timescale': audio_timescale,
            'duration_ms': round(timescale_audio_duration * 1000.0 / audio_timescale),
        } if audio_samples else None,
    }
    if facts['video'] and facts['audio']:
        facts['duration_ms'] = max(facts['video']['duration_ms'], facts['audio']['duration_ms'])
    elif facts['video']:
        facts['duration_ms'] = facts['video']['duration_ms']
    elif facts['audio']:
        facts['duration_ms'] = facts['audio']['duration_ms']
    else:
        facts['duration_ms'] = 0
    return data, facts


def _strip_stbl_tables(trak):
    """把 stbl 里的 stts/stsc/stsz/stco 去掉（造一个「没有样本表」的分片 MP4）。"""
    out = bytearray()
    pos = 0
    while pos + 8 <= len(trak):
        size = struct.unpack('>I', trak[pos:pos + 4])[0]
        typ = trak[pos + 4:pos + 8]
        payload = trak[pos + 8:pos + size]
        if typ in (b'trak', b'mdia', b'minf', b'stbl'):
            inner = bytearray()
            p = 0
            while p + 8 <= len(payload):
                isize = struct.unpack('>I', payload[p:p + 4])[0]
                ityp = payload[p + 4:p + 8]
                chunk = payload[p:p + isize]
                if ityp in (b'stts', b'stsc', b'stsz', b'stco', b'ctts', b'stss', b'co64'):
                    chunk = b''
                elif ityp in (b'trak', b'mdia', b'minf', b'stbl'):
                    chunk = _strip_stbl_tables(chunk)
                inner += chunk
                p += isize
            out += u32(8 + len(inner)) + typ + inner
        else:
            out += trak[pos:pos + size]
        pos += size
    return bytes(out)


def _shift_chunk_offsets(data, delta):
    """把所有 stco 条目 + delta（mdat 实际起始偏移随 ftyp/moov 长度变化）。"""
    out = bytearray(data)
    pos = 0
    while pos + 8 <= len(out):
        size = struct.unpack('>I', out[pos:pos + 4])[0]
        typ = bytes(out[pos + 4:pos + 8])
        if typ == b'stco':
            count = struct.unpack('>I', out[pos + 12:pos + 16])[0]
            for i in range(count):
                off = pos + 16 + i * 4
                value = struct.unpack('>I', out[off:off + 4])[0]
                out[off:off + 4] = u32(value + delta)
        pos += size
    return bytes(out)


# ----------------------------------------------------------------- 读盒

def box_at(data, start, end):
    size = struct.unpack('>I', data[start:start + 4])[0]
    typ = data[start + 4:start + 8].decode('latin1')
    hs = 8
    if size == 1:
        size = struct.unpack('>Q', data[start + 8:start + 16])[0]
        hs = 16
    elif size == 0:
        size = end - start
    if size < hs or start + size > end:
        raise ValueError('bad box %s at %d size=%d' % (typ, start, size))
    return dict(start=start, size=size, type=typ, hs=hs, ps=start + hs, end=start + size)


def children(data, start, end):
    out = []
    pos = start
    while pos + 8 <= end:
        b = box_at(data, pos, end)
        out.append(b)
        pos = b['end']
    return out


def find(data, parent, typ):
    for b in children(data, parent['ps'], parent['end']):
        if b['type'] == typ:
            return b
    return None


def descend(data, parent, *types):
    cur = parent
    for t in types:
        cur = find(data, cur, t)
        if cur is None:
            return None
    return cur


def u16(data, off):
    return struct.unpack('>H', data[off:off + 2])[0]


def u32at(data, off):
    return struct.unpack('>I', data[off:off + 4])[0]


def i32at(data, off):
    return struct.unpack('>i', data[off:off + 4])[0]


def u64at(data, off):
    return struct.unpack('>Q', data[off:off + 8])[0]


def source_tracks(data):
    """独立解析 progressive MP4 的样本表：{trackId: {...}}。"""
    moov = [b for b in children(data, 0, len(data)) if b['type'] == 'moov']
    if not moov:
        raise ValueError('源文件没有 moov')
    tracks = {}
    for trak in children(data, moov[0]['ps'], moov[0]['end']):
        if trak['type'] != 'trak':
            continue
        tkhd = find(data, trak, 'tkhd')
        mdhd = descend(data, trak, 'mdia', 'mdhd')
        hdlr = descend(data, trak, 'mdia', 'hdlr')
        stbl = descend(data, trak, 'mdia', 'minf', 'stbl')
        stsd = find(data, stbl, 'stsd')
        entry_start = stsd['ps'] + 8
        entry_len = u32at(data, entry_start)
        stsz = find(data, stbl, 'stsz')
        stts = find(data, stbl, 'stts')
        stsc = find(data, stbl, 'stsc')
        stco = find(data, stbl, 'stco')
        stss = find(data, stbl, 'stss')
        ctts = find(data, stbl, 'ctts')
        count = u32at(data, stsz['ps'] + 8)
        sizes = [u32at(data, stsz['ps'] + 12 + 4 * i) for i in range(count)]
        durations = []
        for i in range(u32at(data, stts['ps'] + 4)):
            durations += [u32at(data, stts['ps'] + 12 + 8 * i)] * u32at(data, stts['ps'] + 8 + 8 * i)
        cts = [0] * count
        if ctts is not None:
            cur = 0
            for i in range(u32at(data, ctts['ps'] + 4)):
                n = u32at(data, ctts['ps'] + 8 + 8 * i)
                v = u32at(data, ctts['ps'] + 12 + 8 * i)
                for _ in range(n):
                    if cur < count:
                        cts[cur] = v
                        cur += 1
        chunks = [u32at(data, stco['ps'] + 8 + 4 * i) for i in range(u32at(data, stco['ps'] + 4))]
        entries = [(u32at(data, stsc['ps'] + 8 + 12 * i), u32at(data, stsc['ps'] + 12 + 12 * i))
                   for i in range(u32at(data, stsc['ps'] + 4))]
        sync = set()
        if stss is not None:
            sync = set(u32at(data, stss['ps'] + 8 + 4 * i) for i in range(u32at(data, stss['ps'] + 4)))
        offsets = []
        cursor = 0
        for i, (first_chunk, per_chunk) in enumerate(entries):
            last = len(chunks) if i + 1 >= len(entries) else entries[i + 1][0] - 1
            for chunk in range(first_chunk, last + 1):
                pos = chunks[chunk - 1]
                for _ in range(per_chunk):
                    if cursor >= count:
                        break
                    offsets.append(pos)
                    pos += sizes[cursor]
                    cursor += 1
        handler = data[hdlr['ps'] + 8:hdlr['ps'] + 12].decode('latin1')
        tracks[u32at(data, tkhd['ps'] + 12)] = dict(
            handler=handler, timescale=u32at(data, mdhd['ps'] + 12), sizes=sizes, durations=durations,
            cts=cts, offsets=offsets, sync=sync, codec=bytes(data[entry_start + 4:entry_start + 8]).decode('latin1'))
    return tracks


def parse_fmp4(data, label):
    """独立解析单轨 fMP4（init + sidx + 分片）。"""
    top = children(data, 0, len(data))
    types = [b['type'] for b in top]
    if types[:3] != ['ftyp', 'moov', 'sidx']:
        raise ValueError('%s: 头部盒序应为 ftyp/moov/sidx，实际 %s' % (label, types[:3]))
    moov, sidx = top[1], top[2]
    traks = [b for b in children(data, moov['ps'], moov['end']) if b['type'] == 'trak']
    if len(traks) != 1:
        raise ValueError('%s: moov 里应有 1 条 trak，实际 %d' % (label, len(traks)))
    trak = traks[0]
    mdhd = descend(data, trak, 'mdia', 'mdhd')
    hdlr = descend(data, trak, 'mdia', 'hdlr')
    stsd = descend(data, trak, 'mdia', 'minf', 'stbl', 'stsd')
    timescale = u32at(data, mdhd['ps'] + 12)
    handler = data[hdlr['ps'] + 8:hdlr['ps'] + 12].decode('latin1')
    entry_len = u32at(data, stsd['ps'] + 8)
    entry_start = stsd['ps'] + 8
    codec = data[entry_start + 4:entry_start + 8].decode('latin1')
    config = None
    for probe in (b'avcC', b'av1C', b'esds'):
        if data.find(probe, entry_start, entry_start + entry_len) >= 0:
            config = probe.decode()
            break
    sp = sidx['ps']
    version = data[sp]
    ref_id = u32at(data, sp + 4)
    sidx_ts = u32at(data, sp + 8)
    if version == 0:
        earliest, first_offset, p = u32at(data, sp + 12), u32at(data, sp + 16), sp + 20
    else:
        earliest, first_offset, p = u64at(data, sp + 12), u64at(data, sp + 20), sp + 28
    ref_count = u16(data, p + 2)
    p += 4
    refs = []
    for _ in range(ref_count):
        ref = u32at(data, p)
        refs.append((ref & 0x7FFFFFFF, u32at(data, p + 4), bool(u32at(data, p + 8) & 0x80000000)))
        p += 12
    frags = []
    pos = sidx['end']
    while pos + 8 <= len(data):
        moof = box_at(data, pos, len(data))
        if moof['type'] != 'moof':
            raise ValueError('%s: 分片 %d 处是 %s' % (label, len(frags), moof['type']))
        mdat = box_at(data, moof['end'], len(data))
        if mdat['type'] != 'mdat':
            raise ValueError('%s: moof 后是 %s' % (label, mdat['type']))
        traf = find(data, moof, 'traf')
        tfdt = find(data, traf, 'tfdt')
        trun = find(data, traf, 'trun')
        flags = (data[trun['ps'] + 1] << 16) | (data[trun['ps'] + 2] << 8) | data[trun['ps'] + 3]
        q = trun['ps'] + 4
        sample_count = u32at(data, q)
        q += 4
        data_offset = i32at(data, q) if flags & 0x1 else None
        q += 4 if flags & 0x1 else 0
        samples = []
        for _ in range(sample_count):
            dur = u32at(data, q) if flags & 0x100 else None
            q += 4 if flags & 0x100 else 0
            size = u32at(data, q) if flags & 0x200 else None
            q += 4 if flags & 0x200 else 0
            cts = i32at(data, q) if flags & 0x800 else None
            q += 4 if flags & 0x800 else 0
            samples.append((dur, size, cts))
        frags.append(dict(offset=pos, moof_size=moof['size'], mdat_size=mdat['size'], samples=samples,
                          base=u64at(data, tfdt['ps'] + 4), payload=data[mdat['ps']:mdat['end']],
                          data_offset=data_offset, flags=flags))
        pos = mdat['end']
    return dict(file_size=len(data), init_end=moov['end'], index_start=sidx['start'], index_size=sidx['size'],
                timescale=timescale, handler=handler, codec=codec, config=config, refs=refs, frags=frags,
                sidx_ts=sidx_ts, earliest=earliest, first_offset=first_offset, ref_id=ref_id)


def source_sample_bytes(data, track):
    return [data[track['offsets'][i]:track['offsets'][i] + track['sizes'][i]] for i in range(len(track['sizes']))]


def fmp4_sample_bytes(parsed):
    out = []
    for frag in parsed['frags']:
        pos = 0
        for _dur, size, _cts in frag['samples']:
            out.append(frag['payload'][pos:pos + size])
            pos += size
    return out


def sha256(data):
    return hashlib.sha256(data).hexdigest()
