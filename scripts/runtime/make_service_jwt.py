#!/usr/bin/env python3

import argparse
import base64
import hashlib
import hmac
import json
import time


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def main() -> None:
    parser = argparse.ArgumentParser(description="Create a local HS256 service JWT")
    parser.add_argument("--secret", required=True)
    parser.add_argument("--subject", default="runtime-smoke")
    parser.add_argument("--service", default="runtime-smoke")
    parser.add_argument("--issuer", default="jarvis-internal")
    parser.add_argument("--audience", default="jarvis-services")
    parser.add_argument("--ttl-seconds", type=int, default=300)
    args = parser.parse_args()

    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "iss": args.issuer,
        "sub": args.subject,
        "aud": [args.audience],
        "iat": now,
        "exp": now + args.ttl_seconds,
        "token_type": "service",
        "svc": args.service,
        "roles": ["SVC_INTERNAL"],
    }

    signing_input = ".".join(
        [
            b64url(json.dumps(header, separators=(",", ":")).encode("utf-8")),
            b64url(json.dumps(payload, separators=(",", ":")).encode("utf-8")),
        ]
    )
    signature = hmac.new(
        args.secret.encode("utf-8"),
        signing_input.encode("ascii"),
        hashlib.sha256,
    ).digest()
    print(f"{signing_input}.{b64url(signature)}")


if __name__ == "__main__":
    main()
