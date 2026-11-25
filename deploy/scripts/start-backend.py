"""Start a prepared local backend with an explicit environment file (Windows/Linux/macOS)."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
from database_support import ROOT, environment

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--env-file', type=Path, required=True)
args = parser.parse_args()
values, _, env, _ = environment(args.env_file)
java = Path(values['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if values.get('JAVA_HOME') else shutil.which('java')
if not java or not Path(java).is_file():
    parser.error('Set JAVA_HOME or put JDK 17+ on PATH')
raise SystemExit(subprocess.call([str(java), '-Dfile.encoding=UTF-8', '-jar',
                                 str(ROOT / 'backend/target/metric-code.jar')], env=env, cwd=ROOT))
