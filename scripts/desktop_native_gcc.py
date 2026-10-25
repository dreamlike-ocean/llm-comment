#!/usr/bin/env python3
"""Graal 25 的链接导出表默认隐藏外部静态 JNI；保留 JavaFX 的 JNI 查找入口。"""
import os
from pathlib import Path
import sys

for argument in sys.argv[1:]:
    # native-image 将版本脚本放在单个 -Wl,--version-script,<path> 参数中。
    if "exported_symbols.list" in argument:
        candidate = argument.rsplit(",", 1)[-1].rsplit("=", 1)[-1]
        path = Path(candidate)
        if path.is_file():
            contents = path.read_text()
            if "Java_*;" not in contents:
                path.write_text(contents.replace("global:", "global:\nJava_*;\nJNI_*;"))
os.execv("/usr/bin/gcc", ["/usr/bin/gcc", *sys.argv[1:]])
