#!/usr/bin/env python3
"""实际启动 CLI/MCP 程序，检查原生发布产物的持久化及协议行为。"""
import argparse
import json
from pathlib import Path
import queue
import subprocess
import sys
import tempfile
import threading

TIMEOUT = 30


def require(condition, message):
    if not condition:
        raise AssertionError(message)


class Cli:
    def __init__(self, executable, workspace):
        self.command = [executable, '--workspace', str(workspace)]

    def call(self, operation, **args):
        command = self.command + [operation]
        if operation == 'create':
            command += [args.pop('file'), '--lines', str(args.pop('startLine'))]
        elif operation in ('get', 'update', 'delete', 'history'):
            command += [args.pop('id')]
        elif operation == 'snapshot':
            command += [args.pop('fileVersionId')]
        names = {'baseRevisionId': '--base-revision', 'includeDeleted': '--include-deleted'}
        for key, value in args.items():
            command += [names.get(key, '--' + key)]
            if not isinstance(value, bool):
                command += [str(value)]
        result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', timeout=TIMEOUT)
        if result.returncode:
            require(not result.stdout, f'CLI错误污染stdout: {result.stdout}')
            return True, json.loads(result.stderr)
        require(not result.stderr, f'CLI产生非预期stderr: {result.stderr}')
        return False, json.loads(result.stdout)

    def close(self):
        pass


class Mcp:
    def __init__(self, executable, workspace, runtime_args=()):
        self.process = subprocess.Popen([executable, *runtime_args, '--workspace', str(workspace)], stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8')
        self.responses = queue.Queue()
        self.errors = []
        self.sequence = 0
        # 独立线程持续读取，避免管道填满，并为每条响应设置超时。
        def read_stdout():
            for line in self.process.stdout:
                self.responses.put(line)
            self.responses.put(None)
        def read_stderr():
            self.errors.extend(self.process.stderr.readlines())
        self.output_thread = threading.Thread(target=read_stdout, daemon=True)
        self.error_thread = threading.Thread(target=read_stderr, daemon=True)
        self.output_thread.start()
        self.error_thread.start()
        try:
            self.request('initialize', {'protocolVersion': '2025-11-25', 'capabilities': {},
                                       'clientInfo': {'name': 'native-smoke', 'version': '1'}})
            self.send({'jsonrpc': '2.0', 'method': 'notifications/initialized'})
            tools = self.request('tools/list', {})['tools']
            require({tool['name'] for tool in tools} == {'comment_' + name for name in
                    ('create', 'get', 'list', 'update', 'delete', 'history', 'snapshot')}, 'MCP工具列表不完整')
        except Exception:
            self.process.kill()
            self.process.wait(timeout=TIMEOUT)
            raise

    def send(self, message):
        self.process.stdin.write(json.dumps(message, ensure_ascii=False) + '\n')
        self.process.stdin.flush()

    def request(self, method, params):
        self.sequence += 1
        self.send({'jsonrpc': '2.0', 'id': self.sequence, 'method': method, 'params': params})
        try:
            line = self.responses.get(timeout=TIMEOUT)
        except queue.Empty as error:
            raise AssertionError(f'MCP响应超时: {method}; stderr={"".join(self.errors)}') from error
        require(line is not None, f'MCP提前退出: {"".join(self.errors)}')
        response = json.loads(line)
        require(response.get('jsonrpc') == '2.0' and response.get('id') == self.sequence,
                f'MCP响应标识不匹配: {response}')
        require('error' not in response, f'MCP协议错误: {response}')
        return response['result']

    def call(self, operation, **args):
        result = self.request('tools/call', {'name': 'comment_' + operation, 'arguments': args})
        data = result['structuredContent']
        require(json.loads(result['content'][0]['text']) == data, 'MCP文本结果与结构化结果不同')
        if operation == 'list' and not result['isError']:
            data = data['comments']
        if operation == 'history' and not result['isError']:
            data = data['revisions']
        return result['isError'], data

    def close(self):
        self.process.stdin.close()
        try:
            code = self.process.wait(timeout=TIMEOUT)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
            raise AssertionError('MCP在stdin EOF后没有退出')
        self.error_thread.join(timeout=1)
        require(code == 0, f'MCP退出码{code}: {"".join(self.errors)}')
        require(not self.errors, f'MCP产生非预期stderr: {"".join(self.errors)}')


def exercise(client, workspace):
    source = workspace / '示例.java'
    original = '// 第一行\r\nclass 示例 {}\r\n// 第三行\r\n'.encode('utf-8')
    source.write_bytes(original)

    def ok(operation, **args):
        failure, data = client.call(operation, **args)
        require(not failure, f'{operation}失败: {data}')
        return data

    created = ok('create', file=source.name, startLine=2, body='中文评论\n第二段', author='集成检查')
    identity, revision = created['id'], created['currentRevisionId']
    snapshot = created['location']['fileVersionId']
    require(created['location']['startLine'] == 2, '创建行号错误')
    require(len(ok('list', file=source.name)) == 1, '列表遗漏评论')
    require(source.read_bytes() == original, '创建或查询修改了源码')

    moved_text = '// 前置新增\r\n'.encode('utf-8') + original
    source.write_bytes(moved_text)
    moved = ok('get', id=identity)
    require(moved['location']['startLine'] == 3, '前置插入未迁移行号')
    require(moved['currentRevisionId'] == revision, '位置迁移新增了正文版本')
    require(len(ok('history', id=identity)) == 1, '位置迁移污染历史')
    require(ok('snapshot', fileVersionId=snapshot)['text'].encode('utf-8') == original, '原始快照被覆盖')

    source.unlink()
    missing = ok('get', id=identity)
    require(missing['location']['status'] == 'MISSING_FILE', '文件缺失状态错误')
    require(missing['currentRevisionId'] == revision, '文件缺失改变正文版本')
    source.write_bytes(moved_text)
    restored = ok('get', id=identity)
    require(restored['location']['startLine'] == 3 and restored['location']['status'] in ('ACTIVE', 'MODIFIED'),
            '文件恢复后未重新定位')

    updated = ok('update', id=identity, baseRevisionId=revision, body='更新的中文正文', author='集成检查')
    require(updated['currentRevisionId'] != revision and updated['body'] == '更新的中文正文', '更新未追加版本')
    failure, conflict = client.call('update', id=identity, baseRevisionId=revision, body='旧版本冲突', author='集成检查')
    require(failure and 'CONFLICT' in conflict['code'], f'旧版本未返回冲突: {conflict}')
    history = ok('history', id=identity)
    require(len(history) == 2 and history[0]['id'] == revision, '历史版本数量或顺序错误')
    require(history[0]['body'] == '中文评论\n第二段' and history[0]['location']['startLine'] == 2,
            '原始历史版本被改变')
    require(ok('snapshot', fileVersionId=snapshot)['text'].encode('utf-8') == original, '更新破坏原始快照')
    deleted = ok('delete', id=identity, baseRevisionId=updated['currentRevisionId'], author='集成检查')
    require(deleted['deleted'], '删除未标记评论')
    require(ok('list') == [], '默认列表包含删除评论')
    require(len(ok('list', includeDeleted=True)) == 1, '包含删除的列表遗漏评论')
    require(ok('get', id=identity)['deleted'], '删除后不能读取评论')
    require(len(ok('history', id=identity)) == 3, '删除未保留历史')
    require(source.read_bytes() == moved_text, '评论操作修改了源码')


def exercise_shared(cli_executable, mcp_executable):
    """两个入口通过同一数据库读取历史并检测过时更新。"""
    with tempfile.TemporaryDirectory(prefix='llm-comment-shared-') as temporary:
        workspace = Path(temporary)
        source = workspace / 'shared.java'
        source.write_text('first\ntarget\nlast\n', encoding='utf-8')
        cli = Cli(str(Path(cli_executable).resolve()), workspace)
        mcp = Mcp(str(Path(mcp_executable).resolve()), workspace)
        try:
            failed, created = cli.call('create', file=source.name, startLine=2, body='CLI创建')
            require(not failed, f'共享库创建失败: {created}')
            failed, fetched = mcp.call('get', id=created['id'])
            require(not failed and fetched['currentRevisionId'] == created['currentRevisionId'], 'MCP未读取CLI创建的评论')
            failed, updated = mcp.call('update', id=created['id'], baseRevisionId=created['currentRevisionId'], body='MCP更新')
            require(not failed, f'共享库更新失败: {updated}')
            failed, fetched = cli.call('get', id=created['id'])
            require(not failed and fetched['body'] == 'MCP更新', 'CLI未读取MCP更新的评论')
            failed, conflict = cli.call('update', id=created['id'], baseRevisionId=created['currentRevisionId'], body='过时修改')
            require(failed and conflict['code'] == 'CONFLICT', '跨入口修改未检测过时版本')
            source.write_text('inserted\nfirst\ntarget\nlast\n', encoding='utf-8')
            _, moved_mcp = mcp.call('get', id=created['id'])
            _, moved_cli = cli.call('get', id=created['id'])
            require(moved_mcp['location'] == moved_cli['location'] and moved_cli['location']['startLine'] == 3,
                    '两个入口的自动定位不一致')
        finally:
            mcp.close()
    print('CLI/MCP 共享数据库检查通过')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cli')
    parser.add_argument('--mcp')
    args = parser.parse_args()
    if not args.cli and not args.mcp:
        parser.error('至少提供 --cli 或 --mcp')
    for name, executable, implementation in [('CLI', args.cli, Cli), ('MCP', args.mcp, Mcp)]:
        if not executable:
            continue
        with tempfile.TemporaryDirectory(prefix='llm-comment-native-') as temporary:
            client = None
            try:
                client = implementation(str(Path(executable).resolve()), Path(temporary))
                exercise(client, Path(temporary))
            finally:
                if client is not None:
                    client.close()
        print(f'{name} 原生集成检查通过')
    if args.cli and args.mcp:
        exercise_shared(args.cli, args.mcp)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(f'原生集成检查失败: {error}', file=sys.stderr)
        sys.exit(1)
