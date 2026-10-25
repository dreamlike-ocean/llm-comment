#!/usr/bin/env python3
"""准备桌面字体资源；仅写用户缓存和指定输出目录，不依赖系统字体或 dpkg。"""
import argparse
import hashlib
import io
import os
from pathlib import Path
import tarfile
import tempfile
import urllib.request

CJK_BASE = "https://raw.githubusercontent.com/notofonts/noto-cjk/f8d157532fbfaeda587e826d4cd5b21a49186f7c/Sans/"
EMOJI_BASE = "https://raw.githubusercontent.com/google/fonts/e7db6ca603fd4862010383ff17ffd6cc4ffac1c0/ofl/notoemoji/"
SOURCES = (
    ("NotoSansMonoCJKsc-Regular.otf", CJK_BASE + "Mono/NotoSansMonoCJKsc-Regular.otf",
     "ec04cc376b34887cedbdf84074e2e226ed2761eeabdcb9173fc1dd7bfd153ef7"),
    ("NotoSansCJK-LICENSE.txt", CJK_BASE + "LICENSE",
     "6a73f9541c2de74158c0e7cf6b0a58ef774f5a780bf191f2d7ec9cc53efe2bf2"),
    ("NotoEmoji.ttf", EMOJI_BASE + "NotoEmoji%5Bwght%5D.ttf",
     "de6c18832938afc99caf132b39d6a30a19bac7f2e812e28db2535b4608d27551"),
    ("NotoEmoji-OFL.txt", EMOJI_BASE + "OFL.txt",
     "500bb1ccf43df7bbb522112f9133a52b16e1c35e809632f5d8609b179152de5b"),
    ("fonts-hanazono_20170904-3_all.deb",
     "https://archive.ubuntu.com/ubuntu/pool/universe/f/fonts-hanazono/fonts-hanazono_20170904-3_all.deb",
     "f4a0287e489d6fadc498432fc59e6b95c770e7b18f271c7eec1016609fd68f83"),
)
HANA_SHA = "829543436cdf7b4c6730e2af5806fa406f8ddf0d4b656e0cd49c58d4db1c8d3c"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def atomic_write(destination, data):
    """同目录临时文件加原子替换，允许并行构建共享已校验缓存。"""
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_file() and destination.read_bytes() == data:
        return
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(data)
        temporary.replace(destination)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def download(cache, name, url, checksum):
    destination = cache / name
    if destination.is_file() and sha256(destination.read_bytes()) == checksum:
        return destination.read_bytes()
    print(f"[desktop-fonts] 下载 {url}", flush=True)
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read()
    if sha256(data) != checksum:
        raise RuntimeError(f"字体资源 SHA-256 不匹配：{url}")
    atomic_write(destination, data)
    return data


def deb_data(archive):
    """读取 Debian ar 中的 data.tar 成员，不解包任意路径。"""
    if not archive.startswith(b"!<arch>\n"):
        raise RuntimeError("字体 Debian 包的 ar 头无效")
    offset = 8
    while offset + 60 <= len(archive):
        header = archive[offset:offset + 60]
        if header[58:60] != b"`\n":
            raise RuntimeError("字体 Debian 包的 ar 成员头无效")
        size = int(header[48:58].strip())
        name = header[:16].decode("ascii").strip().rstrip("/")
        start = offset + 60
        if start + size > len(archive):
            raise RuntimeError("字体 Debian 包的 ar 成员不完整")
        if name.startswith("data.tar"):
            return archive[start:start + size]
        offset = start + size + size % 2
    raise RuntimeError("字体 Debian 包缺少 data.tar 成员")


def hanazono_files(archive):
    requested = {
        "usr/share/fonts/truetype/hanazono/HanaMinB.ttf": "HanaMinB.ttf",
        "usr/share/doc/fonts-hanazono/copyright": "HanaMinB-copyright.txt",
    }
    result = {}
    with tarfile.open(fileobj=io.BytesIO(deb_data(archive)), mode="r:*") as tar:
        for member in tar:
            name = member.name.removeprefix("./")
            if name in requested:
                if not member.isfile():
                    raise RuntimeError(f"字体包成员不是普通文件：{name}")
                with tar.extractfile(member) as stream:
                    result[requested[name]] = stream.read()
    if len(result) != len(requested) or sha256(result.get("HanaMinB.ttf", b"")) != HANA_SHA:
        raise RuntimeError("HanaMinB 字体或许可证缺失，或字体 SHA-256 不匹配")
    return result


def prepare(output):
    cache = Path(os.environ.get("XDG_CACHE_HOME") or Path.home() / ".cache") / "llm-comments/desktop-fonts-v1"
    files = {name: download(cache, name, url, checksum) for name, url, checksum in SOURCES}
    files.update(hanazono_files(files.pop("fonts-hanazono_20170904-3_all.deb")))
    root = output / "io/github/dreamlike/llmcomment/desktop/fonts"
    for name, data in files.items():
        destination = root / name if name.endswith((".ttf", ".otf")) else root / "licenses" / name
        atomic_write(destination, data)
    provenance = "桌面字体固定来源与 SHA-256；许可证保留供应方原文。\n\n"
    provenance += "\n\n".join(f"{name}\n{url}\nSHA-256: {checksum}" for name, url, checksum in SOURCES)
    provenance += f"\n\nDebian 包成员 usr/share/fonts/truetype/hanazono/HanaMinB.ttf\nSHA-256: {HANA_SHA}\n"
    atomic_write(root / "licenses/SOURCES.txt", provenance.encode("utf-8"))
    print(f"[desktop-fonts] 资源已准备：{root}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Maven 生成资源根目录")
    prepare(parser.parse_args().output.resolve())
