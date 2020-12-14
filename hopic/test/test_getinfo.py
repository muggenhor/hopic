# Copyright (c) 2019 - 2020 TomTom N.V. (https://tomtom.com)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from . import hopic_cli

from click.testing import CliRunner
from collections import OrderedDict
from collections.abc import Sequence
from textwrap import dedent
import json
import os
import sys
import stat


class ExecutableFile:
    def __init__(self, file=None):
        self.file = file

    def __enter__(self):
        if self.file:
            self.file_context = open(self.file, 'w')
            return self.file_context.__enter__()

    def __exit__(self, exc_type, exc_val, exc_tb):
        if self.file:
            os.chmod(self.file, os.stat(self.file).st_mode | stat.S_IEXEC)
            return self.file_context.__exit__()


def run_with_config(config, args, resource=()):
    runner = CliRunner(mix_stderr=False)
    with runner.isolated_filesystem():
        with open('hopic-ci-config.yaml', 'w') as f:
            f.write(config)

        if resource:
            with ExecutableFile(resource[0]) as f:
                f.write(resource[1])
        result = runner.invoke(hopic_cli, args)

    if result.stdout_bytes:
        print(result.stdout, end='')
    if result.stderr_bytes:
        print(result.stderr, end='', file=sys.stderr)

    if result.exception is not None and not isinstance(result.exception, SystemExit):
        raise result.exception

    return result


def test_order():
    """
    The order of phase/variant combinations must be the same in the output JSON as in the config.
    """

    result = run_with_config('''\
phases:
  build:
    a:
      - sh: ./build.sh a
    b:
      - sh: ./build.sh b
  test:
    a:
      - sh: ./test.sh a
  upload:
    a:
      - sh: ./upload.sh a
    b:
      - sh: ./upload.sh a
''', ('getinfo',))

    assert result.exit_code == 0
    output = json.loads(result.stdout, object_pairs_hook=OrderedDict)

    assert tuple(output.keys()) == ('build', 'test', 'upload')
    assert tuple(output['build' ].keys()) == ('a', 'b')         # noqa: E202
    assert tuple(output['test'  ].keys()) == ('a',)             # noqa: E202
    assert tuple(output['upload'].keys()) == ('a', 'b')


def test_variants_without_metadata():
    """
    Phase/variant combinations without meta data should still appear in the output JSON.
    """

    result = run_with_config('''\
phases:
  build:
    a:
      - ./build.sh a
    b:
      - sh: ./build.sh b
  test:
    a:
      - ./test.sh a
  upload:
    a:
      - ./upload.sh a
    b:
      - sh: ./upload.sh a
''', ('getinfo',))

    assert result.exit_code == 0
    output = json.loads(result.stdout, object_pairs_hook=OrderedDict)

    assert 'build'  in output  # noqa: E272
    assert 'test'   in output  # noqa: E272
    assert 'upload' in output  # noqa: E272

    assert 'a' in output['build']
    assert 'b' in output['build']
    assert 'a' in output['test']
    assert 'a' in output['upload']
    assert 'b' in output['upload']


def test_with_credentials_format():
    result = run_with_config('''\
    phases:
      build:
        a:
        - with-credentials: test_id
        - with-credentials:
            id: second_id
        - with-credentials:
            - id: third_id
            - id: fourth_id
    ''', ('getinfo',))

    assert result.exit_code == 0
    output = json.loads(result.stdout, object_pairs_hook=OrderedDict)

    with_credentials = output['build']['a']['with-credentials']
    assert isinstance(with_credentials, Sequence)
    assert len(with_credentials) == 4
    assert 'test_id' in with_credentials[0]['id']
    assert 'second_id' in with_credentials[1]['id']
    assert 'third_id' in with_credentials[2]['id']
    assert 'fourth_id' in with_credentials[3]['id']


def test_embed_variants_file():
    generate_script_path = "generate-variants.py"
    result = run_with_config(
        dedent(f'''\
            phases:
              build:
                a: []

              test: !embed
                cmd: {generate_script_path}
            '''),
        ('getinfo',),
        (generate_script_path, dedent('''\
            #!/usr/bin/env python3

            print(\'\'\'test-variant:
              - echo Bob the builder\'\'\')
            '''))
        )

    assert result.exit_code == 0
    output = json.loads(result.stdout, object_pairs_hook=OrderedDict)

    assert 'build' in output
    assert 'a' in output['build']
    assert 'test' in output
    assert 'test-variant' in output['test']


def test_embed_variants_non_existing_file():
    generate_script_path = "generate-variants.py"
    result = run_with_config(
        dedent(f'''\
            phases:
              build:
                a: []

              test: !embed
                cmd: {generate_script_path}
            '''),
        ('getinfo',))

    assert result.exit_code == 0
    output = json.loads(result.stdout, object_pairs_hook=OrderedDict)

    assert 'build' in output
    assert 'a' in output['build']
    assert 'test' in output
    assert 'error-variant' in output['test']


def test_embed_variants_error_in_file():
    generate_script_path = "generate-variants.py"
    result = run_with_config(
        dedent(f'''\
            phases:
              build:
                a: []

              test: !embed
                cmd: {generate_script_path}
            '''),
        ('getinfo',),
        (generate_script_path, dedent('''\
            #!/usr/bin/env python3
            print(\'\'\'test-variant:
            error\'\'\')
            ''')))

    assert result.exit_code == 0
    output = json.loads(result.stdout, object_pairs_hook=OrderedDict)

    assert 'build' in output
    assert 'a' in output['build']
    assert 'test' in output
    assert 'error-variant' in output['test']


def test_embed_variants_script_with_arguments():
    generate_script_path = "generate-variants.py"
    generate_script_args = 'argument-variant'
    result = run_with_config(
        dedent(f'''\
            phases:
              test: !embed
                cmd: '{generate_script_path} {generate_script_args}'
            '''),
        ('getinfo',),
        (generate_script_path, dedent('''\
            #!/usr/bin/env python3
            import sys

            print(\'\'\'test-%s:
              - echo Bob the builder\'\'\' % sys.argv[1])
            ''')
         ))

    assert result.exit_code == 0
    output = json.loads(result.stdout, object_pairs_hook=OrderedDict)

    assert 'test' in output
    assert f'test-{generate_script_args}' in output['test']


def test_embed_variants_cmd():
    cmd = dedent("'printf \"%s\"'" % '''test-variant:\n
  - Bob the builder''')

    result = run_with_config(dedent(f'''\
                phases:
                  test: !embed
                    cmd: {cmd}
                '''), ('getinfo',))

    assert result.exit_code == 0
    output = json.loads(result.stdout, object_pairs_hook=OrderedDict)

    assert 'test' in output
    assert 'test-variant' in output['test']
