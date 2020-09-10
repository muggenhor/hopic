# Copyright (c) 2020 - 2020 TomTom N.V. (https://tomtom.com)
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

import logging
import os
import subprocess
import sys
import tempfile
from typing import Final

try:
    # Python >= 3.8
    from importlib import metadata
except ImportError:
    import importlib_metadata as metadata

import click

from ..execution import echo_cmd


PACKAGE : Final[str] = __package__.split('.')[0]


@click.command()
@click.pass_context
def install_extensions(ctx):
    """
    Install all extensions required during build execution.
    """

    pip_cfg = ctx.obj.config['pip']
    packages = pip_cfg['packages']
    if not packages:
        return

    if hasattr(sys, 'real_prefix') or getattr(sys, 'base_prefix', None) != sys.prefix:
        is_venv = True

    with tempfile.TemporaryDirectory() as td:
        # Prevent changing the Hopic version
        constraints_file = os.path.join(td, 'constraints.txt')
        with open(constraints_file, 'w', encoding='UTF-8') as cf:
            cf.write(f"{PACKAGE}=={metadata.distribution(PACKAGE).version}\n")

        cmd = [
                sys.executable, '-m', 'pip', 'install',
                '-c', constraints_file,
            ]

        plog = logging.getLogger(PACKAGE)
        if plog.isEnabledFor(logging.DEBUG):
            cmd.append('--verbose')

        for index in pip_cfg['extra-index']:
            cmd.extend(['--extra-index-url', index])

        if not is_venv:
            cmd.append('--user')

        cmd.extend(packages)

        echo_cmd(subprocess.check_call, cmd)
