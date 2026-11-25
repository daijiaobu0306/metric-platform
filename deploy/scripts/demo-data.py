"""Enable/disable external-customer collection through authenticated APIs; disabling preserves ingested data."""
import argparse
import json
import urllib.request
from urllib.parse import urlsplit
from database_support import environment


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['enable', 'disable', 'clear', 'status'], help='clear is a compatibility alias for disable; no ingested rows are deleted')
    parser.add_argument('--env-file', required=True)
    parser.add_argument('--base-url', default='http://127.0.0.1:28080/metric-code')
    args = parser.parse_args()
    _, _, env, _ = environment(args.env_file)
    url = urlsplit(args.base_url)
    if url.hostname not in ('localhost', '127.0.0.1', '::1') or url.scheme not in ('http', 'https'):
        parser.error('This demo tool is restricted to localhost')
    def request(path, method='GET', data=None, key=None):
        headers = {'Content-Type': 'application/json'}
        if key:
            headers['X-Session-Key'] = key
        req = urllib.request.Request(args.base_url.rstrip('/') + path,
            data=json.dumps(data).encode() if data is not None else None, headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=60) as response:
            result = json.load(response)
        if result.get('code') != 200:
            raise RuntimeError(result.get('message', 'API request failed'))
        return result.get('data')
    profile = request('/api/session/login', 'POST', {
        'username': env.get('METRIC_ADMIN_USERNAME', 'admin'), 'password': env.get('METRIC_ADMIN_PASSWORD', '')})
    key = profile['sessionKey']
    try:
        path = '/api/data/external-customer/demo-state'
        result = request(path, key=key) if args.action == 'status' else request(
            path, 'PUT', {'enabled': args.action == 'enable'}, key)
        print(json.dumps(result, ensure_ascii=False))
    finally:
        request('/api/session/end', 'POST', {'sessionKey': key}, key)


if __name__ == '__main__':
    main()
