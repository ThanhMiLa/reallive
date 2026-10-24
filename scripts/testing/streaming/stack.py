#!/usr/bin/env python3
"""Start only the Streaming MVP fixture stack, with optional public-registry config."""

import argparse
import json
import os
import subprocess
import fixture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['up', 'down', 'status'])
    parser.add_argument('--anonymous-registry', action='store_true',
                        help='Use isolated auths={} for public images if the Docker credential helper times out')
    args = parser.parse_args()
    environment = dict(os.environ)
    if args.anonymous_registry:
        directory = fixture.DEFAULT_STATE / 'docker-public'
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        fixture.write_private_json(directory / 'config.json', {'auths': {}})
        context = json.loads(subprocess.check_output(['docker', 'context', 'inspect']))[0]
        environment['DOCKER_HOST'] = context['Endpoints']['docker']['Host']
        environment['DOCKER_CONFIG'] = str(directory)
        environment.pop('DOCKER_CONTEXT', None)
    commands = {'up': ['up', '-d', '--build', '--wait'], 'down': ['down'], 'status': ['ps']}
    result = subprocess.run(['docker', 'compose', '--project-directory', str(fixture.REPO),
                             '-f', str(fixture.REPO / 'scripts/testing/streaming/compose.fixture.yml'),
                             *commands[args.command]], env=environment, cwd=fixture.REPO)
    return result.returncode


if __name__ == '__main__':
    raise SystemExit(main())
