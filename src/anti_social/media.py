"""Turn an fMP4 file's `sidx` index into segments and HLS playlists. No network here."""

import struct
from dataclasses import dataclass


class MediaFormatError(Exception):
    pass


@dataclass(frozen=True)
class Segment:
    start_byte: int
    end_byte: int  # inclusive, as in an HTTP Range header
    start_time: float
    end_time: float


@dataclass(frozen=True)
class SegmentIndex:
    init_end_byte: int  # inclusive; bytes 0..init_end_byte are the ftyp + moov header
    segments: tuple[Segment, ...]


def locate_sidx(head: bytes) -> tuple[int, int] | None:
    """Return (offset, size) of the sidx box, or None if `head` ends before reaching it."""
    pos = 0
    while pos + 8 <= len(head):
        size, kind = struct.unpack(">I4s", head[pos : pos + 8])
        if kind == b"sidx":
            return pos, size
        if kind in (b"moof", b"mdat"):
            raise MediaFormatError("file has no sidx index")
        if size < 8:
            raise MediaFormatError("malformed mp4 box")
        pos += size
    return None


def parse_index(head: bytes, sidx_offset: int, sidx_size: int) -> SegmentIndex:
    box = head[sidx_offset : sidx_offset + sidx_size]
    if len(box) < sidx_size:
        raise MediaFormatError("truncated sidx box")
    version = box[8]
    timescale = struct.unpack(">I", box[16:20])[0]
    if version == 0:
        earliest, first_offset = struct.unpack(">II", box[20:28])
        pos = 28
    else:
        earliest, first_offset = struct.unpack(">QQ", box[20:36])
        pos = 36
    count = struct.unpack(">H", box[pos + 2 : pos + 4])[0]
    pos += 4

    byte = sidx_offset + sidx_size + first_offset
    time = earliest / timescale
    segments = []
    for _ in range(count):
        ref, duration = struct.unpack(">II", box[pos : pos + 8])
        pos += 12
        size = ref & 0x7FFFFFFF
        end_time = time + duration / timescale
        segments.append(Segment(byte, byte + size - 1, time, end_time))
        byte += size
        time = end_time
    return SegmentIndex(sidx_offset - 1, tuple(segments))


@dataclass(frozen=True)
class Track:
    format_id: str
    codec: str
    bandwidth: int
    width: int | None = None
    height: int | None = None
    fps: int | None = None


def master_playlist(videos: list[Track], audio: Track) -> str:
    lines = [
        "#EXTM3U",
        "#EXT-X-INDEPENDENT-SEGMENTS",
        f'#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="audio",DEFAULT=YES,AUTOSELECT=YES,'
        f'URI="{audio.format_id}/index.m3u8"',
    ]
    for track in videos:
        attrs = [
            f"BANDWIDTH={track.bandwidth + audio.bandwidth}",
            f'CODECS="{track.codec},{audio.codec}"',
            f"RESOLUTION={track.width}x{track.height}",
        ]
        if track.fps:
            attrs.append(f"FRAME-RATE={track.fps}")
        attrs.append('AUDIO="audio"')
        lines.append("#EXT-X-STREAM-INF:" + ",".join(attrs))
        lines.append(f"{track.format_id}/index.m3u8")
    return "\n".join(lines) + "\n"


def media_playlist(index: SegmentIndex) -> str:
    target = max(int(s.end_time - s.start_time) + 1 for s in index.segments)
    lines = [
        "#EXTM3U",
        "#EXT-X-VERSION:7",
        "#EXT-X-PLAYLIST-TYPE:VOD",
        f"#EXT-X-TARGETDURATION:{target}",
        '#EXT-X-MAP:URI="init.mp4"',
    ]
    for number, segment in enumerate(index.segments):
        lines.append(f"#EXTINF:{segment.end_time - segment.start_time:.6f},")
        lines.append(f"seg/{number}.m4s")
    lines.append("#EXT-X-ENDLIST")
    return "\n".join(lines) + "\n"
