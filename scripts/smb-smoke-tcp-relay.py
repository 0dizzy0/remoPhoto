#!/usr/bin/env python3
"""Loopback-only TCP relay for the Android emulator SMB smoke test."""

from __future__ import annotations

import argparse
import logging
import socket
import threading


def copy_stream(source: socket.socket, target: socket.socket, counters: list[int], index: int) -> None:
    try:
        while chunk := source.recv(64 * 1024):
            target.sendall(chunk)
            counters[index] += len(chunk)
    except (ConnectionError, OSError):
        pass
    finally:
        try:
            target.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(client: socket.socket, target_host: str, target_port: int) -> None:
    counters = [0, 0]
    logging.info("connection_open")
    try:
        with client, socket.create_connection((target_host, target_port), timeout=10) as upstream:
            client.settimeout(None)
            upstream.settimeout(None)
            threads = [
                threading.Thread(target=copy_stream, args=(client, upstream, counters, 0), daemon=True),
                threading.Thread(target=copy_stream, args=(upstream, client, counters, 1), daemon=True),
            ]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join()
    except OSError as error:
        logging.error("connection_error type=%s", type(error).__name__)
    finally:
        logging.info("connection_close sent=%d received=%d", counters[0], counters[1])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen-port", required=True, type=int)
    parser.add_argument("--target-host", required=True)
    parser.add_argument("--target-port", required=True, type=int)
    parser.add_argument("--log-file", required=True)
    args = parser.parse_args()
    logging.basicConfig(
        filename=args.log_file,
        level=logging.INFO,
        encoding="utf-8",
        format="%(asctime)s %(levelname)s %(message)s",
    )
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", args.listen_port))
        listener.listen(16)
        logging.info(
            "relay_start listen=loopback:%d target_port=%d",
            args.listen_port,
            args.target_port,
        )
        while True:
            client, _ = listener.accept()
            threading.Thread(target=handle, args=(client, args.target_host, args.target_port), daemon=True).start()


if __name__ == "__main__":
    main()
