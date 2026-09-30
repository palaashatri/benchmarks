#!/usr/bin/env python3
"""External correctness checks; never benchmark measurements."""
import concurrent.futures
import contextlib
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKLOAD = sys.argv[1] if len(sys.argv) > 1 else ''
CLASSES = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else None
if WORKLOAD:
    del sys.argv[1:3]


def eventually(check, timeout=25):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                return value
        except (OSError, ValueError, KeyError, AssertionError) as exc:
            last = exc
        time.sleep(.05)
    raise AssertionError(f'condition did not become true: {last}')


def alive(pid):
    try:
        os.kill(pid, 0)
        stat = Path(f'/proc/{pid}/stat')
        return not stat.exists() or stat.read_text().split()[2] != 'Z'
    except ProcessLookupError:
        return False


class App:
    def __init__(self, java_args=()):
        self.java_args = java_args

    def __enter__(self):
        self.temp = tempfile.TemporaryDirectory(prefix='workload-test-')
        self.port_file = Path(self.temp.name) / 'ready.port'
        self.token = 'test-' + os.urandom(12).hex()
        self.log = open(Path(self.temp.name) / 'app.log', 'w+')
        env = dict(os.environ, PORT='0', BENCH_PORT_FILE=str(self.port_file),
                   BENCH_RUN_TOKEN=self.token)
        for key in ('BENCH_SERVICE_ROLE', 'BENCH_SERVICE_ID', 'BENCH_GENERATION', 'BENCH_PARENT_PID'):
            env.pop(key, None)
        self.process = subprocess.Popen([
            'java', *self.java_args, '-cp', str(CLASSES),
            f'com.palaashatri.bench.b{WORKLOAD[:2]}.app.BenchmarkApp', '0'],
            env=env, stdout=self.log, stderr=subprocess.STDOUT)
        self.children = set()
        try:
            self.port = int(eventually(lambda: self.port_file.read_text().strip()
                                      if self.port_file.exists() else None))
            self.base = f'http://127.0.0.1:{self.port}'
            identity = self.get('/runtime')
            assert identity['pid'] == self.process.pid and identity['run_token'] == self.token
            return self
        except BaseException:
            self.stop()
            self.log.seek(0)
            print(self.log.read(), file=sys.stderr)
            self.log.close()
            self.temp.cleanup()
            raise

    def request(self, path, data=None, method=None):
        req = urllib.request.Request(self.base + path,
            data=None if data is None else json.dumps(data).encode(),
            method=method or ('GET' if data is None else 'POST'),
            headers={'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as exc:
            return exc.code, json.load(exc)

    def get(self, path):
        status, body = self.request(path)
        assert status == 200, (status, body)
        return body

    def post(self, path, data=None):
        status, body = self.request(path, {} if data is None else data)
        assert 200 <= status < 300, (status, body)
        return body

    def stop(self):
        self.process.terminate()
        try:
            self.process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)

    def __exit__(self, exc_type, exc, tb):
        self.stop()
        leaked = []
        for pid in self.children:
            try:
                eventually(lambda: not alive(pid), 5)
            except AssertionError:
                leaked.append(pid)
                os.kill(pid, signal.SIGKILL)
        if exc:
            self.log.seek(0)
            print(self.log.read(), file=sys.stderr)
        self.log.close()
        self.temp.cleanup()
        if leaked:
            raise AssertionError(f'owned children leaked: {leaked}')


class Session:
    def __init__(self, port):
        self.socket = socket.create_connection(('127.0.0.1', port), timeout=5)
        self.socket.settimeout(5)
        self.reader = self.socket.makefile('rb')

    def send(self, value):
        self.socket.sendall(json.dumps(value).encode() + b'\n')

    def read(self):
        line = self.reader.readline()
        assert line, 'session unexpectedly closed'
        return json.loads(line)

    def close(self):
        try:
            self.socket.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.reader.close()
        self.socket.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


class ArchitectureTests(unittest.TestCase):
    @unittest.skipUnless(WORKLOAD[:2] in ('02', '10', '11'), 'process workloads only')
    def test_failed_gateway_bind_reaps_children_started_before_the_failure(self):
        with tempfile.TemporaryDirectory() as temporary, socket.socket() as occupied:
            occupied.bind(('127.0.0.1', 0))
            occupied.listen()
            port = occupied.getsockname()[1]
            token = 'failed-bind-' + os.urandom(8).hex()
            env = dict(os.environ, PORT=str(port), BENCH_RUN_TOKEN=token)
            for key in ('BENCH_SERVICE_ROLE', 'BENCH_PORT_FILE', 'BENCH_PARENT_PID'):
                env.pop(key, None)
            with open(Path(temporary) / 'parent.log', 'w+') as log:
                process = subprocess.Popen(['java', f'-Djava.io.tmpdir={temporary}', '-cp', str(CLASSES),
                    f'com.palaashatri.bench.b{WORKLOAD[:2]}.app.BenchmarkApp', str(port)],
                    env=env, stdout=log, stderr=subprocess.STDOUT)
                observed = set()
                deadline = time.monotonic() + 30
                try:
                    while process.poll() is None and time.monotonic() < deadline:
                        for ready in Path(temporary).glob('bench-child-*/ready.port'):
                            try:
                                with urllib.request.urlopen(f'http://127.0.0.1:{int(ready.read_text())}/runtime', timeout=.2) as response:
                                    identity = json.load(response)
                                if identity['run_token'].startswith(token):
                                    observed.add(identity['pid'])
                            except (OSError, ValueError):
                                pass
                        time.sleep(.02)
                    self.assertIsNotNone(process.poll(), 'startup failure did not terminate')
                    self.assertNotEqual(process.returncode, 0)
                    log.seek(0)
                    output = log.read()
                    self.assertIn('BindException', output, 'startup failed for an unexpected reason: ' + output)
                    self.assertTrue(observed, 'no child was observed before the failed gateway bind: ' + output)
                    for pid in observed:
                        eventually(lambda: not alive(pid), 5)
                    self.assertFalse(list(Path(temporary).glob('bench-child-*')))
                finally:
                    if process.poll() is None:
                        process.kill()
                    process.wait(timeout=5)
                    for pid in observed:
                        if alive(pid):
                            os.kill(pid, signal.SIGKILL)

    @unittest.skipUnless(WORKLOAD[:2] in ('02', '10', '11'), 'process workloads only')
    def test_forced_parent_exit_closes_owned_children(self):
        with App() as app:
            if WORKLOAD.startswith('02'):
                children = app.get('/health')['services']
            elif WORKLOAD.startswith('10'):
                children = app.get('/api/v1/fleet/status')['services']
            else:
                children = app.get('/api/v1/metrics/scaling')['replicas']
            app.children.update(child['pid'] for child in children)
            app.process.kill()
            app.process.wait(timeout=5)
            for pid in app.children:
                eventually(lambda: not alive(pid), 8)

    @unittest.skipUnless(WORKLOAD.startswith('02'), 'mesh only')
    def test_mesh_has_distinct_service_processes_and_real_calls(self):
        with App() as app:
            health = app.get('/health')
            self.assertEqual(health['external_processes'], 3)
            self.assertEqual(health['process_model'], 'multi-jvm-mesh')
            services = health['services']
            pids = {s['pid'] for s in services}
            app.children.update(pids)
            self.assertEqual(len(pids), 3)
            self.assertNotIn(app.process.pid, pids)
            self.assertTrue(all(alive(pid) for pid in pids))
            user = app.get('/api/v1/users/1001')
            self.assertEqual(user['account']['id'], '1001')
            order = app.post('/api/v1/orders', {'from_id': '1001', 'item': 'demo'})
            self.assertEqual(order['transaction']['status'], 'RECORDED')
            transaction = next(s for s in services if s['role'] == 'transaction')
            notification = next(s for s in services if s['role'] == 'notification')
            def child_get(service, path):
                with urllib.request.urlopen(f"http://127.0.0.1:{service['port']}{path}", timeout=5) as r:
                    return json.load(r)
            self.assertEqual(child_get(transaction, '/state')['transactions_retained'], 1)
            eventually(lambda: child_get(notification, '/state')['notifications_accepted'] >= 1)
            identity = child_get(transaction, '/runtime')
            self.assertEqual(identity['pid'], transaction['pid'])
            os.kill(transaction['pid'], signal.SIGTERM)
            eventually(lambda: app.request('/health')[0] == 503)
            self.assertEqual(app.request('/api/v1/orders', {'from_id': '1001'})[0], 503)

    @unittest.skipUnless(WORKLOAD.startswith('10'), 'fleet only')
    def test_fleet_deploy_replaces_only_selected_jvm(self):
        with App() as app:
            before = app.get('/api/v1/fleet/status')
            self.assertEqual(before['external_processes'], 5)
            old = {s['id']: s['pid'] for s in before['services']}
            app.children.update(old.values())
            self.assertEqual(len(set(old.values())), 5)
            value = app.get('/api/v1/service/0/inventory/item-1')
            replacement = app.post('/api/v1/fleet/deploy/0')
            app.children.add(replacement['new_pid'])
            self.assertTrue(replacement['external_process_restarted'])
            self.assertNotEqual(replacement['new_pid'], old[0])
            eventually(lambda: not alive(old[0]))
            after = app.get('/api/v1/fleet/status')
            self.assertEqual(after['services'][0]['generation'], 2)
            self.assertEqual([s['pid'] for s in after['services'][1:]], [old[i] for i in range(1,5)])
            self.assertNotEqual(app.get('/api/v1/service/0/inventory/item-1')['value'], value['value'])
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                futures = [pool.submit(app.post, '/api/v1/fleet/deploy/0') for _ in range(2)]
                probes = 0
                while not all(future.done() for future in futures):
                    self.assertEqual(app.get('/api/v1/service/1/inventory/item-1')['pid'], old[1])
                    probes += 1
                deploys = [future.result() for future in futures]
            self.assertGreater(probes, 0)
            app.children.update(d['new_pid'] for d in deploys)
            self.assertEqual(app.get('/api/v1/fleet/status')['services'][0]['generation'], 4)
            self.assertEqual(app.request('/api/v1/fleet/deploy/nope', {})[0], 400)
            self.assertEqual(app.request('/api/v1/service/99/inventory/item-1')[0], 404)
            self.assertEqual(app.request('/api/v1/fleet/deploy/1')[0], 405)

    @unittest.skipUnless(WORKLOAD.startswith('11'), 'autoscaling only')
    def test_burst_spawns_and_retires_real_replicas_without_losing_work(self):
        with App() as app:
            initial = app.get('/api/v1/metrics/scaling')
            self.assertEqual(initial['external_replicas'], 1)
            app.children.update(s['pid'] for s in initial['replicas'])
            with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
                responses = list(pool.map(lambda n: app.request('/api/v1/catalog/search',
                    {'query': f'item-{n}', 'work_ms': 150}), range(60)))
            self.assertTrue(all(status == 202 for status, _ in responses), responses)
            self.assertTrue(all(body['completed'] is False for _, body in responses))
            def scaled():
                state = app.get('/api/v1/metrics/scaling')
                app.children.update(s['pid'] for s in state['replicas'])
                return state if state['external_replicas'] > 1 else None
            state = eventually(scaled)
            self.assertEqual(state['scaling_model'], 'local-process-replicas')
            self.assertLessEqual(state['external_replicas'], 4)
            self.assertEqual(len({s['pid'] for s in state['replicas']}), state['external_replicas'])
            eventually(lambda: app.get('/api/v1/metrics/scaling')['completed'] == 60)
            state = eventually(lambda: (s if (s := app.get('/api/v1/metrics/scaling'))['external_replicas'] == 1
                                       and s['scale_down_count'] > 0 else None), 30)
            self.assertEqual(state['accepted'], 60)
            self.assertEqual(state['failed'], 0)
            self.assertEqual(state['queue_depth'], 0)
            self.assertEqual(app.request('/api/v1/catalog/search')[0], 405)

    @unittest.skipUnless(WORKLOAD.startswith('11'), 'autoscaling only')
    def test_replica_death_accounts_failed_inflight_work_and_drains_the_queue(self):
        with App() as app:
            initial = app.get('/api/v1/metrics/scaling')['replicas'][0]['pid']
            app.children.add(initial)
            with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
                responses = list(pool.map(lambda n: app.request('/api/v1/catalog/search',
                    {'query': str(n), 'work_ms': 500}), range(40)))
            self.assertTrue(all(status == 202 for status, _ in responses))
            eventually(lambda: app.get('/api/v1/metrics/scaling')['active_workers'] >= 2)
            os.kill(initial, signal.SIGKILL)
            def accounted():
                state = app.get('/api/v1/metrics/scaling')
                app.children.update(replica['pid'] for replica in state['replicas'])
                return state if state['completed'] + state['failed'] == 40 else None
            state = eventually(accounted, 30)
            self.assertGreater(state['failed'], 0)
            self.assertGreater(state['completed'], 0)
            self.assertEqual(state['queue_depth'], 0)
            self.assertGreaterEqual(state['external_replicas'], 1)
            self.assertLessEqual(state['external_replicas'], 4)

    @unittest.skipUnless(WORKLOAD.startswith('11'), 'autoscaling only')
    def test_admission_rejects_overload_and_keeps_queue_bounded(self):
        with App() as app:
            app.children.update(replica['pid'] for replica in app.get('/api/v1/metrics/scaling')['replicas'])
            with concurrent.futures.ThreadPoolExecutor(max_workers=48) as pool:
                responses = list(pool.map(lambda n: app.request('/api/v1/catalog/search',
                    {'query': str(n), 'work_ms': 500}), range(1200)))
            self.assertTrue(all(status in (202, 429) for status, _ in responses))
            self.assertTrue(any(status == 429 for status, _ in responses), 'overload was not rejected')
            state = app.get('/api/v1/metrics/scaling')
            app.children.update(replica['pid'] for replica in state['replicas'])
            self.assertLessEqual(state['queue_depth'], 1000)
            self.assertEqual(state['accepted'], sum(status == 202 for status, _ in responses))
            self.assertEqual(state['rejected'], sum(status == 429 for status, _ in responses))

    @unittest.skipUnless(WORKLOAD.startswith('06'), 'chat only')
    def test_persistent_chat_fanout_room_isolation_and_disconnect_cleanup(self):
        with App() as app:
            health = app.get('/health')
            self.assertTrue(health['persistent_connections'])
            port = health['tcp_port']
            with Session(port) as alice, Session(port) as bob, Session(port) as outsider:
                for session, user, room in ((alice,'alice','team'), (bob,'bob','team'), (outsider,'carol','other')):
                    session.send({'op':'subscribe','room':room,'user':user})
                    self.assertEqual(session.read()['subscribed'], True)
                for n in range(2):
                    result = app.post('/rooms/team/messages', {'sender':'alice','content':f'hello {n} "\\\n'})
                    self.assertEqual(result['queued_deliveries'], 2)
                    self.assertEqual(alice.read()['content'], f'hello {n} "\\\n')
                    self.assertEqual(bob.read()['message_id'], result['message_id'])
                outsider.send({'op':'ping'})
                self.assertEqual(outsider.read()['op'], 'pong')
                alice.send({'op':'invalid'})
                self.assertEqual(alice.read()['error'], 'unknown_operation')
                alice.send({'op':'ping'})
                self.assertEqual(alice.read()['op'], 'pong')
                self.assertEqual(app.get('/api/v1/stats')['persistent_connections'], 3)
                eventually(lambda: app.get('/api/v1/stats')['socket_deliveries'] == 4)
            eventually(lambda: app.get('/api/v1/stats')['persistent_connections'] == 0)
            self.assertEqual(app.get('/api/v1/stats')['subscribers'], 0)
            self.assertEqual(app.post('/rooms/team/messages', {'content':'no readers'})['queued_deliveries'], 0)
            with Session(port) as bad:
                bad.socket.sendall(b'x' * 65537 + b'\n')
                self.assertEqual(bad.read()['error'], 'frame_too_large')

    @unittest.skipUnless(WORKLOAD.startswith('06'), 'chat only')
    def test_chat_disconnect_during_subscribe_does_not_leave_memberships(self):
        import struct
        with App() as app:
            port = app.get('/health')['tcp_port']
            for _ in range(150):
                peer = Session(port)
                peer.send({'op':'subscribe','room':'race','user':'one'})
                peer.read()
                peer.send({'op':'subscribe','room':'other-race','user':'one'})
                peer.socket.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0))
                peer.reader.close()
                peer.socket.close()
            eventually(lambda: app.get('/api/v1/stats')['persistent_connections'] == 0)
            eventually(lambda: app.get('/api/v1/stats')['subscribers'] == 0, 5)

    @unittest.skipUnless(WORKLOAD.startswith('06'), 'chat only')
    def test_oversized_input_disconnects_a_stalled_chat_reader(self):
        with App() as app:
            port = app.get('/health')['tcp_port']
            peer = socket.socket()
            peer.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
            peer.settimeout(5)
            peer.connect(('127.0.0.1', port))
            reader = peer.makefile('rb')
            try:
                peer.sendall(b'{"op":"subscribe","room":"stall","user":"slow"}\n')
                self.assertTrue(json.loads(reader.readline())['subscribed'])
                for _ in range(200):
                    app.post('/rooms/stall/messages', {'content': 'z' * 16384})
                self.assertEqual(app.get('/api/v1/stats')['persistent_connections'], 1)
                peer.sendall(b'x' * 65537 + b'\n')
                eventually(lambda: app.get('/api/v1/stats')['persistent_connections'] == 0, 5)
                self.assertEqual(app.get('/api/v1/stats')['subscribers'], 0)
                with Session(port) as healthy:
                    healthy.send({'op':'ping'})
                    self.assertEqual(healthy.read()['op'], 'pong')
            finally:
                reader.close(); peer.close()

    @unittest.skipUnless(WORKLOAD.startswith('12'), 'trading only')
    def test_persistent_trading_transport_shares_order_book_with_http(self):
        with App() as app:
            health = app.get('/health')
            self.assertEqual(health['transport'], 'http-and-framed-tcp')
            self.assertFalse(health['grpc_active'])
            buy = app.post('/orders', {'symbol':'ALPHA','side':'BUY','quantity':100,'price_nanos':150})
            with Session(health['tcp_port']) as session:
                session.send({'op':'SubmitOrder','symbol':'BETA','side':'SELL','quantity':100,'price_nanos':100})
                other = session.read()
                self.assertTrue(other['accepted'])
                self.assertEqual(app.get('/orders/' + buy['order_id'])['remaining_quantity'], 100)
                session.send({'op':'SubmitOrder','symbol':'ALPHA','side':'SELL','quantity':40,'price_nanos':140})
                sell = session.read()
                self.assertEqual(app.get('/orders/' + sell['order_id'])['status'], 'FILLED')
                session.send({'op':'GetOrderStatus','order_id':buy['order_id']})
                partial = session.read()
                self.assertEqual(partial['status'], 'PARTIALLY_FILLED')
                self.assertEqual(partial['remaining_quantity'], 60)
                session.send({'op':'CancelOrder','order_id':buy['order_id']})
                self.assertTrue(session.read()['accepted'])
                self.assertEqual(app.get('/orders/' + buy['order_id'])['status'], 'CANCELLED')
                for malformed in (b'{not json}\n', b'{\"op\":\"GetOrderStatus\",\"order_id\":\"\\u+123\"}\n'):
                    session.socket.sendall(malformed)
                    self.assertEqual(session.read()['error'], 'invalid_frame')
                session.send({'op':'SubmitOrder','symbol':'X','side':'NOPE','quantity':1,'price_nanos':1})
                self.assertFalse(session.read()['accepted'])
                session.send({'op':'GetOrderStatus','order_id':other['order_id']})
                self.assertEqual(session.read()['status'], 'OPEN')
                first = app.post('/orders', {'symbol':'FIFO','side':'BUY','quantity':30,'price_nanos':150})
                second = app.post('/orders', {'symbol':'FIFO','side':'BUY','quantity':40,'price_nanos':150})
                session.send({'op':'SubmitOrder','symbol':'FIFO','side':'SELL','quantity':50,'price_nanos':150})
                self.assertTrue(session.read()['accepted'])
                self.assertEqual(app.get('/orders/' + first['order_id'])['status'], 'FILLED')
                self.assertEqual(app.get('/orders/' + second['order_id'])['remaining_quantity'], 20)
            with Session(health['tcp_port']) as bad:
                bad.socket.sendall(b'x' * 65537 + b'\n')
                self.assertEqual(bad.read()['error'], 'frame_too_large')


if __name__ == '__main__':
    unittest.main(verbosity=2)
