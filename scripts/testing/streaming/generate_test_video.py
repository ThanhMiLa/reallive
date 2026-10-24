#!/usr/bin/env python3
"""Generate a non-personal moving pattern and tone for a manual OBS media source."""

import os
import subprocess
import fixture


def main():
    os.umask(0o077)
    destination = fixture.DEFAULT_STATE / 'obs-test.mp4'
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    result = subprocess.run(['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error', '-y',
                             '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=30',
                             '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
                             '-t', '12', '-c:v', 'libx264', '-preset', 'fast', '-pix_fmt', 'yuv420p',
                             '-g', '60', '-c:a', 'aac', '-movflags', '+faststart', str(destination)],
                            stdin=subprocess.DEVNULL, capture_output=True)
    if result.returncode:
        raise SystemExit('Could not generate OBS test clip; check FFmpeg/libx264 availability')
    print('Generated test pattern with H264/AAC: ' + str(destination))


if __name__ == '__main__':
    main()
