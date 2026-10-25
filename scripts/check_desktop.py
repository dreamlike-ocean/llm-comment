#!/usr/bin/env python3
"""运行独立桌面检查；默认 JVM，--native 使用单独构建的 checker。"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog=(
        "检查参数原样传递：--smoke-test [--scale 1.5] [--maximized] [--screenshot PATH]；"
        "--ime-test [1.75] [截图路径]；--scroll [100000] [dense]。需要 DISPLAY/桌面会话。"))
    parser.add_argument("--native", action="store_true", help="运行 target/llm-comment-desktop-checks")
    parser.add_argument("--java-home", type=Path, help="JVM 检查使用的 JDK，默认 JAVA_HOME 或 PATH")
    parser.add_argument("--vm-arg", action="append", default=[], help="可重复传入运行时选项，例如 --vm-arg=-Xmx2g")
    args, check_args = parser.parse_known_args()
    repository = Path(__file__).resolve().parent.parent
    target = repository / "comment-desktop/target"
    if not check_args:
        check_args = ["--smoke-test"]
    if args.native:
        executable = target / "llm-comment-desktop-checks"
        if not executable.is_file():
            raise RuntimeError("缺少独立原生 checker，请先运行 native-test/native-release verify，或使用 build_desktop_native.py --checks。")
        command = [str(executable), *args.vm_arg, *check_args]
    else:
        test_classes = target / "test-classes"
        if not (test_classes / "io/github/dreamlike/llmcomment/desktop/DesktopCheckMain.class").is_file():
            raise RuntimeError("缺少独立检查类，请先运行 mvn -pl comment-desktop -am package。")
        jars = sorted(target.glob("comment-desktop-*.jar"))
        if len(jars) != 1:
            raise RuntimeError("找不到唯一桌面 JAR，请先运行 mvn -pl comment-desktop -am package。")
        java_home = args.java_home or (Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
        java = str(java_home / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else shutil.which("java")
        if not java:
            raise RuntimeError("找不到 Java，请设置 JAVA_HOME 或 --java-home。")
        command = [java, "--enable-native-access=ALL-UNNAMED", *args.vm_arg, "-cp",
                   os.pathsep.join(map(str, [test_classes, jars[0]])),
                   "io.github.dreamlike.llmcomment.desktop.DesktopCheckMain", *check_args]
    return subprocess.run(command, cwd=repository).returncode


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, OSError) as error:
        print(f"桌面检查失败：{error}", file=sys.stderr)
        sys.exit(1)
