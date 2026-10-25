#!/usr/bin/env python3
"""独立 JVM 性能基准：仅测 relocate 或 CommentService 调用；不含输入/建库/写文件/校验。"""

import argparse
import hashlib
import json
import os
import pathlib
import platform
import statistics
import subprocess
import sys
import tempfile
import time

CASES = (
    "anchor-single",
    "anchor-single-unchanged",
    "anchor-small-many",
    "anchor-prefix",
    "anchor-wide",
    "anchor-wide-internal",
    "anchor-repeat",
    "anchor-rewrite",
    "service-unchanged",
    "service-changed",
    "service-get",
    "anchor-chinese-prefix",
    "anchor-chinese-wide",
    "anchor-chinese-wide-internal",
    "anchor-chinese-repeat",
    "anchor-chinese-rewrite",
    "anchor-chinese-nearmiss",
    "anchor-emoji-prefix",
    "anchor-chinese-small-many",
    "service-chinese-unchanged",
    "service-chinese-changed",
    "service-chinese-get",
)
PROBE = pathlib.Path(__file__).with_name("PerformanceProbe.java")


def positive(value):
    result = int(value)
    if result < 1:
        raise argparse.ArgumentTypeError("必须大于零")
    return result


def nonnegative(value):
    result = int(value)
    if result < 0:
        raise argparse.ArgumentTypeError("不得小于零")
    return result


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument(
        "--baseline-jar",
        type=pathlib.Path,
        help="可选：先以同一配置测量基线，再测候选并输出倍率",
    )
    parser.add_argument("--warmup", type=nonnegative, default=10)
    parser.add_argument("--samples", type=positive, default=20)
    parser.add_argument("--forks", type=positive, default=3)
    parser.add_argument("--cases", nargs="+", choices=CASES, default=list(CASES))
    parser.add_argument("--java", default="java")
    parser.add_argument("--javac", default="javac")
    args = parser.parse_args()
    for jar in (args.jar, args.baseline_jar):
        if jar is not None and not jar.is_file():
            parser.error(f"JAR 不存在：{jar}")
    if len(set(args.cases)) != len(args.cases):
        parser.error("--cases 不得重复")
    return args


def run(command):
    result = subprocess.run(command, text=True, capture_output=True)
    if result.returncode:
        # 不隐藏子进程的断言、编译或存储错误。
        raise RuntimeError(
            f"命令失败（exit={result.returncode}）：{command!r}\n"
            f"{result.stdout}\n{result.stderr}"
        )
    return result.stdout


def percentile(values, quantile):
    values = sorted(values)
    position = (len(values) - 1) * quantile
    lower = int(position)
    upper = min(lower + 1, len(values) - 1)
    return values[lower] + (values[upper] - values[lower]) * (position - lower)


def save(path, report):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")


def main():
    args = arguments()
    baseline_output = pathlib.Path(str(args.output) + ".baseline.json")
    if args.baseline_jar:
        common = [
            "--warmup", str(args.warmup),
            "--samples", str(args.samples),
            "--forks", str(args.forks),
            "--java", args.java,
            "--javac", args.javac,
            "--cases", *args.cases,
        ]
        subprocess.run(
            [sys.executable, __file__, "--jar", str(args.baseline_jar),
             "--output", str(baseline_output), *common],
            check=True,
        )

    jar = args.jar.resolve()
    configuration = {
        key: str(value) if isinstance(value, pathlib.Path) else value
        for key, value in vars(args).items()
    }
    report = {
        "environment": {
            "platform": platform.platform(),
            "java": subprocess.run(
                [args.java, "-version"], capture_output=True, text=True, check=True
            ).stderr,
            "cpu_count": os.cpu_count(),
            "processor": platform.processor(),
            "jar_sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "probe_sha256": hashlib.sha256(PROBE.read_bytes()).hexdigest(),
        },
        "configuration": configuration,
        "measurement": (
            "nanoTime 围绕单次 relocate/list/get；固定输入，大文件约 1MiB UTF-16、"
            "1000 评论均匀分布；single 用 100 行/1 评论；repeat 用 8192 相同行。"
            "预热不记录；输入构造、建库、写文件、校验不计时，setupNanos 单独记录。"
            "allocatedBytes 为当前线程分配量，不含其他线程/native；每 case/fork 新进程，"
            "-Xms128m -Xmx512m；wide 为同一文件中跨大跨度的多处编辑；"
            "p50/p95 对所有 fork 的测量样本合并后线性插值，非吞吐量。"
        ),
        "results": {},
    }
    start = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="comment-probe-") as temporary:
        run([args.javac, "-cp", str(jar), "-d", temporary, str(PROBE)])
        for case in args.cases:
            forks = []
            for fork in range(args.forks):
                command = [
                    args.java, "-Xms128m", "-Xmx512m", "-cp",
                    temporary + os.pathsep + str(jar), "PerformanceProbe",
                    case, str(args.warmup), str(args.samples),
                ]
                rows = [
                    json.loads(line)
                    for line in run(command).splitlines()
                    if line.startswith("{")
                ]
                if len(rows) != args.samples:
                    raise RuntimeError(f"{case} fork {fork} 样本数错误：{len(rows)}")
                forks.append({"fork": fork, "command": command, "samples": rows})
            rows = [row for fork in forks for row in fork["samples"]]
            milliseconds = [row["nanos"] / 1e6 for row in rows]
            allocated = [row["allocatedBytes"] for row in rows]
            result = {
                "input": rows[0]["input"],
                "p50_ms": percentile(milliseconds, 0.5),
                "p95_ms": percentile(milliseconds, 0.95),
                "mean_ms": statistics.mean(milliseconds),
                "allocated_bytes_p50": percentile(allocated, 0.5),
                "states": sorted(set(row["states"] for row in rows)),
                "forks": forks,
            }
            report["results"][case] = result
            print(case, json.dumps({key: value for key, value in result.items()
                                    if key != "forks"}), flush=True)
            save(args.output, report)
    report["elapsed_seconds"] = time.monotonic() - start
    if args.baseline_jar:
        baseline = json.loads(baseline_output.read_text(encoding="utf-8"))
        report["comparison"] = {
            case: {
                "p50_speedup": baseline["results"][case]["p50_ms"] / result["p50_ms"],
                "p95_speedup": baseline["results"][case]["p95_ms"] / result["p95_ms"],
            }
            for case, result in report["results"].items()
        }
    save(args.output, report)


if __name__ == "__main__":
    main()
