#!/usr/bin/env python3
"""生成固定 OpenJFX 21.0.8 的字形补丁、虚拟列表兼容源码及来源材料。"""
import argparse
import hashlib
import io
import os
from pathlib import Path
import tempfile
import urllib.request
import zipfile

SOURCE_URL = "https://repo.maven.apache.org/maven2/org/openjfx/javafx-graphics/21.0.8/javafx-graphics-21.0.8-sources.jar"
SOURCE_SHA = "28d39ae14b5532b2770bafd52feed32290cd4cda6a3ccb670f42c0c4c899a949"
LICENSE_BASE = "https://raw.githubusercontent.com/openjdk/jfx21u/3bbff12986916db4f9416853012a298f690ba43b/"
LICENSES = {
    "LICENSE": "4b9abebc4338048a7c2dc184e9f800deb349366bdf28eb23c2677a77b4c87726",
    "ADDITIONAL_LICENSE_INFO": "a69bce275ba7a3570af6579cb0f55682cd75fedfcd49e0e8e9022270c447c916",
    "ASSEMBLY_EXCEPTION": "a44eb7b5caf5534c6ef536b21edb40b4d6babf91bf97d9d45596868618b2c6fb",
}
SOURCE_PATH = "com/sun/javafx/font/freetype/PangoGlyphLayout.java"
CONTROLS_URL = "https://repo.maven.apache.org/maven2/org/openjfx/javafx-controls/21.0.8/javafx-controls-21.0.8-sources.jar"
CONTROLS_SHA = "d21fecd570ed72c9a4321fda3ba7dd7daff9e7379402a6d749a5eba17b81abf8"
VIRTUAL_FLOW_PATH = "javafx/scene/control/skin/VirtualFlow.java"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def atomic_write(destination, data):
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
    if destination.is_file():
        cached = destination.read_bytes()
        if sha256(cached) == checksum:
            return cached
    print(f"[desktop-javafx] 下载 {url}", flush=True)
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read()
    if sha256(data) != checksum:
        raise RuntimeError(f"OpenJFX 资源 SHA-256 不匹配：{url}")
    atomic_write(destination, data)
    return data


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise RuntimeError("OpenJFX 补丁目标不是唯一匹配，停止生成")
    return source.replace(old, new, 1)


def patch(source):
    """修复空字形、UTF-16 索引，以及换行拆分后的 UTF-8 缓存范围。"""
    source = replace_once(
        source,
        "    private Map<TextRun, Long> runUtf8 = new LinkedHashMap<>();",
        "    private record Utf8Run(long address, int start, int end) {}\n"
        "    private Map<TextRun, Utf8Run> runUtf8 = new LinkedHashMap<>();",
    )
    source = replace_once(
        source,
        "        Long str = runUtf8.get(run);",
        "        Utf8Run cached = runUtf8.get(run);\n"
        "        // 换行会原地拆分 TextRun，旧 UTF-8 缓冲区必须随范围变化失效。\n"
        "        if (cached != null && (cached.start() != run.getStart()\n"
        "                || cached.end() != run.getEnd())) {\n"
        "            OSPango.g_free(cached.address());\n"
        "            runUtf8.remove(run);\n"
        "            cached = null;\n"
        "        }\n"
        "        Long str = cached == null ? null : cached.address();",
    )
    source = replace_once(
        source,
        "            runUtf8.put(run, str);",
        "            runUtf8.put(run, new Utf8Run(str, run.getStart(), run.getEnd()));",
    )
    source = replace_once(
        source,
        "        for (Long str: runUtf8.values()) {\n            OSPango.g_free(str);",
        "        for (Utf8Run cached: runUtf8.values()) {\n            OSPango.g_free(cached.address());",
    )
    source = replace_once(
        source,
        "                            if (0 <= gg && gg <= CompositeGlyphMapper.GLYPHMASK) {",
        "                            if (gg == 0x0FFFFFFF) {\n"
        "                                // Pango 的空字形不能退化为缺字方框。\n"
        "                                glyphs[gii] = com.sun.javafx.font.CharToGlyphMapper.INVISIBLE_GLYPH_ID;\n"
        "                            } else if (0 <= gg && gg <= CompositeGlyphMapper.GLYPHMASK) {",
    )
    source = replace_once(
        source,
        "            int ci = rtl ? run.getLength() : 0;",
        "            // Pango 返回码点索引；TextRun 的命中测试和选择使用相对 UTF-16 索引。\n"
        "            int codePointCount = Character.codePointCount(text, run.getStart(), run.getLength());\n"
        "            int[] utf16Offsets = new int[codePointCount + 1];\n"
        "            int utf16Offset = 0;\n"
        "            for (int i = 0; i < codePointCount; i++) {\n"
        "                utf16Offsets[i] = utf16Offset;\n"
        "                utf16Offset += Character.charCount(Character.codePointAt(\n"
        "                        text, run.getStart() + utf16Offset, run.getEnd()));\n"
        "            }\n"
        "            utf16Offsets[codePointCount] = run.getLength();\n"
        "            int ci = rtl ? codePointCount : 0;",
    )
    return replace_once(source,
                        "                        indices[gii] = g.log_clusters[i] + ci;",
                        "                        indices[gii] = utf16Offsets[g.log_clusters[i] + ci];")


def prepare(source_output, resource_output):
    cache = Path(os.environ.get("XDG_CACHE_HOME") or Path.home() / ".cache") / "llm-comments/desktop-javafx-v1"
    jar = download(cache, "javafx-graphics-21.0.8-sources.jar", SOURCE_URL, SOURCE_SHA)
    with zipfile.ZipFile(io.BytesIO(jar)) as archive:
        original = archive.read(SOURCE_PATH)
    revised = patch(original.decode("utf-8")).encode("utf-8")
    controls = download(cache, "javafx-controls-21.0.8-sources.jar", CONTROLS_URL, CONTROLS_SHA)
    with zipfile.ZipFile(io.BytesIO(controls)) as archive:
        virtual_flow = archive.read(VIRTUAL_FLOW_PATH)
    licenses = {name: download(cache, name, LICENSE_BASE + name, checksum)
                for name, checksum in LICENSES.items()}
    root = resource_output / "io/github/dreamlike/llmcomment/desktop/javafx-compat"
    atomic_write(source_output / SOURCE_PATH, revised)
    atomic_write(root / "original" / SOURCE_PATH, original)
    atomic_write(root / "modified" / SOURCE_PATH, revised)
    # 两种产物编译同一份上游源码，静态 SDK 的旧列表实现会逐单元重扫行高缓存。
    atomic_write(source_output / VIRTUAL_FLOW_PATH, virtual_flow)
    atomic_write(root / "original" / VIRTUAL_FLOW_PATH, virtual_flow)
    atomic_write(root / "compiled" / VIRTUAL_FLOW_PATH, virtual_flow)
    for name, data in licenses.items():
        atomic_write(root / name, data)
    provenance = (
        "OpenJFX 21.0.8 PangoGlyphLayout 兼容修订；保留原始版权文件头。\n"
        "修改：PANGO_GLYPH_EMPTY(0x0FFFFFFF) 映射到 INVISIBLE_GLYPH_ID(65535)；\n"
        "Pango 码点索引转换为当前 TextRun 的相对 UTF-16 索引，RTL 初值使用码点数。\n"
        "UTF-8 缓存记录 TextRun 起止范围；换行原地拆分后释放旧缓冲区并重新转换。\n"
        "字符、字形顺序、宽度及输入正文保持不变。\n\n"
        f"{SOURCE_URL}\nSHA-256: {SOURCE_SHA}\nJAR 成员: {SOURCE_PATH}\n"
        f"原文 SHA-256: {sha256(original)}\n修订 SHA-256: {sha256(revised)}\n\n"
    )
    provenance += (
        "OpenJFX 21.0.8 VirtualFlow 上游源码，原样编译，保留原始版权文件头。\n"
        "JVM 与原生共用该实现；替代 Gluon 21-ea+11.3 的旧实现。\n"
        "旧实现的 resizeCell 每次重算总高度，updateCellSize 在行高未变时也重扫缓存；\n"
        "21.0.8 的单元缓存更新在行高变化时重算，行高仍按控件字体与 CSS 测量。\n"
        f"{CONTROLS_URL}\nSHA-256: {CONTROLS_SHA}\nJAR 成员: {VIRTUAL_FLOW_PATH}\n"
        f"编译原文 SHA-256: {sha256(virtual_flow)}\n\n"
    )
    provenance += "\n\n".join(f"{LICENSE_BASE}{name}\nSHA-256: {checksum}"
                              for name, checksum in LICENSES.items()) + "\n"
    atomic_write(root / "SOURCES.txt", provenance.encode("utf-8"))
    print(f"[desktop-javafx] 兼容源码与来源已准备：{source_output}；{root}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-output", type=Path, required=True)
    parser.add_argument("--resource-output", type=Path, required=True)
    options = parser.parse_args()
    prepare(options.source_output.resolve(), options.resource_output.resolve())
