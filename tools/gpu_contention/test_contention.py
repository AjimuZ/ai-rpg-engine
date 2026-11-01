"""No-GPU self-test of the contention harness: arguments, output format, unavailable cases.

Everything runs against stand-ins: local HTTP servers on 127.0.0.1 for the text, image,
speech and metrics routes, and standin_tool.py for nvidia-smi, docker and the competitor.
Nothing here touches a GPU, a real model server, the engine or a container, so a pass says
the harness handles arguments and writes its file as documented, not that any server or
card behaves as the harness expects.
"""
import contextlib
import io
import json
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

import contention

STANDIN = str(Path(__file__).resolve().parent / 'standin_tool.py')
SESSION = 'abcdefghij' * 4 + 'klm'
MODEL_NAME = 'model-secret-name'
OOM_LINE = 'CUDA error: out of memory'


class Server:
    """A local HTTP stand-in; ``routes`` maps (method, path) to ``handler -> (status, body bytes)``."""

    def __init__(self, routes):
        self.requests = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def _serve(self, method):
                body = self.rfile.read(int(self.headers.get('Content-Length') or 0))
                outer.requests.append((method, self.path, self.headers.get('Cookie'), body))
                route = routes.get((method, self.path))
                status, payload = route(self) if route else (404, b'')
                self.send_response(status)
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def do_GET(self):
                self._serve('GET')

            def do_POST(self):
                self._serve('POST')

            def log_message(self, *args):
                pass

        self._httpd = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.port = self._httpd.server_address[1]
        self.url = f'http://127.0.0.1:{self.port}'
        threading.Thread(target=self._httpd.serve_forever, daemon=True).start()

    def close(self):
        self._httpd.shutdown()
        self._httpd.server_close()


def ok(body=b'{}'):
    return lambda handler: (200, body)


def python_tool(flag, mode, *more):
    """Arguments that make the harness run standin_tool.py in ``mode`` as nvidia-smi, docker or the competitor."""
    return [flag, sys.executable, f'{flag}-arg=-B', f'{flag}-arg={STANDIN}', f'{flag}-arg=--mode={mode}',
            *[f'{flag}-arg={item}' for item in more]]


class HarnessCase(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(self._remove_tree)
        self.output = self.tmp / 'observations.json'
        self.session_file = self.tmp / 'session.txt'
        self.session_file.write_text(SESSION + '\n', encoding='ascii')

    def _remove_tree(self):
        for path in sorted(self.tmp.rglob('*'), reverse=True):
            path.unlink() if path.is_file() else path.rmdir()
        self.tmp.rmdir()

    def serve(self, routes):
        server = Server(routes)
        self.addCleanup(server.close)
        return server

    def smi(self, mode='rising'):
        extra = (f'--state={self.tmp / "smi-state"}',) if mode == 'rising' else ()
        return python_tool('--nvidia-smi', mode, *extra)

    def invoke(self, *args, output=True):
        """Run main() with the short test window; @return (exit code, stdout, stderr)"""
        argv = list(args)
        if output:
            argv = ['--output', str(self.output), '--duration', '0.4', '--interval', '0.05'] + argv
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = contention.main(argv)
        return code, out.getvalue(), err.getvalue()

    def observations(self):
        return json.loads(self.output.read_text(encoding='utf-8'))


class ArgumentTests(HarnessCase):
    def refused(self, *args, output=True):
        code, out, err = self.invoke(*args, output=output)
        self.assertEqual(code, 2, err)
        self.assertNotIn(SESSION, out + err)
        return err

    def test_a_backend_url_is_required(self):
        self.assertIn('at least one', self.refused())
        self.assertFalse(self.output.exists())

    def test_a_text_or_speech_url_needs_its_model_and_the_reverse(self):
        for kind in ('text', 'speech'):
            with self.subTest(kind=kind):
                self.assertIn(f'--{kind}-url needs', self.refused(f'--{kind}-url', 'http://127.0.0.1:9'))
                self.assertIn(f'--{kind}-model needs',
                              self.refused(f'--{kind}-model', 'm', '--image-url', 'http://127.0.0.1:9'))

    def test_urls_with_credentials_query_fragment_or_another_scheme_are_refused(self):
        for url in ('http://user:pw@127.0.0.1:9', 'http://user@127.0.0.1:9', 'http://127.0.0.1:9?x=1',
                    'http://127.0.0.1:9#frag', 'ftp://127.0.0.1:9', '127.0.0.1:9', 'http://127.0.0.1:notaport',
                    'http://127.0.0.1:9/a b', 'http://'):
            with self.subTest(url=url):
                err = self.refused('--image-url', url)
                self.assertIn('base URL', err)

    def test_numbers_outside_their_range_are_refused_without_echoing_them(self):
        for flag, value in (('--duration', '0'), ('--duration', '99999'), ('--duration', 'nan'), ('--concurrency', '9'),
                            ('--concurrency', '1.5'), ('--interval', '0.001'), ('--max-requests', '0'),
                            ('--request-timeout', 'abc'), ('--image-size', '10'), ('--image-steps', '-1')):
            with self.subTest(flag=flag, value=value):
                err = self.refused('--image-url', 'http://127.0.0.1:9', flag, value)
                self.assertIn('must be a', err)
                self.assertNotIn(f"'{value}'", err)

    def test_request_paths_must_be_plain_absolute_paths(self):
        for path in ('v1/x', '/a b', '/a?b=1', '/a#b', ''):
            with self.subTest(path=path):
                self.refused('--image-url', 'http://127.0.0.1:9', '--image-path', path)

    def test_an_existing_output_is_never_replaced(self):
        self.output.write_text('keep me', encoding='utf-8')
        err = self.refused('--image-url', 'http://127.0.0.1:9')
        self.assertIn('already exists', err)
        self.assertEqual(self.output.read_text(encoding='utf-8'), 'keep me')

    def test_an_output_in_a_missing_directory_is_refused_before_any_load(self):
        server = self.serve({})
        err = self.refused('--output', str(self.tmp / 'missing' / 'out.json'), '--image-url', server.url,
                           output=False)
        self.assertIn('cannot be created', err)
        self.assertEqual(server.requests, [])

    def test_the_metrics_url_and_the_session_file_go_together(self):
        self.assertIn('go together', self.refused('--image-url', 'http://127.0.0.1:9',
                                                  '--metrics-url', 'http://127.0.0.1:9'))
        self.assertIn('go together', self.refused('--image-url', 'http://127.0.0.1:9',
                                                  '--metrics-session-file', str(self.session_file)))

    def test_a_bad_session_file_is_refused_without_quoting_it(self):
        base = ('--image-url', 'http://127.0.0.1:9', '--metrics-url', 'http://127.0.0.1:9', '--metrics-session-file')
        secret_looking = self.tmp / 'short.txt'
        secret_looking.write_text('not-a-session-but-secret-looking', encoding='ascii')
        too_large = self.tmp / 'large.txt'
        too_large.write_text('a' * 300, encoding='ascii')
        for path, expected in ((secret_looking, '43-character'), (too_large, 'too large'),
                               (self.tmp / 'absent.txt', 'cannot be read')):
            with self.subTest(expected=expected):
                err = self.refused(*base, str(path))
                self.assertIn(expected, err)
                self.assertNotIn('secret-looking', err)

    def test_the_session_value_goes_only_to_a_loopback_metrics_url(self):
        for host in ('http://example.invalid:80', 'http://192.0.2.1:80', 'http://[2001:db8::1]:80'):
            with self.subTest(host=host):
                self.assertIn('loopback', self.refused('--image-url', 'http://127.0.0.1:9', '--metrics-url', host,
                                                       '--metrics-session-file', str(self.session_file)))

    def test_competitor_arguments_need_a_competitor_that_starts_inside_the_run(self):
        self.assertIn('needs --competitor', self.refused('--image-url', 'http://127.0.0.1:9', '--competitor-arg', 'x'))
        self.assertIn('less than --duration', self.refused('--image-url', 'http://127.0.0.1:9',
                                                           '--competitor', 'x', '--competitor-start-after', '5'))

    def test_container_names_and_docker_arguments_are_checked(self):
        self.assertIn('container name', self.refused('--image-url', 'http://127.0.0.1:9', '--docker-logs', '-rm x'))
        self.assertIn('needs --docker-logs', self.refused('--image-url', 'http://127.0.0.1:9', '--docker-arg', 'x'))

    def test_help_exits_zero_and_writes_nothing(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(contention.main(['--help']), 0)
        self.assertFalse(self.output.exists())


class FullRunTests(HarnessCase):
    def stand_ins(self):
        """Text, image, speech and metrics stand-ins; the first text answer writes an out-of-memory line."""
        self.server_log = self.tmp / 'server.log'
        self.engine_log = self.tmp / 'engine.log'
        self.server_log.write_text(f'old {OOM_LINE}\nready\n', encoding='utf-8')
        self.engine_log.write_text('started\n', encoding='utf-8')
        logged, refused, polls = [], [], []

        def text(handler):
            if not logged:
                logged.append(True)
                with open(self.server_log, 'a', encoding='utf-8') as log:
                    log.write(f'{OOM_LINE}\n')
            return 200, b'{"choices": []}'

        def image(handler):
            if not refused:
                refused.append(True)
                with open(self.engine_log, 'a', encoding='utf-8') as log:
                    log.write('worker answered gpu-out-of-memory\n')
            return 507, b'{"code": "gpu-out-of-memory"}'

        def metrics(handler):
            if handler.headers.get('Cookie') != f'ENGINE_SESSION={SESSION}':
                return 403, b'{"code": "LOCAL_ADMISSION_DENIED"}'
            polls.append(True)
            return 200, json.dumps({'jobs_running': 0, 'requests_waiting': len(polls)}).encode()

        self.text = self.serve({('POST', '/v1/chat/completions'): text})
        self.image = self.serve({('POST', '/sdapi/v1/txt2img'): image})
        self.speech = self.serve({('POST', '/v1/audio/speech'): ok(b'RIFF')})
        self.engine = self.serve({('GET', contention.METRICS_PATH): metrics})

    def full_arguments(self):
        return ['--text-url', self.text.url, '--text-model', MODEL_NAME, '--image-url', self.image.url,
                '--speech-url', self.speech.url, '--speech-model', MODEL_NAME,
                '--metrics-url', self.engine.url, '--metrics-session-file', str(self.session_file),
                '--server-log', str(self.server_log), '--engine-log', str(self.engine_log),
                *self.smi(), *python_tool('--competitor', 'sleep')]

    def test_one_run_records_load_memory_oom_events_metrics_and_the_competitor(self):
        self.stand_ins()
        code, out, err = self.invoke(*self.full_arguments())
        self.assertEqual((code, err), (0, ''))
        data = self.observations()
        self.assertEqual(data['format'], 'gpu-contention-observations/1')
        self.assertTrue(data['complete'])
        self.assertEqual(set(data), {'format', 'complete', 'started_at', 'duration_seconds', 'settings', 'load',
                                     'competitor', 'gpu_memory', 'out_of_memory', 'executor_metrics', 'limits'})
        for name in ('text', 'speech'):
            self.assertEqual(data['load'][name]['status'], 'ok', name)
            self.assertEqual(set(data['load'][name]['statuses']), {'200'})
            self.assertGreater(data['load'][name]['answered'], 0)
        self.assertEqual(set(data['load']['image']['statuses']), {'507'})
        self.assertEqual(data['competitor'], {'status': 'ran', 'exited_early': False, 'exit_code': None})
        gpus = data['gpu_memory']['gpus']
        self.assertEqual(data['gpu_memory']['status'], 'ok')
        self.assertEqual(gpus['0'], {'baseline_mib': 100, 'peak_mib': 900, 'total_mib': 8192})
        self.assertEqual(gpus['1'], {'baseline_mib': 50, 'peak_mib': 50, 'total_mib': 4096})
        oom = data['out_of_memory']
        self.assertEqual(oom['status'], 'ok')
        self.assertEqual(oom['events'], 2)
        self.assertEqual(oom['sources']['server-log-1']['events'], 1)
        self.assertEqual(oom['sources']['server-log-1']['by_pattern'], {'out-of-memory-text': 1})
        self.assertEqual(oom['sources']['server-log-1']['line_numbers'], [1])
        self.assertEqual(oom['sources']['engine-log-1']['by_pattern'], {'worker-code': 1})
        metrics = data['executor_metrics']
        self.assertEqual(metrics['status'], 'ok')
        self.assertEqual(metrics['first']['requests_waiting'], 1)
        self.assertGreaterEqual(metrics['last']['requests_waiting'], 2)
        self.assertEqual(metrics['peak']['requests_waiting'], metrics['last']['requests_waiting'])
        self.assertEqual(metrics['driven'], {'text': True, 'image': True, 'speech': True})
        self.assertIn('complete=true', out)
        self.assertIn('gpu_memory=peak_mib=900', out)
        self.assertIn('out_of_memory=ok events=2', out)

    def test_only_lines_written_during_the_run_are_scanned(self):
        self.stand_ins()
        self.invoke(*self.full_arguments())
        self.assertEqual(self.observations()['out_of_memory']['sources']['server-log-1']['lines_scanned'], 1)

    def test_the_file_holds_no_endpoint_model_session_or_path_and_is_stable_json_with_lf(self):
        self.stand_ins()
        code, out, err = self.invoke(*self.full_arguments())
        raw = self.output.read_bytes()
        text = raw.decode('utf-8')
        for secret in (SESSION, MODEL_NAME, '127.0.0.1', str(self.text.port), str(self.tmp), 'lighthouse',
                       'harbour'):
            self.assertNotIn(secret, text, secret)
            self.assertNotIn(SESSION, out + err)
        self.assertNotIn(b'\r', raw)
        self.assertTrue(text.endswith('}\n'))
        self.assertEqual(text, json.dumps(json.loads(text), indent=2, sort_keys=True) + '\n')

    def test_the_session_goes_to_the_metrics_route_only(self):
        self.stand_ins()
        self.invoke(*self.full_arguments())
        for server in (self.text, self.image, self.speech):
            self.assertTrue(server.requests)
            self.assertEqual({cookie for _, _, cookie, _ in server.requests}, {None})
        self.assertEqual({cookie for _, _, cookie, _ in self.engine.requests}, {f'ENGINE_SESSION={SESSION}'})

    def test_a_session_file_may_carry_the_cookie_name(self):
        self.stand_ins()
        self.session_file.write_text(f'ENGINE_SESSION={SESSION}', encoding='ascii')
        self.invoke(*self.full_arguments())
        self.assertEqual(self.observations()['executor_metrics']['status'], 'ok')

    def test_requests_carry_the_fixed_shapes_and_stay_under_the_cap(self):
        self.stand_ins()
        self.invoke('--text-url', self.text.url, '--text-model', MODEL_NAME, '--image-url', self.image.url,
                    '--image-steps', '7', '--image-size', '512', '--speech-url', self.speech.url,
                    '--speech-model', 'voice-model', '--max-requests', '3', *self.smi())
        data = self.observations()
        for name in ('text', 'image', 'speech'):
            self.assertEqual(data['load'][name]['requests'], 3, name)
        text = json.loads(self.text.requests[0][3])
        self.assertEqual((text['model'], text['stream'], text['max_tokens']), (MODEL_NAME, False, 16))
        self.assertEqual(text['messages'][0]['role'], 'user')
        image = json.loads(self.image.requests[0][3])
        self.assertEqual((image['steps'], image['width'], image['height']), (7, 512, 512))
        speech = json.loads(self.speech.requests[0][3])
        self.assertEqual(speech['model'], 'voice-model')
        self.assertEqual(len(self.text.requests), 3)

    def test_backends_that_were_not_configured_are_unavailable_not_zero(self):
        self.stand_ins()
        self.invoke('--text-url', self.text.url, '--text-model', 'm', *self.smi())
        data = self.observations()
        self.assertEqual(data['load']['text']['status'], 'ok')
        for name in ('image', 'speech'):
            self.assertEqual(data['load'][name], {'status': 'unavailable', 'reason': 'not-configured'})
        self.assertEqual(data['competitor'], {'status': 'unavailable', 'reason': 'not-configured'})
        self.assertEqual(data['executor_metrics']['reason'], 'not-configured')
        self.assertEqual(data['executor_metrics']['driven'], {'text': True, 'image': False, 'speech': False})
        self.assertEqual(data['out_of_memory']['reason'], 'no-log-source')

    def test_a_backend_that_does_not_answer_is_unavailable_with_its_error_codes(self):
        dead = self.serve({})
        dead.close()
        self.invoke('--image-url', dead.url, '--image-path', '/x', *self.smi())
        image = self.observations()['load']['image']
        self.assertEqual((image['status'], image['reason'], image['answered']), ('unavailable', 'no-answer', 0))
        self.assertEqual(set(image['errors']), {'connection-refused'})
        self.assertGreater(image['requests'], 0)

    def test_a_backend_answering_with_errors_still_counts_as_answered(self):
        server = self.serve({('POST', '/x'): lambda handler: (503, b'busy')})
        self.invoke('--image-url', server.url, '--image-path', '/x', *self.smi())
        image = self.observations()['load']['image']
        self.assertEqual((image['status'], list(image['statuses'])), ('ok', ['503']))


class MetricsRouteTests(HarnessCase):
    def metrics_for(self, routes, dead=False):
        server = self.serve(routes)
        if dead:
            server.close()
        backend = self.serve({('POST', '/x'): ok()})
        code, _, err = self.invoke('--image-url', backend.url, '--image-path', '/x',
                                   '--metrics-url', server.url, '--metrics-session-file', str(self.session_file),
                                   *self.smi('fail'))
        self.assertEqual((code, err), (0, ''))
        return self.observations()['executor_metrics']

    def test_a_missing_route_is_unavailable(self):
        result = self.metrics_for({})
        self.assertEqual((result['status'], result['reason'], result['samples']), ('unavailable', 'route-missing', 0))
        self.assertGreater(result['failed_samples'], 0)

    def test_a_refused_request_is_unavailable(self):
        self.assertEqual(self.metrics_for({('GET', contention.METRICS_PATH): lambda h: (403, b'{}')})['reason'],
                         'refused')

    def test_other_statuses_are_recorded_by_number(self):
        self.assertEqual(self.metrics_for({('GET', contention.METRICS_PATH): lambda h: (500, b'')})['reason'],
                         'http-500')

    def test_a_body_that_is_not_a_map_of_numbers_is_unavailable(self):
        for body in (b'not json', b'[1, 2]', b'{"a": "text"}', b'{"a": true}', b'{"a": {"b": 1}}'):
            with self.subTest(body=body):
                self.output.unlink(missing_ok=True)  # one output file per run
                result = self.metrics_for({('GET', contention.METRICS_PATH): ok(body)})
                self.assertEqual(result['reason'], 'invalid-body')

    def test_an_unreachable_engine_is_unavailable(self):
        result = self.metrics_for({}, dead=True)
        self.assertEqual((result['status'], result['reason']), ('unavailable', 'unreachable'))


class GpuSamplingTests(HarnessCase):
    def gpu_for(self, *smi, patch_timeout=None):
        backend = self.serve({('POST', '/x'): ok()})
        with mock.patch.object(contention, 'COMMAND_TIMEOUT', patch_timeout or contention.COMMAND_TIMEOUT):
            code, out, err = self.invoke('--image-url', backend.url, '--image-path', '/x', *smi)
        self.assertEqual((code, err), (0, ''))
        data = self.observations()
        self.assertEqual(data['load']['image']['status'], 'ok')  # the load is still recorded
        return data['gpu_memory'], out

    def test_a_missing_tool_is_unavailable(self):
        gpu, out = self.gpu_for('--nvidia-smi', str(self.tmp / 'no-such-tool'))
        self.assertEqual((gpu['status'], gpu['reason'], gpu['samples']), ('unavailable', 'tool-missing', 0))
        self.assertIn('gpu_memory=unavailable (tool-missing)', out)

    def test_a_failing_tool_is_unavailable(self):
        self.assertEqual(self.gpu_for(*self.smi('fail'))[0]['reason'], 'exit-nonzero')

    def test_unparsable_output_is_unavailable(self):
        self.assertEqual(self.gpu_for(*self.smi('garbage'))[0]['reason'], 'unparsable')

    def test_a_tool_that_hangs_is_cut_off(self):
        self.assertEqual(self.gpu_for(*self.smi('slow'), patch_timeout=0.3)[0]['reason'], 'timeout')

    def test_table_parsing_is_strict(self):
        self.assertEqual(contention.parse_gpu_table('0, 10, 20\n1, 5, 20\n'), {0: (10, 20), 1: (5, 20)})
        for text in ('', '\n', '0, 10', '0, 10, 20, 30', '0, -1, 20', '0, 1.5, 20', '0, x, 20', '0, 1, 2\n0, 3, 4',
                     '0, ' + chr(0x663) + ', 20'):
            with self.subTest(text=text):
                with self.assertRaises(ValueError):
                    contention.parse_gpu_table(text)


class LogScanTests(HarnessCase):
    def scan(self, source):
        return contention.scan_source(source, '2026-01-01T00:00:00Z', [sys.executable, '-B', STANDIN, '--mode=fail'])

    def file_source(self, text, offset=0):
        path = self.tmp / 'log.txt'
        path.write_text(text, encoding='utf-8')
        source = contention.LogSource('server-log-1', 'file', str(path))
        source.offset = offset
        return source

    def test_every_pattern_counts_one_line_once_without_copying_it(self):
        lines = ['CUDA error: out of memory', 'torch.OutOfMemoryError: boom', 'CUDA_ERROR_OUT_OF_MEMORY',
                 'code=gpu-out-of-memory', 'ggml: cudaMalloc failed', 'Killed by oom-killer', 'all fine',
                 'CUDA out of memory and failed to allocate']
        result = self.scan(self.file_source('\n'.join(lines) + '\n'))
        self.assertEqual((result['status'], result['lines_scanned'], result['events']), ('ok', 8, 7))
        self.assertEqual(result['line_numbers'], [1, 2, 3, 4, 5, 6, 8])
        self.assertEqual(result['by_pattern']['out-of-memory-text'], 2)
        self.assertEqual(set(result), {'status', 'lines_scanned', 'events', 'by_pattern', 'line_numbers', 'rotated',
                                       'truncated'})
        self.assertNotIn('boom', json.dumps(result))

    def test_a_log_without_matches_reports_zero_events_as_ok(self):
        result = self.scan(self.file_source('a\nb\n'))
        self.assertEqual((result['status'], result['events'], result['line_numbers']), ('ok', 0, []))

    def test_a_missing_file_is_unavailable(self):
        source = contention.LogSource('server-log-1', 'file', str(self.tmp / 'absent.log'))
        self.assertEqual(self.scan(source), {'status': 'unavailable', 'reason': 'file-missing'})

    def test_a_file_that_shrank_is_read_from_its_start(self):
        result = self.scan(self.file_source(f'{OOM_LINE}\n', offset=9999))
        self.assertEqual((result['events'], result['rotated']), (1, True))

    def test_event_details_are_capped_but_counting_continues(self):
        count = contention.MAX_EVENT_DETAILS + 5
        result = self.scan(self.file_source((OOM_LINE + '\n') * count))
        self.assertEqual((result['events'], len(result['line_numbers'])), (count, contention.MAX_EVENT_DETAILS))

    def test_a_container_log_is_read_with_stderr_and_a_failure_is_unavailable(self):
        source = contention.LogSource('container-log-1', 'docker', 'engine-gpu')
        good = contention.scan_source(source, 'since', [sys.executable, '-B', STANDIN, '--mode=docker-oom'])
        self.assertEqual((good['events'], good['line_numbers']), (1, [2]))
        self.assertEqual(self.scan(source), {'status': 'unavailable', 'reason': 'exit-nonzero'})
        missing = contention.scan_source(source, 'since', [str(self.tmp / 'no-docker')])
        self.assertEqual(missing, {'status': 'unavailable', 'reason': 'tool-missing'})

    def test_the_run_reads_a_container_log_through_the_configured_program(self):
        backend = self.serve({('POST', '/x'): ok()})
        self.invoke('--image-url', backend.url, '--image-path', '/x', *self.smi('fail'),
                    *python_tool('--docker', 'docker-oom'), '--docker-logs', 'engine-gpu')
        oom = self.observations()['out_of_memory']
        self.assertEqual((oom['status'], oom['events']), ('ok', 1))
        self.assertEqual(oom['sources']['container-log-1']['line_numbers'], [2])

    def test_unreadable_sources_make_the_whole_scan_unavailable(self):
        backend = self.serve({('POST', '/x'): ok()})
        self.invoke('--image-url', backend.url, '--image-path', '/x', *self.smi('fail'),
                    '--server-log', str(self.tmp / 'absent.log'))
        oom = self.observations()['out_of_memory']
        self.assertEqual((oom['status'], oom['reason'], oom['sources_unavailable']),
                         ('unavailable', 'all-sources-unavailable', 1))
        self.assertEqual(oom['events'], 0)

    def test_one_readable_source_keeps_the_scan_ok_and_counts_the_other_as_unavailable(self):
        backend = self.serve({('POST', '/x'): ok()})
        readable = self.tmp / 'readable.log'
        readable.write_text('', encoding='utf-8')
        self.invoke('--image-url', backend.url, '--image-path', '/x', *self.smi('fail'),
                    '--server-log', str(readable), '--engine-log', str(self.tmp / 'absent.log'))
        oom = self.observations()['out_of_memory']
        self.assertEqual((oom['status'], oom['sources_unavailable']), ('ok', 1))


class CompetitorTests(HarnessCase):
    def competitor_for(self, *competitor):
        backend = self.serve({('POST', '/x'): ok()})
        code, _, err = self.invoke('--image-url', backend.url, '--image-path', '/x', *self.smi('fail'),
                                   *competitor)
        self.assertEqual((code, err), (0, ''))
        return self.observations()['competitor']

    def test_a_competitor_that_cannot_start_is_unavailable(self):
        self.assertEqual(self.competitor_for('--competitor', str(self.tmp / 'no-such-program')),
                         {'status': 'unavailable', 'reason': 'start-failed'})

    def test_a_competitor_that_exits_early_is_recorded_with_its_code(self):
        # Give the stand-in time to start and exit before the 0.4 s window closes.
        with mock.patch.object(contention, 'drive', lambda *args: args[6].wait(1.5)):
            result = self.competitor_for(*python_tool('--competitor', 'exit3'))
        self.assertEqual(result, {'status': 'ran', 'exited_early': True, 'exit_code': 3})

    def test_a_competitor_scheduled_after_the_load_ended_never_starts(self):
        # One request ends the load at once, long before the competitor's start time inside the window.
        result = self.competitor_for(*python_tool('--competitor', 'sleep'), '--competitor-start-after', '0.399',
                                     '--max-requests', '1')
        self.assertEqual(result, {'status': 'unavailable', 'reason': 'run-ended-before-start'})


if __name__ == '__main__':
    unittest.main()
