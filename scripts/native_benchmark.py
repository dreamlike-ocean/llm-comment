#!/usr/bin/env python3
"""比较两个 MCP 原生程序：持久进程中的列表往返耗时，包含协议传输和 JSON 解析。"""
import argparse
import hashlib
import json
import platform
from pathlib import Path
import sqlite3
import statistics
import tempfile
import time

from native_smoke import Mcp, require


def percentile(values, ratio):
    values = sorted(values)
    position = (len(values) - 1) * ratio
    low = int(position)
    high = min(low + 1, len(values) - 1)
    return values[low] + (values[high] - values[low]) * (position - low)


def prepare(client, workspace, lines, count):
    failed, first = client.call('create', file='code.txt', startLine=2, body='性能测量', author='benchmark')
    require(not failed, f'创建失败: {first}')
    starts = [0]
    for line in lines:
        starts.append(starts[-1] + len(line.encode('utf-16-le')) // 2)
    source = ''.join(lines)
    encoded = source.encode('utf-16-le')
    expected = {first['id']: 2}
    with sqlite3.connect(workspace / '.llm-comments/comments.db') as connection:
        # 只在准备阶段通过 SQL 批量填充等价记录；计时阶段全部通过 MCP 的公开接口访问。
        for i in range(1, count):
            line = 2 + i * 8
            identity, revision = f'comment-{i}', f'revision-{i}'
            start, end = starts[line - 1], starts[line] - 2
            # Unicode 上下文不能截断代理对，边缘半个字符直接忽略。
            before = encoded[max(0, start - 64) * 2:start * 2].decode('utf-16-le', errors='ignore')
            after = encoded[end * 2:min(len(encoded), (end + 64) * 2)].decode('utf-16-le', errors='ignore')
            selected = lines[line - 1][:-2]
            connection.execute(
                'INSERT INTO comments SELECT ?,file,?,body,author,created,updated,deleted,?,?,?,?,?,? '
                'FROM comments WHERE id=?',
                (identity, revision, start, end, 'ACTIVE', selected, before, after, first['id']))
            connection.execute(
                'INSERT INTO revisions(id,comment_id,parent,action,body,author,created,snapshot,'
                'start,end,start_line,end_line,status,selected) '
                'SELECT ?,?,parent,action,body,author,created,snapshot,?,?,?,?,?,? FROM revisions WHERE id=?',
                (revision, identity, start, end, line, line, 'ACTIVE', selected, first['currentRevisionId']))
            expected[identity] = line
    return expected


def measure(executable, warmup, samples, forks, heap, cases):
    digits = '零一二三四五六七八九'
    body = '春风吹过青山绿水程序逐行记录评论位置文件快照保存历史版本'
    lines = []
    for i in range(8192):
        prefix = '第' + ''.join(digits[int(digit)] for digit in f'{i:05d}') + '行'
        lines.append(prefix + (body * 128)[:126 - len(prefix)] + '\r\n')
    source = ''.join(lines)
    all_samples = {case: [] for case in cases if case != 'get-nearmiss'}
    for fork in range(forks if all_samples else 0):
        with tempfile.TemporaryDirectory(prefix='comment-native-bench-') as temporary:
            workspace = Path(temporary)
            target = workspace / 'code.txt'
            target.write_bytes(source.encode('utf-8'))
            client = Mcp(str(Path(executable).resolve()), workspace, [f'-Xmx{heap}'])
            try:
                expected = prepare(client, workspace, lines, 1000)
                for case in all_samples:
                    rows = []
                    for sample in range(-warmup, samples):
                        inserted = case == 'list-changed' and sample % 2 == 0
                        if case == 'list-changed':
                            target.write_bytes((('前置新增内容\r\n' if inserted else '') + source).encode('utf-8'))
                        start = time.perf_counter_ns()
                        failed, comments = client.call('list', file='code.txt')
                        elapsed = (time.perf_counter_ns() - start) / 1e6
                        require(not failed and len(comments) == 1000, '列表失败或数量错误')
                        for comment in comments:
                            require(comment['location']['status'] == 'ACTIVE', '意外的定位状态')
                            require(comment['location']['startLine'] == expected[comment['id']] + int(inserted),
                                    '自动定位行号错误')
                        if sample >= 0:
                            rows.append(elapsed)
                    all_samples[case].extend(rows)
                    print(f'{Path(executable).name} fork={fork} {case}: p50={statistics.median(rows):.3f} ms', flush=True)
            finally:
                client.close()
    if 'get-nearmiss' in cases:
        all_samples['get-nearmiss'] = measure_nearmiss(executable, warmup, samples, forks, heap)
    return {case: {'p50_ms': statistics.median(values), 'p95_ms': percentile(values, .95),
                   'samples_ms': values} for case, values in all_samples.items()}


def measure_nearmiss(executable, warmup, samples, forks, heap):
    """通过持续变更文件触发失效范围恢复，测量长中文前缀搜索的实际协议耗时。"""
    values = []
    pattern = '甲' * 10000 + '丙'
    for fork in range(forks):
        with tempfile.TemporaryDirectory(prefix='comment-native-search-') as temporary:
            workspace = Path(temporary)
            target = workspace / 'code.txt'
            target.write_text('独立文件头\n' + pattern + '\n独立文件尾', encoding='utf-8')
            client = Mcp(str(Path(executable).resolve()), workspace, [f'-Xmx{heap}'])
            try:
                failed, comment = client.call('create', file='code.txt', startLine=2, body='寻找原文')
                require(not failed, '搜索场景创建失败')
                # 初次使范围失效，随后每次编辑都触发同一原文的恢复搜索。
                target.write_text('甲' * 1000000 + '乙', encoding='utf-8')
                failed, missing = client.call('get', id=comment['id'])
                require(not failed and missing['location']['status'] == 'ORPHANED', '失效状态错误')
                rows = []
                for sample in range(-warmup, samples):
                    target.write_text('甲' * 1000000 + ('丁' if sample % 2 == 0 else '乙'), encoding='utf-8')
                    start = time.perf_counter_ns()
                    failed, missing = client.call('get', id=comment['id'])
                    elapsed = (time.perf_counter_ns() - start) / 1e6
                    require(not failed and missing['location']['status'] == 'ORPHANED', '搜索错误绑定评论')
                    require(missing['currentRevisionId'] == comment['currentRevisionId'], '搜索改变正文版本')
                    if sample >= 0:
                        rows.append(elapsed)
                values.extend(rows)
                print(f'{Path(executable).name} fork={fork} get-nearmiss: p50={statistics.median(rows):.3f} ms',
                      flush=True)
            finally:
                client.close()
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before', required=True)
    parser.add_argument('--after', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--warmup', type=int, default=3)
    parser.add_argument('--samples', type=int, default=10)
    parser.add_argument('--forks', type=int, default=2)
    parser.add_argument('--heap', default='512m', help='两版均采用同一 Native Image 堆上限')
    parser.add_argument('--cases', nargs='+', choices=['list-unchanged', 'list-changed', 'get-nearmiss'],
                        default=['list-unchanged', 'list-changed', 'get-nearmiss'])
    args = parser.parse_args()
    if min(args.samples, args.forks) < 1 or args.warmup < 0:
        parser.error('samples/forks 必须为正，warmup 不得为负')
    report = {'environment': platform.platform(), 'configuration': vars(args),
              'sha256': {label: hashlib.sha256(Path(executable).read_bytes()).hexdigest()
                         for label, executable in [('before', args.before), ('after', args.after)]},
              'input': {'utf16_units': 1048576, 'utf8_bytes': 3112960, 'comments': 1000, 'language': 'chinese'},
              'nearmiss_input': {'utf16_units': 1000001, 'pattern_utf16_units': 10001, 'comments': 1},
              'measurement': '每 fork 一个持久 MCP 进程；中文主体8192行、1000个均匀分布评论。计时包含 '
              'MCP stdio 往返、SQL、源码读取、定位、JSON 编解码及客户端结构化结果一致性检查；'
              '不含进程启动、建库、外部源码写入和行号校验。'}
    for label, executable in [('before', args.before), ('after', args.after)]:
        report[label] = measure(executable, args.warmup, args.samples, args.forks, args.heap, args.cases)
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        Path(args.output).write_text(json.dumps(report, indent=2, ensure_ascii=False))
    for case in report['before']:
        before, after = report['before'][case]['p50_ms'], report['after'][case]['p50_ms']
        print(f'{case}: {before:.3f} -> {after:.3f} ms ({before / after:.2f}x)')


if __name__ == '__main__':
    main()
