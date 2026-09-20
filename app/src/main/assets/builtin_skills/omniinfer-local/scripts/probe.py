#!/usr/bin/env python3
"""One-shot loopback inference probe. Never downloads, installs or retries."""
import argparse
import json
import sys
import urllib.error
import urllib.request


def probe(port=9099, timeout=60):
    if not 1 <= port <= 65535 or not 0 < timeout <= 300:
        raise ValueError('Invalid port or timeout')
    # Do not follow redirects from loopback to a different inference service.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    stage = 'health'

    def request(path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(f'http://127.0.0.1:{port}{path}', data=data,
                                     headers={'Content-Type': 'application/json'})
        with opener.open(req, timeout=timeout) as response:
            raw = response.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                raise ValueError('Response exceeds limit')
            value = json.loads(raw)
            if not isinstance(value, dict):
                raise ValueError('Expected JSON object')
            return value

    try:
        if request('/health').get('status') != 'ok':
            raise ValueError('Service is not healthy')
        stage = 'models'
        models = request('/v1/models').get('data')
        if not isinstance(models, list) or not models:
            raise ValueError('No model loaded')
        model = models[0].get('id') if isinstance(models[0], dict) else None
        if not isinstance(model, str) or not model.strip():
            raise ValueError('Missing model id')
        stage = 'inference'
        result = request('/v1/chat/completions', {
            'model': model, 'messages': [{'role': 'user', 'content': 'Say READY only.'}],
            'stream': False, 'temperature': 0, 'max_tokens': 32, 'reasoning_effort': 'none',
        })
        choices = result.get('choices')
        if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
            raise ValueError('Missing assistant choice')
        message = choices[0].get('message')
        content = message.get('content') if isinstance(message, dict) else None
        if not isinstance(content, str) or not content.strip():
            raise ValueError('Missing assistant text')
        return {'ok': True, 'stage': stage, 'port': port, 'reply_chars': len(content),
                'scope': 'loopback_http_smoke_only'}
    except (urllib.error.URLError, OSError, ValueError, TypeError) as error:
        if isinstance(error, urllib.error.HTTPError):
            error.close()
        # Error body may contain credentials or conversation data; do not emit it.
        return {'ok': False, 'stage': stage, 'port': port, 'error': type(error).__name__}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=9099)
    parser.add_argument('--timeout', type=float, default=60)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not 0 < args.timeout <= 300:
        parser.error('port must be 1..65535; timeout must be >0 and <=300')
    result = probe(args.port, args.timeout)
    print(json.dumps(result))
    return 0 if result['ok'] else 1


if __name__ == '__main__':
    sys.exit(main())
