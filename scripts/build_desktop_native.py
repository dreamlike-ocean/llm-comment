#!/usr/bin/env python3
"""Linux x64 JavaFX Native Image 构建。下载和解包只写用户缓存，不修改 JDK/系统。

固定 Gluon Substrate/JavaFX 静态 SDK；使用供应方随 JAR 附带的 JNI/反射元数据。
GluonFX 旧链接器不识别 Graal 25 的目录布局，因此直接调用 native-image。
供应方源码：https://github.com/gluonhq/substrate （GluonFeature 与 SDK 配置）。
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import urllib.request
import zipfile

SDK_VERSION = "21-ea+11.3"
SDK_SHA = "a2fa01c8c3f086f539e26b71198cab80bc432cd9f4b40dd750adebb6719f0003"
SUBSTRATE_VERSION = "0.0.69"
SUBSTRATE_SHA = "d2ba5f26578e4aa81e358f2e9fdf107c1d528294920db4e4a70841a678e49cf4"
LIBRARIES = ["prism_es2", "prism_sw", "glass", "glassgtk3", "javafx_font",
             "javafx_font_freetype", "javafx_font_pango", "javafx_iio"]
PACKAGES = ["gl", "x11", "gtk+-x11-3.0", "freetype2", "pangoft2", "gthread-2.0",
            "zlib", "xtst", "gmodule-no-export-2.0"]
OS_PACKAGES = ["libgl-dev", "libx11-dev", "libgtk-3-dev", "libfreetype-dev",
               "libpango1.0-dev", "libglib2.0-dev", "zlib1g-dev", "libxtst-dev"]


def run(command, **kwargs):
    print("[desktop-native] " + " ".join(map(str, command)), flush=True)
    return subprocess.run(list(map(str, command)), check=True, **kwargs)


def download(url, destination, checksum):
    if not destination.exists() or hashlib.sha256(destination.read_bytes()).hexdigest() != checksum:
        temporary = destination.with_suffix(destination.suffix + ".download")
        print(f"[desktop-native] 下载 {url}", flush=True)
        urllib.request.urlretrieve(url, temporary)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != checksum:
            temporary.unlink()
            raise RuntimeError(f"下载文件 SHA-256 不匹配：{url}")
        temporary.replace(destination)
    return destination


def linker_environment(cache):
    env = os.environ.copy()
    prefix = cache / "system-libraries"
    pc = prefix / "usr/lib/x86_64-linux-gnu/pkgconfig"
    env["PKG_CONFIG_PATH"] = str(pc) + ":" + str(prefix / "usr/share/pkgconfig") + ":" + env.get("PKG_CONFIG_PATH", "")
    missing = subprocess.run(["pkg-config", "--exists", *PACKAGES], env=env).returncode
    if missing:
        if not shutil.which("apt") or not shutil.which("dpkg-deb"):
            raise RuntimeError("缺少 Linux 开发库，请安装：" + " ".join(OS_PACKAGES))
        debs = cache / "debs"
        debs.mkdir(exist_ok=True)
        # 递归只下载尚未安装的开发包。不会运行 apt install，也不需要 sudo。
        depends = subprocess.check_output(["apt-cache", "depends", "--recurse", "--no-recommends",
            "--no-suggests", "--no-conflicts", "--no-breaks", "--no-replaces", "--no-enhances", *OS_PACKAGES], text=True)
        packages = set(OS_PACKAGES)
        packages.update(line.strip() for line in depends.splitlines()
                        if line and not line[0].isspace() and line.strip().endswith("-dev"))
        needed = []
        for package in sorted(packages):
            status = subprocess.run(["dpkg-query", "-W", "-f=${db:Status-Status}", package],
                                    capture_output=True, text=True)
            if status.stdout != "installed":
                needed.append(package)
        if needed:
            run(["apt", "download", *needed], cwd=debs)
        for deb in sorted(debs.glob("*.deb")):
            run(["dpkg-deb", "-x", deb, prefix])
        # 开发包的相对 .so 链接指向已经安装的运行库。
        libdir = prefix / "usr/lib/x86_64-linux-gnu"
        for entry in libdir.iterdir():
            if entry.is_symlink() and not entry.exists():
                runtime = Path("/usr/lib/x86_64-linux-gnu") / entry.readlink().name
                if runtime.exists():
                    entry.unlink()
                    entry.symlink_to(runtime)
        if subprocess.run(["pkg-config", "--exists", *PACKAGES], env=env).returncode:
            raise RuntimeError("本地解包后仍缺少开发库，请安装：" + " ".join(OS_PACKAGES))
    flags = subprocess.check_output(["pkg-config", "--libs", *PACKAGES], env=env, text=True).split()
    if prefix.exists():
        flags.insert(0, "-L" + str(prefix / "usr/lib/x86_64-linux-gnu"))
    return flags


def metadata(jars, substrate, output):
    reflection, jni = [], []
    with zipfile.ZipFile(substrate) as archive:
        reflection.extend(json.loads(archive.read("config/reflectionconfig-java.json")))
        jni.extend(json.loads(archive.read("config/jniconfig-java.json")))
        reflection.extend(json.loads(archive.read("config/reflectionconfig-javafxsw.json")))
        jni.extend(json.loads(archive.read("config/jniconfig-javafxsw.json")))
    bundles = set()
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith("META-INF/substrate/config/"):
                    base = name.rsplit("/", 1)[-1]
                    if base in ["reflectionconfig.json", "reflectionconfig-x86_64-linux.json"]:
                        reflection.extend(json.loads(archive.read(name)))
                    elif base in ["jniconfig.json", "jniconfig-x86_64-linux.json"]:
                        jni.extend(json.loads(archive.read(name)))
                    elif base == "resourcebundles":
                        bundles.update(x.strip() for x in archive.read(name).decode().splitlines() if x.strip())
    for name in ["io.github.dreamlike.llmcomment.desktop.Main", "io.github.dreamlike.llmcomment.desktop.DesktopApplication"]:
        reflection.append({"name": name, "allPublicConstructors": True})
    (output / "reflect-config.json").write_text(json.dumps(reflection))
    (output / "jni-config.json").write_text(json.dumps(jni))
    (output / "resource-config.json").write_text(json.dumps({"resources": {"includes": [
        {"pattern": r".*\.(css|bss|png|jpg|jpeg|gif|bmp|ttf|raw|xml|fxml|gls|json|dat|frag|vert)$"}]},
        "bundles": [{"name": name.replace("/", ".")} for name in sorted(bundles)]}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--module", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--optimization", choices=["-O0", "-O3"], required=True)
    args = parser.parse_args()
    if platform.system() != "Linux" or platform.machine() not in ["x86_64", "amd64"]:
        raise RuntimeError("桌面原生构建当前仅支持 Linux x64；其他平台请先使用 JVM JAR。")
    for tool in ["gcc", "objcopy", "pkg-config"]:
        if not shutil.which(tool):
            raise RuntimeError(f"桌面原生构建缺少命令：{tool}")
    java_home = args.java_home.resolve()
    native_image = java_home / "bin/native-image"
    if not native_image.is_file():
        raise RuntimeError(f"Maven 所用 JDK 缺少 native-image：{native_image}")
    module = args.module.resolve()
    repository = module.parent
    target = module / "target"
    work = target / "desktop-native"
    work.mkdir(parents=True, exist_ok=True)
    cache = Path(os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache"))) / "llm-comments/desktop-native-v1"
    cache.mkdir(parents=True, exist_ok=True)
    sdk_zip = download(f"https://download2.gluonhq.com/substrate/javafxstaticsdk/openjfx-{SDK_VERSION}-linux-x86_64-static.zip",
                       cache / "javafx-static.zip", SDK_SHA)
    substrate = download(f"https://repo.maven.apache.org/maven2/com/gluonhq/substrate/{SUBSTRATE_VERSION}/substrate-{SUBSTRATE_VERSION}.jar",
                         cache / "substrate.jar", SUBSTRATE_SHA)
    if not (cache / "sdk/lib/javafx.graphics.jar").exists():
        with zipfile.ZipFile(sdk_zip) as archive:
            archive.extractall(cache)
    licenses = work / "licenses"
    shutil.copytree(cache / "sdk/legal", licenses / "openjfx", dirs_exist_ok=True)
    with zipfile.ZipFile(substrate) as archive:
        for name in archive.namelist():
            if any(token in name.upper() for token in ["LICENSE", "NOTICE", "COPYING"]) and not name.endswith("/"):
                destination = licenses / "substrate" / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(archive.read(name))
    for name, checksum in [
            ("LICENSE", "8177f97513213526df2cf6184d8ff986c675afb514d4e68a404010521b880643"),
            ("GluonFeature.java", "e6eda0f3430ff45c80a3481452f3ed0237082f7a5b688f4727c8f64f2d8ac3e4")]:
        relative = "LICENSE" if name == "LICENSE" else "src/main/java/com/gluonhq/substrate/feature/GluonFeature.java"
        source = download(f"https://raw.githubusercontent.com/gluonhq/substrate/{SUBSTRATE_VERSION}/{relative}",
                          cache / ("substrate-" + name), checksum)
        (licenses / "substrate").mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, licenses / "substrate" / name)
    (licenses / "SOURCES.txt").write_text(
        f"OpenJFX static SDK: https://download2.gluonhq.com/substrate/javafxstaticsdk/openjfx-{SDK_VERSION}-linux-x86_64-static.zip\n"
        f"Substrate source: https://github.com/gluonhq/substrate/tree/{SUBSTRATE_VERSION}\n"
        "项目原生适配器：comment-desktop/src/main/native/jni-version.c 与 scripts/desktop_native_gcc.py。\n"
        "原始静态 SDK 不修改；只对构建目录内副本做 JNI 版本声明适配和符号导出。\n")
    sdk = cache / "sdk/lib"
    javafx_jars = [sdk / f"javafx.{name}.jar" for name in ["base", "graphics", "controls"]]
    flags = linker_environment(cache)
    for name in LIBRARIES:
        run(["objcopy", "--redefine-sym", f"JNI_OnLoad_{name}=original_JNI_OnLoad_{name}",
             sdk / f"lib{name}.a", work / f"lib{name}.a"])
    run(["gcc", "-c", "-fPIC", "-I" + str(java_home / "include"), "-I" + str(java_home / "include/linux"),
         module / "src/main/native/jni-version.c", "-o", work / "jni-version.o"])
    metadata(javafx_jars, substrate, work)
    dependencies = (target / "native-classpath.txt").read_text().strip().split(os.pathsep)
    dependencies = [path for path in dependencies if "/org/openjfx/" not in path]
    classpath = os.pathsep.join(map(str, [target / "classes", *javafx_jars, substrate, *dependencies]))
    link = [str(work / "jni-version.o"), "-Wl,--whole-archive"]
    link += [str(work / f"lib{name}.a") for name in LIBRARIES]
    link += ["-Wl,--no-whole-archive", *flags, "-lstdc++", "-lm"]
    command = [str(native_image), "--no-fallback", args.optimization, "--enable-native-access=ALL-UNNAMED",
        "-march=compatibility", "--parallelism=4", "-J-Xmx4g", "-H:+UnlockExperimentalVMOptions",
        "--add-exports=org.graalvm.nativeimage.builder/com.oracle.svm.core.jdk=ALL-UNNAMED",
        "--features=com.gluonhq.substrate.feature.GluonFeature", "-H:+AddAllCharsets",
        "-H:ReflectionConfigurationFiles=" + str(work / "reflect-config.json"),
        "-H:JNIConfigurationFiles=" + str(work / "jni-config.json"),
        "-H:ResourceConfigurationFiles=" + str(work / "resource-config.json"),
        "--native-compiler-path=" + str(repository / "scripts/desktop_native_gcc.py"),
        "-cp", classpath, "-o", str(target / "llm-comment-desktop")]
    command += ["-H:NativeLinkerOption=" + option for option in link]
    command += ["io.github.dreamlike.llmcomment.desktop.Main"]
    # 构建报告保留完整选项、SDK校验值，发布构建可确认实际采用 -O3。
    (work / "build-recipe.json").write_text(json.dumps({"command": command, "sdkVersion": SDK_VERSION,
        "sdkSha256": SDK_SHA, "substrateVersion": SUBSTRATE_VERSION, "substrateSha256": SUBSTRATE_SHA}, indent=2))
    run(command, cwd=module)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"桌面原生构建失败：{error}", file=sys.stderr)
        sys.exit(1)
